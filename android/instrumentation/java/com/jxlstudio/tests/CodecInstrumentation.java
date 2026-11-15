package com.jxlstudio.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import android.widget.Spinner;
import com.jxlstudio.app.NativeCodec;
import com.jxlstudio.app.UiLanguage;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Device/emulator tests using Android graphics, real Rust JNI, and the actual activity. */
public final class CodecInstrumentation extends Instrumentation {
    private static final int WIDTH = 17;
    private static final int HEIGHT = 19;
    private Activity activeActivity;

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
            testImportAfterStop();
            result.putString("jxlResult", "PASS");
            result.putString("stream", "jxlResult=PASS\nJXL Android instrumentation passed: real JNI conversion, "
                    + "dimensions, transparent and opaque pixels, preview, PNG export, Japanese paths, "
                    + "no-overwrite errors, preview size limits, MainActivity launch, persistent Japanese/English switching, and importing after stopping.\n");
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
                    View japanese = findText(decor, "日本語");
                    check(japanese != null && japanese.performClick(), "Japanese language control is unavailable");
                    check(findText(decor, "画像を開く") != null, "Japanese labels did not appear");
                    Spinner fileList = findSpinner(decor);
                    check(fileList != null && "画像がありません".equals(fileList.getAdapter().getItem(0).toString()),
                            "Japanese list state did not appear");
                    View english = findText(decor, "English");
                    check(english != null && english.performClick(), "English language control is unavailable");
                    check(findText(decor, "Open images") != null, "English labels did not appear");
                    check("No images".equals(fileList.getAdapter().getItem(0).toString()), "English list state did not appear");
                    check("en".equals(activity.getSharedPreferences(UiLanguage.PREFERENCES, Context.MODE_PRIVATE)
                            .getString(UiLanguage.KEY, "")), "The language preference was not saved");
                    check(UiLanguage.loadEnglish(activity), "A fresh language preference read did not return English");
                    check("Converting: 元画像.png".equals(UiLanguage.translate("変換中: 元画像.png", true)),
                            "The Japanese image name changed during translation");
                    UiLanguage.registerNativeError("画像処理に失敗: 元画像.png", "Image processing failed: 元画像.png");
                    check("Error: Image processing failed: 元画像.png".equals(
                            UiLanguage.translate("エラー: 画像処理に失敗: 元画像.png", true)),
                            "Native error translation altered the image name");
                } catch (Throwable failure) {
                    uiFailure[0] = failure;
                }
            }
        });
        if (uiFailure[0] != null) throw new AssertionError("MainActivity UI smoke test failed", uiFailure[0]);
        runOnMainSync(() -> activity.finish());
        waitForIdleSync();
        final Activity restarted = startActivitySync(intent);
        check(restarted != null && restarted != activity, "MainActivity did not restart");
        waitForIdleSync();
        final Throwable[] restartFailure = new Throwable[1];
        runOnMainSync(() -> {
            try {
                check(findText(restarted.getWindow().getDecorView(), "Open images") != null,
                        "English preference was not restored after restarting MainActivity");
                check(UiLanguage.loadEnglish(restarted), "Restarted activity lost the saved language");
            } catch (Throwable failure) { restartFailure[0] = failure; }
        });
        if (restartFailure[0] != null) throw new AssertionError("Language persistence test failed", restartFailure[0]);
        activeActivity = restarted;
    }

    /** A completed Stop must not cancel a later, independent file import. */
    private void testImportAfterStop() throws Exception {
        final Activity activity = activeActivity;
        check(activity != null && !activity.isFinishing(), "The restarted activity is unavailable for import testing");
        final File fixture = new File(getTargetContext().getCacheDir(), "元画像-import-after-stop-" + System.nanoTime() + ".png");
        Bitmap pixels = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        pixels.eraseColor(Color.rgb(12, 45, 200));
        try (FileOutputStream output = new FileOutputStream(fixture)) {
            check(pixels.compress(Bitmap.CompressFormat.PNG, 100, output), "Cannot write the import fixture");
        }
        pixels.recycle();
        final Object[] sessionHolder = new Object[1];
        final Field[] busyField = new Field[1];
        final Throwable[] startFailure = new Throwable[1];
        runOnMainSync(() -> {
            try {
                Object session = accessibleField(activity, "session").get(activity);
                sessionHolder[0] = session;
                busyField[0] = accessibleField(session, "busy");
                check(!busyField[0].getBoolean(session), "The test activity is already busy");
                check(((List<?>) accessibleField(session, "items").get(session)).isEmpty(),
                        "The restarted activity should begin with an empty image list");
                accessibleField(session, "cancel").setBoolean(session, true);
                Method importImages = activity.getClass().getDeclaredMethod("importImages", List.class);
                importImages.setAccessible(true);
                importImages.invoke(activity, Collections.singletonList(Uri.fromFile(fixture)));
                check(busyField[0].getBoolean(session), "The new import did not start");
            } catch (Throwable failure) { startFailure[0] = failure; }
        });
        if (startFailure[0] != null) throw new AssertionError("Cannot start the import-after-stop regression", startFailure[0]);

        final Bitmap[] displayed = new Bitmap[1];
        final File[] importedSource = new File[1];
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (true) {
            final boolean[] idle = new boolean[1];
            final Throwable[] importFailure = new Throwable[1];
            runOnMainSync(() -> {
                try {
                    Object session = sessionHolder[0];
                    idle[0] = !busyField[0].getBoolean(session);
                    if (!idle[0]) return;
                    String message = String.valueOf(accessibleField(session, "message").get(session));
                    List<?> items = (List<?>) accessibleField(session, "items").get(session);
                    check(items.size() == 1, "Import after Stop added " + items.size() + " images; status=" + message);
                    check(accessibleField(session, "selected").getInt(session) == 0, "The new image was not selected");
                    displayed[0] = (Bitmap) accessibleField(session, "bitmap").get(session);
                    check(displayed[0] != null && !displayed[0].isRecycled(), "The imported image preview is missing; status=" + message);
                    check(accessibleField(session, "width").getInt(session) == WIDTH
                                    && accessibleField(session, "height").getInt(session) == HEIGHT,
                            "The imported image dimensions changed");
                    Object viewer = accessibleField(activity, "viewer").get(activity);
                    check(accessibleField(viewer, "bitmap").get(viewer) == displayed[0],
                            "The imported preview was not sent to the displayed viewer");
                    importedSource[0] = (File) accessibleField(items.get(0), "input").get(items.get(0));
                } catch (Throwable failure) { importFailure[0] = failure; }
            });
            if (importFailure[0] != null) throw new AssertionError("Import-after-stop regression failed", importFailure[0]);
            if (idle[0]) break;
            check(System.nanoTime() < deadline, "Import after Stop did not finish in time");
            Thread.sleep(25);
        }
        check(fixture.isFile() && importedSource[0] != null && importedSource[0].isFile(), "Import source files are missing");
        check(!fixture.equals(importedSource[0]), "Import must copy the selected source into its session cache");
        check(Arrays.equals(Files.readAllBytes(fixture.toPath()), Files.readAllBytes(importedSource[0].toPath())),
                "Import changed the source image bytes");
        Bitmap expected = decode(fixture);
        assertPixelIdentity(expected, displayed[0], "Imported preview after Stop");
        expected.recycle();
    }

    private static Field accessibleField(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Spinner findSpinner(View root) {
        if (root instanceof Spinner) return (Spinner) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                Spinner found = findSpinner(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findText(View root, String text) {
        if (root instanceof TextView && text.equals(((TextView) root).getText().toString())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
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
