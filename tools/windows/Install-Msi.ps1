# Installs or updates AutoPilot silently from the newest built MSI, then checks the result.
# Run on Windows as an administrator, for example over SSH from the Mac:
#   ssh home-legion 'powershell -NoProfile -ExecutionPolicy RemoteSigned -File C:\Users\jagde\Projects\autopilot-cmp\tools\windows\Install-Msi.ps1'
# App data in %USERPROFILE%\.autopilot is never touched. A running AutoPilot is closed first:
# close it normally on the desktop beforehand so a routine's progress is saved.
[CmdletBinding()]
param(
    [string]$Msi,
    [switch]$LaunchCheck,
    # Exit successfully, without reinstalling, when this version is already installed.
    [switch]$SkipIfInstalled
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$installDir = 'C:\Program Files\AutoPilot'
$uninstallKeys = 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*'

function Get-InstalledVersion {
    (Get-ItemProperty $uninstallKeys -ErrorAction SilentlyContinue | Where-Object DisplayName -eq 'AutoPilot').DisplayVersion
}

$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) { throw 'Run this as an administrator; the MSI installs for all users.' }

if (-not $Msi) {
    $Msi = (Get-ChildItem (Join-Path $projectRoot 'composeApp\build\compose\binaries\main-release\msi\AutoPilot-*.msi') -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime | Select-Object -Last 1).FullName
    if (-not $Msi) { throw 'No built MSI found. Build it first with :composeApp:packageReleaseMsi.' }
}
$newVersion = [regex]::Match((Split-Path $Msi -Leaf), '\d+\.\d+\.\d+').Value
$installed = Get-InstalledVersion
Write-Output "MSI:       $Msi"
Write-Output "SHA256:    $((Get-FileHash -LiteralPath $Msi).Hash)"
Write-Output "Installed: $(if ($installed) { $installed } else { 'none' })"
if ($SkipIfInstalled -and $installed -and $newVersion -and ([version]$newVersion -eq [version]$installed)) {
    Write-Output "Version $installed is already installed; nothing to do."
    return
}
if ($installed -and $newVersion -and ([version]$newVersion -le [version]$installed)) {
    throw "Version $newVersion is not newer than the installed $installed, so Windows won't update it. Build a newer commit, or uninstall first."
}

Get-Process javaw, AutoPilot -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$installDir\*" } | Stop-Process

$log = Join-Path $env:TEMP 'AutoPilot-install.log'
$start = Get-Date
$process = Start-Process msiexec.exe -ArgumentList "/i `"$Msi`" /qn /norestart /L*V `"$log`"" -Wait -PassThru
Write-Output "msiexec exit: $($process.ExitCode)  (log: $log)"
if ($process.ExitCode -notin 0, 3010) {
    Select-String -LiteralPath $log -Pattern 'Error 1', 'Return value 3' -Context 6, 2 | Select-Object -First 3 | ForEach-Object { $_.ToString() }
    throw "Installation failed with exit code $($process.ExitCode)."
}
if ($process.ExitCode -eq 3010) { Write-Output 'Installed; Windows wants a restart to finish.' }
Write-Output "Installed: $(Get-InstalledVersion)"

$javaw = Join-Path $installDir 'runtime\bin\javaw.exe'
$signature = if (Test-Path $javaw) { (Get-AuthenticodeSignature $javaw).Status } else { 'missing' }
Write-Output "javaw.exe signature: $signature"
$shell = New-Object -ComObject WScript.Shell
$shortcuts = @("$env:PUBLIC\Desktop\AutoPilot.lnk", "$env:ProgramData\Microsoft\Windows\Start Menu\Programs\AutoPilot\AutoPilot.lnk")
$problems = @()
foreach ($shortcut in $shortcuts) {
    $target = if (Test-Path $shortcut) { $shell.CreateShortcut($shortcut).TargetPath } else { 'missing' }
    Write-Output "Shortcut $shortcut -> $target"
    if ($target -ne $javaw) { $problems += "$shortcut doesn't start the signed javaw.exe" }
}
if ($signature -ne 'Valid') { $problems += 'javaw.exe is missing or not validly signed' }

if ($LaunchCheck) {
    # Runs in this session, so no window appears on the desktop.
    Start-Process $shortcuts[0]
    Start-Sleep 15
    $app = Get-Process javaw -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $javaw }
    Write-Output "Running 15 s after opening the shortcut: $([bool]$app)"
    if (-not $app) { $problems += 'The app was not running 15 s after opening its shortcut' }
    $app | Stop-Process
}

$blocks = @(Get-WinEvent -LogName 'Microsoft-Windows-CodeIntegrity/Operational' -MaxEvents 100 -ErrorAction SilentlyContinue |
    Where-Object { $_.TimeCreated -gt $start -and $_.Id -eq 3077 })
Write-Output "Smart App Control blocks since install: $($blocks.Count)"
$blocks | ForEach-Object { Write-Output ('  ' + ($_.Message -replace '(?s).*attempted to load (.*?) that.*', '$1')) }
if ($blocks.Count -gt 0) { $problems += 'Smart App Control blocked files' }

if ($problems.Count -gt 0) { throw ('Installed, but: ' + ($problems -join '; ') + '. See private/docs/05_FIX_WINDOWS_INSTALLER.md.') }
Write-Output 'OK: open AutoPilot from its desktop or Start menu shortcut.'
