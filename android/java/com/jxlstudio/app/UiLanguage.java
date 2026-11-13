package com.jxlstudio.app;

import android.content.Context;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** App-owned text only; file names and useful OS/decoder details remain intact. */
public final class UiLanguage {
    public static final String PREFERENCES = "jxl_studio";
    public static final String KEY = "display_language";
    private static final Map<String, String> TEXT = new LinkedHashMap<>();
    private static final Map<String, String> NATIVE_ERRORS = new ConcurrentHashMap<>();
    private static final Pattern ADDED = Pattern.compile("^([0-9]+)件を追加しました(.*)$", Pattern.DOTALL);
    private static final Pattern SUMMARY = Pattern.compile("^(停止しました|変換完了): 成功([0-9]+)件・エラー([0-9]+)件$");

    static {
        text("JPEG XL 変換 & ビューアー  /  Rust", "JPEG XL converter & viewer / Rust");
        text("画像を開く", "Open images");
        text("JXLへ一括変換", "Convert all to JXL");
        text("画像の一覧", "Image list");
        text("元画像", "Original");
        text("変換後", "Converted");
        text("全体", "Fit");
        text("画像ビューアー。ピンチで拡大、ドラッグで移動、ダブルタップで全体表示切り替え",
                "Image viewer. Pinch to zoom, drag to pan, double-tap to toggle fit.");
        text("ピンチで拡大・ドラッグで移動", "Pinch to zoom · Drag to pan");
        text("JXLを保存", "Save JXL");
        text("PNGに保存", "Save PNG");
        text("変換後は「保存」で端末に書き出してください。\n可逆圧縮のみ。メタデータは保持しません。",
                "Save converted images to keep them on your device.\nLossless only. Metadata is not retained.");
        text("画像がありません", "No images");
        text("現在の画像後に停止", "Stopping after image");
        text("変換を停止", "Stop conversion");
        text("画像を選んでください。通信は行いません。", "Choose images. Processing stays on your device.");
        text("前回の画像を復元しました。", "Restored the previous images.");
        text("変換待ち", "Ready to convert");
        text("JPEG XL / 表示用", "JPEG XL / viewer");
        text("変換済み", "Converted");
        text("画像を読み込んでいます…", "Importing images…");
        text("一覧に追加できる画像は32件までです", "The list can contain up to 32 images");
        text("プレビューを読み込んでいます…", "Loading preview…");
        text("表示しました。変換後は保存ボタンで書き出してください。", "Image displayed. Use Save to keep converted images.");
        text("プレビューを表示できません", "Cannot display the preview");
        text("一時画像を復元できませんでした。画像を開き直し、もう一度保存してください。",
                "The temporary image is no longer available. Open the image again and save it.");
        text("JPEG・PNG・WebP・GIF・BMP・TIFF・JXLを選んでください", "Choose JPEG, PNG, WebP, GIF, BMP, TIFF or JXL");
        text("PNGを書き出しています…", "Exporting PNG…");
        text("選択した場所に保存しています…", "Saving to the selected location…");
        text("保存しました。", "Saved.");
        text("確認してください", "Please check");
        text("Rustから応答がありません", "The image engine did not return a response");
        text("画像処理に失敗しました", "Image processing failed");
        text("入力画像は128 MiBまでです", "Input images must be 128 MiB or smaller");
        text("ファイル選択を開けません", "Cannot open the file picker");
        text("画像を開けません", "Cannot open the image");
        text("保存先を選択できません", "Cannot choose a save location");
        text("保存先を開けません", "Cannot open the save location");
        text("JPEG XLを、そのまま開く。", "Open JPEG XL images.");
        // Fragments occur in messages that contain image names, sizes or decoder details.
        text("  ·  ピンチで拡大  ·  プレビュー最大2048px", "  ·  Pinch to zoom  ·  Preview up to 2048px");
        text("変換中…", "Converting…");
        text("変換中: ", "Converting: ");
        text("完了 ", "Done ");
        text("エラー: ", "Error: ");
    }

    private UiLanguage() {}
    private static void text(String japanese, String english) { TEXT.put(japanese, english); }

    public static boolean loadEnglish(Context context) {
        String saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, "");
        if ("en".equals(saved)) return true;
        if ("ja".equals(saved)) return false;
        return !"ja".equals(Locale.getDefault().getLanguage());
    }

    public static void saveEnglish(Context context, boolean english) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY, english ? "en" : "ja").apply();
    }

    public static void registerNativeError(String original, String english) {
        if (original != null && english != null && !english.isEmpty()) NATIVE_ERRORS.put(original, english);
    }

    public static String translate(String value, boolean english) {
        if (!english || value == null || value.isEmpty()) return value;
        String nativeError = NATIVE_ERRORS.get(value);
        if (nativeError != null) return nativeError;
        String exact = TEXT.get(value);
        if (exact != null) return exact;
        Matcher added = ADDED.matcher(value);
        if (added.matches()) {
            String suffix = added.group(2);
            if (suffix.startsWith(" / ")) suffix = " / " + translate(suffix.substring(3), true);
            return "Added " + added.group(1) + " image(s)" + suffix;
        }
        Matcher summary = SUMMARY.matcher(value);
        if (summary.matches()) return ("停止しました".equals(summary.group(1)) ? "Stopped" : "Conversion complete")
                + ": " + summary.group(2) + " succeeded, " + summary.group(3) + " failed";
        if (value.startsWith("エラー: ")) return "Error: " + translate(value.substring(5), true);
        if (value.startsWith("変換中: ")) return "Converting: " + value.substring(5);
        if (value.startsWith("完了 ")) return "Done " + value.substring(3);
        for (Map.Entry<String, String> text : TEXT.entrySet()) {
            String prefix = text.getKey() + ": ";
            if (value.startsWith(prefix)) return text.getValue() + ": " + value.substring(prefix.length());
        }
        String previewSuffix = "  ·  ピンチで拡大  ·  プレビュー最大2048px";
        if (value.endsWith(previewSuffix)) return value.substring(0, value.length() - previewSuffix.length())
                + "  ·  Pinch to zoom  ·  Preview up to 2048px";
        return value;
    }

}
