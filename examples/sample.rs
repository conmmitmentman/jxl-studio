//! Generate an original, deterministic sample image for trying the converter.
use image::{ImageBuffer, Rgb};
use std::path::PathBuf;

fn main() -> anyhow::Result<()> {
    let output = std::env::args_os()
        .nth(1)
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("sample.png"));
    let image = ImageBuffer::from_fn(960, 640, |x, y| {
        let u = x as f32 / 960.0;
        let v = y as f32 / 640.0;
        let glow =
            |cx: f32, cy: f32, spread: f32| (-((u - cx).powi(2) + (v - cy).powi(2)) / spread).exp();
        let mint = glow(0.25, 0.30, 0.12);
        let violet = glow(0.76, 0.34, 0.09);
        let blue = glow(0.47, 0.55, 0.15);
        let ridge = 0.69 + 0.07 * (u * 11.0).sin() + 0.025 * (u * 26.0).cos();
        let shade = if v > ridge { 0.15 } else { 1.0 };
        Rgb([
            ((12.0 + 75.0 * mint + 145.0 * violet + 20.0 * blue) * shade) as u8,
            ((20.0 + 195.0 * mint + 40.0 * violet + 80.0 * blue) * shade) as u8,
            ((34.0 + 130.0 * mint + 145.0 * violet + 120.0 * blue) * shade) as u8,
        ])
    });
    image.save(&output)?;
    println!("Saved {}", output.display());
    Ok(())
}
