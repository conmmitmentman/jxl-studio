# JXL Studio for Android

[日本語](README.md) | **English**

An Android app that losslessly converts JPEG, PNG, WebP, GIF, BMP, and TIFF images to JPEG XL and displays JXL files in its built-in viewer.
The interface and file picker use Android's native APIs. Encoding and decoding use the same Rust code as the desktop application.
Use the app's **日本語 / English** control to switch the display language.

## Install and use

1. [Download the signed APK](https://github.com/conmmitmentman/jxl-studio/releases/download/android-v0.1.1/jxl-studio-android.apk) to your device and open it with Android's package installer.
2. Choose **Open images** to select an image. The system picker also supports selecting multiple files. JXL files can be opened directly for viewing.
3. Convert JPEG, PNG, and other supported source images using the batch-conversion button. Select an entry in the list to change the displayed image.
4. Use **Save JXL** to export the selected conversion result to your device. PNG export is also available.

Pinch to zoom and drag to pan. Use the fit/100% controls or double-tap to change the display scale.
On short or landscape windows, scroll the screen vertically to reach the save buttons.
Transparent areas use a checkerboard background. Japanese filenames are supported.

## Supported scope and limitations

- Android 8.0 (API 26) or later, with arm64-v8a / x86_64. Devices that support only 32-bit apps are not supported.
- Input files are limited to 128 MiB each and the library to 32 images. Pixel-count, color, metadata, and still-image restrictions follow the [main guide](../README.en.md).
- Previews use 8-bit RGBA and are limited to 2048 pixels per side. JXL conversion and PNG export use the original resolution and supported 8/16-bit samples.
- Original files are not modified. Android's standard save dialog creates a new filename when the requested name already exists.
- The app reads only files selected through the system picker. It requests neither Internet access nor access to all storage.
- Conversion results remain in the app's temporary storage. **Save your results before closing the app.** Android may remove cached files, including unsaved conversion results.
- After batch conversion, select each result in the list and save it individually. Automatic batch export into a chosen folder is not supported.
- Lossy compression, full-animation conversion, and ICC/other metadata preservation are not supported. Large images also depend on the device's available memory.

## Development and tests

[BUILDING.md](BUILDING.md) describes building, signing, and where the update-signing key is stored. Private signing keys are not included in the repository.

```powershell
# Call the real JNI bridge from a desktop JVM to test Rust integration
pwsh -File android/test-jni.ps1

# Build Android instrumentation tests signed with the same certificate as the app
pwsh -File android/build-apk.ps1 -BuildTests
adb install -r dist/jxl-studio-android.apk
adb install -r dist/jxl-studio-android-tests.apk
adb shell am instrument -w com.jxlstudio.tests/com.jxlstudio.tests.CodecInstrumentation
```

The GitHub Actions Android job runs emulator checks on API 26 and 35 for Rust JNI conversion, transparency, PNG export, no-overwrite behavior, and activity startup.
CI test APKs use a development certificate. Distributed APKs use a separate persistent signing certificate.
