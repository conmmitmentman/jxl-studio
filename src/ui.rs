use std::{
    path::PathBuf,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
        mpsc::{self, Receiver, Sender},
    },
    time::Duration,
};

use eframe::egui::{self, Color32, RichText, Vec2};
use jxl_studio::{ConversionReport, available_output, format_bytes, is_convertible, is_jxl};

const ACCENT: Color32 = Color32::from_rgb(104, 231, 190);
const MUTED: Color32 = Color32::from_rgb(148, 163, 184);

enum State {
    Ready,
    Working,
    Done(ConversionReport),
    Failed(String),
}
struct Item {
    path: PathBuf,
    state: State,
}
struct Preview {
    rgba: Vec<u8>,
    texture_size: [usize; 2],
    original: [u32; 2],
}
enum Event {
    Started(usize),
    Converted(usize, Result<ConversionReport, String>),
    Finished,
    Preview(u64, PathBuf, Result<Preview, String>),
    Exported(Result<PathBuf, String>),
}

pub struct Studio {
    items: Vec<Item>,
    selected: Option<usize>,
    output_directory: Option<PathBuf>,
    tx: Sender<Event>,
    rx: Receiver<Event>,
    busy: bool,
    processed: usize,
    total: usize,
    cancel: Arc<AtomicBool>,
    texture: Option<egui::TextureHandle>,
    preview_path: Option<PathBuf>,
    original_size: [u32; 2],
    texture_size: [usize; 2],
    preview_generation: u64,
    pending_preview: Option<(u64, PathBuf)>,
    preview_busy: bool,
    export_busy: bool,
    fit: bool,
    zoom: f32,
    checkerboard: bool,
    message: String,
    error: Option<String>,
}

impl Studio {
    pub fn new(cc: &eframe::CreationContext<'_>) -> Self {
        let ctx = &cc.egui_ctx;
        ctx.set_theme(egui::Theme::Dark);
        let mut visuals = egui::Visuals::dark();
        visuals.panel_fill = Color32::from_rgb(18, 24, 35);
        visuals.window_fill = Color32::from_rgb(24, 32, 46);
        visuals.extreme_bg_color = Color32::from_rgb(12, 17, 25);
        visuals.selection.bg_fill = Color32::from_rgb(35, 84, 76);
        visuals.selection.stroke.color = ACCENT;
        ctx.set_visuals(visuals);
        ctx.style_mut(|style| {
            style.spacing.item_spacing = Vec2::new(10.0, 10.0);
            style.spacing.button_padding = Vec2::new(12.0, 9.0);
        });
        install_japanese_font(ctx);
        let (tx, rx) = mpsc::channel();
        let mut app = Self {
            items: Vec::new(),
            selected: None,
            output_directory: None,
            tx,
            rx,
            busy: false,
            processed: 0,
            total: 0,
            cancel: Arc::new(AtomicBool::new(false)),
            texture: None,
            preview_path: None,
            original_size: [0, 0],
            texture_size: [0, 0],
            preview_generation: 0,
            pending_preview: None,
            preview_busy: false,
            export_busy: false,
            fit: true,
            zoom: 1.0,
            checkerboard: true,
            message: "画像を追加すると、ここに進行状況が表示されます".into(),
            error: None,
        };
        app.add_paths(std::env::args_os().skip(1).map(PathBuf::from).collect());
        app
    }

    fn add_paths(&mut self, paths: Vec<PathBuf>) {
        if self.busy {
            return;
        }
        let mut first_new = None;
        let mut rejected = 0;
        for path in paths {
            if !path.is_file() || !(is_convertible(&path) || is_jxl(&path)) {
                rejected += 1;
                continue;
            }
            let path = path.canonicalize().unwrap_or(path);
            if self.items.iter().any(|item| item.path == path) {
                continue;
            }
            first_new.get_or_insert(self.items.len());
            self.items.push(Item {
                path,
                state: State::Ready,
            });
        }
        if let Some(index) = first_new {
            self.select(index);
        }
        if rejected > 0 {
            self.error = Some(format!(
                "{rejected}件を追加できませんでした。対応する画像ファイルを選択してください。フォルダーは追加できません。"
            ));
        }
    }

    fn select(&mut self, index: usize) {
        self.selected = Some(index);
        let path = match &self.items[index].state {
            State::Done(report) => report.output.clone(),
            _ => self.items[index].path.clone(),
        };
        self.request_preview(path);
    }

    fn request_preview(&mut self, path: PathBuf) {
        self.preview_generation += 1;
        self.pending_preview = Some((self.preview_generation, path));
        self.texture = None;
        self.preview_path = None;
        self.fit = true;
    }

    fn launch_preview(&mut self, ctx: &egui::Context) {
        if self.preview_busy {
            return;
        }
        let Some((generation, path)) = self.pending_preview.take() else {
            return;
        };
        self.preview_busy = true;
        let tx = self.tx.clone();
        let ctx = ctx.clone();
        std::thread::spawn(move || {
            let result = guarded(|| {
                let image = jxl_studio::read_preview(&path)?;
                let original = [image.width(), image.height()];
                let thumbnail = image.thumbnail(4096, 4096).to_rgba8();
                Ok(Preview {
                    texture_size: [thumbnail.width() as usize, thumbnail.height() as usize],
                    original,
                    rgba: thumbnail.into_raw(),
                })
            });
            let _ = tx.send(Event::Preview(generation, path, result));
            ctx.request_repaint();
        });
    }

    fn start_conversion(&mut self, ctx: &egui::Context) {
        if self.busy {
            return;
        }
        let jobs: Vec<_> = self
            .items
            .iter()
            .enumerate()
            .filter(|(_, item)| is_convertible(&item.path) && !matches!(item.state, State::Done(_)))
            .map(|(i, item)| (i, item.path.clone()))
            .collect();
        if jobs.is_empty() {
            return;
        }
        self.busy = true;
        self.processed = 0;
        self.total = jobs.len();
        self.cancel.store(false, Ordering::Relaxed);
        self.error = None;
        let (tx, cancel, directory, ctx) = (
            self.tx.clone(),
            self.cancel.clone(),
            self.output_directory.clone(),
            ctx.clone(),
        );
        std::thread::spawn(move || {
            for (index, input) in jobs {
                if cancel.load(Ordering::Relaxed) {
                    break;
                }
                let _ = tx.send(Event::Started(index));
                ctx.request_repaint();
                let directory = directory
                    .as_deref()
                    .unwrap_or_else(|| input.parent().unwrap_or(std::path::Path::new(".")));
                let output = available_output(&input, directory);
                let result = guarded(|| jxl_studio::convert_file(&input, &output));
                let _ = tx.send(Event::Converted(index, result));
                ctx.request_repaint();
            }
            let _ = tx.send(Event::Finished);
            ctx.request_repaint();
        });
    }

    fn poll(&mut self, ctx: &egui::Context) {
        while let Ok(event) = self.rx.try_recv() {
            match event {
                Event::Started(i) => {
                    self.items[i].state = State::Working;
                    self.message = format!("変換中: {}", display_name(&self.items[i].path));
                }
                Event::Converted(i, result) => {
                    self.processed += 1;
                    match result {
                        Ok(report) => {
                            let output = report.output.clone();
                            self.items[i].state = State::Done(report);
                            if self.selected == Some(i) {
                                self.request_preview(output);
                            }
                        }
                        Err(message) => {
                            self.items[i].state = State::Failed(message);
                        }
                    }
                }
                Event::Finished => {
                    self.busy = false;
                    let failures = self
                        .items
                        .iter()
                        .filter(|i| matches!(i.state, State::Failed(_)))
                        .count();
                    self.message = format!(
                        "{}: {} / {}件処理済み・エラー{}件",
                        if self.cancel.load(Ordering::Relaxed) {
                            "停止しました"
                        } else {
                            "変換が完了しました"
                        },
                        self.processed,
                        self.total,
                        failures
                    );
                }
                Event::Preview(generation, path, result) => {
                    self.preview_busy = false;
                    if generation != self.preview_generation {
                        continue;
                    }
                    match result {
                        Ok(preview) => {
                            self.original_size = preview.original;
                            self.texture_size = preview.texture_size;
                            self.texture = Some(ctx.load_texture(
                                "preview",
                                egui::ColorImage::from_rgba_unmultiplied(
                                    preview.texture_size,
                                    &preview.rgba,
                                ),
                                egui::TextureOptions::LINEAR,
                            ));
                            self.preview_path = Some(path);
                        }
                        Err(error) => {
                            self.error = Some(error);
                        }
                    }
                }
                Event::Exported(result) => {
                    self.export_busy = false;
                    match result {
                        Ok(path) => self.message = format!("PNGを保存しました: {}", path.display()),
                        Err(error) => self.error = Some(error),
                    }
                }
            }
        }
        self.launch_preview(ctx);
    }

    fn export(&mut self, ctx: &egui::Context) {
        if self.export_busy {
            return;
        }
        let Some(input) = self.preview_path.clone() else {
            return;
        };
        let filename = input.with_extension("png");
        let Some(output) = rfd::FileDialog::new()
            .set_title("PNGとして保存（既存ファイルは上書きしません）")
            .add_filter("PNG", &["png"])
            .set_file_name(display_name(&filename))
            .save_file()
        else {
            return;
        };
        self.export_busy = true;
        let (tx, ctx) = (self.tx.clone(), ctx.clone());
        std::thread::spawn(move || {
            let result = guarded(|| {
                jxl_studio::export_png(&input, &output)?;
                Ok(output)
            });
            let _ = tx.send(Event::Exported(result));
            ctx.request_repaint();
        });
    }

    fn header(&mut self, ctx: &egui::Context) {
        egui::TopBottomPanel::top("header")
            .frame(
                egui::Frame::new()
                    .fill(Color32::from_rgb(22, 30, 43))
                    .inner_margin(20.0),
            )
            .show(ctx, |ui| {
                ui.horizontal(|ui| {
                    ui.label(RichText::new("JXL").size(28.0).strong().color(ACCENT));
                    ui.label(RichText::new("STUDIO").size(25.0).strong());
                    ui.add_space(12.0);
                    ui.label(RichText::new("画像を、JPEG XLへ。").color(MUTED));
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        ui.label(
                            RichText::new(format!("v{}", env!("CARGO_PKG_VERSION")))
                                .small()
                                .color(MUTED),
                        );
                        if ui
                            .add_enabled(!self.busy, egui::Button::new("画像を追加  /  開く"))
                            .clicked()
                        {
                            self.pick_files();
                        }
                    });
                });
            });
    }

    fn pick_files(&mut self) {
        if let Some(paths) = rfd::FileDialog::new()
            .set_title("画像を追加 / JPEG XLを開く")
            .add_filter(
                "画像",
                &[
                    "jpg", "jpeg", "png", "webp", "gif", "bmp", "tif", "tiff", "jxl",
                ],
            )
            .pick_files()
        {
            self.add_paths(paths);
        }
    }

    fn sidebar(&mut self, ctx: &egui::Context) {
        egui::SidePanel::left("files").default_width(320.0).min_width(260.0).max_width(450.0)
            .show(ctx, |ui| {
                ui.add_space(16.0);
                ui.horizontal(|ui| { ui.heading("ライブラリ"); ui.label(RichText::new(format!("{}件", self.items.len())).color(MUTED)); });
                ui.label(RichText::new("JPEG · PNG · WebP · GIF · BMP · TIFF · JXL").small().color(MUTED));
                ui.add_space(8.0);
                let mut clicked = None;
                egui::ScrollArea::vertical().id_salt("files_scroll").max_height((ui.available_height() - 305.0).max(80.0)).show(ui, |ui| {
                    if self.items.is_empty() {
                        ui.add_space(24.0);
                        ui.label(RichText::new("画像をドロップして追加\nまたは上のボタンから選択").color(MUTED));
                    }
                    for (i, item) in self.items.iter().enumerate() {
                        let (status, color) = match &item.state {
                            State::Ready => (if is_jxl(&item.path) { "JPEG XL / 表示用".to_string() } else { "変換待ち".to_string() }, MUTED),
                            State::Working => ("変換中…".to_string(), ACCENT),
                            State::Done(r) => (format!("完了  {}", format_bytes(r.output_bytes)), ACCENT),
                            State::Failed(_) => ("エラー / 再試行できます".to_string(), Color32::LIGHT_RED),
                        };
                        let response = ui.add_sized([ui.available_width(), 64.0], egui::Button::new(
                            RichText::new(format!("{}\n{status}", display_name(&item.path))).color(color))
                            .selected(self.selected == Some(i)));
                        if response.clicked() { clicked = Some(i); }
                        response.on_hover_text(item.path.display().to_string());
                    }
                });
                if let Some(i) = clicked { self.select(i); }
                ui.separator();
                ui.label(RichText::new("変換設定").strong());
                ui.label(RichText::new("可逆圧縮  /  8・16 bit  /  透過対応").color(ACCENT).small());
                ui.label(RichText::new("元の画素を保持します。容量が小さくなるとは限りません。").small().color(MUTED));
                ui.horizontal(|ui| {
                    if ui.add_enabled(!self.busy, egui::Button::new("保存先を選択")).clicked()
                        && let Some(path) = rfd::FileDialog::new().pick_folder() { self.output_directory = Some(path); }
                    if self.output_directory.is_some() && ui.add_enabled(!self.busy, egui::Button::new("解除")).clicked() { self.output_directory = None; }
                });
                ui.label(RichText::new(self.output_directory.as_ref().map(|p| p.display().to_string())
                    .unwrap_or_else(|| "保存先: 元画像と同じフォルダー".into())).small().color(MUTED));
                let can_convert = self.items.iter().any(|i| is_convertible(&i.path) && !matches!(i.state, State::Done(_)));
                if self.busy {
                    ui.add(egui::ProgressBar::new(self.processed as f32 / self.total.max(1) as f32).show_percentage());
                    let stopping = self.cancel.load(Ordering::Relaxed);
                    if ui.add_enabled(!stopping, egui::Button::new(if stopping { "現在の画像が終わると停止します" } else { "変換を停止" })).clicked() {
                        self.cancel.store(true, Ordering::Relaxed);
                    }
                } else if ui.add_enabled(can_convert, egui::Button::new(RichText::new("JPEG XL に一括変換").color(Color32::from_rgb(12, 35, 28))).fill(ACCENT)
                    .min_size(Vec2::new(ui.available_width(), 42.0))).clicked() { self.start_conversion(ctx); }
                ui.horizontal(|ui| {
                    if ui.add_enabled(!self.busy && self.selected.is_some(), egui::Button::new("選択を削除")).clicked()
                        && let Some(i) = self.selected {
                        self.items.remove(i);
                        self.clear_preview();
                        if !self.items.is_empty() { self.select(i.min(self.items.len() - 1)); }
                    }
                    if ui.add_enabled(!self.busy && !self.items.is_empty(), egui::Button::new("一覧をクリア")).clicked() {
                        self.items.clear(); self.clear_preview();
                    }
                });
                ui.label(RichText::new("EXIF・ICC等のメタデータは引き継ぎません。\nアニメーション・複数ページは先頭画像のみ。")
                    .size(11.0).color(MUTED));
            });
    }

    fn clear_preview(&mut self) {
        self.selected = None;
        self.texture = None;
        self.preview_path = None;
        self.pending_preview = None;
        self.preview_generation += 1;
    }

    fn viewer(&mut self, ctx: &egui::Context) {
        egui::CentralPanel::default().show(ctx, |ui| {
            ui.add_space(10.0);
            ui.horizontal(|ui| {
                ui.heading("ビューアー");
                if let Some(path) = &self.preview_path {
                    ui.label(RichText::new(display_name(path)).color(MUTED));
                }
            });
            ui.horizontal_wrapped(|ui| {
                if ui.selectable_label(self.fit, "画面に合わせる").clicked() {
                    self.fit = true;
                }
                if ui
                    .selectable_label(!self.fit && self.zoom == 1.0, "100%")
                    .clicked()
                {
                    self.fit = false;
                    self.zoom = 1.0;
                }
                if ui.button("−").clicked() {
                    self.fit = false;
                    self.zoom = (self.zoom / 1.25).max(0.05);
                }
                if ui.button("＋").clicked() {
                    self.fit = false;
                    self.zoom = (self.zoom * 1.25).min(8.0);
                }
                ui.label(format!("{:.0}%", self.zoom * 100.0));
                ui.checkbox(&mut self.checkerboard, "透過背景");
                if let Some(index) = self.selected
                    && let State::Done(report) = &self.items[index].state
                {
                    let (source, output) = (self.items[index].path.clone(), report.output.clone());
                    if ui.button("元画像").clicked() {
                        self.request_preview(source);
                    }
                    if ui.button("変換後").clicked() {
                        self.request_preview(output);
                    }
                }
                if ui
                    .add_enabled(
                        self.preview_path.is_some() && !self.export_busy,
                        egui::Button::new("PNGに保存"),
                    )
                    .clicked()
                {
                    self.export(ctx);
                }
            });
            ui.separator();
            if let Some(texture) = &self.texture {
                let original =
                    Vec2::new(self.original_size[0] as f32, self.original_size[1] as f32);
                let available = (ui.available_size() - Vec2::new(24.0, 55.0)).max(Vec2::splat(1.0));
                if self.fit {
                    self.zoom = (available.x / original.x)
                        .min(available.y / original.y)
                        .min(1.0);
                }
                let scroll_zoom = ui.input(|i| {
                    if i.modifiers.ctrl {
                        i.smooth_scroll_delta.y
                    } else {
                        0.0
                    }
                });
                if scroll_zoom.abs() > 0.0 {
                    self.fit = false;
                    self.zoom = (self.zoom * (scroll_zoom * 0.005).exp()).clamp(0.05, 8.0);
                }
                let size = original * self.zoom;
                egui::ScrollArea::both()
                    .id_salt("image_scroll")
                    .auto_shrink([false, false])
                    .max_height((ui.available_height() - 35.0).max(1.0))
                    .show(ui, |ui| {
                        let canvas = ui.available_size().max(size);
                        let (rect, _) = ui.allocate_exact_size(canvas, egui::Sense::hover());
                        let image_rect = egui::Rect::from_center_size(rect.center(), size);
                        let painter = ui.painter().with_clip_rect(ui.clip_rect());
                        painter.rect_filled(image_rect, 0.0, Color32::from_gray(34));
                        if self.checkerboard {
                            let visible = image_rect.intersect(ui.clip_rect());
                            let cell = 16.0;
                            let x0 = ((visible.left() - image_rect.left()) / cell).floor() as i32;
                            let y0 = ((visible.top() - image_rect.top()) / cell).floor() as i32;
                            let x1 = ((visible.right() - image_rect.left()) / cell).ceil() as i32;
                            let y1 = ((visible.bottom() - image_rect.top()) / cell).ceil() as i32;
                            for y in y0..y1 {
                                for x in x0..x1 {
                                    if (x + y) % 2 == 0 {
                                        let tile = egui::Rect::from_min_size(
                                            image_rect.min
                                                + Vec2::new(x as f32 * cell, y as f32 * cell),
                                            Vec2::splat(cell),
                                        )
                                        .intersect(image_rect);
                                        painter.rect_filled(tile, 0.0, Color32::from_gray(55));
                                    }
                                }
                            }
                        }
                        painter.image(
                            texture.id(),
                            image_rect,
                            egui::Rect::from_min_max(egui::Pos2::ZERO, egui::pos2(1.0, 1.0)),
                            Color32::WHITE,
                        );
                    });
                ui.label(
                    RichText::new(format!(
                        "{} × {} px  ·  Ctrl + ホイールで拡大  ·  スクロールで移動{}",
                        self.original_size[0],
                        self.original_size[1],
                        if self.original_size[0] > self.texture_size[0] as u32
                            || self.original_size[1] > self.texture_size[1] as u32
                        {
                            "  ·  プレビューは最大4096px"
                        } else {
                            ""
                        }
                    ))
                    .small()
                    .color(MUTED),
                );
            } else {
                ui.vertical_centered(|ui| {
                    ui.add_space((ui.available_height() * 0.28).max(20.0));
                    if self.preview_busy || self.pending_preview.is_some() {
                        ui.spinner();
                        ui.label("画像を読み込んでいます…");
                    } else {
                        ui.label(
                            RichText::new("JPEG XLを、そのまま開く。")
                                .size(27.0)
                                .strong(),
                        );
                        ui.add_space(12.0);
                        ui.label(
                            RichText::new("画像をドロップするか、ライブラリから選択してください。")
                                .color(MUTED),
                        );
                        ui.label(
                            RichText::new("変換した画像も、この画面で確認できます。").color(MUTED),
                        );
                    }
                });
            }
        });
    }
}

impl eframe::App for Studio {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        self.poll(ctx);
        let dropped = ctx.input(|i| {
            i.raw
                .dropped_files
                .iter()
                .filter_map(|f| f.path.clone())
                .collect()
        });
        self.add_paths(dropped);
        if !self.busy && ctx.input(|i| i.modifiers.command && i.key_pressed(egui::Key::O)) {
            self.pick_files();
        }
        self.header(ctx);
        egui::TopBottomPanel::bottom("status").show(ctx, |ui| {
            ui.add_space(4.0);
            ui.label(RichText::new(&self.message).small().color(MUTED));
            if let Some(index) = self.selected
                && let State::Failed(message) = &self.items[index].state
            {
                ui.colored_label(Color32::LIGHT_RED, message);
            }
            let mut dismiss = false;
            if let Some(error) = &self.error {
                ui.horizontal_wrapped(|ui| {
                    ui.colored_label(Color32::LIGHT_RED, error);
                    dismiss = ui.small_button("閉じる").clicked();
                });
            }
            if dismiss {
                self.error = None;
            }
            ui.add_space(4.0);
        });
        self.sidebar(ctx);
        self.viewer(ctx);
        if self.busy || self.preview_busy || self.export_busy {
            ctx.request_repaint_after(Duration::from_millis(100));
        }
    }
}

fn guarded<T>(job: impl FnOnce() -> anyhow::Result<T>) -> Result<T, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(job))
        .map_err(|_| "画像処理中にエラーが発生しました。別の画像をお試しください。".to_string())?
        .map_err(|e| format!("{e:#}"))
}

fn display_name(path: &std::path::Path) -> String {
    path.file_name()
        .unwrap_or_default()
        .to_string_lossy()
        .into_owned()
}

fn install_japanese_font(ctx: &egui::Context) {
    let candidates = [
        "C:/Windows/Fonts/meiryo.ttc",
        "C:/Windows/Fonts/YuGothM.ttc",
        "/System/Library/Fonts/ヒラギノ角ゴシック W3.ttc",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc",
    ];
    for path in candidates {
        if let Ok(bytes) = std::fs::read(path) {
            let mut fonts = egui::FontDefinitions::default();
            fonts
                .font_data
                .insert("japanese".into(), egui::FontData::from_owned(bytes).into());
            for family in [egui::FontFamily::Proportional, egui::FontFamily::Monospace] {
                fonts
                    .families
                    .entry(family)
                    .or_default()
                    .push("japanese".into());
            }
            ctx.set_fonts(fonts);
            break;
        }
    }
}
