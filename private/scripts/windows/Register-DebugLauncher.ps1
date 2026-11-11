# Run ONCE in the signed-in Windows user's PowerShell. No password is stored.
# Creates an on-demand task only; it does not start an app or change firewall/SSH permissions.
[CmdletBinding()]
param([switch]$Remove)
$ErrorActionPreference = 'Stop'
$taskName = 'AutoPilot-Debug'
if ($Remove) {
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
    return
}
$identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$scriptPath = Join-Path $PSScriptRoot 'Start-Debug.ps1'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ('-NoProfile -ExecutionPolicy RemoteSigned -File "' + $scriptPath + '"') -WorkingDirectory $projectRoot
$principal = New-ScheduledTaskPrincipal -UserId $identity -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours 4) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName $taskName -Action $action -Principal $principal -Settings $settings -Description 'On-demand AutoPilot desktop debug run for the currently signed-in user' -Force | Out-Null
Write-Output "Registered $taskName for $identity. Keep this Windows desktop signed in and unlocked for Chrome automation testing."
