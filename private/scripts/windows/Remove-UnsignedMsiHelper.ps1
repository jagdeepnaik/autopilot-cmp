# Removes jpackage's unsigned custom-action DLL (JpCaDll) from a built MSI.
#
# Why: Smart App Control blocks loading that unsigned DLL, so the MSI fails with
# Error 1723 at JpFindRelatedProducts (see private/docs/WINDOWS_INSTALLER_DIAGNOSIS.md).
# The DLL backs two optional actions:
#   JpFindRelatedProducts - refines the standard FindRelatedProducts action, which the MSI
#                           still runs against its Upgrade table, so upgrades keep working.
#   JpCheckInstallDir     - on the folder page, warns when the chosen folder is not empty.
#                           The signed WiX path check still validates the folder.
# Any other action backed by the DLL stops the script and leaves the MSI unchanged.
# The WiX helper DLLs that stay in the package are signed by the WiX Toolset.
#
# Safe to run more than once. Called by Gradle after packageMsi / packageReleaseMsi.
[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$Path)
$ErrorActionPreference = 'Stop'

$helperBinary = 'JpCaDll'
$removableActions = @('JpFindRelatedProducts', 'JpCheckInstallDir')
$sequenceTables = @('InstallExecuteSequence', 'InstallUISequence', 'AdminExecuteSequence', 'AdminUISequence', 'AdvtExecuteSequence')
$installer = New-Object -ComObject WindowsInstaller.Installer

function Invoke-MsiSql($database, [string]$sql) {
    $view = $database.OpenView($sql)
    [void]$view.Execute()
    [void]$view.Close()
    [void][Runtime.InteropServices.Marshal]::ReleaseComObject($view)
}

function Get-MsiColumn($database, [string]$sql) {
    $view = $database.OpenView($sql)
    [void]$view.Execute()
    $values = @()
    while ($record = $view.Fetch()) {
        $values += [string]$record.StringData(1)
        [void][Runtime.InteropServices.Marshal]::ReleaseComObject($record)
    }
    [void]$view.Close()
    [void][Runtime.InteropServices.Marshal]::ReleaseComObject($view)
    return , $values
}

function Test-MsiTable($database, [string]$table) {
    return (Get-MsiColumn $database "SELECT ``Name`` FROM ``_Tables`` WHERE ``Name``='$table'").Count -gt 0
}

$msiFiles = if (Test-Path -LiteralPath $Path -PathType Container) {
    @(Get-ChildItem -LiteralPath $Path -Filter '*.msi')
} else {
    @(Get-Item -LiteralPath $Path)
}
if ($msiFiles.Count -eq 0) { throw "No MSI found at $Path." }

foreach ($msi in $msiFiles) {
    # 1 = msiOpenDatabaseModeTransact: nothing is written unless Commit() is reached.
    $database = $installer.OpenDatabase($msi.FullName, 1)
    try {
        $actions = Get-MsiColumn $database "SELECT ``Action`` FROM ``CustomAction`` WHERE ``Source``='$helperBinary'"
        $hasBinary = (Get-MsiColumn $database "SELECT ``Name`` FROM ``Binary`` WHERE ``Name``='$helperBinary'").Count -gt 0
        if ($actions.Count -eq 0 -and -not $hasBinary) {
            Write-Output "Already free of ${helperBinary}: $($msi.Name)"
            continue
        }

        $unknown = @($actions | Where-Object { $removableActions -notcontains $_ })
        if ($unknown.Count -gt 0) {
            throw "$($msi.Name) uses $helperBinary for unreviewed actions [$($unknown -join ', ')]. MSI left unchanged."
        }

        foreach ($action in $actions) {
            foreach ($table in $sequenceTables) {
                if (Test-MsiTable $database $table) {
                    Invoke-MsiSql $database "DELETE FROM ``$table`` WHERE ``Action``='$action'"
                }
            }
            if (Test-MsiTable $database 'ControlEvent') {
                Invoke-MsiSql $database "DELETE FROM ``ControlEvent`` WHERE ``Event``='DoAction' AND ``Argument``='$action'"
            }
            Invoke-MsiSql $database "DELETE FROM ``CustomAction`` WHERE ``Action``='$action'"
        }
        Invoke-MsiSql $database "DELETE FROM ``Binary`` WHERE ``Name``='$helperBinary'"
        $database.Commit()
        Write-Output "Removed $helperBinary and [$($actions -join ', ')] from $($msi.Name)"
    } finally {
        # Release every handle so the MSI file is closed before the next step reads it.
        [void][Runtime.InteropServices.Marshal]::ReleaseComObject($database)
        [GC]::Collect()
        [GC]::WaitForPendingFinalizers()
    }
}
