//! Android JNI entry points. Java copies content URIs to the app cache before calling.
//! All codec work runs on the Java caller's worker thread; no Java UI access occurs here.

use std::{
    path::{Path, PathBuf},
    ptr,
};

use anyhow::{Context, Result, ensure};
use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::{jint, jstring},
};
use serde_json::{Value, json};

fn path_from_java(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Result<PathBuf> {
    let value: String = env
        .get_string(value)
        .context("ファイルパスを読み取れません")?
        .into();
    ensure!(!value.is_empty(), "ファイルパスが空です");
    ensure!(
        !value.contains('\0'),
        "ファイルパスに不正な文字が含まれています"
    );
    Ok(PathBuf::from(value))
}

/// Keep Rust panics inside the JNI boundary and return errors as JSON.
/// If Java cannot allocate even a result string, leave a Java exception pending
/// and return null, as required by JNI, rather than unwinding across the boundary.
fn respond(env: &mut JNIEnv<'_>, action: impl FnOnce(&mut JNIEnv<'_>) -> Result<Value>) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| action(env)));
    let payload = match result {
        Ok(Ok(value)) => value,
        Ok(Err(error)) => json!({"ok": false, "error": format!("{error:#}")}),
        Err(_) => json!({
            "ok": false,
            "error": "画像処理中にエラーが発生しました。別の画像をお試しください。"
        }),
    };
    match env.new_string(payload.to_string()) {
        Ok(value) => value.into_raw(),
        Err(_) => {
            if !env.exception_check().unwrap_or(true) {
                let _ = env.throw_new(
                    "java/lang/RuntimeException",
                    "Cannot allocate codec response",
                );
            }
            ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jxlstudio_app_NativeCodec_nativeConvert(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    input_path: JString<'_>,
    output_path: JString<'_>,
) -> jstring {
    respond(&mut env, |env| {
        let input = path_from_java(env, &input_path)?;
        let output = path_from_java(env, &output_path)?;
        let report = jxl_studio::convert_file(&input, &output)?;
        Ok(json!({
            "ok": true,
            "width": report.width,
            "height": report.height,
            "inputBytes": report.input_bytes,
            "outputBytes": report.output_bytes,
            "elapsedMs": report.elapsed.as_millis().min(u128::from(u64::MAX)) as u64,
            "output": report.output.to_string_lossy()
        }))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jxlstudio_app_NativeCodec_nativePreview(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    input_path: JString<'_>,
    output_png_path: JString<'_>,
    max_side: jint,
) -> jstring {
    respond(&mut env, |env| {
        let input = path_from_java(env, &input_path)?;
        let output = path_from_java(env, &output_png_path)?;
        ensure!(max_side > 0, "プレビューの最大サイズは1以上にしてください");
        ensure_distinct_paths(&input, &output)?;
        let image = jxl_studio::read_preview(&input)?;
        let (width, height) = (image.width(), image.height());
        let side = (max_side as u32).min(2048);
        let preview = if width > side || height > side {
            image.thumbnail(side, side)
        } else {
            image
        }
        .to_rgba8();
        let (preview_width, preview_height) = (preview.width(), preview.height());
        // This path is owned app-cache scratch space, not an exported user file.
        // Replacing an old preview is intentional. Conversion/export use no-clobber saves.
        preview
            .save_with_format(&output, image::ImageFormat::Png)
            .context("プレビュー画像を保存できません")?;
        Ok(json!({
            "ok": true,
            "width": width,
            "height": height,
            "previewWidth": preview_width,
            "previewHeight": preview_height
        }))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_jxlstudio_app_NativeCodec_nativeExportPng(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    input_path: JString<'_>,
    output_path: JString<'_>,
) -> jstring {
    respond(&mut env, |env| {
        let input = path_from_java(env, &input_path)?;
        let output = path_from_java(env, &output_path)?;
        jxl_studio::export_png(&input, &output)?;
        Ok(json!({"ok": true}))
    })
}

fn ensure_distinct_paths(input: &Path, output: &Path) -> Result<()> {
    ensure!(
        input != output,
        "入力画像とプレビューの保存先は別のパスにしてください"
    );
    if let (Ok(input), Ok(output)) = (input.canonicalize(), output.canonicalize()) {
        ensure!(input != output, "入力画像をプレビューで上書きできません");
    }
    Ok(())
}
