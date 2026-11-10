use image::{DynamicImage, ImageBuffer, ImageFormat, Luma, LumaA, Rgb, Rgba};
use jxl_studio::{
    available_output, convert_file, encode_lossless, export_png, read_jxl, read_source, save_new,
};
use std::{fs, path::Path, process::Command};

fn rgb(width: u32, height: u32) -> DynamicImage {
    DynamicImage::ImageRgb8(ImageBuffer::from_fn(width, height, |x, y| {
        Rgb([
            (x * 13 + y * 7) as u8,
            (y * 23 + x) as u8,
            (x * 19 + y * 11) as u8,
        ])
    }))
}

fn assert_roundtrip(image: DynamicImage) {
    let dir = tempfile::tempdir().unwrap();
    let output = dir.path().join("roundtrip.jxl");
    save_new(&output, &encode_lossless(&image).unwrap()).unwrap();
    let decoded = read_jxl(&output, false).unwrap();
    assert_eq!(
        (image.width(), image.height()),
        (decoded.width(), decoded.height())
    );
    assert_eq!(image.to_rgba16(), decoded.to_rgba16());
}

#[test]
fn lossless_rgb8_including_small_odd_and_multigroup_sizes() {
    for (w, h) in [(1, 1), (2, 3), (17, 19), (256, 257), (513, 9)] {
        assert_roundtrip(rgb(w, h));
    }
}

#[test]
fn lossless_rgba8_preserves_transparent_pixels() {
    assert_roundtrip(DynamicImage::ImageRgba8(ImageBuffer::from_fn(
        27,
        15,
        |x, y| Rgba([(x * 7) as u8, (y * 17) as u8, 243, (x * y) as u8]),
    )));
}

#[test]
fn lossless_gray8() {
    assert_roundtrip(DynamicImage::ImageLuma8(ImageBuffer::from_fn(
        31,
        5,
        |x, y| Luma([(x * y) as u8]),
    )));
}

#[test]
fn lossless_gray_alpha8() {
    assert_roundtrip(DynamicImage::ImageLumaA8(ImageBuffer::from_fn(
        13,
        17,
        |x, y| LumaA([(x * 19) as u8, (y * 17) as u8]),
    )));
}

#[test]
fn lossless_rgb16() {
    assert_roundtrip(DynamicImage::ImageRgb16(ImageBuffer::from_fn(
        19,
        23,
        |x, y| Rgb([(x * 3109) as u16, (y * 2801) as u16, u16::MAX]),
    )));
}

#[test]
fn lossless_rgba16() {
    assert_roundtrip(DynamicImage::ImageRgba16(ImageBuffer::from_fn(
        15,
        21,
        |x, y| {
            Rgba([
                (x * 4511) as u16,
                1,
                (y * 3001) as u16,
                (x * y * 317) as u16,
            ])
        },
    )));
}

#[test]
fn lossless_gray16() {
    assert_roundtrip(DynamicImage::ImageLuma16(ImageBuffer::from_fn(
        9,
        11,
        |x, y| Luma([(x * y * 1013) as u16]),
    )));
}

#[test]
fn lossless_gray_alpha16() {
    assert_roundtrip(DynamicImage::ImageLumaA16(ImageBuffer::from_fn(
        11,
        13,
        |x, y| LumaA([(x * 6811) as u16, (y * 5003) as u16]),
    )));
}

#[test]
fn supported_input_formats_convert_to_real_decodable_jxl() {
    let dir = tempfile::tempdir().unwrap();
    for (ext, format) in [
        ("jpg", ImageFormat::Jpeg),
        ("png", ImageFormat::Png),
        ("webp", ImageFormat::WebP),
        ("gif", ImageFormat::Gif),
        ("bmp", ImageFormat::Bmp),
        ("tiff", ImageFormat::Tiff),
    ] {
        let input = dir.path().join(format!("input.{ext}"));
        let output = dir.path().join(format!("{ext}.jxl"));
        rgb(23, 31).save_with_format(&input, format).unwrap();
        let expected = read_source(&input).unwrap();
        let report = convert_file(&input, &output).unwrap();
        assert!(report.output_bytes > 0);
        assert_eq!(
            expected.to_rgba16(),
            read_jxl(&output, false).unwrap().to_rgba16(),
            "format: {ext}"
        );
    }
}

#[test]
fn png16_file_conversion_keeps_precision() {
    let dir = tempfile::tempdir().unwrap();
    let input = dir.path().join("16bit.png");
    let output = dir.path().join("16bit.jxl");
    let expected = DynamicImage::ImageRgba16(ImageBuffer::from_fn(9, 7, |x, y| {
        Rgba([x as u16, y as u16, 65535, 32767])
    }));
    expected.save(&input).unwrap();
    convert_file(&input, &output).unwrap();
    assert_eq!(
        expected.to_rgba16(),
        read_jxl(&output, false).unwrap().to_rgba16()
    );
}

#[test]
fn existing_files_are_never_overwritten() {
    let dir = tempfile::tempdir().unwrap();
    let input = dir.path().join("input.png");
    let output = dir.path().join("existing.jxl");
    rgb(3, 3).save(&input).unwrap();
    fs::write(&output, b"keep me").unwrap();
    assert!(convert_file(&input, &output).is_err());
    assert!(save_new(&output, b"replacement").is_err());
    assert_eq!(fs::read(&output).unwrap(), b"keep me");
}

#[test]
fn duplicate_stems_get_separate_outputs_and_unicode_works() {
    let dir = tempfile::tempdir().unwrap();
    let input = dir.path().join("日本語の画像.png");
    rgb(5, 7).save(&input).unwrap();
    let first = available_output(&input, dir.path());
    convert_file(&input, &first).unwrap();
    let second = available_output(&input.with_extension("jpg"), dir.path());
    assert_ne!(first, second);
    assert_eq!(second.file_name().unwrap(), "日本語の画像 (1).jxl");
    convert_file(&input, &second).unwrap();
    assert_eq!(
        read_jxl(&first, true).unwrap().to_rgba8(),
        read_jxl(&second, true).unwrap().to_rgba8()
    );
}

#[test]
fn png_export_is_decodable_and_does_not_overwrite() {
    let dir = tempfile::tempdir().unwrap();
    let jxl = dir.path().join("input.jxl");
    let png = dir.path().join("export.png");
    let expected = rgb(13, 17);
    save_new(&jxl, &encode_lossless(&expected).unwrap()).unwrap();
    export_png(&jxl, &png).unwrap();
    assert_eq!(expected.to_rgba16(), image::open(&png).unwrap().to_rgba16());
    assert!(export_png(&jxl, &png).is_err());
}

#[test]
fn errors_for_corrupt_unsupported_missing_and_bad_destination() {
    let dir = tempfile::tempdir().unwrap();
    let png = dir.path().join("broken.png");
    let jxl = dir.path().join("broken.jxl");
    fs::write(&png, b"not a PNG").unwrap();
    fs::write(&jxl, b"not a JXL").unwrap();
    assert!(read_source(&png).is_err());
    assert!(read_jxl(&jxl, true).is_err());
    assert!(read_source(Path::new("missing.png")).is_err());
    assert!(read_source(&jxl).is_err());
    assert!(jxl_studio::validate_dimensions(0, 3).is_err());
    assert!(jxl_studio::validate_dimensions(100_000, 100_000).is_err());
    assert!(save_new(&dir.path().join("missing/file.jxl"), b"data").is_err());
}

#[test]
fn exif_orientation_is_applied_before_conversion() {
    let dir = tempfile::tempdir().unwrap();
    let input = dir.path().join("rotated.jpg");
    let mut jpeg = std::io::Cursor::new(Vec::new());
    rgb(7, 11).write_to(&mut jpeg, ImageFormat::Jpeg).unwrap();
    let jpeg = jpeg.into_inner();
    let mut exif =
        b"Exif\0\0II\x2a\0\x08\0\0\0\x01\0\x12\x01\x03\0\x01\0\0\0\x06\0\0\0\0\0\0\0".to_vec();
    let length = (exif.len() as u16 + 2).to_be_bytes();
    let mut oriented = vec![0xff, 0xd8, 0xff, 0xe1, length[0], length[1]];
    oriented.append(&mut exif);
    oriented.extend_from_slice(&jpeg[2..]);
    fs::write(&input, oriented).unwrap();
    let decoded = read_source(&input).unwrap();
    assert_eq!((decoded.width(), decoded.height()), (11, 7));
    let output = dir.path().join("rotated.jxl");
    convert_file(&input, &output).unwrap();
    assert_eq!(
        decoded.to_rgba16(),
        read_jxl(&output, false).unwrap().to_rgba16()
    );
}

#[test]
fn cli_converts_exports_and_reports_failure() {
    let dir = tempfile::tempdir().unwrap();
    let input = dir.path().join("cli.png");
    let jxl = dir.path().join("cli.jxl");
    let png = dir.path().join("export.png");
    rgb(7, 9).save(&input).unwrap();
    let executable = env!("CARGO_BIN_EXE_jxl-studio-cli");
    assert!(
        Command::new(executable)
            .arg("convert")
            .arg(&input)
            .arg(&jxl)
            .output()
            .unwrap()
            .status
            .success()
    );
    assert!(
        Command::new(executable)
            .arg("export-png")
            .arg(&jxl)
            .arg(&png)
            .output()
            .unwrap()
            .status
            .success()
    );
    assert!(
        !Command::new(executable)
            .arg("convert")
            .arg(&input)
            .arg(&jxl)
            .output()
            .unwrap()
            .status
            .success()
    );
    assert_eq!(
        image::open(input).unwrap().to_rgba16(),
        image::open(png).unwrap().to_rgba16()
    );
}
