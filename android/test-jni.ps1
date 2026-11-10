#requires -Version 7.0
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    cargo build --locked -p jxl-studio-android
    if ($LASTEXITCODE -ne 0) { throw 'Host JNI build failed' }
    $javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files (x86)\Android\openjdk\jdk-17.0.14' }
    $metadata = cargo metadata --locked --format-version 1 --no-deps | ConvertFrom-Json
    $classes = Join-Path $PSScriptRoot 'build/host-tests'
    [IO.Directory]::CreateDirectory($classes) | Out-Null
    & "$javaHome/bin/javac" -encoding UTF-8 -d $classes android/java/com/jxlstudio/app/NativeCodec.java android/tests/com/jxlstudio/app/JniSmokeTest.java
    if ($LASTEXITCODE -ne 0) { throw 'JNI test Java compilation failed' }
    $libraryDirectory = Join-Path $metadata.target_directory 'debug'
    & "$javaHome/bin/java" "-Djava.library.path=$libraryDirectory" -cp $classes com.jxlstudio.app.JniSmokeTest
    if ($LASTEXITCODE -ne 0) { throw 'JNI smoke test failed' }
} finally { Pop-Location }
