# Run locally or through SSH. This builds files; it does not launch the desktop application.
[CmdletBinding()]
param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
Push-Location $projectRoot
try {
    # SSH's non-interactive PowerShell does not always inherit the user's refreshed PATH.
    # Recover a per-machine Temurin JDK installation before failing the build.
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        $jdk = @(
            Get-ChildItem "$env:ProgramFiles\Eclipse Adoptium\jdk-21*" -Directory -ErrorAction SilentlyContinue
            Get-ChildItem "$env:ProgramFiles\Java\jdk-21*" -Directory -ErrorAction SilentlyContinue
            Get-ChildItem "$env:LOCALAPPDATA\Programs\Eclipse Adoptium\jdk-21*" -Directory -ErrorAction SilentlyContinue
        ) | Sort-Object FullName -Descending | Select-Object -First 1
        if ($jdk) {
            $env:JAVA_HOME = $jdk.FullName
            $env:Path = "$($jdk.FullName)\bin;$env:Path"
        }
    }
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Install a full JDK 21 and reopen PowerShell first.' }
    # Low-memory settings: the build laptop also runs Chrome and the app.
    $gradleTasks = @('--no-daemon', '--console=plain', '--max-workers=2', '-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8', '-Pkotlin.compiler.execution.strategy=in-process')
    if (-not $SkipTests) {
        $gradleTasks += @(':core:automation:jvmTest', ':feature:home:jvmTest', '--tests', '*DgsStudy*', ':shared:jvmTest', '--tests', '*DesktopDataPathsTest*')
    }
    $gradleTasks += ':desktopApp:packageReleaseMsi'
    & .\gradlew.bat @gradleTasks
    if ($LASTEXITCODE -ne 0) { throw "Windows build failed (exit $LASTEXITCODE)." }
    $installers = @(Get-ChildItem 'desktopApp/build/compose/binaries/main-release/msi/*.msi' -ErrorAction Stop)
    if ($installers.Count -eq 0) { throw 'The build did not produce an MSI installer.' }
    $installers | ForEach-Object {
        Write-Output "Installer: $($_.FullName)"
        Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName
    }
} finally { Pop-Location }
