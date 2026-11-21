[CmdletBinding()]
param(
    [string]$MsiPath = 'dist/jxl-studio-windows-x64.msi',
    [string]$PackageDirectory = 'dist/jxl-studio-windows-x64',
    [string]$LogDirectory = 'dist/msi-test-logs'
)

$ErrorActionPreference = 'Stop'

# This script deliberately installs a package. Run it only on disposable CI runners.
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:CI -ne 'true') {
    throw 'MSI install/repair/uninstall tests must run on a disposable GitHub Actions Windows runner.'
}
if (-not $IsWindows) { throw 'MSI tests require Windows.' }

$MsiPath = (Resolve-Path -LiteralPath $MsiPath).Path
$PackageDirectory = (Resolve-Path -LiteralPath $PackageDirectory).Path
$LogDirectory = [IO.Path]::GetFullPath($LogDirectory)
New-Item -ItemType Directory -Path $LogDirectory -Force | Out-Null

function Quote-Argument([string]$Value) {
    if ($Value.Contains('"')) { throw 'An MSI command argument contains an invalid quote.' }
    return '"' + $Value + '"'
}

function Invoke-Msi([string[]]$Arguments, [string]$LogName) {
    $log = Join-Path $LogDirectory $LogName
    $commandArguments = $Arguments + @('/qn', '/norestart', '/l*v', (Quote-Argument $log))
    $process = Start-Process -FilePath "$env:SystemRoot\System32\msiexec.exe" `
        -ArgumentList $commandArguments -WindowStyle Hidden -Wait -PassThru
    if ($process.ExitCode -notin @(0, 3010)) {
        if (Test-Path -LiteralPath $log) { Get-Content -LiteralPath $log -Tail 100 | Write-Host }
        throw "Windows Installer failed with exit code $($process.ExitCode). See $log"
    }
}

function Read-MsiProperty([string]$Name) {
    $installer = $null
    $database = $null
    $view = $null
    $record = $null
    try {
        $installer = New-Object -ComObject WindowsInstaller.Installer
        $database = $installer.OpenDatabase($MsiPath, 0)
        $view = $database.OpenView("SELECT ``Value`` FROM ``Property`` WHERE ``Property``='$Name'")
        $view.Execute()
        $record = $view.Fetch()
        if ($null -eq $record) { throw "MSI property $Name was not found." }
        return $record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, @(1))
    } finally {
        if ($null -ne $view) { $view.Close() }
        foreach ($comObject in @($record, $view, $database, $installer)) {
            if ($null -ne $comObject) { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($comObject) }
        }
    }
}

$productCode = Read-MsiProperty 'ProductCode'
if ((Read-MsiProperty 'ProductName') -ne 'JXL Studio') { throw 'Unexpected MSI product name.' }
$installDirectory = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Programs\JXL Studio'
$shortcutDirectory = Join-Path ([Environment]::GetFolderPath('Programs')) 'JXL Studio'
$shortcut = Join-Path $shortcutDirectory 'JXL Studio.lnk'
$uninstallKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\$productCode"
$markerKey = 'HKCU:\Software\JXLStudio\Installer'

# Never replace an existing installation, even on a misconfigured runner.
if ((Test-Path -LiteralPath $installDirectory) -or (Test-Path -LiteralPath $uninstallKey) `
    -or (Test-Path -LiteralPath $shortcutDirectory) -or (Test-Path -LiteralPath $markerKey)) {
    throw 'The runner already contains a JXL Studio installation; refusing to modify it.'
}

$payload = @(Get-ChildItem -LiteralPath $PackageDirectory -File -Recurse)
if ($payload.Count -eq 0) { throw 'The expected package directory is empty.' }
foreach ($required in @('jxl-studio.exe', 'jxl-studio-cli.exe', 'README.md', 'README.en.md', 'LICENSE')) {
    if (-not (Test-Path -LiteralPath (Join-Path $PackageDirectory $required) -PathType Leaf)) {
        throw "Required source payload is missing: $required"
    }
}

function Assert-InstalledPayload {
    foreach ($source in $payload) {
        $relative = [IO.Path]::GetRelativePath($PackageDirectory, $source.FullName)
        $destination = Join-Path $installDirectory $relative
        if (-not (Test-Path -LiteralPath $destination -PathType Leaf)) { throw "Missing installed file: $relative" }
        if ((Get-FileHash -LiteralPath $source.FullName -Algorithm SHA256).Hash `
            -ne (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash) {
            throw "Installed file differs from the package payload: $relative"
        }
    }
    if (-not (Test-Path -LiteralPath $shortcut -PathType Leaf)) { throw 'Start menu shortcut was not installed.' }
    $shell = New-Object -ComObject WScript.Shell
    $link = $null
    try {
        $link = $shell.CreateShortcut($shortcut)
        if ($link.TargetPath -ne (Join-Path $installDirectory 'jxl-studio.exe')) {
            throw 'The Start menu shortcut does not target the installed application.'
        }
    } finally {
        if ($null -ne $link) { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($link) }
        [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($shell)
    }
    $uninstall = Get-ItemProperty -LiteralPath $uninstallKey
    if ($uninstall.DisplayName -ne 'JXL Studio' -or [string]::IsNullOrWhiteSpace($uninstall.UninstallString)) {
        throw 'The current-user Windows uninstall registration is missing or incorrect.'
    }
    if ((Get-ItemProperty -LiteralPath $markerKey -Name Installed).Installed -ne 1) {
        throw 'The current-user installer component marker is missing.'
    }
}

$installed = $false
try {
    Invoke-Msi @('/i', (Quote-Argument $MsiPath)) 'install.log'
    $installed = $true
    Assert-InstalledPayload
    # Repair must restore a missing executable and a missing translated document.
    Remove-Item -LiteralPath (Join-Path $installDirectory 'jxl-studio-cli.exe')
    Remove-Item -LiteralPath (Join-Path $installDirectory 'README.en.md')
    Invoke-Msi @('/fa', (Quote-Argument $MsiPath)) 'repair.log'
    Assert-InstalledPayload
} finally {
    if ($installed -or (Test-Path -LiteralPath $uninstallKey)) {
        Invoke-Msi @('/x', (Quote-Argument $MsiPath)) 'uninstall.log'
    }
}

foreach ($removedPath in @($installDirectory, $shortcutDirectory, $uninstallKey, $markerKey)) {
    if (Test-Path -LiteralPath $removedPath) { throw "Uninstall left installer-owned content behind: $removedPath" }
}
Write-Host "MSI smoke test passed: $($payload.Count) files, per-user installation, shortcut, repair and clean uninstall."
