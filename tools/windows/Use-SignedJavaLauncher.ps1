# Points an MSI's shortcuts at the bundled, Microsoft-signed javaw.exe instead of jpackage's launcher.
#
# Why: jpackage generates a new, unsigned <App>.exe for every build. Smart App Control has never seen
# that file, so it blocks it ("Smart App Control blocked an app that may be unsafe"). The bundled
# runtime's DLLs are the JDK's own files, which Smart App Control already knows, and javaw.exe is
# signed by the JDK vendor. Gradle copies that javaw.exe into the runtime image's bin folder; this
# script makes the Start menu and desktop shortcuts start the app through it, with the main class and
# JVM options the generated launcher would have used. Gradle writes those to the -Launcher file: the
# main class on the first line, then one JVM option per line.
#
# Safe to run more than once. Called by Gradle after packageMsi / packageReleaseMsi.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Path,
    [Parameter(Mandatory = $true)][string]$Launcher,
    [Parameter(Mandatory = $true)][string]$Javaw,
    [string]$LauncherName = 'AutoPilot.exe'
)
$ErrorActionPreference = 'Stop'
$installer = New-Object -ComObject WindowsInstaller.Installer

function Get-MsiRows($database, [string]$sql, [int]$columns) {
    $view = $database.OpenView($sql)
    [void]$view.Execute()
    $rows = @()
    while ($record = $view.Fetch()) {
        $rows += , @(1..$columns | ForEach-Object { [string]$record.StringData($_) })
        [void][Runtime.InteropServices.Marshal]::ReleaseComObject($record)
    }
    [void]$view.Close()
    [void][Runtime.InteropServices.Marshal]::ReleaseComObject($view)
    return , $rows
}

# File table names are "SHORT~1|Long name.ext" or just the name.
function Get-LongName([string]$fileName) { return ($fileName -split '\|')[-1] }

# --- What the generated launcher would have run ------------------------------------------------
if (-not (Test-Path -LiteralPath $Javaw)) { throw "No javaw.exe at $Javaw; the Gradle step that copies it into the runtime image did not run." }
$signature = Get-AuthenticodeSignature -LiteralPath $Javaw
if ($signature.Status -ne 'Valid') { throw "$Javaw is not validly signed ($($signature.Status)); Smart App Control would block it." }

$lines = @(Get-Content -LiteralPath $Launcher | ForEach-Object { $_.Trim() } | Where-Object { $_ })
if ($lines.Count -eq 0) { throw "No main class in $Launcher." }
$mainClass = $lines[0]
$options = @($lines | Select-Object -Skip 1)

# $APPDIR is the install folder's app directory. INSTALLDIR always ends with a backslash.
$quoted = @($options | ForEach-Object { '"' + ($_ -replace '\$APPDIR', '[INSTALLDIR]app') + '"' })
$arguments = (($quoted + '-cp' + '"[INSTALLDIR]app\*"' + $mainClass) -join ' ')

# --- Rewrite the shortcuts ---------------------------------------------------------------------
$msiFiles = if (Test-Path -LiteralPath $Path -PathType Container) { @(Get-ChildItem -LiteralPath $Path -Filter '*.msi') } else { @(Get-Item -LiteralPath $Path) }
if ($msiFiles.Count -eq 0) { throw "No MSI found at $Path." }

foreach ($msi in $msiFiles) {
    # 1 = msiOpenDatabaseModeTransact: nothing is written unless Commit() is reached.
    $database = $installer.OpenDatabase($msi.FullName, 1)
    try {
        $files = Get-MsiRows $database 'SELECT `File`,`FileName` FROM `File`' 2
        $javawKeys = @($files | Where-Object { (Get-LongName $_[1]) -eq 'javaw.exe' } | ForEach-Object { $_[0] })
        if ($javawKeys.Count -ne 1) { throw "$($msi.Name) has $($javawKeys.Count) javaw.exe files; expected 1. MSI left unchanged." }
        $javawTarget = "[#$($javawKeys[0])]"
        $launcherKeys = @($files | Where-Object { (Get-LongName $_[1]) -eq $LauncherName } | ForEach-Object { "[#$($_[0])]" })

        $icons = Get-MsiRows $database 'SELECT `Name` FROM `Icon`' 1
        $icon = if ($icons.Count -gt 0) { $icons[0][0] } else { $null }

        $shortcuts = Get-MsiRows $database 'SELECT `Shortcut`,`Target`,`Arguments` FROM `Shortcut`' 3
        if ($shortcuts.Count -eq 0) { throw "$($msi.Name) has no shortcuts. MSI left unchanged." }
        $changed = 0
        foreach ($shortcut in $shortcuts) {
            if ($shortcut[1] -eq $javawTarget -and $shortcut[2] -eq $arguments) { continue }
            if ($launcherKeys -notcontains $shortcut[1] -and $shortcut[1] -ne $javawTarget) {
                throw "Shortcut $($shortcut[0]) points at $($shortcut[1]), not $LauncherName. MSI left unchanged."
            }
            $view = $database.OpenView('UPDATE `Shortcut` SET `Target`=?, `Arguments`=?, `Icon_`=?, `IconIndex`=0 WHERE `Shortcut`=?')
            $record = $installer.CreateRecord(4)
            $record.StringData(1) = $javawTarget
            $record.StringData(2) = $arguments
            $record.StringData(3) = $icon
            $record.StringData(4) = $shortcut[0]
            [void]$view.Execute($record)
            [void]$view.Close()
            [void][Runtime.InteropServices.Marshal]::ReleaseComObject($record)
            [void][Runtime.InteropServices.Marshal]::ReleaseComObject($view)
            $changed++
        }
        if ($changed -eq 0) { Write-Output "Shortcuts already use javaw.exe: $($msi.Name)"; continue }
        $database.Commit()
        Write-Output "Pointed $changed shortcuts at the signed runtime\bin\javaw.exe in $($msi.Name)"
    } finally {
        [void][Runtime.InteropServices.Marshal]::ReleaseComObject($database)
        [GC]::Collect()
        [GC]::WaitForPendingFinalizers()
    }
}
