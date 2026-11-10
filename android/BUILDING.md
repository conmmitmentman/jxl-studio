# Building the Android APK on Windows

The Android app uses a native Java UI and the shared Rust JPEG XL codec through JNI.
The build does not require Gradle, Android Studio itself, or a network service at runtime.
The manifest requests neither Internet access nor broad storage permissions; the app uses Android's system file picker.

## Requirements

- PowerShell 7, Rust stable, and `cargo install cargo-ndk --locked`.
- Rust targets: `rustup target add aarch64-linux-android x86_64-linux-android`.
- Android SDK platform 36 and Android SDK build-tools 36.0.0.
- Android NDK r27 or newer and a JDK, preferably Java 17.

Set `ANDROID_SDK_ROOT`, `ANDROID_NDK_HOME`, and `JAVA_HOME` if the SDK, NDK, or JDK is outside the standard locations.
The script also checks the Android tools installed by Visual Studio under `C:\Program Files (x86)\Android` and the SDK under `%LOCALAPPDATA%\Android\Sdk`.
Rust must be on `PATH` or installed under `CARGO_HOME` / `%USERPROFILE%\.cargo`.

```powershell
$env:ANDROID_SDK_ROOT = 'C:\Program Files (x86)\Android\android-sdk'
$env:ANDROID_NDK_HOME = 'C:\Program Files (x86)\Android\AndroidNDK\android-ndk-r27c'
$env:JAVA_HOME = 'C:\Program Files (x86)\Android\openjdk\jdk-17.0.14'
pwsh -File .\android\build-apk.ps1
```

The output files are `dist/jxl-studio-android.apk` and `dist/android-SHA256SUMS.txt`.
The APK supports Android 8.0 / API 26 and newer, targeting API 36, with ARM64 and x86_64 native codecs.
Native libraries are linked with 16 KiB page alignment, stored uncompressed in the APK, and checked with `zipalign -P 16`.
The script verifies APK signatures after signing. Intermediate files stay under `android/build`.
Use `-SkipNativeBuild` only when `android/build/jni` already contains the native libraries from the current source.

## Signing and updates

The default build creates a persistent local signing key in `%LOCALAPPDATA%\JxlStudio\signing\jxl-studio.p12`.
Its random password is stored separately as `password.dpapi`, encrypted by Windows for the current user.
These files are outside the repository and are never included in the APK.
Back up both files securely; the same signing key is required to install future updates over an existing installation.
The DPAPI password file can be decrypted only under the original Windows user profile.
Existing signing keys are never replaced automatically.

To use an existing keystore, set `JXL_ANDROID_KEYSTORE`, `JXL_ANDROID_KEYSTORE_PASSWORD`, and optionally `JXL_ANDROID_KEY_ALIAS` (default `jxlstudio`).
Pass secrets through your local environment or secret manager; do not commit passwords or keystores.
The same password is used for the keystore and private key.

For disposable local tests or CI, use `pwsh -File .\android\build-apk.ps1 -DebugSigning`.
This uses the standard debug password and a key stored in the ignored `android/build` directory.
Debug signing is for development builds; it does not share the persistent certificate used by the default build.

## Install on a connected device

```powershell
adb install -r .\dist\jxl-studio-android.apk
adb shell am start -n com.jxlstudio.app/.MainActivity
```

Enable USB debugging and authorize your computer on the device before running ADB.
The APK also can be copied to a device and opened with Android's package installer.

## Dependency notices

Application and dependency license files are bundled under `assets/licenses` inside the APK.
The codec dependencies and local encoder fixes are recorded in `Cargo.lock` and `vendor/zune-jpegxl/PATCHES.md`.
