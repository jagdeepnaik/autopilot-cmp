# 01 — Build AutoPilot for Windows on Legion

[All guides](00_INDEX.md) · [Copy installer](02_COPY_WINDOWS_INSTALLER.md) · [Install and test](03_INSTALL_AND_TEST_WINDOWS.md)

## PART C — BUILD AN EXISTING PROJECT — repeat for each build

### C1. Connect to Legion

**Mac Terminal:**

```sh
ssh home-legion
```

If the connected prompt does not start with `PS`, enter:

```text
powershell.exe -NoProfile
```

Expected: `PS C:\Users\jagde>`. You are now in **Windows SSH PowerShell**. All remaining Part C commands run there.

### C2. Open AutoPilot

```powershell
Set-Location C:\Users\jagde\Projects\autopilot-cmp
```

Expected prompt: `PS C:\Users\jagde\Projects\autopilot-cmp>`. Stop if the folder is missing.

### C3. Check for local edits

```powershell
git status --short
```

No output means clean. If files are listed, preserve them and stop before pulling. Do not reset or overwrite edits.

### C4. Update AutoPilot — only when C3 is clean

```powershell
git pull --ff-only
```

Stop if it fails. This downloads pushed commits; uncommitted Mac changes are not included. Record the revision:

```powershell
git log -1 --oneline
```

### C5. Run focused tests and build the MSI

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\tools\windows\Build-Distribution.ps1
```

This AutoPilot helper runs `core:automation` tests, DGS study tests, desktop data-path tests, and `:composeApp:packageReleaseMsi`. The script permission applies only to this PowerShell process. Do not add `-SkipTests` for a release check.

Wait for **BUILD SUCCESSFUL**, the installer path, and SHA256 output. Stop on any error; do not distribute an older MSI as the new build. This builds the installer; it does not install or launch AutoPilot.

For changes to the desktop window/sidebar, also run:

```powershell
.\gradlew.bat :composeApp:jvmTest --tests '*WindowsWindowControlsTest' --tests '*DesktopShellUiTest' --console=plain
```

### C6. Locate the AutoPilot installer

```powershell
Get-ChildItem .\composeApp\build\compose\binaries\main-release\msi\AutoPilot-*.msi
```

Record its hash separately if needed:

```powershell
Get-ChildItem .\composeApp\build\compose\binaries\main-release\msi\AutoPilot-*.msi | Get-FileHash -Algorithm SHA256
```

The filename contains the MSI version, `<major>.<minor>.<commit count>`, for example `AutoPilot-1.0.22.msi` (commit `c11518c` is the 22nd). A newer version updates an installed copy in place. Use the actual output of this build. A matching hash verifies copying, not signing.
