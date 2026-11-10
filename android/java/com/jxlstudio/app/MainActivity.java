package com.jxlstudio.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Android file access stays in the system picker. Pixels are processed by Rust. */
public final class MainActivity extends Activity {
    private static final int OPEN = 10, SAVE = 11;
    private static final int BG = Color.rgb(16, 23, 34);
    private static final int TEXT = Color.rgb(234, 242, 248);
    private static final int MUTED = Color.rgb(156, 174, 192);
    private static final int MINT = Color.rgb(104, 231, 190);
    private static final long MAX_IMPORT_BYTES = 128L * 1024 * 1024;
    private Session session;
    private Button open, convert, saveJxl, savePng, original, converted;
    private TextView status, details;
    private ProgressBar progress;
    private Spinner files;
    private ZoomImageView viewer;
    private ArrayAdapter<String> adapter;
    private boolean updating;

    static final class Item {
        final File input;
        final String name;
        File output;
        String state = "変換待ち";
        Item(File input, String name) {
            this.input = input;
            this.name = name;
            if (isJxl(input)) state = "JPEG XL / 表示用";
        }
    }

    /** Retained across activity recreation; callbacks always use the current screen. */
    static final class Session {
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final Handler main = new Handler(Looper.getMainLooper());
        final ArrayList<Item> items = new ArrayList<>();
        final File cache;
        WeakReference<MainActivity> activity = new WeakReference<>(null);
        int selected = -1, width, height;
        boolean busy, converting, sourceView;
        volatile boolean cancel;
        Bitmap bitmap;
        String message = "画像を選んでください。通信は行いません。";
        File pendingExport;
        Session(File cache) { this.cache = cache; }
        void post(Runnable action) {
            main.post(() -> {
                action.run();
                MainActivity current = activity.get();
                if (current != null && !current.isFinishing()) current.refresh();
            });
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Object retained = getLastNonConfigurationInstance();
        if (retained instanceof Session) session = (Session) retained;
        else if (state != null && restoreSession(state)) {
            session.message = "前回の画像を復元しました。";
        } else {
            File cache = new File(getCacheDir(), "images-" + UUID.randomUUID());
            if (!cache.mkdirs()) { finish(); return; }
            session = new Session(cache);
        }
        session.activity = new WeakReference<>(this);
        createScreen();
        refresh();
    }

    @Override public Object onRetainNonConfigurationInstance() { return session; }

    @Override protected void onResume() {
        super.onResume();
        session.main.post(() -> {
            if (!isFinishing() && !session.busy && session.bitmap == null && selected() != null && session.pendingExport == null) loadPreview();
        });
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("cache", session.cache.getName());
        ArrayList<String> inputs = new ArrayList<>(), outputs = new ArrayList<>(), names = new ArrayList<>();
        for (Item item : session.items) {
            inputs.add(item.input.getName()); outputs.add(item.output == null ? null : item.output.getName()); names.add(item.name);
        }
        state.putStringArrayList("inputs", inputs); state.putStringArrayList("outputs", outputs); state.putStringArrayList("names", names);
        state.putInt("selected", session.selected); state.putBoolean("sourceView", session.sourceView);
        if (session.pendingExport != null) state.putString("pendingExport", session.pendingExport.getName());
    }

    private boolean restoreSession(Bundle state) {
        String name = state.getString("cache");
        if (name == null || !name.matches("images-[0-9a-fA-F-]{36}")) return false;
        File cache = new File(getCacheDir(), name);
        if (!cache.isDirectory()) return false;
        session = new Session(cache);
        ArrayList<String> inputs = state.getStringArrayList("inputs"), outputs = state.getStringArrayList("outputs"), names = state.getStringArrayList("names");
        if (inputs != null && outputs != null && names != null) {
            int count = Math.min(32, Math.min(inputs.size(), Math.min(outputs.size(), names.size())));
            for (int i = 0; i < count; i++) {
                File input = cacheFile(cache, inputs.get(i));
                if (input == null) continue;
                Item item = new Item(input, names.get(i) == null ? input.getName() : names.get(i));
                item.output = cacheFile(cache, outputs.get(i));
                if (item.output != null) item.state = "変換済み";
                session.items.add(item);
            }
        }
        session.selected = session.items.isEmpty() ? -1 : Math.max(0, Math.min(session.items.size() - 1, state.getInt("selected", 0)));
        session.sourceView = state.getBoolean("sourceView");
        session.pendingExport = cacheFile(cache, state.getString("pendingExport"));
        return true;
    }

    private static File cacheFile(File directory, String name) {
        if (name == null || name.contains("/") || name.contains("\\") || name.equals("..")) return null;
        File file = new File(directory, name);
        return file.isFile() && file.length() > 0 ? file : null;
    }

    @Override protected void onDestroy() {
        if (session != null && session.activity.get() == this) {
            session.activity.clear();
            if (isFinishing()) {
                session.cancel = true;
                Session closing = session;
                if (!closing.worker.isShutdown()) {
                    closing.worker.execute(() -> {
                        // Only flat, session-owned cache copies; never original/provider files.
                        File[] files = closing.cache.listFiles();
                        if (files != null) for (File file : files) if (file.isFile()) file.delete();
                        closing.cache.delete();
                    });
                    closing.worker.shutdown();
                }
            }
        }
        super.onDestroy();
    }

    private void createScreen() {
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(dp(16) + bars.left, dp(10) + bars.top, dp(16) + bars.right, dp(10) + bars.bottom);
                return insets;
            });
        }
        TextView heading = label("JXL STUDIO", 25, MINT);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(heading);
        root.addView(label("JPEG XL 変換 & ビューアー  /  Rust", 12, MUTED));

        LinearLayout actions = row();
        open = button("画像を開く", true, this::pickImages);
        convert = button("JXLへ一括変換", true, this::convertImages);
        addButton(actions, open); addButton(actions, convert);
        root.addView(actions);

        files = new Spinner(this);
        files.setContentDescription("画像の一覧");
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, new ArrayList<>()) {
            @Override public View getView(int position, View view, android.view.ViewGroup parent) {
                TextView text = (TextView) super.getView(position, view, parent);
                text.setTextColor(TEXT); text.setTextSize(13); return text;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        files.setAdapter(adapter);
        files.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!updating && !session.busy && position != session.selected) {
                    session.selected = position; session.sourceView = false; loadPreview();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        root.addView(files, new LinearLayout.LayoutParams(-1, dp(44)));

        LinearLayout modes = row();
        original = button("元画像", false, () -> { session.sourceView = true; loadPreview(); });
        converted = button("変換後", false, () -> { session.sourceView = false; loadPreview(); });
        addButton(modes, original); addButton(modes, converted);
        Button fit = button("全体", false, () -> viewer.fit());
        Button actual = button("100%", false, () -> viewer.actualSize());
        addButton(modes, fit); addButton(modes, actual);
        root.addView(modes);

        viewer = new ZoomImageView(this);
        viewer.setMinimumHeight(dp(200));
        viewer.setContentDescription("画像ビューアー。ピンチで拡大、ドラッグで移動、ダブルタップで全体表示切り替え");
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(-1, dp(200), 1);
        imageParams.topMargin = dp(8); imageParams.bottomMargin = dp(4);
        root.addView(viewer, imageParams);
        details = label("ピンチで拡大・ドラッグで移動", 11, MUTED);
        root.addView(details);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));
        status = label("", 12, TEXT);
        status.setPadding(0, dp(6), 0, dp(4));
        status.setMinLines(2); status.setMaxLines(3);
        root.addView(status);
        LinearLayout saves = row();
        saveJxl = button("JXLを保存", true, () -> prepareExport(false));
        savePng = button("PNGに保存", false, () -> prepareExport(true));
        addButton(saves, saveJxl); addButton(saves, savePng);
        root.addView(saves);
        TextView note = label("変換後は「保存」で端末に書き出してください。\n可逆圧縮のみ。メタデータは保持しません。", 10, MUTED);
        note.setPadding(0, dp(6), 0, 0);
        root.addView(note);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
    }

    private void refresh() {
        updating = true;
        adapter.clear();
        if (session.items.isEmpty()) adapter.add("画像がありません");
        else for (Item item : session.items) adapter.add(item.name + "  —  " + item.state);
        adapter.notifyDataSetChanged();
        if (session.selected >= 0) files.setSelection(session.selected, false);
        updating = false;
        Item item = selected();
        boolean canConvert = false;
        for (Item candidate : session.items) if (!isJxl(candidate.input) && candidate.output == null) canConvert = true;
        open.setEnabled(!session.busy);
        files.setEnabled(!session.busy && !session.items.isEmpty());
        convert.setText(session.converting ? (session.cancel ? "現在の画像後に停止" : "変換を停止") : "JXLへ一括変換");
        convert.setEnabled(session.converting ? !session.cancel : (!session.busy && canConvert));
        original.setEnabled(!session.busy && item != null && item.output != null);
        converted.setEnabled(!session.busy && item != null && item.output != null);
        saveJxl.setEnabled(!session.busy && item != null && (item.output != null || isJxl(item.input)));
        savePng.setEnabled(!session.busy && item != null && session.bitmap != null);
        progress.setVisibility(session.busy ? View.VISIBLE : View.INVISIBLE);
        status.setText(session.message);
        viewer.setImage(session.bitmap, session.width, session.height);
        details.setText(session.bitmap == null ? "ピンチで拡大・ドラッグで移動" :
                session.width + " × " + session.height + " px  ·  ピンチで拡大  ·  プレビュー最大2048px");
    }

    private Item selected() {
        return session.selected >= 0 && session.selected < session.items.size() ? session.items.get(session.selected) : null;
    }

    private File displayedFile() {
        Item item = selected();
        return item == null ? null : (!session.sourceView && item.output != null ? item.output : item.input);
    }

    private void pickImages() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Some document providers report .jxl as application/octet-stream.
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(intent, OPEN); }
        catch (Exception e) { showError("ファイル選択を開けません: " + message(e)); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SAVE) {
            File file = session.pendingExport;
            session.pendingExport = null;
            if (result == RESULT_OK && data != null && data.getData() != null) {
                if (file != null && file.isFile()) copyExport(file, data.getData());
                else showError("一時画像を復元できませんでした。画像を開き直し、もう一度保存してください。");
            }
        } else if (request == OPEN && result == RESULT_OK && data != null) {
            List<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
            else if (data.getData() != null) uris.add(data.getData());
            importImages(uris);
        }
    }

    private void importImages(List<Uri> uris) {
        if (uris.isEmpty() || session.busy) return;
        final Session work = session;
        final ContentResolver resolver = getContentResolver();
        work.busy = true; work.message = "画像を読み込んでいます…"; refresh();
        work.worker.execute(() -> {
            ArrayList<Item> imported = new ArrayList<>();
            ArrayList<String> errors = new ArrayList<>();
            int capacity = Math.min(32 - work.items.size(), uris.size());
            for (int i = 0; i < capacity; i++) {
                if (work.cancel) break;
                try { imported.add(importImage(resolver, uris.get(i), work.cache)); }
                catch (Exception e) { errors.add(message(e)); }
            }
            if (capacity < uris.size()) errors.add("一覧に追加できる画像は32件までです");
            work.post(() -> {
                int first = work.items.size();
                work.items.addAll(imported); work.busy = false;
                work.message = imported.size() + "件を追加しました" + (errors.isEmpty() ? "" : " / " + errors.get(0));
                MainActivity screen = work.activity.get();
                if (!imported.isEmpty()) { work.selected = first; work.sourceView = false; if (screen != null) screen.loadPreview(); }
            });
        });
    }

    private static Item importImage(ContentResolver resolver, Uri uri, File directory) throws Exception {
        String name = "image";
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0);
        }
        String extension = extension(name);
        if (!supported(extension)) {
            String mime = resolver.getType(uri);
            if ("image/jxl".equals(mime)) extension = "jxl";
            else if ("image/jpeg".equals(mime)) extension = "jpg";
            else if ("image/png".equals(mime)) extension = "png";
            else if ("image/webp".equals(mime)) extension = "webp";
            else if ("image/gif".equals(mime)) extension = "gif";
            else if ("image/tiff".equals(mime)) extension = "tiff";
            else throw new Exception("JPEG・PNG・WebP・GIF・BMP・TIFF・JXLを選んでください: " + name);
        }
        File input = File.createTempFile("input-", "." + extension, directory);
        try (InputStream in = resolver.openInputStream(uri); OutputStream out = new FileOutputStream(input)) {
            if (in == null) throw new Exception("画像を開けません");
            copy(in, out, MAX_IMPORT_BYTES);
        } catch (Exception e) { input.delete(); throw e; }
        return new Item(input, name);
    }

    private void loadPreview() {
        File input = displayedFile();
        if (input == null || session.busy) return;
        final Session work = session;
        work.busy = true; work.bitmap = null; work.message = "プレビューを読み込んでいます…"; refresh();
        work.worker.execute(() -> {
            try {
                File preview = new File(work.cache, "preview.png");
                JSONObject info = checked(NativeCodec.nativePreview(input.getAbsolutePath(), preview.getAbsolutePath(), 2048));
                Bitmap bitmap = BitmapFactory.decodeFile(preview.getAbsolutePath());
                if (bitmap == null) throw new Exception("プレビューを表示できません");
                int width = info.getInt("width"), height = info.getInt("height");
                work.post(() -> {
                    work.bitmap = bitmap; work.width = width; work.height = height; work.busy = false;
                    work.message = "表示しました。変換後は保存ボタンで書き出してください。";
                });
            } catch (Exception | LinkageError e) { fail(work, e); }
        });
    }

    private void convertImages() {
        if (session.converting) { session.cancel = true; refresh(); return; }
        if (session.busy) return;
        final Session work = session;
        final ArrayList<Item> jobs = new ArrayList<>();
        for (Item item : work.items) if (!isJxl(item.input) && item.output == null) jobs.add(item);
        if (jobs.isEmpty()) return;
        work.busy = true; work.converting = true; work.cancel = false; refresh();
        work.worker.execute(() -> {
            int done = 0, failed = 0;
            for (Item item : jobs) {
                if (work.cancel) break;
                work.post(() -> { item.state = "変換中…"; work.message = "変換中: " + item.name; });
                try {
                    File output = new File(work.cache, "converted-" + UUID.randomUUID() + ".jxl");
                    JSONObject report = checked(NativeCodec.nativeConvert(item.input.getAbsolutePath(), output.getAbsolutePath()));
                    String state = "完了 " + bytes(report.getLong("inputBytes")) + " → " + bytes(report.getLong("outputBytes"));
                    work.post(() -> { item.output = output; item.state = state; });
                    done++;
                } catch (Exception | LinkageError e) {
                    String error = message(e); work.post(() -> { item.state = "エラー: " + error; }); failed++;
                }
            }
            final String summary = (work.cancel ? "停止しました" : "変換完了") + ": 成功" + done + "件・エラー" + failed + "件";
            work.post(() -> {
                work.busy = false; work.converting = false; work.sourceView = false; work.message = summary;
                MainActivity screen = work.activity.get();
                if (screen != null && screen.selected() != null && screen.selected().output != null) screen.loadPreview();
            });
        });
    }

    private void prepareExport(boolean png) {
        Item item = selected();
        if (item == null || session.busy) return;
        if (!png) {
            File file = item.output != null ? item.output : item.input;
            launchSave(file, stem(item.name) + ".jxl", "image/jxl");
            return;
        }
        File input = displayedFile();
        String title = stem(item.name) + ".png";
        final Session work = session;
        work.busy = true; work.message = "PNGを書き出しています…"; refresh();
        work.worker.execute(() -> {
            try {
                File output = new File(work.cache, "export-" + UUID.randomUUID() + ".png");
                checked(NativeCodec.nativeExportPng(input.getAbsolutePath(), output.getAbsolutePath()));
                work.post(() -> {
                    work.busy = false;
                    MainActivity screen = work.activity.get();
                    if (screen != null) screen.launchSave(output, title, "image/png");
                });
            } catch (Exception | LinkageError e) { fail(work, e); }
        });
    }

    private void launchSave(File file, String title, String mime) {
        session.pendingExport = file;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mime);
        intent.putExtra(Intent.EXTRA_TITLE, title);
        try { startActivityForResult(intent, SAVE); }
        catch (Exception e) { session.pendingExport = null; showError("保存先を選択できません: " + message(e)); }
    }

    private void copyExport(File file, Uri destination) {
        final Session work = session;
        ContentResolver resolver = getContentResolver();
        work.busy = true; work.message = "選択した場所に保存しています…"; refresh();
        work.worker.execute(() -> {
            try (InputStream in = new FileInputStream(file); OutputStream out = resolver.openOutputStream(destination, "w")) {
                if (out == null) throw new Exception("保存先を開けません");
                copy(in, out, Long.MAX_VALUE);
                work.post(() -> { work.busy = false; work.message = "保存しました。"; });
            } catch (Exception e) { fail(work, e); }
        });
    }

    private static void fail(Session work, Throwable error) {
        String text = message(error);
        work.post(() -> { work.busy = false; work.converting = false; work.message = "エラー: " + text; });
    }

    private void showError(String text) {
        session.message = text; refresh();
        new AlertDialog.Builder(this).setTitle("確認してください").setMessage(text).setPositiveButton("OK", null).show();
    }

    private static JSONObject checked(String json) throws Exception {
        if (json == null) throw new Exception("Rustから応答がありません");
        JSONObject value = new JSONObject(json);
        if (!value.optBoolean("ok")) throw new Exception(value.optString("error", "画像処理に失敗しました"));
        return value;
    }

    private static void copy(InputStream in, OutputStream out, long limit) throws Exception {
        byte[] buffer = new byte[64 * 1024]; long total = 0; int count;
        while ((count = in.read(buffer)) != -1) {
            total += count;
            if (total > limit) throw new Exception("入力画像は128 MiBまでです");
            out.write(buffer, 0, count);
        }
        out.flush();
    }

    private TextView label(String text, float size, int color) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); return view;
    }
    private LinearLayout row() { LinearLayout layout = new LinearLayout(this); layout.setGravity(Gravity.CENTER_VERTICAL); return layout; }
    private Button button(String text, boolean accent, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setAllCaps(false); button.setTextSize(12);
        button.setMinHeight(dp(44)); button.setMinimumWidth(0); button.setMinWidth(0); button.setPadding(dp(3), 0, dp(3), 0);
        button.setBackgroundTintList(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{Color.rgb(35, 48, 57), accent ? MINT : Color.rgb(41, 56, 73)}));
        button.setTextColor(accent ? Color.rgb(10, 34, 29) : TEXT);
        button.setOnClickListener(v -> action.run()); return button;
    }
    private void addButton(LinearLayout row, Button button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
        params.setMargins(dp(2), dp(2), dp(2), dp(2)); row.addView(button, params);
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String extension(String name) { int dot = name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT); }
    private static String stem(String name) { int dot = name.lastIndexOf('.'); return dot > 0 ? name.substring(0, dot) : name; }
    private static boolean isJxl(File file) { return "jxl".equals(extension(file.getName())); }
    private static boolean supported(String ext) { return ext.matches("jpg|jpeg|png|webp|gif|bmp|tif|tiff|jxl"); }
    private static String bytes(long value) { return String.format(Locale.ROOT, "%.1f KiB", value / 1024.0); }
    private static String message(Throwable error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
}
