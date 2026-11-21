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
        [void]$view.Execute()
        $record = $view.Fetch()
        if ($null -eq $record) { throw "MSI property $Name was not found." }
        return $record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, @(1))
    } finally {
        if ($null -ne $view) { [void]$view.Close() }
        foreach ($comObject in @($record, $view, $database, $installer)) {
            if ($null -ne $comObject) { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($comObject) }
        }
    }
}

function Get-ProductRegistration([string]$Code) {
    $installer = $null
    $products = $null
    $contexts = [Collections.Generic.List[int]]::new()
    try {
        $installer = New-Object -ComObject WindowsInstaller.Installer
        $state = [int]$installer.GetType().InvokeMember('ProductState', 'GetProperty', $null, $installer, @($Code))
        # ProductsEx with an empty SID queries this user plus the machine context.
        $products = $installer.GetType().InvokeMember('ProductsEx', 'GetProperty', $null, $installer, @($Code, '', 7))
        foreach ($product in $products) {
            try {
                [void]$contexts.Add([int]$product.GetType().InvokeMember('Context', 'GetProperty', $null, $product, $null))
            } finally { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($product) }
        }
        $name = $null
        $assignment = $null
        # These ProductInfo attributes require an installed product (state 5).
        if ($state -eq 5) {
            $name = $installer.GetType().InvokeMember('ProductInfo', 'GetProperty', $null, $installer, @($Code, 'InstalledProductName'))
            $assignment = $installer.GetType().InvokeMember('ProductInfo', 'GetProperty', $null, $installer, @($Code, 'AssignmentType'))
        }
        return [pscustomobject]@{
            State = $state
            InstalledProductName = $name
            AssignmentType = $assignment
            Contexts = $contexts.ToArray()
        }
    } finally {
        if ($null -ne $products) { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($products) }
        if ($null -ne $installer) { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer) }
    }
}

$productCode = Read-MsiProperty 'ProductCode'
if ((Read-MsiProperty 'ProductName') -ne 'JXL Studio') { throw 'Unexpected MSI product name.' }
$installDirectory = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Programs\JXL Studio'
$shortcutDirectory = Join-Path ([Environment]::GetFolderPath('Programs')) 'JXL Studio'
$shortcut = Join-Path $shortcutDirectory 'JXL Studio.lnk'
$markerKey = 'HKCU:\Software\JXLStudio\Installer'

# Never replace an existing installation, even on a misconfigured runner.
$existingRegistration = Get-ProductRegistration $productCode
if ($existingRegistration.State -ne -1 -or $existingRegistration.Contexts.Count -ne 0 `
    -or (Test-Path -LiteralPath $installDirectory) `
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
    # Use Windows Installer's public API instead of assuming an ARP registry layout.
    # AssignmentType=0 is per-user; contexts 1/2 are user-managed/unmanaged, 4 is machine.
    $registration = Get-ProductRegistration $productCode
    if ($registration.State -ne 5 -or $registration.InstalledProductName -ne 'JXL Studio' `
        -or $registration.AssignmentType -ne '0' -or $registration.Contexts.Count -ne 1 `
        -or $registration.Contexts[0] -notin @(1, 2)) {
        throw 'Windows Installer did not register this product as installed for the current user.'
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
    if ($installed -or (Get-ProductRegistration $productCode).State -ne -1) {
        Invoke-Msi @('/x', (Quote-Argument $MsiPath)) 'uninstall.log'
    }
}

foreach ($removedPath in @($installDirectory, $shortcutDirectory, $markerKey)) {
    if (Test-Path -LiteralPath $removedPath) { throw "Uninstall left installer-owned content behind: $removedPath" }
}
$removedRegistration = Get-ProductRegistration $productCode
if ($removedRegistration.State -ne -1 -or $removedRegistration.Contexts.Count -ne 0) {
    throw 'Uninstall left the product registered with Windows Installer.'
}
Write-Host "MSI smoke test passed: $($payload.Count) files, per-user installation, shortcut, repair and clean uninstall."
