# Start in the SIGNED-IN Windows desktop, not directly in the SSH service session.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$logDirectory = Join-Path $env:USERPROFILE '.autopilot/logs'
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$logPath = Join-Path $logDirectory ("debug-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
Push-Location $projectRoot
try {
    Start-Transcript -Path $logPath | Out-Null
    Write-Output 'Waiting for the Mac debugger on Windows localhost:5005. Use the SSH tunnel; do not open a debugger firewall port.'
    & .\gradlew.bat --no-daemon --console=plain :composeApp:run --debug-jvm
    if ($LASTEXITCODE -ne 0) { throw "Debug run failed (exit $LASTEXITCODE). Log: $logPath" }
} finally {
    Stop-Transcript -ErrorAction SilentlyContinue | Out-Null
    Pop-Location
}
