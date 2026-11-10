Rust製のJPEG XL変換・ビューアー「JXL Studio」の初回リリースです。

Windows x64版ZIPを展開し、`jxl-studio.exe`を起動してください。
Rustや外部のJPEG XLツールをインストールする必要はありません。

- JPEG・PNG・WebP・GIF・BMP・TIFFからJPEG XLへ可逆変換
- 日本語GUI、複数ファイルの一括変換、ドラッグ＆ドロップ
- 8/16bit・グレースケール・RGB・透過に対応
- JPEG XLの表示、拡大縮小、変換前後の切り替え、PNG書き出し
- CLI、既存ファイルを上書きしない保存、依存ライブラリのライセンスを同梱

Windows・Ubuntuの自動テストと静的チェック、Windows配布版の画面操作を確認済みです。

可逆圧縮では容量が増える場合があります。アニメーションは先頭画像のみを扱い、
EXIF・ICC等のメタデータは保持しません。詳しい対応範囲はREADMEをご覧ください。
