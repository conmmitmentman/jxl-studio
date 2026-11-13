use anyhow::{Result, bail};
use jxl_studio::language::Language;
use std::{ffi::OsString, path::Path};

fn main() {
    let (language, args) = Language::arguments(std::env::args_os().skip(1).collect());
    if let Err(error) = run(language, args) {
        eprintln!("{}", language.error(&format!("{error:#}")));
        std::process::exit(1);
    }
}

fn run(language: Language, args: Vec<OsString>) -> Result<()> {
    if args.is_empty() || args[0] == "--help" || args[0] == "-h" {
        println!(
            "JXL Studio CLI {}\n\n  jxl-studio-cli [--lang ja|en] convert INPUT OUTPUT.jxl\n  jxl-studio-cli [--lang ja|en] export-png INPUT.jxl OUTPUT.png\n\n{}",
            env!("CARGO_PKG_VERSION"),
            language.text(
                "画素を保持する可逆変換です。既存ファイルは上書きしません。",
                "Lossless pixel encoding; existing files are never overwritten."
            )
        );
        return Ok(());
    }
    if args.len() != 3 {
        bail!(
            "{}",
            language.text(
                "使い方: jxl-studio-cli [--lang ja|en] [convert|export-png] INPUT OUTPUT",
                "Usage: jxl-studio-cli [--lang ja|en] [convert|export-png] INPUT OUTPUT"
            )
        );
    }
    let (input, output) = (Path::new(&args[1]), Path::new(&args[2]));
    if args[0] == "convert" {
        let report = jxl_studio::convert_file(input, output)?;
        println!(
            "{} → {} | {} × {} | {} → {} | {:.2}s",
            input.display(),
            output.display(),
            report.width,
            report.height,
            jxl_studio::format_bytes(report.input_bytes),
            jxl_studio::format_bytes(report.output_bytes),
            report.elapsed.as_secs_f32()
        );
    } else if args[0] == "export-png" {
        jxl_studio::export_png(input, output)?;
        println!(
            "{} {}",
            language.text("保存しました:", "Saved"),
            output.display()
        );
    } else {
        bail!(
            "{}",
            language.text(
                "不明なコマンドです。--helpを参照してください",
                "Unknown command. See --help."
            )
        );
    }
    Ok(())
}
