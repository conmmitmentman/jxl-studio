$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$packageName = 'jxl-studio-windows-x64'
$packagePath = Join-Path (Get-Location) "dist/$packageName"
New-Item -ItemType Directory -Path $packagePath -Force | Out-Null
Copy-Item target/release/jxl-studio.exe,target/release/jxl-studio-cli.exe,README.md,LICENSE,THIRD_PARTY_NOTICES.md -Destination $packagePath

# Copy the licenses of the complete locked dependency graph, including embedded fonts.
$metadata = cargo metadata --locked --format-version 1 | ConvertFrom-Json
if ($LASTEXITCODE -ne 0) { throw 'cargo metadata failed' }
foreach ($package in $metadata.packages) {
    if ($package.name -eq 'jxl-studio') { continue }
    $sourcePath = Split-Path $package.manifest_path -Parent
    $licensePath = Join-Path $packagePath "licenses/$($package.name)-$($package.version)"
    $licenseFiles = Get-ChildItem -LiteralPath $sourcePath -Recurse -File |
        Where-Object { $_.Name -match '(?i)^(licen[sc]e|copying|notice|ofl|ufl|copyright)([.\-_].*)?$' }
    foreach ($file in $licenseFiles) {
        $relative = [System.IO.Path]::GetRelativePath($sourcePath, $file.FullName)
        $destination = Join-Path $licensePath $relative
        New-Item -ItemType Directory -Path (Split-Path $destination -Parent) -Force | Out-Null
        Copy-Item -LiteralPath $file.FullName -Destination $destination -Force
    }
}
Compress-Archive -Path "$packagePath/*" -DestinationPath "dist/$packageName.zip" -Force
$hash = Get-FileHash "dist/$packageName.zip" -Algorithm SHA256
"$($hash.Hash.ToLowerInvariant())  $packageName.zip" | Set-Content dist/SHA256SUMS.txt -Encoding utf8NoBOM
Get-Item "dist/$packageName.zip",dist/SHA256SUMS.txt | Select-Object Name,Length
