//! Shared language selection and presentation of codec diagnostics.
use std::{
    ffi::OsString,
    fs,
    path::{Path, PathBuf},
};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Language {
    Japanese,
    English,
}

impl Language {
    pub fn text<'a>(self, japanese: &'a str, english: &'a str) -> &'a str {
        match self {
            Self::Japanese => japanese,
            Self::English => english,
        }
    }

    pub fn code(self) -> &'static str {
        self.text("ja", "en")
    }

    pub fn parse(value: &str) -> Option<Self> {
        match value.trim() {
            "ja" => Some(Self::Japanese),
            "en" => Some(Self::English),
            _ => None,
        }
    }

    pub fn system() -> Self {
        #[cfg(target_os = "windows")]
        {
            #[link(name = "kernel32")]
            unsafe extern "system" {
                fn GetUserDefaultUILanguage() -> u16;
            }
            // This Windows API takes no pointers and returns the current user's UI LANGID.
            if unsafe { GetUserDefaultUILanguage() } & 0x3ff == 0x11 {
                Self::Japanese
            } else {
                Self::English
            }
        }
        #[cfg(not(target_os = "windows"))]
        {
            for name in ["LC_ALL", "LC_MESSAGES", "LANG"] {
                if let Ok(value) = std::env::var(name)
                    && !value.is_empty()
                {
                    return if value.to_ascii_lowercase().starts_with("ja") {
                        Self::Japanese
                    } else {
                        Self::English
                    };
                }
            }
            Self::English
        }
    }

    pub fn preference_path() -> Option<PathBuf> {
        #[cfg(target_os = "windows")]
        let base = std::env::var_os("APPDATA").map(PathBuf::from);
        #[cfg(target_os = "macos")]
        let base =
            std::env::var_os("HOME").map(|p| PathBuf::from(p).join("Library/Application Support"));
        #[cfg(not(any(target_os = "windows", target_os = "macos")))]
        let base = std::env::var_os("XDG_CONFIG_HOME")
            .map(PathBuf::from)
            .or_else(|| std::env::var_os("HOME").map(|p| PathBuf::from(p).join(".config")));
        base.map(|p| p.join("JxlStudio/language.txt"))
    }

    pub fn load() -> Self {
        Self::preference_path()
            .as_deref()
            .and_then(Self::read_preference)
            .unwrap_or_else(Self::system)
    }

    fn read_preference(path: &Path) -> Option<Self> {
        Self::parse(&fs::read_to_string(path).ok()?)
    }

    pub fn save(self) -> std::io::Result<()> {
        let path = Self::preference_path()
            .ok_or_else(|| std::io::Error::other("User configuration directory is unavailable"))?;
        self.save_to(&path)
    }

    fn save_to(self, path: &Path) -> std::io::Result<()> {
        use std::io::Write;
        let parent = path
            .parent()
            .ok_or_else(|| std::io::Error::other("Invalid preference path"))?;
        fs::create_dir_all(parent)?;
        let mut file = tempfile::NamedTempFile::new_in(parent)?;
        file.write_all(self.code().as_bytes())?;
        file.as_file().sync_all()?;
        file.persist(path).map_err(|e| e.error)?;
        Ok(())
    }

    /// --lang only overrides this launch. An explicit UI choice updates the preference.
    pub fn arguments(args: Vec<OsString>) -> (Self, Vec<OsString>) {
        let mut language = Self::load();
        let mut remaining = Vec::new();
        let mut iter = args.into_iter().peekable();
        while let Some(arg) = iter.next() {
            if arg == "--lang"
                && let Some(value) = iter.peek().and_then(|v| v.to_str()).and_then(Self::parse)
            {
                language = value;
                iter.next();
            } else {
                remaining.push(arg);
            }
        }
        (language, remaining)
    }

    pub fn startup() -> Self {
        Self::arguments(std::env::args_os().skip(1).collect()).0
    }

    pub fn window_title(self) -> &'static str {
        self.text(
            "JXL Studio — 画像変換 & ビューアー",
            "JXL Studio — Image converter & viewer",
        )
    }

    pub fn error(self, message: &str) -> String {
        match self {
            Self::Japanese => message.to_owned(),
            Self::English => error_message_english(message),
        }
    }
}

/// Translate project-controlled diagnostic prefixes; never modify file names or codec details.
pub fn error_message_english(message: &str) -> String {
    const MESSAGES: &[(&str, &str)] = &[
        ("ファイルを開けません", "Cannot open the file"),
        (
            "通常の画像ファイルを選択してください",
            "Select a regular image file",
        ),
        (
            "512 MiBを超える画像は扱えません",
            "Input files must not exceed 512 MiB",
        ),
        ("画像サイズが不正です", "Invalid image dimensions"),
        (
            "画像は6400万画素まで対応しています",
            "Images must not exceed 64 million pixels",
        ),
        (
            "対応する形式はJPEG / PNG / WebP / GIF / BMP / TIFFです",
            "Supported inputs: JPEG / PNG / WebP / GIF / BMP / TIFF",
        ),
        ("画像のデコードに失敗しました", "Failed to decode the image"),
        (
            "浮動小数点・HDR入力の変換には対応していません",
            "Floating-point and HDR inputs are not supported",
        ),
        (
            "JPEG XLのエンコードに失敗しました",
            "Failed to encode JPEG XL",
        ),
        ("JPEG XLの読み込みに失敗しました", "Failed to read JPEG XL"),
        ("JPEG XLを表示できません", "Cannot display JPEG XL"),
        (
            "画像データが途中で終了しました",
            "Image data ended unexpectedly",
        ),
        ("画素データが不正です", "Invalid pixel data"),
        (
            "この画像のカラーチャンネルには対応していません",
            "This image's color channels are not supported",
        ),
        (
            "保存先フォルダーが存在しません",
            "The output folder does not exist",
        ),
        (
            "保存先に書き込めません",
            "Cannot write to the output folder",
        ),
        (
            "保存できません（同名ファイルは上書きしません）",
            "Cannot save the file (existing files are never overwritten)",
        ),
        (
            "出力ファイルの拡張子は.jxlにしてください",
            "The output file must have a .jxl extension",
        ),
        (
            "同名のファイルが存在します",
            "A file with this name already exists",
        ),
        (
            "出力ファイルの拡張子は.pngにしてください",
            "The output file must have a .png extension",
        ),
        (
            "画像処理中にエラーが発生しました。別の画像をお試しください。",
            "Image processing failed. Try another image.",
        ),
        ("ファイルパスを読み取れません", "Cannot read the file path"),
        ("ファイルパスが空です", "The file path is empty"),
        (
            "ファイルパスに不正な文字が含まれています",
            "The file path contains an invalid character",
        ),
        (
            "プレビューの最大サイズは1以上にしてください",
            "The preview size must be at least 1",
        ),
        (
            "入力画像とプレビューの保存先は別のパスにしてください",
            "The input and preview must use different paths",
        ),
        (
            "入力画像をプレビューで上書きできません",
            "The preview cannot overwrite the input image",
        ),
        (
            "プレビュー画像を保存できません",
            "Cannot save the preview image",
        ),
    ];
    for &(japanese, english) in MESSAGES {
        if let Some(tail) = message.strip_prefix(japanese) {
            if tail.is_empty() {
                return english.to_owned();
            }
            if let Some(cause) = tail.strip_prefix(": ") {
                return format!("{english}: {}", error_message_english(cause));
            }
        }
    }
    message.to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn selected_language_survives_relaunch_and_can_be_changed_back() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("settings/language.txt");
        assert_eq!(Language::read_preference(&path), None);
        Language::English.save_to(&path).unwrap();
        assert_eq!(Language::read_preference(&path), Some(Language::English));
        Language::Japanese.save_to(&path).unwrap();
        assert_eq!(Language::read_preference(&path), Some(Language::Japanese));
        fs::write(&path, "broken").unwrap();
        assert_eq!(Language::read_preference(&path), None);
    }
    #[test]
    fn english_diagnostics_preserve_unicode_paths_and_technical_causes() {
        let error =
            "ファイルを開けません: C:\\Pictures\\画像サイズが不正です.png: permission denied";
        assert_eq!(
            error_message_english(error),
            "Cannot open the file: C:\\Pictures\\画像サイズが不正です.png: permission denied"
        );
        assert_eq!(
            error_message_english("画像のデコードに失敗しました: invalid PNG signature"),
            "Failed to decode the image: invalid PNG signature"
        );
    }
}
