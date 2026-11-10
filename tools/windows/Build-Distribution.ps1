# Run locally or through SSH. This builds files; it does not launch the desktop application.
[CmdletBinding()]
param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $projectRoot
try {
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Install a full JDK 21 and reopen PowerShell first.' }
    $gradleTasks = @('--no-daemon', '--console=plain')
    if (-not $SkipTests) {
        $gradleTasks += @(':core:automation:jvmTest', ':feature:home:jvmTest', '--tests', '*DgsStudy*', ':composeApp:jvmTest', '--tests', '*DesktopDataPathsTest*')
    }
    $gradleTasks += ':composeApp:packageReleaseMsi'
    & .\gradlew.bat @gradleTasks
    if ($LASTEXITCODE -ne 0) { throw "Windows build failed (exit $LASTEXITCODE)." }
    $installers = @(Get-ChildItem 'composeApp/build/compose/binaries/main-release/msi/*.msi' -ErrorAction Stop)
    if ($installers.Count -eq 0) { throw 'The build did not produce an MSI installer.' }
    $installers | ForEach-Object {
        Write-Output "Installer: $($_.FullName)"
        Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName
    }
} finally { Pop-Location }
