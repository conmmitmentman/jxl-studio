//! Codec and safe file operations shared by the GUI and CLI.
use std::{
    fs,
    io::Write,
    path::{Path, PathBuf},
    time::{Duration, Instant},
};

use anyhow::{Context, Result, bail, ensure};
use image::{DynamicImage, ImageBuffer, ImageDecoder, ImageReader};
use jxl_oxide::{AllocTracker, EnumColourEncoding, JxlImage, RenderingIntent};
use zune_core::{bit_depth::BitDepth, colorspace::ColorSpace, options::EncoderOptions};
use zune_jpegxl::JxlSimpleEncoder;

pub const INPUT_EXTENSIONS: &[&str] = &["jpg", "jpeg", "png", "webp", "gif", "bmp", "tif", "tiff"];
pub const MAX_PIXELS: u64 = 64_000_000;
const MAX_FILE_BYTES: u64 = 512 * 1024 * 1024;
const DECODE_BUDGET: usize = 1024 * 1024 * 1024;

#[derive(Debug, Clone)]
pub struct ConversionReport {
    pub output: PathBuf,
    pub input_bytes: u64,
    pub output_bytes: u64,
    pub width: u32,
    pub height: u32,
    pub elapsed: Duration,
}

pub fn is_jxl(path: &Path) -> bool {
    path.extension()
        .is_some_and(|e| e.eq_ignore_ascii_case("jxl"))
}

pub fn is_convertible(path: &Path) -> bool {
    path.extension()
        .and_then(|e| e.to_str())
        .is_some_and(|e| INPUT_EXTENSIONS.iter().any(|x| e.eq_ignore_ascii_case(x)))
}

fn validate_file(path: &Path) -> Result<()> {
    let metadata =
        fs::metadata(path).with_context(|| format!("ファイルを開けません: {}", path.display()))?;
    ensure!(metadata.is_file(), "通常の画像ファイルを選択してください");
    ensure!(
        metadata.len() <= MAX_FILE_BYTES,
        "512 MiBを超える画像は扱えません"
    );
    Ok(())
}

pub fn validate_dimensions(width: u32, height: u32) -> Result<()> {
    ensure!(width > 0 && height > 0, "画像サイズが不正です");
    ensure!(
        u64::from(width) * u64::from(height) <= MAX_PIXELS,
        "画像は6400万画素まで対応しています"
    );
    Ok(())
}

/// Decode ordinary images with EXIF orientation applied. Integer samples are retained.
pub fn read_source(path: &Path) -> Result<DynamicImage> {
    validate_file(path)?;
    ensure!(
        is_convertible(path),
        "対応する形式はJPEG / PNG / WebP / GIF / BMP / TIFFです"
    );
    let dimensions = ImageReader::open(path)?
        .with_guessed_format()?
        .into_dimensions()?;
    validate_dimensions(dimensions.0, dimensions.1)?;
    let mut reader = ImageReader::open(path)?.with_guessed_format()?;
    let mut limits = image::Limits::default();
    limits.max_alloc = Some(DECODE_BUDGET as u64);
    reader.limits(limits);
    let mut decoder = reader.into_decoder()?;
    let orientation = decoder.orientation()?;
    let mut image = DynamicImage::from_decoder(decoder).context("画像のデコードに失敗しました")?;
    image.apply_orientation(orientation);
    Ok(image)
}

/// The original ICC/EXIF/XMP metadata is intentionally not copied into the codestream.
pub fn encode_lossless(image: &DynamicImage) -> Result<Vec<u8>> {
    validate_dimensions(image.width(), image.height())?;
    let (space, depth) = match image {
        DynamicImage::ImageLuma8(_) => (ColorSpace::Luma, BitDepth::Eight),
        DynamicImage::ImageLumaA8(_) => (ColorSpace::LumaA, BitDepth::Eight),
        DynamicImage::ImageRgb8(_) => (ColorSpace::RGB, BitDepth::Eight),
        DynamicImage::ImageRgba8(_) => (ColorSpace::RGBA, BitDepth::Eight),
        DynamicImage::ImageLuma16(_) => (ColorSpace::Luma, BitDepth::Sixteen),
        DynamicImage::ImageLumaA16(_) => (ColorSpace::LumaA, BitDepth::Sixteen),
        DynamicImage::ImageRgb16(_) => (ColorSpace::RGB, BitDepth::Sixteen),
        DynamicImage::ImageRgba16(_) => (ColorSpace::RGBA, BitDepth::Sixteen),
        _ => bail!("浮動小数点・HDR入力の変換には対応していません"),
    };
    let options = EncoderOptions::new(
        image.width() as usize,
        image.height() as usize,
        space,
        depth,
    );
    let encoder = JxlSimpleEncoder::new(image.as_bytes(), options);
    let mut output = Vec::new();
    encoder
        .encode(&mut output)
        .map_err(|e| anyhow::anyhow!("JPEG XLのエンコードに失敗しました: {e:?}"))?;
    Ok(output)
}

/// Decode the first displayed JXL frame as 16-bit samples, including orientation and alpha.
/// Preview requests sRGB; export retains the decoder's original color encoding.
pub fn read_jxl(path: &Path, srgb: bool) -> Result<DynamicImage> {
    validate_file(path)?;
    let mut image = JxlImage::builder()
        .alloc_tracker(AllocTracker::with_limit(DECODE_BUDGET))
        .open(path)
        .map_err(anyhow::Error::from_boxed)
        .context("JPEG XLの読み込みに失敗しました")?;
    let (width, height) = (image.width(), image.height());
    validate_dimensions(width, height)?;
    if srgb {
        image.request_color_encoding(EnumColourEncoding::srgb(RenderingIntent::Relative));
    }
    let render = image
        .render_frame(0)
        .map_err(anyhow::Error::from_boxed)
        .context("JPEG XLを表示できません")?;
    let mut stream = render.stream();
    let channels = stream.channels() as usize;
    let mut samples = vec![0u16; width as usize * height as usize * channels];
    ensure!(
        stream.write_to_buffer(&mut samples) == samples.len(),
        "画像データが途中で終了しました"
    );
    Ok(match channels {
        1 => DynamicImage::ImageLuma16(
            ImageBuffer::from_raw(width, height, samples).context("画素データが不正です")?,
        ),
        2 => DynamicImage::ImageLumaA16(
            ImageBuffer::from_raw(width, height, samples).context("画素データが不正です")?,
        ),
        3 => DynamicImage::ImageRgb16(
            ImageBuffer::from_raw(width, height, samples).context("画素データが不正です")?,
        ),
        4 => DynamicImage::ImageRgba16(
            ImageBuffer::from_raw(width, height, samples).context("画素データが不正です")?,
        ),
        _ => bail!("この画像のカラーチャンネルには対応していません"),
    })
}

pub fn read_preview(path: &Path) -> Result<DynamicImage> {
    if is_jxl(path) {
        read_jxl(path, true)
    } else {
        read_source(path)
    }
}

/// Atomic, no-clobber write: failures leave existing user files untouched.
pub fn save_new(path: &Path, bytes: &[u8]) -> Result<()> {
    let parent = path
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or_else(|| Path::new("."));
    ensure!(
        parent.is_dir(),
        "保存先フォルダーが存在しません: {}",
        parent.display()
    );
    let mut temporary =
        tempfile::NamedTempFile::new_in(parent).context("保存先に書き込めません")?;
    temporary.write_all(bytes)?;
    temporary.as_file().sync_all()?;
    temporary
        .persist_noclobber(path)
        .map_err(|e| e.error)
        .with_context(|| {
            format!(
                "保存できません（同名ファイルは上書きしません）: {}",
                path.display()
            )
        })?;
    Ok(())
}

pub fn convert_file(input: &Path, output: &Path) -> Result<ConversionReport> {
    ensure!(is_jxl(output), "出力ファイルの拡張子は.jxlにしてください");
    ensure!(
        !output.exists(),
        "同名のファイルが存在します: {}",
        output.display()
    );
    let started = Instant::now();
    let image = read_source(input)?;
    let bytes = encode_lossless(&image)?;
    save_new(output, &bytes)?;
    Ok(ConversionReport {
        output: output.to_owned(),
        input_bytes: fs::metadata(input)?.len(),
        output_bytes: bytes.len() as u64,
        width: image.width(),
        height: image.height(),
        elapsed: started.elapsed(),
    })
}

/// Export the sRGB first frame to 16-bit PNG. Never silently replace an existing file.
pub fn export_png(input: &Path, output: &Path) -> Result<()> {
    ensure!(
        output
            .extension()
            .is_some_and(|e| e.eq_ignore_ascii_case("png")),
        "出力ファイルの拡張子は.pngにしてください"
    );
    let image = read_preview(input)?;
    let mut buffer = std::io::Cursor::new(Vec::new());
    image.write_to(&mut buffer, image::ImageFormat::Png)?;
    save_new(output, &buffer.into_inner())
}

/// Keep same-stem inputs distinct, including names that differ only by case on Windows.
pub fn available_output(input: &Path, directory: &Path) -> PathBuf {
    let stem = input.file_stem().unwrap_or_default().to_string_lossy();
    for number in 0u64.. {
        let name = if number == 0 {
            format!("{stem}.jxl")
        } else {
            format!("{stem} ({number}).jxl")
        };
        let candidate = directory.join(name);
        if !candidate.exists() {
            return candidate;
        }
    }
    unreachable!()
}

pub fn format_bytes(bytes: u64) -> String {
    if bytes >= 1024 * 1024 {
        format!("{:.2} MiB", bytes as f64 / (1024.0 * 1024.0))
    } else if bytes >= 1024 {
        format!("{:.1} KiB", bytes as f64 / 1024.0)
    } else {
        format!("{bytes} B")
    }
}
