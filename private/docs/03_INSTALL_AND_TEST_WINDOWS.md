# 03 — Install and Test AutoPilot on Windows

[All guides](00_INDEX.md) · [Build installer](01_BUILD_WINDOWS_ON_LEGION.md) · [Fix installer errors](05_FIX_WINDOWS_INSTALLER.md)

## PART E — INSTALL AND TEST — Legion desktop

Use the physical Legion desktop for installer dialogs and GUI testing. SSH alone does not show the Windows screen.

The current AutoPilot build removes the blocked unsigned Java MSI helper through `tools/windows/Remove-UnsignedMsiHelper.ps1`. The recorded installation result and remaining validation are in [Windows installer diagnosis](17_WINDOWS_INSTALLER_DIAGNOSIS.md). Keep Smart App Control enabled. Successful installation on Legion does not establish trust on every recipient's PC; trusted signing remains a separate distribution step.

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
