# 03 — Install and Test AutoPilot on Windows

[All guides](00_INDEX.md) · [Build installer](01_BUILD_WINDOWS_ON_LEGION.md) · [Fix installer errors](05_FIX_WINDOWS_INSTALLER.md)

## PART E — INSTALL AND TEST — Legion desktop

Use the physical Legion desktop for installer dialogs and GUI testing. SSH alone does not show the Windows screen. To install or update without touching the laptop, use [PART S — install over SSH](#part-s--install-or-update-over-ssh--from-the-mac) below, then check the window on the desktop.

The current AutoPilot build removes the blocked unsigned Java MSI helper through `tools/windows/Remove-UnsignedMsiHelper.ps1`, and its shortcuts start the app through the bundled, Microsoft-signed `javaw.exe` because Smart App Control blocks the generated `AutoPilot.exe`. Always open AutoPilot from its Start menu or desktop shortcut. The recorded installation result and remaining validation are in [Windows installer diagnosis](17_WINDOWS_INSTALLER_DIAGNOSIS.md). Keep Smart App Control enabled. Successful installation on Legion does not establish trust on every recipient's PC; trusted signing remains a separate distribution step.

### E1. Close AutoPilot normally

Stop any running routine, save changes, and close AutoPilot. Preserve its `.autopilot` settings, databases, and question banks.

### E2. Open the installer folder

**Legion File Explorer — paste into its address bar:**

```text
C:\Users\jagde\Projects\autopilot-cmp\composeApp\build\compose\binaries\main-release\msi
```

Select the MSI from the successful build. Right-click → **Copy as path**.

### E3. Install with a log

**Legion desktop — open PowerShell as Administrator**, then run:

```powershell
$msi = (Read-Host 'Paste the AutoPilot MSI path').Trim('"')
```

Paste the copied path when asked. Then run:

```powershell
$log = Join-Path $env:TEMP 'AutoPilot-install.log'
```

Then:

```powershell
msiexec.exe /i "$msi" /L*V "$log" /norestart
```

Follow the installer dialogs and wait. Do not start a second installer while this one is active.

### E4. If installation fails

In the same local PowerShell:

```powershell
Select-String -Path "$log" -Pattern 'Error 1723','Return value 3' -Context 12,6
```

Keep the error context and full log for review. Do not assume every DLL error is the earlier helper problem; compare the actual action and Windows events. Keep logs containing personal paths out of the public repository.

### E5. Test installed AutoPilot

Open **AutoPilot** from Windows Start. Verify the window controls, sidebar Home action, settings persistence, extension connection, and the routine being tested. Confirm the extension is loaded from the Windows user's `.autopilot` folder using AutoPilot's extension setup instructions. Do not copy private Mac databases or answer files into a public release.

Record the commit, MSI version/hash, installation outcome, and tested routine. Building, installing, launching, and completing a routine are separate checks.

## PART S — INSTALL OR UPDATE OVER SSH — from the Mac

Installs or updates AutoPilot silently, with no installer dialogs, from a Mac Terminal. The SSH session on Legion runs with administrator rights, so the per-machine install needs no extra prompt. Use this after a successful build (Part C), then open the app on the Legion desktop to check the window (E5).

| Item | Value |
| --- | --- |
| MSI folder on Legion | `C:\Users\jagde\Projects\autopilot-cmp\composeApp\build\compose\binaries\main-release\msi` |
| Install folder | `C:\Program Files\AutoPilot\` |
| App data (never touched by install, update or uninstall) | `C:\Users\jagde\.autopilot` |
| Install log | `%TEMP%\AutoPilot-install.log` |

### S1. Connect

**Mac Terminal:**

```sh
ssh home-legion
```

If the prompt doesn't start with `PS`, enter `powershell.exe -NoProfile`. Everything below runs in this **Windows SSH PowerShell** window.

### S2. Pick the newest MSI and compare versions

The folder can hold several builds (for example `AutoPilot-1.0.24.msi` and `AutoPilot-1.0.25.msi`), so pick the newest:

```powershell
$msi = (Get-ChildItem C:\Users\jagde\Projects\autopilot-cmp\composeApp\build\compose\binaries\main-release\msi\AutoPilot-*.msi | Sort-Object LastWriteTime | Select-Object -Last 1).FullName
$installed = (Get-ItemProperty 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*' | Where-Object DisplayName -eq 'AutoPilot').DisplayVersion
"New MSI:   $msi"
"Installed: $installed"
Get-FileHash $msi
```

- **New version higher than installed** (for example 1.0.25 over 1.0.24): continue. The MSI updates in place; there's no need to uninstall.
- **Nothing installed:** continue. It's a fresh install.
- **Same version:** Windows treats it as already installed and won't update. Either build from a newer commit, or uninstall first (S6) and then continue.

### S3. Close AutoPilot

If someone is using the app on the desktop, ask them to stop the routine and close it normally first, so progress is saved. Then make sure no copy is still running:

```powershell
Get-Process javaw, AutoPilot -ErrorAction SilentlyContinue | Where-Object { $_.Path -like 'C:\Program Files\AutoPilot\*' } | Stop-Process
```

### S4. Install or update

```powershell
$log = "$env:TEMP\AutoPilot-install.log"
$start = Get-Date
$p = Start-Process msiexec.exe -ArgumentList "/i `"$msi`" /qn /norestart /L*V `"$log`"" -Wait -PassThru
"msiexec exit: $($p.ExitCode)"
Select-String -Path $log -Pattern 'error status' | ForEach-Object { $_.Line.Trim() }
(Get-ItemProperty 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*' | Where-Object DisplayName -eq 'AutoPilot').DisplayVersion
```

Expected: `msiexec exit: 0`, `Installation success or error status: 0`, and the new version. `/qn` means no dialogs; `/norestart` prevents an automatic reboot. An exit code of 3010 also means success, but Windows wants a restart.

### S5. Check the install

```powershell
"javaw signed: " + (Get-AuthenticodeSignature 'C:\Program Files\AutoPilot\runtime\bin\javaw.exe').Status
$sh = New-Object -ComObject WScript.Shell
"Desktop shortcut:    " + $sh.CreateShortcut("$env:PUBLIC\Desktop\AutoPilot.lnk").TargetPath
"Start menu shortcut: " + $sh.CreateShortcut("$env:ProgramData\Microsoft\Windows\Start Menu\Programs\AutoPilot\AutoPilot.lnk").TargetPath
"Smart App Control blocks: " + @(Get-WinEvent -LogName 'Microsoft-Windows-CodeIntegrity/Operational' -MaxEvents 50 | Where-Object { $_.TimeCreated -gt $start -and $_.Id -eq 3077 }).Count
```

Expected: `Valid`, both shortcuts pointing at `C:\Program Files\AutoPilot\runtime\bin\javaw.exe`, and `0` blocks. If a shortcut points at `AutoPilot.exe`, the MSI was built without the Smart App Control fix; see [Fix installer errors](05_FIX_WINDOWS_INSTALLER.md).

Optional quick launch check. It starts the app inside the SSH session, so no window appears on the desktop, and it closes it again after 15 seconds:

```powershell
Start-Process "$env:PUBLIC\Desktop\AutoPilot.lnk"; Start-Sleep 15
$app = Get-Process javaw -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq 'C:\Program Files\AutoPilot\runtime\bin\javaw.exe' }
"Running after 15 s: $([bool]$app)"; $app | Stop-Process
```

Then open AutoPilot from its shortcut **on the Legion desktop** and check the window (E5). About and the status bar show the installed version.

### S6. Uninstall (only when needed)

Needed only to reinstall the same version, or to go back to an older one. It leaves `.autopilot` in place.

```powershell
$app = Get-ItemProperty 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*' | Where-Object DisplayName -eq 'AutoPilot'
if ($app) { $p = Start-Process msiexec.exe -ArgumentList "/x $($app.PSChildName) /qn /norestart" -Wait -PassThru; "uninstall exit: $($p.ExitCode)" }
```

### S7. If it fails

```powershell
Select-String -Path "$env:TEMP\AutoPilot-install.log" -Pattern 'Error 1','Return value 3' -Context 12,6
Get-WinEvent -FilterHashtable @{LogName='Application'; ProviderName='MsiInstaller'; StartTime=(Get-Date).AddHours(-1)} | Format-List TimeCreated, Id, Message
```

| Exit code or message | Meaning | What to do |
| --- | --- | --- |
| 1603 with Error 1723 naming a `Jp…` action | The MSI still has the unsigned helper | Rebuild from a commit that has the fix ([05](05_FIX_WINDOWS_INSTALLER.md), H4) |
| 1638 | This version, or a newer one, is already installed | Build a newer commit, or uninstall (S6) |
| 1618 | Another installation is running | Wait for it to finish, then repeat S4 |
| 1925 or "insufficient privileges" | The SSH session isn't elevated | Run S4 in PowerShell as Administrator on the Legion desktop |
| App blocked when opened | The shortcut starts `AutoPilot.exe` | Rebuild with the Smart App Control fix ([05](05_FIX_WINDOWS_INSTALLER.md), H0b) |

### S8. One command from the Mac

`tools/windows/Install-Msi.ps1` does S2–S5 in one go: it picks the newest MSI, refuses one that isn't newer than the installed version, closes a running copy, installs silently, then checks the version, the signed `javaw.exe`, both shortcuts, and Smart App Control blocks. Close AutoPilot normally on the desktop first.

**Mac Terminal:**

```sh
ssh home-legion 'powershell -NoProfile -ExecutionPolicy RemoteSigned -File C:\Users\jagde\Projects\autopilot-cmp\tools\windows\Install-Msi.ps1'
```

Add `-LaunchCheck` after `Install-Msi.ps1` to also open the app through its shortcut for 15 seconds (no window appears on the desktop). To install a particular file, add `-Msi C:\path\to\AutoPilot-1.0.25.msi`.

Expected last line: `OK: open AutoPilot from its desktop or Start menu shortcut.` Otherwise the script stops with the reason (for example, *"Version 1.0.25 is not newer than the installed 1.0.25"*). See S7 for exit codes.

Typing the S4 commands straight into an `ssh home-legion '…'` command line doesn't work reliably, because the quotes get mangled between the Mac shell, Windows and PowerShell. Use the script or an interactive session (S1).
