# Installs or updates AutoPilot silently from the newest built MSI, then checks the result.
# Run on Windows as an administrator, for example over SSH from the Mac:
#   ssh home-legion 'powershell -NoProfile -ExecutionPolicy RemoteSigned -File C:\Users\jagde\Projects\autopilot-cmp\private\scripts\windows\Install-Msi.ps1'
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
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$installDir = 'C:\Program Files\AutoPilot'
$uninstallKeys = 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*'

function Get-InstalledVersion {
    (Get-ItemProperty $uninstallKeys -ErrorAction SilentlyContinue | Where-Object DisplayName -eq 'AutoPilot').DisplayVersion
}

$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) { throw 'Run this as an administrator; the MSI installs for all users.' }

if (-not $Msi) {
    $Msi = (Get-ChildItem (Join-Path $projectRoot 'desktopApp\build\compose\binaries\main-release\msi\AutoPilot-*.msi') -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime | Select-Object -Last 1).FullName
    if (-not $Msi) { throw 'No built MSI found. Build it first with :desktopApp:packageReleaseMsi.' }
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
$launchArguments = $null
foreach ($shortcut in $shortcuts) {
    $shortcutObject = if (Test-Path $shortcut) { $shell.CreateShortcut($shortcut) } else { $null }
    $target = if ($shortcutObject) { $shortcutObject.TargetPath } else { 'missing' }
    Write-Output "Shortcut $shortcut -> $target"
    if ($target -ne $javaw) { $problems += "$shortcut doesn't start the signed javaw.exe" }
    if (-not $launchArguments -and $shortcutObject) { $launchArguments = $shortcutObject.Arguments }
}
if ($signature -ne 'Valid') { $problems += 'javaw.exe is missing or not validly signed' }
$unsigned = @(Get-ChildItem (Join-Path $installDir 'runtime\bin') -Recurse -Include *.dll, *.exe | Where-Object { (Get-AuthenticodeSignature $_.FullName).Status -ne 'Valid' })
Write-Output "Unsigned files in the bundled runtime: $($unsigned.Count)"
$unsigned | ForEach-Object { Write-Output ('  ' + $_.FullName.Substring($installDir.Length + 1)) }

if ($LaunchCheck) {
    # An SSH service session is non-interactive. Launching the shortcut directly there can put the
    # GUI process in session 0, so it never appears on the signed-in desktop. Use a temporary task
    # with an interactive principal to exercise the same javaw.exe and shortcut arguments there.
    $checkTask = "AutoPilot-InstallCheck-$PID"
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    $app = @()
    try {
        if (-not $launchArguments) { throw 'The desktop shortcut has no launch arguments.' }
        $action = New-ScheduledTaskAction -Execute $javaw -Argument $launchArguments -WorkingDirectory $installDir
        $principal = New-ScheduledTaskPrincipal -UserId $identity -LogonType Interactive -RunLevel Limited
        Register-ScheduledTask -TaskName $checkTask -Action $action -Principal $principal -Force | Out-Null
        Start-ScheduledTask -TaskName $checkTask
        Start-Sleep 15
        $app = @(Get-Process javaw -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $javaw })
        # javaw.exe stays open while it shows a "can't load jvm.dll" error, so check the JVM really loaded.
        $jvmLoaded = @($app | Where-Object { $_.Modules.ModuleName -contains 'jvm.dll' }).Count -gt 0
        Write-Output "Running 15 s after opening the shortcut in the interactive desktop: $($app.Count -gt 0); Java VM loaded: $jvmLoaded"
        if (-not $jvmLoaded) { $problems += 'The app did not start: its Java VM was not loaded 15 s after opening the shortcut' }
    } finally {
        $app | Stop-Process -ErrorAction SilentlyContinue
        Unregister-ScheduledTask -TaskName $checkTask -Confirm:$false -ErrorAction SilentlyContinue
    }
}

$blocks = @(Get-WinEvent -LogName 'Microsoft-Windows-CodeIntegrity/Operational' -MaxEvents 100 -ErrorAction SilentlyContinue |
    Where-Object { $_.TimeCreated -gt $start -and $_.Id -eq 3077 })
Write-Output "Smart App Control blocks since install: $($blocks.Count)"
$blocks | ForEach-Object { Write-Output ('  ' + ($_.Message -replace '(?s).*attempted to load (.*?) that.*', '$1')) }
if ($blocks.Count -gt 0) { $problems += 'Smart App Control blocked files' }

if ($problems.Count -gt 0) { throw ('Installed, but: ' + ($problems -join '; ') + '. See private/docs/09_FIX_WINDOWS_INSTALLER.md.') }
Write-Output 'OK: open AutoPilot from its desktop or Start menu shortcut.'
