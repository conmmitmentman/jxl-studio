package com.jxlstudio.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import com.jxlstudio.app.NativeCodec;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Device/emulator tests using Android graphics, real Rust JNI, and the actual activity. */
public final class CodecInstrumentation extends Instrumentation {
    private static final int WIDTH = 17;
    private static final int HEIGHT = 19;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            testCodecRoundTrip();
            testActivityLaunch();
            result.putString("jxlResult", "PASS");
            result.putString("stream", "jxlResult=PASS\nJXL Android instrumentation passed: real JNI conversion, "
                    + "dimensions, transparent and opaque pixels, preview, PNG export, Japanese paths, "
                    + "no-overwrite errors, preview size limits, and MainActivity launch.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("jxlResult", "FAIL");
            result.putString("stream", "JXL Android instrumentation failed:\n"
                    + Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void testCodecRoundTrip() throws Exception {
        Context context = getTargetContext();
        File directory = new File(context.getCacheDir(), "instrumentation-" + System.nanoTime());
        check(directory.mkdirs(), "Cannot create the test cache directory");
        File input = new File(directory, "日本語の透過画像.png");
        File jxl = new File(directory, "変換結果.jxl");
        File preview = new File(directory, "表示用.png");
        File exported = new File(directory, "書き出し.png");

        Bitmap original = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        original.setHasAlpha(true);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int alpha = (x + y) % 7 == 0 ? 0
                        : ((x + y) % 3 == 0 ? 64 + (x * 17 + y * 11) % 128 : 255);
                original.setPixel(x, y, Color.argb(alpha,
                        (x * 13) & 255, (y * 11) & 255, (x * 7 + y * 19) & 255));
            }
        }
        try (FileOutputStream output = new FileOutputStream(input)) {
            check(original.compress(Bitmap.CompressFormat.PNG, 100, output), "Cannot write input PNG");
        }
        original.recycle();
        // Android premultiplies Bitmap samples. Compare to the decoded input PNG,
        // which is the canonical input after Android's own PNG serialization.
        Bitmap expected = decode(input);
        check(Color.alpha(expected.getPixel(0, 0)) == 0, "Transparent fixture pixel was lost");
        check(Color.alpha(expected.getPixel(1, 0)) == 255, "Opaque fixture pixel was lost");
        check(expected.hasAlpha(), "The input fixture must have alpha");

        JSONObject conversion = success(NativeCodec.nativeConvert(input.getPath(), jxl.getPath()));
        check(conversion.getInt("width") == WIDTH && conversion.getInt("height") == HEIGHT,
                "Conversion reported the wrong dimensions");
        check(conversion.getLong("inputBytes") == input.length(), "Wrong input byte count");
        check(conversion.getLong("outputBytes") == jxl.length() && jxl.length() > 0,
                "The JPEG XL output is missing or has the wrong size");
        check(conversion.getLong("elapsedMs") >= 0, "Invalid conversion duration");
        check(conversion.getString("output").equals(jxl.getPath()), "Unicode output path changed");

        JSONObject previewResult = success(NativeCodec.nativePreview(jxl.getPath(), preview.getPath(), 2048));
        check(previewResult.getInt("width") == WIDTH && previewResult.getInt("height") == HEIGHT,
                "Preview reported the wrong original dimensions");
        check(previewResult.getInt("previewWidth") == WIDTH
                        && previewResult.getInt("previewHeight") == HEIGHT,
                "The small preview must keep its original dimensions");
        Bitmap previewBitmap = decode(preview);
        assertPixelIdentity(expected, previewBitmap, "JXL preview");
        previewBitmap.recycle();

        success(NativeCodec.nativeExportPng(jxl.getPath(), exported.getPath()));
        Bitmap exportedBitmap = decode(exported);
        check(exportedBitmap.getWidth() == WIDTH && exportedBitmap.getHeight() == HEIGHT,
                "Android could not display the exported PNG dimensions");
        check(exportedBitmap.hasAlpha() && Color.alpha(exportedBitmap.getPixel(0, 0)) == 0,
                "Android could not display the exported PNG transparency");
        exportedBitmap.recycle();
        // Android 8's 16-bit PNG -> ARGB8888 display conversion can quantize opaque
        // RGB differently from its 8-bit PNG decoder. Validate the exported samples
        // through Rust's PNG reader, then display that losslessly normalized 8-bit
        // preview. Core/JVM tests separately compare the full 16-bit PNG samples.
        File exportedPreview = new File(directory, "PNG再読み込み.png");
        success(NativeCodec.nativePreview(exported.getPath(), exportedPreview.getPath(), 2048));
        Bitmap exportedPixels = decode(exportedPreview);
        assertPixelIdentity(expected, exportedPixels, "PNG export decoded through Rust");
        exportedPixels.recycle();

        byte[] jxlBefore = Files.readAllBytes(jxl.toPath());
        byte[] exportBefore = Files.readAllBytes(exported.toPath());
        failure(NativeCodec.nativeConvert(input.getPath(), jxl.getPath()));
        failure(NativeCodec.nativeExportPng(jxl.getPath(), exported.getPath()));
        check(Arrays.equals(jxlBefore, Files.readAllBytes(jxl.toPath())), "Existing JXL was overwritten");
        check(Arrays.equals(exportBefore, Files.readAllBytes(exported.toPath())), "Existing PNG was overwritten");

        failure(NativeCodec.nativePreview(input.getPath(), input.getPath(), 2048));
        failure(NativeCodec.nativePreview(input.getPath(), preview.getPath(), 0));
        Bitmap unchanged = decode(input);
        assertPixelIdentity(expected, unchanged, "Input after rejected overwrite");
        unchanged.recycle();

        JSONObject smallResult = success(NativeCodec.nativePreview(jxl.getPath(), preview.getPath(), 8));
        Bitmap small = decode(preview);
        check(small.getWidth() <= 8 && small.getHeight() <= 8, "Preview size limit was ignored");
        check(small.getWidth() > 0 && small.getHeight() > 0, "Preview dimensions are empty");
        check(smallResult.getInt("previewWidth") == small.getWidth()
                        && smallResult.getInt("previewHeight") == small.getHeight(),
                "Preview metadata does not match the PNG");
        small.recycle();
        expected.recycle();
    }

    private void testActivityLaunch() throws Exception {
        Intent intent = new Intent();
        intent.setClassName(getTargetContext().getPackageName(), "com.jxlstudio.app.MainActivity");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final Activity activity = startActivitySync(intent);
        check(activity != null, "MainActivity did not launch");
        final CountDownLatch laidOut = new CountDownLatch(1);
        runOnMainSync(new Runnable() {
            @Override
            public void run() {
                final View decor = activity.getWindow().getDecorView();
                if (decor.getWidth() > 0 && decor.getHeight() > 0) {
                    laidOut.countDown();
                } else {
                    decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            if (decor.getWidth() > 0 && decor.getHeight() > 0) {
                                decor.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                                laidOut.countDown();
                            }
                        }
                    });
                }
            }
        });
        check(laidOut.await(10, TimeUnit.SECONDS), "MainActivity layout timed out");
        waitForIdleSync();
        final Throwable[] uiFailure = new Throwable[1];
        runOnMainSync(new Runnable() {
            @Override
            public void run() {
                try {
                    View decor = activity.getWindow().getDecorView();
                    check(!activity.isFinishing() && !activity.isDestroyed(), "MainActivity closed during startup");
                    check(decor.getWidth() > 0 && decor.getHeight() > 0, "MainActivity did not lay out its UI");
                    check(decor.isShown(), "MainActivity decor is not visible");
                } catch (Throwable failure) {
                    uiFailure[0] = failure;
                }
            }
        });
        if (uiFailure[0] != null) throw new AssertionError("MainActivity UI smoke test failed", uiFailure[0]);
    }

    private static Bitmap decode(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
        check(bitmap != null, "Android could not decode " + file.getName());
        return bitmap;
    }

    private static void assertPixelIdentity(Bitmap expected, Bitmap actual, String label) {
        check(expected.getWidth() == actual.getWidth() && expected.getHeight() == actual.getHeight(),
                label + ": dimensions changed");
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                int before = expected.getPixel(x, y);
                int after = actual.getPixel(x, y);
                int alpha = Color.alpha(before);
                check(alpha == Color.alpha(after), label + ": alpha changed at " + x + "," + y);
                if (alpha == 0) continue; // Hidden RGB is undefined in Android's premultiplied Bitmap.
                // Opaque samples must match exactly. Partial-alpha samples allow one
                // step for Android's premultiply/unpremultiply rounding during decode.
                int tolerance = alpha == 255 ? 0 : 1;
                check(Math.abs(Color.red(before) - Color.red(after)) <= tolerance
                                && Math.abs(Color.green(before) - Color.green(after)) <= tolerance
                                && Math.abs(Color.blue(before) - Color.blue(after)) <= tolerance,
                        label + ": RGB changed at " + x + "," + y
                                + " before=" + Integer.toHexString(before) + " after=" + Integer.toHexString(after));
            }
        }
    }

    private static JSONObject success(String response) throws Exception {
        check(response != null, "JNI returned null");
        JSONObject json = new JSONObject(response);
        check(json.optBoolean("ok", false), "JNI failed: " + response);
        return json;
    }

    private static void failure(String response) throws Exception {
        check(response != null, "JNI returned null instead of a JSON error");
        JSONObject json = new JSONObject(response);
        check(json.has("ok") && !json.getBoolean("ok") && !json.optString("error").isEmpty(),
                "Expected a JSON error: " + response);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
