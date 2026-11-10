#requires -Version 7.0
[CmdletBinding()]
param(
    [switch]$DebugSigning,
    [switch]$SkipNativeBuild,
    [switch]$BuildTests
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $IsWindows) { throw 'This script currently supports Windows with PowerShell 7.' }

$projectRoot = Split-Path $PSScriptRoot -Parent
$androidRoot = $PSScriptRoot
$buildRoot = Join-Path $androidRoot 'build'
$workRoot = Join-Path $buildRoot ('work-' + [Guid]::NewGuid().ToString('N'))
$jniRoot = Join-Path $buildRoot 'jni'
$distRoot = Join-Path $projectRoot 'dist'
$environmentNames = @('PATH', 'JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'ANDROID_NDK_HOME',
    'RUSTFLAGS', 'CARGO_ENCODED_RUSTFLAGS', 'JXL_BUILD_KEYSTORE_PASSWORD')
$oldEnvironment = @{}
foreach ($name in $environmentNames) { $oldEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }

function Find-ToolDirectory {
    param([string[]]$Candidates, [string]$RelativeTool, [string]$Label)
    foreach ($candidate in $Candidates) {
        if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate $RelativeTool))) {
            return [IO.Path]::GetFullPath($candidate)
        }
    }
    throw "$Label not found. Set the corresponding environment variable; see android/BUILDING.md."
}

function Invoke-Checked {
    param([string]$Executable, [string[]]$Arguments)
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$(Split-Path $Executable -Leaf) failed with exit code $LASTEXITCODE." }
}

function Add-ZipFile {
    param([IO.Compression.ZipArchive]$Archive, [string]$EntryName, [string]$FilePath,
        [IO.Compression.CompressionLevel]$Compression = [IO.Compression.CompressionLevel]::Optimal)
    $entry = $Archive.CreateEntry($EntryName.Replace('\', '/'), $Compression)
    $inputStream = [IO.File]::OpenRead($FilePath)
    $outputStream = $entry.Open()
    try { $inputStream.CopyTo($outputStream) }
    finally { $outputStream.Dispose(); $inputStream.Dispose() }
}

Push-Location $projectRoot
try {
    $sdkRoot = Find-ToolDirectory -Candidates @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME,
        'C:\Program Files (x86)\Android\android-sdk',
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk')) -RelativeTool 'platforms\android-36\android.jar' -Label 'Android SDK platform 36'
    $ndkCandidates = @($env:ANDROID_NDK_HOME, $env:ANDROID_NDK_ROOT,
        'C:\Program Files (x86)\Android\AndroidNDK\android-ndk-r27c', (Join-Path $sdkRoot 'ndk-bundle'))
    $sideBySideNdk = Join-Path $sdkRoot 'ndk'
    if (Test-Path -LiteralPath $sideBySideNdk) {
        $ndkCandidates += @(Get-ChildItem -LiteralPath $sideBySideNdk -Directory | Sort-Object Name -Descending | ForEach-Object FullName)
    }
    $ndkRoot = Find-ToolDirectory -Candidates $ndkCandidates -RelativeTool 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe' -Label 'Android NDK'
    $javaRoot = Find-ToolDirectory -Candidates @($env:JAVA_HOME,
        'C:\Program Files (x86)\Android\openjdk\jdk-17.0.14',
        'C:\Program Files\Android\Android Studio\jbr') -RelativeTool 'bin\javac.exe' -Label 'JDK'
    $toolsRoot = Join-Path $sdkRoot 'build-tools\36.0.0'
    foreach ($tool in @('aapt2.exe', 'zipalign.exe', 'lib\d8.jar', 'lib\apksigner.jar')) {
        if (-not (Test-Path -LiteralPath (Join-Path $toolsRoot $tool))) { throw "Android build-tools 36.0.0 are required: missing $tool" }
    }
    $cargoCandidates = @()
    $cargoCommand = Get-Command cargo.exe -ErrorAction SilentlyContinue
    if ($cargoCommand) { $cargoCandidates += Split-Path $cargoCommand.Source -Parent }
    if ($env:CARGO_HOME) { $cargoCandidates += Join-Path $env:CARGO_HOME 'bin' }
    $cargoCandidates += Join-Path $env:USERPROFILE '.cargo\bin'
    $cargoBin = Find-ToolDirectory -Candidates $cargoCandidates -RelativeTool 'cargo.exe' -Label 'Rust cargo'
    $cargo = Join-Path $cargoBin 'cargo.exe'
    $java = Join-Path $javaRoot 'bin\java.exe'
    $javac = Join-Path $javaRoot 'bin\javac.exe'
    $keytool = Join-Path $javaRoot 'bin\keytool.exe'
    $androidJar = Join-Path $sdkRoot 'platforms\android-36\android.jar'
    $env:JAVA_HOME = $javaRoot
    $env:ANDROID_HOME = $sdkRoot
    $env:ANDROID_SDK_ROOT = $sdkRoot
    $env:ANDROID_NDK_HOME = $ndkRoot
    $env:PATH = "$cargoBin;$(Join-Path $javaRoot 'bin');$env:PATH"
    $alignmentFlags = @('-C', 'link-arg=-Wl,-z,max-page-size=16384', '-C', 'link-arg=-Wl,-z,common-page-size=16384')
    if ($env:CARGO_ENCODED_RUSTFLAGS) {
        $env:CARGO_ENCODED_RUSTFLAGS += [char]31 + ($alignmentFlags -join [char]31)
    } else { $env:RUSTFLAGS = ($env:RUSTFLAGS + ' ' + ($alignmentFlags -join ' ')).Trim() }
    foreach ($directory in @($workRoot, $jniRoot, $distRoot)) { [IO.Directory]::CreateDirectory($directory) | Out-Null }

    if (-not $SkipNativeBuild) {
        Write-Host 'Building Rust codecs for Android ARM64 and x86_64...'
        Invoke-Checked $cargo @('ndk', '-t', 'arm64-v8a', '-t', 'x86_64', '--platform', '26', '-o', $jniRoot,
            'build', '--release', '--locked', '-p', 'jxl-studio-android')
    }
    foreach ($abi in @('arm64-v8a', 'x86_64')) {
        if (-not (Test-Path -LiteralPath (Join-Path $jniRoot "$abi\libjxl_studio_android.so"))) {
            throw "Missing native codec for $abi. Run without -SkipNativeBuild."
        }
    }

    $generatedRoot = Join-Path $workRoot 'generated'
    $classesRoot = Join-Path $workRoot 'classes'
    $dexRoot = Join-Path $workRoot 'dex'
    foreach ($directory in @($generatedRoot, $classesRoot, $dexRoot)) { [IO.Directory]::CreateDirectory($directory) | Out-Null }
    $compiledResources = Join-Path $workRoot 'resources.zip'
    $unsignedApk = Join-Path $workRoot 'unsigned.apk'
    $alignedApk = Join-Path $workRoot 'aligned.apk'
    $outputApk = Join-Path $distRoot 'jxl-studio-android.apk'
    Invoke-Checked (Join-Path $toolsRoot 'aapt2.exe') @('compile', '--dir', (Join-Path $androidRoot 'res'), '-o', $compiledResources)
    Invoke-Checked (Join-Path $toolsRoot 'aapt2.exe') @('link', '-o', $unsignedApk, '-I', $androidJar,
        '--manifest', (Join-Path $androidRoot 'AndroidManifest.xml'), '--java', $generatedRoot,
        '--min-sdk-version', '26', '--target-sdk-version', '36', $compiledResources)
    $javaSources = @(Get-ChildItem -LiteralPath (Join-Path $androidRoot 'java'), $generatedRoot -Recurse -Filter '*.java' -File | ForEach-Object FullName)
    if ($javaSources.Count -eq 0) { throw 'No Android Java source files found.' }
    $bootClasspath = (Join-Path $toolsRoot 'core-lambda-stubs.jar') + [IO.Path]::PathSeparator + $androidJar
    Invoke-Checked $javac (@('-encoding', 'UTF-8', '-source', '8', '-target', '8', '-bootclasspath', $bootClasspath,
        '-d', $classesRoot) + $javaSources)
    $classFiles = @(Get-ChildItem -LiteralPath $classesRoot -Recurse -Filter '*.class' -File | ForEach-Object FullName)
    Invoke-Checked $java (@('-cp', (Join-Path $toolsRoot 'lib\d8.jar'), 'com.android.tools.r8.D8', '--release',
        '--min-api', '26', '--lib', $androidJar, '--output', $dexRoot) + $classFiles)

    $archive = [IO.Compression.ZipFile]::Open($unsignedApk, [IO.Compression.ZipArchiveMode]::Update)
    try {
        foreach ($dex in Get-ChildItem -LiteralPath $dexRoot -Filter '*.dex' -File) { Add-ZipFile $archive $dex.Name $dex.FullName }
        foreach ($abi in @('arm64-v8a', 'x86_64')) {
            Add-ZipFile $archive "lib/$abi/libjxl_studio_android.so" (Join-Path $jniRoot "$abi\libjxl_studio_android.so") ([IO.Compression.CompressionLevel]::NoCompression)
        }
        foreach ($notice in @('LICENSE', 'THIRD_PARTY_NOTICES.md')) {
            Add-ZipFile $archive "assets/licenses/$notice" (Join-Path $projectRoot $notice)
        }
        $metadataText = & $cargo metadata --locked --no-default-features --filter-platform aarch64-linux-android --format-version 1
        if ($LASTEXITCODE -ne 0) { throw 'cargo metadata failed while gathering dependency licenses.' }
        $metadata = $metadataText | ConvertFrom-Json
        foreach ($package in $metadata.packages) {
            if ($package.name -in @('jxl-studio', 'jxl-studio-android')) { continue }
            $packageRoot = Split-Path $package.manifest_path -Parent
            foreach ($license in Get-ChildItem -LiteralPath $packageRoot -Recurse -File |
                Where-Object { $_.Name -match '(?i)^(licen[sc]e|copying|notice|ofl|ufl|copyright)([.\-_].*)?$' }) {
                $relative = [IO.Path]::GetRelativePath($packageRoot, $license.FullName).Replace('\', '/')
                Add-ZipFile $archive "assets/licenses/$($package.name)-$($package.version)/$relative" $license.FullName
            }
        }
    } finally { $archive.Dispose() }
    Invoke-Checked (Join-Path $toolsRoot 'zipalign.exe') @('-f', '-P', '16', '4', $unsignedApk, $alignedApk)

    if ($BuildTests) {
        $testRoot = Join-Path $workRoot 'instrumentation'
        $testClasses = Join-Path $testRoot 'classes'
        $testDex = Join-Path $testRoot 'dex'
        foreach ($directory in @($testRoot, $testClasses, $testDex)) { [IO.Directory]::CreateDirectory($directory) | Out-Null }
        $testUnsigned = Join-Path $testRoot 'unsigned.apk'
        $testAligned = Join-Path $testRoot 'aligned.apk'
        $testOutput = Join-Path $distRoot 'jxl-studio-android-tests.apk'
        Invoke-Checked (Join-Path $toolsRoot 'aapt2.exe') @('link', '-o', $testUnsigned, '-I', $androidJar,
            '--manifest', (Join-Path $androidRoot 'instrumentation\AndroidManifest.xml'))
        $testSources = @(Get-ChildItem -LiteralPath (Join-Path $androidRoot 'instrumentation\java') -Recurse -Filter '*.java' -File | ForEach-Object FullName)
        Invoke-Checked $javac (@('-encoding', 'UTF-8', '-source', '8', '-target', '8', '-bootclasspath', $bootClasspath,
            '-classpath', $classesRoot, '-d', $testClasses) + $testSources)
        $appClassJar = Join-Path $testRoot 'app-classes.jar'
        [IO.Compression.ZipFile]::CreateFromDirectory($classesRoot, $appClassJar)
        $testClassFiles = @(Get-ChildItem -LiteralPath $testClasses -Recurse -Filter '*.class' -File | ForEach-Object FullName)
        Invoke-Checked $java (@('-cp', (Join-Path $toolsRoot 'lib\d8.jar'), 'com.android.tools.r8.D8', '--release',
            '--min-api', '26', '--lib', $androidJar, '--classpath', $appClassJar, '--output', $testDex) + $testClassFiles)
        $testArchive = [IO.Compression.ZipFile]::Open($testUnsigned, [IO.Compression.ZipArchiveMode]::Update)
        try { foreach ($dex in Get-ChildItem -LiteralPath $testDex -Filter '*.dex' -File) { Add-ZipFile $testArchive $dex.Name $dex.FullName } }
        finally { $testArchive.Dispose() }
        Invoke-Checked (Join-Path $toolsRoot 'zipalign.exe') @('-f', '4', $testUnsigned, $testAligned)
    }

    $alias = if ($env:JXL_ANDROID_KEY_ALIAS) { $env:JXL_ANDROID_KEY_ALIAS } else { 'jxlstudio' }
    $password = $null
    if ($DebugSigning) {
        $keyFile = Join-Path $buildRoot 'debug-signing.p12'
        $alias = 'androiddebugkey'
        $password = 'android'
        $subject = 'CN=Android Debug,O=Android,C=US'
        $generateKey = -not (Test-Path -LiteralPath $keyFile)
    } else {
        $signingRoot = Join-Path $env:LOCALAPPDATA 'JxlStudio\signing'
        $keyFile = if ($env:JXL_ANDROID_KEYSTORE) { [IO.Path]::GetFullPath($env:JXL_ANDROID_KEYSTORE) } else { Join-Path $signingRoot 'jxl-studio.p12' }
        $passwordFile = Join-Path $signingRoot 'password.dpapi'
        $generateKey = -not (Test-Path -LiteralPath $keyFile)
        if ($env:JXL_ANDROID_KEYSTORE_PASSWORD) { $password = $env:JXL_ANDROID_KEYSTORE_PASSWORD }
        elseif ($env:JXL_ANDROID_KEYSTORE) { throw 'Set JXL_ANDROID_KEYSTORE_PASSWORD for the custom keystore.' }
        elseif (Test-Path -LiteralPath $passwordFile) {
            if ($generateKey) { throw 'Signing password exists without its keystore. Restore the original keystore before building.' }
            $securePassword = Get-Content -LiteralPath $passwordFile -Raw | ConvertTo-SecureString
            $password = [Net.NetworkCredential]::new('', $securePassword).Password
        } elseif (-not $generateKey) { throw 'Keystore exists without its password. Set JXL_ANDROID_KEYSTORE_PASSWORD; existing keys are never replaced.' }
        else {
            $randomBytes = [byte[]]::new(32)
            [Security.Cryptography.RandomNumberGenerator]::Fill($randomBytes)
            $password = [Convert]::ToBase64String($randomBytes)
        }
        $subject = 'CN=JXL Studio,O=JXL Studio,C=JP'
    }
    $env:JXL_BUILD_KEYSTORE_PASSWORD = $password
    if ($generateKey) {
        [IO.Directory]::CreateDirectory((Split-Path $keyFile -Parent)) | Out-Null
        $temporaryKey = Join-Path (Split-Path $keyFile -Parent) ([Guid]::NewGuid().ToString('N') + '.p12')
        try {
            Invoke-Checked $keytool @('-genkeypair', '-noprompt', '-keystore', $temporaryKey, '-storetype', 'PKCS12',
                '-storepass:env', 'JXL_BUILD_KEYSTORE_PASSWORD', '-keypass:env', 'JXL_BUILD_KEYSTORE_PASSWORD',
                '-alias', $alias, '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000', '-dname', $subject)
            [IO.File]::Move($temporaryKey, $keyFile, $false)
            if (-not $DebugSigning -and -not $env:JXL_ANDROID_KEYSTORE -and -not $env:JXL_ANDROID_KEYSTORE_PASSWORD) {
                try {
                    $encryptedPassword = ConvertTo-SecureString $password -AsPlainText -Force | ConvertFrom-SecureString
                    $secretStream = [IO.File]::Open($passwordFile, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
                    try {
                        $secretBytes = [Text.Encoding]::UTF8.GetBytes($encryptedPassword)
                        $secretStream.Write($secretBytes, 0, $secretBytes.Length)
                    } finally { $secretStream.Dispose() }
                } catch {
                    # Only the brand-new key from this invocation is rolled back; existing keys are untouched.
                    [IO.File]::Delete($keyFile)
                    throw
                }
            }
        } finally { if (Test-Path -LiteralPath $temporaryKey) { [IO.File]::Delete($temporaryKey) } }
    }
    Write-Host $(if ($DebugSigning) { 'Signing APK with the local debug certificate...' } else { 'Signing APK with the persistent local certificate...' })
    Invoke-Checked $java @('-jar', (Join-Path $toolsRoot 'lib\apksigner.jar'), 'sign', '--ks', $keyFile,
        '--ks-key-alias', $alias, '--ks-pass', 'env:JXL_BUILD_KEYSTORE_PASSWORD', '--key-pass', 'env:JXL_BUILD_KEYSTORE_PASSWORD',
        '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true', '--out', $outputApk, $alignedApk)
    Invoke-Checked $java @('-jar', (Join-Path $toolsRoot 'lib\apksigner.jar'), 'verify', '--verbose', $outputApk)
    Invoke-Checked (Join-Path $toolsRoot 'zipalign.exe') @('-c', '-P', '16', '4', $outputApk)
    if ($BuildTests) {
        Invoke-Checked $java @('-jar', (Join-Path $toolsRoot 'lib\apksigner.jar'), 'sign', '--ks', $keyFile,
            '--ks-key-alias', $alias, '--ks-pass', 'env:JXL_BUILD_KEYSTORE_PASSWORD', '--key-pass', 'env:JXL_BUILD_KEYSTORE_PASSWORD',
            '--out', $testOutput, $testAligned)
        Invoke-Checked $java @('-jar', (Join-Path $toolsRoot 'lib\apksigner.jar'), 'verify', '--verbose', $testOutput)
    }
    $hash = (Get-FileHash -LiteralPath $outputApk -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  jxl-studio-android.apk" | Set-Content -LiteralPath (Join-Path $distRoot 'android-SHA256SUMS.txt') -Encoding utf8NoBOM
    Write-Host "APK ready: $outputApk"
    Get-Item -LiteralPath $outputApk | Select-Object FullName, Length
} finally {
    foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, $oldEnvironment[$name], 'Process') }
    Pop-Location
}
