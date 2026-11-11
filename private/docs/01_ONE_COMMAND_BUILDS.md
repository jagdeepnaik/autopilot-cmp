# 01 — One Command: Sync, Build, Install and Debug on Every Desktop Machine

[All guides](00_INDEX.md) · [Manual Legion build](02_BUILD_WINDOWS_ON_LEGION.md) · [Manual install over SSH](03_INSTALL_AND_TEST_WINDOWS.md#part-s--install-or-update-over-ssh--from-the-mac) · [Manual server build](09_BUILD_LINUX_ON_SERVER.md)

**On this page:** [Go to the scripts folder](#go-to-the-scripts-folder) · [Quick reference](#quick-reference) · [What each machine does](#what-each-machine-does) · [How it works](#how-it-works) · [Server VM start/stop](#server-windows-vm--start-and-stop) · [When it stops](#when-it-stops) · [Files](#files) · [Recorded runs](#recorded-run--11-november-2026)

**Related:** [Git guide](18_GIT_GUIDE.md) · [Start/stop/test the server VM](11_START_STOP_WINDOWS_BUILD_VM.md) · [Install over SSH](03_INSTALL_AND_TEST_WINDOWS.md#part-s--install-or-update-over-ssh--from-the-mac) · [Fix installer errors](08_FIX_WINDOWS_INSTALLER.md)

`private/scripts/desktop.sh` runs the whole build chain for one machine, or all of them, from a Mac Terminal. Every step stops with a plain reason when something isn't right. The manual guides still work; this script runs the same commands in order.

## Go to the scripts folder

**Mac Terminal:**

```sh
cd /Users/jagdeep/Projects/AndroidStudio/AutoPilot/private/scripts
```

From there, run the commands as `./desktop.sh …`, for example:

```sh
./desktop.sh legion
```

The script finds the project itself, so it works from any folder. From the project root, `/Users/jagdeep/Projects/AndroidStudio/AutoPilot`, use `private/scripts/desktop.sh …`, as in the table below.

## Quick reference

From the project root (or replace `private/scripts/desktop.sh` with `./desktop.sh` inside the scripts folder):

| Command | What happens |
| --- | --- |
| `private/scripts/desktop.sh mac` | Build the macOS app with tests, install it in `/Applications`, then start a debug run |
| `private/scripts/desktop.sh mac install` | Build and install only |
| `private/scripts/desktop.sh mac debug` | Debug run from source, waiting on port 5005 |
| `private/scripts/desktop.sh legion` | Sync Legion, build the MSI with tests, install and check it, then start a debug run and tunnel |
| `private/scripts/desktop.sh legion install` | Sync, build, install and check only |
| `private/scripts/desktop.sh legion debug` | Sync, then debug run and tunnel only |
| `private/scripts/desktop.sh server` | Sync the home server, build the Linux DEB with tests, copy it to the Mac |
| `private/scripts/desktop.sh vm` | Sync the server's Windows VM, build the MSI with tests, copy it to the Mac |
| `private/scripts/desktop.sh all` | Mac build and install, Legion build and install, server DEB build and copy |
| any target + `sync` | Push and pull only |
| any target + `build` | Sync, then build only |
| add `--no-tests` | Skip the focused tests |
| add `--remote` | Reach the home server through Cloudflare instead of the local network |

`private/scripts/windows/legion.sh` is a shortcut for `private/scripts/desktop.sh legion`.

## What each machine does

| Machine | Target | Builds | Installs | Debug |
| --- | --- | --- | --- | --- |
| This Mac | `mac` | macOS app (`createReleaseDistributable`) | Copies it to `/Applications/AutoPilot.app` | `gradlew :composeApp:run --debug-jvm`; attach **AutoPilot Mac (local)** |
| Legion laptop (`home-legion`) | `legion` | MSI (`Build-Distribution.ps1`) | Silent update with `Install-Msi.ps1`, then checks | `AutoPilot-Debug` task on the desktop, SSH tunnel; attach **AutoPilot Windows (SSH tunnel)** |
| Home server, Debian (`home-server`) | `server` | DEB (`packageReleaseDeb`, at low priority) | No: it's a headless website host. The DEB is copied to `~/Downloads/Builds/AutoPilot` | No |
| Home server, Windows VM (`home-server-build`) | `vm` | MSI (`Build-Distribution.ps1`) | No. The MSI is copied to `~/Downloads/Builds/AutoPilot`; install it on Legion | No |

Install a copied DEB on a Linux desktop with `sudo apt install ./autopilot_<version>_amd64.deb`, not on the server.

## How it works

### 1. Git is the only way code reaches a build machine

The rule for every remote machine is **push, pull, then build**. Nothing is copied over directly, and the script never builds from a side folder.

1. **Mac check.** The Mac must have no uncommitted changes, be on `main`, and not be behind GitHub. If any of these fails, the script stops and tells you what to do: commit, or pull on the Mac.
2. **Push.** Any unpushed Mac commits are pushed to GitHub.
3. **Pull on the target.** The target's checkout must be clean. The script runs `git pull --ff-only`, then checks that the target is on exactly the Mac's commit.
4. **Uncommitted changes are never overwritten.** If the target has local changes, the script stops. Compare them with GitHub and stash them as a backup (`git stash push -u -m "backup …"`), then run the script again.

The Mac target builds from your working folder and doesn't need a push. If you have uncommitted changes, it says so, because the version number only rises with a commit.

### 2. One version number per commit

Every desktop build uses `<major>.<minor>.<commit count>`, for example `1.0.30`. Major and minor come from `versionName` in `gradle/libs.versions.toml`; the third part is `git rev-list --count HEAD`. The same number goes on:

- the MSI, DMG and DEB (`packageVersion` in `composeApp/build.gradle.kts`);
- the app's About screen and status bar (passed to the app as `-Dautopilot.version`).

Each commit's MSI is therefore newer than the last one, so Windows updates the installed app in place. Building the same commit twice gives the same version: `Install-Msi.ps1 -SkipIfInstalled` then reports *already installed* and moves on, instead of failing. Android and iOS keep `versionName` from the version catalog.

### 3. Talking to Windows over SSH

Windows commands go through `ssh` → the remote default shell (Command Prompt on Legion, PowerShell in the VM) → PowerShell. Quotes get mangled along that path. So the script sends each PowerShell block **base64-encoded** with `powershell -EncodedCommand`. Nothing in it is reinterpreted on the way, and both machines' default shells handle it the same way.

### 4. Build

- **Mac:** `./gradlew` with the focused tests, then `:composeApp:createReleaseDistributable`.
- **Legion and the VM:** `private/scripts/windows/Build-Distribution.ps1`. It runs the focused tests and then `:composeApp:packageReleaseMsi`, with two workers and a 2 GB heap. Packaging also patches the MSI so it installs and runs under Smart App Control: it removes jpackage's unsigned helper DLL and points the shortcuts at the signed `javaw.exe` (see [08](08_FIX_WINDOWS_INSTALLER.md)). The build output shows `Removed JpCaDll …` and `Pointed 2 shortcuts …`.
- **Server:** the same Gradle settings, run with `nice` and `ionice`, so the websites keep priority.

The focused tests are `core:automation`, the DGS study tests, the desktop data-path tests and the version test. They're quick checks, not the full suite.

### 5. Install

- **Mac:** stops if AutoPilot is running, since quitting it could lose a routine's progress. Otherwise it replaces `/Applications/AutoPilot.app` with the new build and checks its signature. `~/.autopilot` isn't touched.
- **Legion:** `private/scripts/windows/Install-Msi.ps1 -LaunchCheck -SkipIfInstalled`. It refuses an older MSI and closes a running copy. Then it installs silently and checks four things: the installed version, the signed `javaw.exe`, that both shortcuts point to it, and a 15-second launch from the desktop shortcut. It finishes by counting Smart App Control blocks. `%USERPROFILE%\.autopilot` isn't touched.
- **Server and VM:** the newest installer is copied to `~/Downloads/Builds/AutoPilot`, and the SHA256 of the copy must match the source.

### 6. Debug

Both debug runs build from source at the synced commit, so breakpoints match the code on the Mac.

- **Mac:** stops if AutoPilot is already running or port 5005 is taken. It then runs `./gradlew :composeApp:run --debug-jvm`, which waits for the debugger. In Android Studio choose **AutoPilot Mac (local)** → Debug. Closing the app window ends the run.
- **Legion:** checks that the `AutoPilot-Debug` task exists and that the installed app isn't open, since both would share `.autopilot`. It starts the task on the signed-in desktop and waits up to 15 minutes until the debug run listens on Legion's port 5005. It then opens `ssh -N -L 127.0.0.1:5005:127.0.0.1:5005 home-legion` on the Mac. In Android Studio choose **AutoPilot Windows (SSH tunnel)** → Debug; the window appears on Legion. Control-C in the Terminal closes the tunnel. Port 5005 is never opened to the network.

## Server Windows VM — start and stop

`desktop.sh vm` needs the server's Windows VM running. Start, stop and test it in one line with `private/scripts/server-vm.sh start --headless`, `stop`, `status` or `test`. The full guide, with the manual commands, is [11 — Start, stop and test the server VM](11_START_STOP_WINDOWS_BUILD_VM.md#one-line-with-the-script).

## Where the scripts live

All build, install and debug scripts are in `private/scripts/` (moved from `tools/` on 11 November 2026): `private/scripts/desktop.sh` for the Mac, and `private/scripts/windows/` for Windows. They're tracked in Git, because Gradle runs the two MSI patch scripts during packaging and the build machines need them. The `AutoPilot-Debug` task on Legion stores its launcher's path. `desktop.sh legion debug` re-registers it automatically when that path is out of date.

## When it stops

| Message | What to do |
| --- | --- |
| *the Mac has uncommitted changes* | Commit (or ask Claude to push), then rerun |
| *GitHub has N commit(s) the Mac doesn't* | `git pull --ff-only` on the Mac, then rerun |
| *Legion / the server has uncommitted changes* | Compare with GitHub, stash as a backup, rerun |
| *AutoPilot is running on the Mac* | Quit it normally, rerun |
| *The installed AutoPilot is open on Legion* | Close it normally on Legion, rerun |
| *Version … is not newer than the installed …* | Commit a change and rerun, or uninstall first ([03, S6](03_INSTALL_AND_TEST_WINDOWS.md)) |
| *the Windows VM isn't running* | `private/scripts/server-vm.sh start --headless`, then rerun |
| *The AutoPilot-Debug task is not registered* | Run `private\scripts\windows\Register-DebugLauncher.ps1` once on the Legion desktop ([22](22_SET_UP_WINDOWS_DEBUG_LAUNCHER.md)) |
| *port 5005 is already in use* | Close the earlier tunnel or debug run (`lsof -nP -iTCP:5005`) |
| A build or test failure | Read the Gradle output above the message; nothing was installed |

## Files

| File | Runs on | Role |
| --- | --- | --- |
| `private/scripts/desktop.sh` | Mac | The single command: sync, build, install, copy, debug |
| `private/scripts/windows/legion.sh` | Mac | Shortcut for `desktop.sh legion` |
| `private/scripts/server-vm.sh` | Mac | Start, stop, check and test the server's Windows VM |
| `private/scripts/windows/Build-Distribution.ps1` | Legion, VM | Focused tests and MSI build |
| `private/scripts/windows/Install-Msi.ps1` | Legion | Silent install or update, plus checks |
| `private/scripts/windows/Remove-UnsignedMsiHelper.ps1`, `Use-SignedJavaLauncher.ps1` | Legion, VM (during packaging) | Smart App Control fixes inside the MSI |
| `private/scripts/windows/Start-Debug.ps1` | Legion (via the `AutoPilot-Debug` task) | Debug run from source |
| `.run/AutoPilot-Mac-local.run.xml`, `.run/AutoPilot-Windows-SSH.run.xml` | Android Studio | Attach to `127.0.0.1:5005` |

## Recorded run — 11 November 2026

`private/scripts/desktop.sh all` at commit `1a13630` (version 1.0.30):

| Step | Result |
| --- | --- |
| Mac check and push | 1 commit pushed; Mac and GitHub on `1a13630` |
| Mac build | Focused tests and `createReleaseDistributable`: BUILD SUCCESSFUL |
| Mac install | `/Applications/AutoPilot.app` replaced; signature verified; version 1.0.30 |
| Legion sync | Pulled to `1a13630` |
| Legion build | BUILD SUCCESSFUL in 57 s; `JpCaDll` removed and both shortcuts pointed at the signed `javaw.exe` |
| Legion install | Updated 1.0.29 → 1.0.30 in place; running 15 s after opening the shortcut; 0 Smart App Control blocks |
| Server sync | Pulled to `1a13630` |
| Server build | Focused tests and `packageReleaseDeb`: BUILD SUCCESSFUL in 1 min 13 s |
| Server copy | `~/Downloads/Builds/AutoPilot/autopilot_1.0.30_amd64.deb`, SHA256 `37a14674…1133635`, matching the server |

Also tested on 11 November 2026, at commit `ac90a44` (version 1.0.31):

| Command | Result |
| --- | --- |
| `private/scripts/desktop.sh vm` | VM running check passed. Synced the VM to `ac90a44`. Focused tests and MSI: BUILD SUCCESSFUL in 6 min 2 s, with both Smart App Control fixes applied. Copied `~/Downloads/Builds/AutoPilot/AutoPilot-1.0.31.msi`; its SHA256 `e3ee33bd…817c22fd52` matches the VM. |
| `private/scripts/desktop.sh legion debug` | Synced Legion. Started `AutoPilot-Debug`; the debug run listened on port 5005. `jdb -attach 127.0.0.1:5005` through the tunnel attached to Legion's JVM 21.0.13, resumed the app and detached. |

The first debug test found a problem: the Mac's SSH config shares connections (`ControlMaster auto`, kept for 30 minutes). Through a shared connection, `ssh -N` opened a Windows prompt, and the 5005 forward stayed attached to the shared connection after the script ended. The tunnel now uses its own connection (`ssh -S none -N …`), so Control-C really closes it. If 5005 is ever stuck on the Mac, `ssh -O cancel -L 127.0.0.1:5005:127.0.0.1:5005 home-legion` releases it.

Ending a Legion debug session: closing the app window on Legion ends the run. `Stop-ScheduledTask AutoPilot-Debug` alone stops only the launcher's PowerShell; the app's JVM keeps running until its window is closed.

