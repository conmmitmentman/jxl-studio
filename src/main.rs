#![cfg_attr(target_os = "windows", windows_subsystem = "windows")]

mod ui;

fn main() -> eframe::Result {
    let options = eframe::NativeOptions {
        viewport: eframe::egui::ViewportBuilder::default()
            .with_inner_size([1040.0, 620.0])
            .with_min_inner_size([880.0, 560.0])
            .with_clamp_size_to_monitor_size(true),
        centered: true,
        ..Default::default()
    };
    eframe::run_native(
        "JXL Studio — 画像変換 & ビューアー",
        options,
        Box::new(|cc| Ok(Box::new(ui::Studio::new(cc)))),
    )
}
