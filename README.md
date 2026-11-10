# JXL Studio

Rustで作った、JPEG XL（`.jxl`）への可逆変換と画像表示のためのデスクトップアプリです。
日本語UIで画像をまとめて変換し、その場で変換前・変換後を確認できます。

## ダウンロード

[Releases](https://github.com/conmmitmentman/jxl-studio/releases)のWindows x64版ZIPを展開して、`jxl-studio.exe`を起動してください。
Rust・Python・`cjxl`などの別途インストールは不要です。CLIは`jxl-studio-cli.exe`です。

## 機能

- JPEG / PNG / WebP / GIF / BMP / TIFF → JPEG XLの可逆変換
- 8bit・16bit、グレースケール・RGB、アルファチャンネルの保持
- ファイルの複数選択とドラッグ＆ドロップ、一括変換、保存先の選択
- JPEG XLの直接表示、変換前後の切り替え
- 画面に合わせる・100%表示・拡大縮小・スクロール、透過チェック背景
- 表示画像のPNG書き出し（JXLからはsRGBの16bit PNG）
- バックグラウンド処理、進捗表示、現在の画像の処理後に停止
- 同名ファイルは連番で保存し、既存ファイルを上書きしない保存処理
- コマンドラインからの変換・PNG書き出し

## 使い方

1. **画像を追加 / 開く**からファイルを選ぶか、ウィンドウに画像をドロップします。
2. 必要に応じて**保存先を選択**します。初期設定では元画像と同じフォルダーです。
3. **JPEG XLに一括変換**を押します。JXLファイルは表示専用として扱います。
4. 左の一覧から画像を選ぶと表示できます。変換完了後は**元画像 / 変換後**で切り替えられます。

`Ctrl+O`で画像を開き、`Ctrl+ホイール`で拡大縮小します。大きい画像はスクロールで移動できます。
一覧の「削除」「クリア」は一覧から取り除くだけで、ディスク上の画像は削除しません。
画像を実行ファイルにドロップしたり、起動引数に渡して開くこともできます。

```powershell
.\jxl-studio.exe "C:\Pictures\photo.jxl"
.\jxl-studio-cli.exe convert "input.png" "output.jxl"
.\jxl-studio-cli.exe export-png "output.jxl" "preview.png"
```

## 対応範囲

- **可逆とはデコード後の整数画素の保持**です。JPEGファイルそのものを完全復元するJPEGビットストリーム再圧縮ではありません。元のJPEGの画質以上にはなりません。
- 容量が必ず小さくなるわけではありません。可逆変換ではJPEGなどより大きくなる場合があります。非可逆圧縮・品質調整は未対応です。
- EXIFの向きは画素に反映します。EXIF / ICC / XMPなどのメタデータは出力に引き継ぎません。入力の画素はsRGBとして符号化するため、広色域ICC画像の色を保持する用途には適しません。
- GIF・APNG・アニメーションWebP / JXL・複数ページTIFFは先頭の表示画像のみです。アニメーションの再生・変換は未対応です。
- 整数8/16bitの静止画を対象とします。HDR入力・浮動小数点入力・HDRモニター出力は未対応です。JXLの表示とPNG出力はSDRのsRGBへ変換します。
- 入力ファイルは512 MiB、画像は6400万画素までです。JXL内部バッファには1 GiBの割当制限も設けています。アプリ全体のメモリー上限を保証するものではありません。
- ビューアーのテクスチャは最大4096pxです。表示サイズ・拡大率の基準は元画像ですが、大きな画像の拡大表示は縮小プレビューの拡大になります。変換・PNG書き出しは元の解像度を使用します。
- フォルダーの再帰追加、OSのファイル関連付け、インストーラーは未対応です。

## 開発

Rust stable（1.88以降）を使います。Windows x64を主対象とし、Linuxでもビルド検証する構成です。

```sh
cargo run --release --bin jxl-studio
cargo test --locked --all-targets
cargo fmt --all -- --check
cargo clippy --locked --all-targets -- -D warnings
cargo build --locked --release --bins
```

WindowsではVisual Studioの「C++によるデスクトップ開発」とWindows SDKが必要です。
Linux（Ubuntu）では`sudo apt-get install libxkbcommon-dev libwayland-dev libgl1-mesa-dev`を実行してください。
日本語フォントはWindowsのメイリオ / 游ゴシック、macOSのヒラギノ、LinuxのNoto Sans CJKの代表的なパスから読み込みます。
Linuxでは`fonts-noto-cjk`もインストールしてください。

エンコーダーは[zune-jpegxl](https://docs.rs/zune-jpegxl/)、デコーダーは[jxl-oxide](https://docs.rs/jxl-oxide/)、
GUIは[egui / eframe](https://github.com/emilk/egui)を使用します。JXLの処理は外部コマンドを呼ばず、Rustのライブラリで行います。
`Cargo.lock`を含め、依存バージョンを固定しています。
エンコーダーには1px画像・16bit透過の修正を加えた同梱ソースを使用します。変更内容は[vendor/zune-jpegxl/PATCHES.md](vendor/zune-jpegxl/PATCHES.md)に記録しています。

## テスト

異なる実装のエンコーダーとデコーダーを使い、8/16bit・RGB・グレースケール・透過・奇数サイズ・複数グループサイズで画素の完全一致を確認します。
各入力形式、EXIFの向き、日本語のパス、同名ファイル、破損ファイル、PNG書き出し、実際のCLI実行も検証します。
GitHub ActionsでWindows・Linuxのテストと静的チェックを実行し、`v*`タグでWindows配布ZIPとSHA-256を作成します。

## ライセンス

MIT。依存ライブラリのライセンスについては[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)も参照してください。
