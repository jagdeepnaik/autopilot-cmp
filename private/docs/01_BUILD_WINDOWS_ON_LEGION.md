# 01 — Build AutoPilot for Windows on Legion

[All guides](00_INDEX.md) · [Copy installer](02_COPY_WINDOWS_INSTALLER.md) · [Install and test](03_INSTALL_AND_TEST_WINDOWS.md)

## ONE COMMAND — sync, build, install, debug

From the Mac, in `/Users/jagdeep/Projects/AndroidStudio/AutoPilot`:

```sh
tools/windows/legion.sh
```

This runs the whole chain. Every step stops with a plain reason if something isn't right.

1. **Sync.** It checks the Mac has no uncommitted changes and isn't behind GitHub, and pushes any unpushed commits. It then pulls on Legion and confirms Legion is on the same commit. Legion always builds from Git; nothing is copied over directly.
2. **Build.** It runs `Build-Distribution.ps1` on Legion: focused tests, then `:composeApp:packageReleaseMsi` with the low-memory settings.
3. **Install.** It runs `Install-Msi.ps1 -LaunchCheck -SkipIfInstalled`: a silent update, then checks of the version, the signed `javaw.exe`, both shortcuts, a 15-second launch and Smart App Control blocks. If this version is already installed, it skips the install.
4. **Debug.** It starts the `AutoPilot-Debug` task on Legion's signed-in desktop and waits until the debug run listens on port 5005. It then opens the SSH tunnel on the Mac. In Android Studio choose **AutoPilot Windows (SSH tunnel)** and click **Debug**; the app window appears on Legion. Press Control-C in the Terminal to close the tunnel.

Run only part of it:

| Command | Does |
| --- | --- |
| `tools/windows/legion.sh sync` | Push and pull only |
| `tools/windows/legion.sh build` | Sync, build |
| `tools/windows/legion.sh install` | Sync, build, install and check |
| `tools/windows/legion.sh debug` | Sync, start the debug run and the tunnel |
| add `--no-tests` | Skip the focused tests during the build |

Before running it:

- Commit your work on the Mac. The script won't push uncommitted changes.
- Close AutoPilot normally on Legion if it's open. The install closes a running copy, and the debug run refuses to start while the installed app is open, because both share `.autopilot`.
- For debugging, Legion must be signed in, with `AutoPilot-Debug` registered once (see [debug launcher setup](13_SET_UP_WINDOWS_DEBUG_LAUNCHER.md)).
- If Legion's checkout has local changes, the script stops. Compare them with GitHub and stash them as a backup, then run it again.

The manual steps below do the same thing one command at a time.

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
