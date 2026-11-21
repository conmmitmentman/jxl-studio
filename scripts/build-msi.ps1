[CmdletBinding()]
param(
    [string] $PackageDirectory,
    [string] $WixDirectory
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repository = Split-Path $PSScriptRoot -Parent
$dist = Join-Path $repository 'dist'
if (-not $PackageDirectory) { $PackageDirectory = Join-Path $dist 'jxl-studio-windows-x64' }
$package = (Resolve-Path -LiteralPath $PackageDirectory).Path
foreach ($required in @('jxl-studio.exe', 'jxl-studio-cli.exe', 'README.md', 'README.en.md', 'LICENSE', 'THIRD_PARTY_NOTICES.md', 'licenses')) {
    if (-not (Test-Path -LiteralPath (Join-Path $package $required))) { throw "Missing packaged file: $required. Run scripts/package.ps1 first." }
}
$manifest = Get-Content -LiteralPath (Join-Path $repository 'Cargo.toml') -Raw
if ($manifest -notmatch '(?m)^version\s*=\s*"(\d+\.\d+\.\d+)"') { throw 'Cannot determine the application version' }
$version = $matches[1]
$cliHelp = @(& (Join-Path $package 'jxl-studio-cli.exe') --lang en --help)
if ($LASTEXITCODE -ne 0 -or $cliHelp.Count -eq 0 -or $cliHelp[0].Trim() -ne "JXL Studio CLI $version") {
    throw "Packaged executable does not match Cargo.toml version $version"
}
$parts = $version.Split('.')
if ([int]$parts[0] -gt 255 -or [int]$parts[1] -gt 255 -or [int]$parts[2] -gt 65535) { throw 'Version exceeds Windows Installer limits' }

# Portable WiX; its download is pinned to the SHA-256 also used by Tauri's bundler.
# https://github.com/tauri-apps/tauri/blob/dev/crates/tauri-bundler/src/bundle/windows/msi/mod.rs
if (-not $WixDirectory) {
    $tools = Join-Path $dist 'msi-tools'
    $WixDirectory = Join-Path $tools 'wix-3.14.1'
    New-Item -ItemType Directory -Path $tools -Force | Out-Null
    $archive = Join-Path $tools 'wix314-binaries.zip'
    $expected = '6ac824e1642d6f7277d0ed7ea09411a508f6116ba6fae0aa5f2c7daa2ff43d31'
    if (-not (Test-Path -LiteralPath $archive)) {
        Invoke-WebRequest -Uri 'https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip' -OutFile $archive
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'WiX download checksum mismatch' }
    if (-not (Test-Path -LiteralPath (Join-Path $WixDirectory 'light.exe'))) {
        Expand-Archive -LiteralPath $archive -DestinationPath $WixDirectory -Force
    }
}
$WixDirectory = (Resolve-Path -LiteralPath $WixDirectory).Path
foreach ($tool in @('candle.exe', 'light.exe', 'WixUIExtension.dll')) {
    if (-not (Test-Path -LiteralPath (Join-Path $WixDirectory $tool))) { throw "Missing WiX tool: $tool" }
}

$work = Join-Path $dist 'msi-build'
New-Item -ItemType Directory -Path $work -Force | Out-Null
function Get-StableHash([string] $Text) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return [Convert]::ToHexString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text))).ToLowerInvariant() }
    finally { $sha.Dispose() }
}
function ConvertTo-Xml([string] $Text) { return [Security.SecurityElement]::Escape($Text) }
function Get-StableGuid([string] $Text) { return ([guid]::ParseExact((Get-StableHash $Text).Substring(0, 32), 'N')).ToString().ToUpperInvariant() }
$components = [Collections.Generic.List[string]]::new()
$xml = [Text.StringBuilder]::new()
[void]$xml.AppendLine('<?xml version="1.0" encoding="utf-8"?>')
[void]$xml.AppendLine('<Wix xmlns="http://schemas.microsoft.com/wix/2006/wi"><Fragment><DirectoryRef Id="INSTALLDIR">')
function Add-PackageDirectory([string] $Directory, [string] $Relative, [bool] $IsRoot) {
    $directoryHash = (Get-StableHash ('JxlStudio.Directory.x64:' + $Relative.ToLowerInvariant())).Substring(0, 32)
    if (-not $IsRoot) {
        $directoryId = 'd_' + $directoryHash
        $name = ConvertTo-Xml ([IO.Path]::GetFileName($Directory))
        [void]$xml.AppendLine("<Directory Id=`"$directoryId`" Name=`"$name`">")
        $component = 'r_' + $directoryHash
        $guid = Get-StableGuid ('JxlStudio.RemoveDirectory.x64:' + $Relative.ToLowerInvariant())
        [void]$xml.AppendLine("<Component Id=`"$component`" Guid=`"$guid`" Win64=`"yes`"><RemoveFolder Id=`"$component`" On=`"uninstall`" /><RegistryValue Root=`"HKCU`" Key=`"Software\JXLStudio\Installer\Directories`" Name=`"$component`" Type=`"integer`" Value=`"1`" KeyPath=`"yes`" /></Component>")
        $components.Add($component)
    }
    foreach ($file in (Get-ChildItem -LiteralPath $Directory -File | Sort-Object Name)) {
        $relativeFile = if ($Relative) { "$Relative/$($file.Name)" } else { $file.Name }
        $hash = (Get-StableHash ('JxlStudio.File.x64:' + $relativeFile.ToLowerInvariant())).Substring(0, 32)
        $component = 'c_' + $hash
        $fileId = 'f_' + $hash
        $guid = Get-StableGuid ('JxlStudio.Component.x64:' + $relativeFile.ToLowerInvariant())
        $name = ConvertTo-Xml $file.Name
        $source = ConvertTo-Xml $file.FullName
        [void]$xml.AppendLine("<Component Id=`"$component`" Guid=`"$guid`" Win64=`"yes`"><File Id=`"$fileId`" Name=`"$name`" Source=`"$source`" /><RegistryValue Root=`"HKCU`" Key=`"Software\JXLStudio\Installer\Files`" Name=`"$fileId`" Type=`"integer`" Value=`"1`" KeyPath=`"yes`" /></Component>")
        $components.Add($component)
    }
    foreach ($child in (Get-ChildItem -LiteralPath $Directory -Directory | Sort-Object Name)) {
        if ($child.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Package directory links are unsupported: $($child.FullName)" }
        $relativeChild = if ($Relative) { "$Relative/$($child.Name)" } else { $child.Name }
        Add-PackageDirectory $child.FullName $relativeChild $false
    }
    if (-not $IsRoot) { [void]$xml.AppendLine('</Directory>') }
}
Add-PackageDirectory $package '' $true
[void]$xml.AppendLine('</DirectoryRef></Fragment><Fragment><ComponentGroup Id="PackageFiles">')
foreach ($component in $components) { [void]$xml.AppendLine("<ComponentRef Id=`"$component`" />") }
[void]$xml.AppendLine('</ComponentGroup></Fragment></Wix>')
$fragment = Join-Path $work 'PackageFiles.wxs'
[IO.File]::WriteAllText($fragment, $xml.ToString(), [Text.UTF8Encoding]::new($false))

$license = Get-Content -LiteralPath (Join-Path $package 'LICENSE') -Raw
$rtf = '{\rtf1\ansi\deff0{\fonttbl{\f0 Segoe UI;}}\fs18 ' + $license.Replace('\', '\\').Replace('{', '\{').Replace('}', '\}').Replace("`r", '').Replace("`n", '\par ') + '}'
$licenseRtf = Join-Path $work 'License.rtf'
[IO.File]::WriteAllText($licenseRtf, $rtf, [Text.ASCIIEncoding]::new())
$productCode = Get-StableGuid "JxlStudio.Product.x64:$version"
$output = Join-Path $dist 'jxl-studio-windows-x64.msi'
& (Join-Path $WixDirectory 'candle.exe') -nologo -arch x64 "-dProductVersion=$version" "-dProductCode=$productCode" "-dLicenseRtf=$licenseRtf" -out "$work\" (Join-Path $repository 'installer/Product.wxs') $fragment
if ($LASTEXITCODE -ne 0) { throw "WiX compilation failed ($LASTEXITCODE)" }
# ICE91 warns about per-user files in packages that might be installed per-machine.
# This package deliberately supports only a per-user installation; keep all other ICE checks.
& (Join-Path $WixDirectory 'light.exe') -nologo -sice:ICE91 -ext (Join-Path $WixDirectory 'WixUIExtension.dll') -cultures:en-us -out $output (Join-Path $work 'Product.wixobj') (Join-Path $work 'PackageFiles.wixobj')
if ($LASTEXITCODE -ne 0) { throw "WiX linking or MSI validation failed ($LASTEXITCODE)" }
$hash = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
"$hash  jxl-studio-windows-x64.msi" | Set-Content -LiteralPath (Join-Path $dist 'msi-SHA256SUMS.txt') -Encoding utf8NoBOM
Write-Host "MSI ready: $output"
Get-Item -LiteralPath $output | Select-Object Name, Length
