# JXL Studio for Android

JPEG・PNG・WebP・GIF・BMP・TIFFからJPEG XLへ可逆変換し、JXLをアプリ内で表示するAndroidアプリです。
画面とファイル選択はAndroidネイティブ、エンコード・デコードはデスクトップ版と同じRustコードです。

## インストールと使い方

1. [署名済みAPK](https://github.com/conmmitmentman/jxl-studio/releases/download/android-v0.1.0/jxl-studio-android.apk)を端末へダウンロードし、Androidのインストーラーで開きます。
2. **画像を開く**から画像を選びます。ファイル選択画面で複数選択もできます。JXLはそのまま表示できます。
3. JPEG・PNG等は**JXLへ一括変換**で変換します。上の一覧から表示する画像を切り替えられます。
4. **JXLを保存**で選択中の変換結果を端末に書き出します。**PNGに保存**も利用できます。

画像はピンチで拡大、ドラッグで移動できます。**全体 / 100%**やダブルタップで表示を切り替えられます。
横画面や小さい画面では、画面全体を上下にスクロールすると保存ボタンへ移動できます。
透過はチェック背景で表示します。日本語ファイル名にも対応しています。

## 対応範囲

- Android 8.0（API 26）以降、arm64-v8a / x86_64。32bit専用端末は非対応です。
- 入力1ファイル128 MiBまで、一度の一覧32件まで。画像の画素数上限・色・メタデータ・静止画の制限はメインREADMEと同じです。
- プレビューは最大2048pxの8bit RGBAです。JXL変換とPNG書き出しは元の解像度・対応する8/16bit画素を使用します。
- 原本は変更しません。Android標準の保存画面は同名ファイルを上書きせず、新しい名前で作成します。
- ファイル選択で許可した画像だけを読みます。インターネット権限・ストレージ全体へのアクセス権限は要求しません。
- 変換結果はアプリの一時領域にあります。**アプリを終了する前に保存してください。** Androidによるキャッシュ削除で未保存の結果は失われる場合があります。
- 一括変換後のファイルは、一覧で選んで1件ずつ保存します。保存先フォルダーへの自動一括書き出しは未対応です。
- 非可逆圧縮・アニメーション全体・ICC等の保持は未対応です。大きな画像は端末の空きメモリーに依存します。

## 開発とテスト

[BUILDING.md](BUILDING.md)にビルド・署名・更新用キーの保管場所を記載しています。署名用の秘密鍵はリポジトリに含めません。

```powershell
# デスクトップJVMから実際のJNIを呼び、Rustとの連携をテスト
pwsh -File android/test-jni.ps1

# APKと同じ署名のAndroid実行テスト用APKを作成
pwsh -File android/build-apk.ps1 -BuildTests
adb install -r dist/jxl-studio-android.apk
adb install -r dist/jxl-studio-android-tests.apk
adb shell am instrument -w com.jxlstudio.tests/com.jxlstudio.tests.CodecInstrumentation
```

GitHub ActionsのAndroidジョブではAPI 26・35のエミュレーター上で、Rust JNIの変換・透過・PNG出力・上書き防止・画面起動を検証します。
CIのテスト用APKは開発用の証明書を使います。配布用APKは別の保管済み証明書で署名しています。
