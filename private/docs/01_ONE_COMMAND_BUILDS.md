# 01 — One Command: Sync, Build, Install and Debug on Every Desktop Machine

[All guides](00_INDEX.md) · [Manual Legion build](02_BUILD_WINDOWS_ON_LEGION.md) · [Manual install over SSH](03_INSTALL_AND_TEST_WINDOWS.md#part-s--install-or-update-over-ssh--from-the-mac) · [Manual server build](10_BUILD_LINUX_ON_SERVER.md)

**On this page:** [Commit and push everything](#commit-and-push-everything) · [Quick reference](#quick-reference) · [Sync machine data](#sync-machine-data) · [What each machine does](#what-each-machine-does) · [How it works](#how-it-works) · [Server VM start/stop](#server-windows-vm--start-and-stop) · [When it stops](#when-it-stops) · [Files](#files) · [Recorded runs](#recorded-run--11-november-2026)

**Related:** [Git guide](19_GIT_GUIDE.md) · [Fetch and verify build artifacts](#fetch-and-verify-build-artifacts) · [Start/stop/test the server VM](12_START_STOP_WINDOWS_BUILD_VM.md) · [Install over SSH](03_INSTALL_AND_TEST_WINDOWS.md#part-s--install-or-update-over-ssh--from-the-mac) · [Fix installer errors](09_FIX_WINDOWS_INSTALLER.md)

`private/scripts/desktop.sh` runs the build chain for one machine, or all of them, from a Mac Terminal. The only supported form puts one action first and the target last. Every step stops with a plain reason when something isn't right.

## Commit and push everything

From any Mac Terminal folder, run the project Git command:

```sh
/Users/jagdeep/Projects/AndroidStudio/AutoPilot/private/scripts/git.sh push-all
```

It stages all non-ignored changes, creates a commit, and pushes `main`. To provide a commit message, add it after the script path:

```sh
/Users/jagdeep/Projects/AndroidStudio/AutoPilot/private/scripts/git.sh push-all "Describe the change"
```

The equivalent one-line Git command is:

```sh
cd /Users/jagdeep/Projects/AndroidStudio/AutoPilot && git add -A && git commit -m "Update AutoPilot" && git push origin main
```

### Project Git script commands

| Command | What it does |
| --- | --- |
| `private/scripts/git.sh status` | Show the current branch and changed files. |
| `private/scripts/git.sh check` | Fetch GitHub and show whether this checkout is ahead or behind. |
| `private/scripts/git.sh fetch` | Download GitHub history without changing project files. |
| `private/scripts/git.sh pull` | Fast-forward clean `main` to the latest GitHub commit. Refuses local changes. |
| `private/scripts/git.sh diff` | Show unstaged and staged changes. |
| `private/scripts/git.sh history` | Show the latest 20 commits. |
| `private/scripts/git.sh branches` | Show local branches and tracking state. |
| `private/scripts/git.sh push-all` | Commit all non-ignored changes and push `main`. |
| `private/scripts/git.sh push-all "Message"` | Same as `push-all`, with a custom commit message. |

The script intentionally has no destructive `reset --hard`, `clean -fd` or force-push command.

Only run this when all current changes belong in the same commit. Files ignored by Git, including `private/plans/`, `private/signing/`, `private/secrets/` and `private/misc/`, are not included. Remote builds require this step before `sync`, `build` or `run` on another machine.

## Go to the scripts folder

**Mac Terminal:**

```sh
cd /Users/jagdeep/Projects/AndroidStudio/AutoPilot/private/scripts
```

From there, run the commands as `./desktop.sh …`, for example:

```sh
./desktop.sh build legion
```

The script finds the project itself, so it works from any folder. From the project root, `/Users/jagdeep/Projects/AndroidStudio/AutoPilot`, use `private/scripts/desktop.sh …`, as in the table below.


## Server Windows VM — start and stop

`desktop.sh build server-vm-win` needs the server's Windows VM running. Start, stop and test it in one line with `private/scripts/server-vm.sh start --headless`, `stop`, `status` or `test`. The full guide, with the manual commands, is [12 — Start, stop and test the server VM](12_START_STOP_WINDOWS_BUILD_VM.md#one-line-with-the-script).

## Quick reference

From the project root (or replace `private/scripts/desktop.sh` with `./desktop.sh` inside the scripts folder):

<table style="width:100%; table-layout:fixed;">
<colgroup><col style="width:32%"><col style="width:68%"></colgroup>
<thead><tr><th>Command or topic</th><th>How it works</th></tr></thead>
<tbody>
<tr><td><strong>Standard syntax</strong></td><td>Write the action first and the machine target last:<br><code>private/scripts/desktop.sh &lt;action&gt; &lt;target&gt;</code></td></tr>
<tr><td colspan="2"><strong>Sync — push and pull only</strong></td></tr>
<tr><td><code>private/scripts/desktop.sh sync all</code></td><td><strong>Sync only:</strong> pushes committed Mac changes to GitHub, then pulls the same commit onto Legion, the home server, the server Windows VM, and both Mac-local VMs.</td></tr>
<tr><td><code>private/scripts/desktop.sh sync legion</code></td><td>Push and pull Legion only.</td></tr>
<tr><td><code>private/scripts/desktop.sh sync server</code></td><td>Push and pull the home server only.</td></tr>
<tr><td><code>private/scripts/desktop.sh sync mac-vm-win</code></td><td>Push and pull the Windows VM running on this Mac.</td></tr>
<tr><td><code>private/scripts/desktop.sh sync mac-vm-linux</code></td><td>Push and pull the Linux VM running on this Mac.</td></tr>
<tr><td><code>private/scripts/desktop.sh sync-data all</code></td><td>Copy each machine's <code>~/.autopilot</code> and <code>~/Downloads/AutoPilot</code> to <code>~/Downloads/AutoPilot/Sync/&lt;machine&gt;</code> on the Mac, with the two folders saved visibly as <code>Database/</code> and <code>AutoPilot/</code>. It does not change Git, build or install. See <a href="#sync-machine-data">Sync machine data</a>.</td></tr>
<tr><td colspan="2"><strong>Build — sync, test and create an installer</strong></td></tr>
<tr><td><code>private/scripts/desktop.sh build all</code></td><td><strong>Build only:</strong> syncs first, then builds every configured desktop artifact: macOS app, Legion MSI, server DEB, server Windows VM MSI, Mac-local Windows MSI and Mac-local Linux DEB. It does not install or copy.</td></tr>
<tr><td><code>private/scripts/desktop.sh build legion</code></td><td>Sync Legion, then build its MSI without installing.</td></tr>
<tr><td><code>private/scripts/desktop.sh build server</code></td><td>Sync and build the home-server Linux DEB. The server is headless, so it is not installed there.</td></tr>
<tr><td><code>private/scripts/desktop.sh build server-vm-win</code></td><td>Sync and build the server Windows VM MSI. Use <code>fetch server-vm-win</code> to copy it to the Mac.</td></tr>
<tr><td><code>private/scripts/desktop.sh build mac-vm-win</code></td><td>Sync and build the Windows VM running on this Mac. Use <code>run mac-vm-win</code> to build/install/check it, or <code>debug mac-vm-win</code> to debug.</td></tr>
<tr><td><code>private/scripts/desktop.sh build mac-vm-linux</code></td><td>Sync and build the Linux VM running on this Mac. Use <code>run mac-vm-linux</code> to build/install it, or <code>debug mac-vm-linux</code> to debug.</td></tr>
<tr><td><code>private/scripts/desktop.sh build local-all</code></td><td>Build macOS, then sync and build the Mac-local Windows and Linux VMs. Use <code>run local-all</code> to build/install/check all supported local builds.</td></tr>
<tr><td colspan="2"><strong>Run — build, install and check</strong></td></tr>
<tr><td><code>private/scripts/desktop.sh run all</code></td><td>Build all configured desktop artifacts, install/check the Mac, Legion and Mac-local VM builds, and copy the server Windows VM MSI. The headless server DEB stays on the server.</td></tr>
<tr><td><code>private/scripts/desktop.sh run legion</code></td><td>Build, install and check the Legion MSI.</td></tr>
<tr><td><code>private/scripts/desktop.sh run mac</code></td><td>Build, install and check the macOS app.</td></tr>
<tr><td><code>private/scripts/desktop.sh run mac-vm-win</code></td><td>Build, install and check the Mac-local Windows VM MSI.</td></tr>
<tr><td><code>private/scripts/desktop.sh run mac-vm-linux</code></td><td>Build, install and check the Mac-local Linux DEB.</td></tr>
<tr><td><code>private/scripts/desktop.sh run local-all</code></td><td>Build and install/check macOS, the local Windows VM and the local Linux VM.</td></tr>
<tr><td colspan="2"><strong>Fetch — copy already-built remote artifacts</strong></td></tr>
<tr><td><code>private/scripts/desktop.sh fetch all</code></td><td>Copies existing Legion MSIs and server DEBs to <code>~/Downloads/Builds/AutoPilot</code>, checking each SHA256. It does not sync, build or install.</td></tr>
<tr><td><code>private/scripts/desktop.sh fetch legion</code></td><td>Copies every existing Legion MSI to <code>~/Downloads/Builds/AutoPilot</code>, checking each SHA256. It does not sync, build or install.</td></tr>
<tr><td><code>private/scripts/desktop.sh fetch server-vm-win</code></td><td>Builds the server Windows VM MSI, then copies it to <code>~/Downloads/Builds/AutoPilot</code>.</td></tr>
<tr><td colspan="2"><strong>Debug — sync and start one debug session</strong></td></tr>
<tr><td><code>private/scripts/desktop.sh debug mac</code></td><td>Sync, then start the Mac debug run on port 5005.</td></tr>
<tr><td><code>private/scripts/desktop.sh debug legion</code></td><td>Sync, start the Legion debug run, and tunnel port 5005.</td></tr>
<tr><td><code>private/scripts/desktop.sh debug mac-vm-win</code></td><td>Sync, start the Mac-local Windows VM debug run, and tunnel port 5005.</td></tr>
<tr><td><code>private/scripts/desktop.sh debug mac-vm-linux</code></td><td>Sync, start the Mac-local Linux VM debug run, and tunnel port 5005.</td></tr>
<tr><td><strong>Targets</strong></td><td><code>mac</code>, <code>legion</code>, <code>server</code>, <code>server-vm-win</code>, <code>mac-vm-win</code>, <code>mac-vm-linux</code>, <code>local-all</code> and <code>all</code>.</td></tr>
<tr><td><strong>Mac-local VM setup</strong></td><td>Use your SSH aliases and checkout paths, for example:<br><code>MAC_WINDOWS_HOST=mac-windows-vm MAC_WINDOWS_DIR='C:\Users\Jagdeep\Projects\AutoPilot'</code><br><code>MAC_LINUX_HOST=mac-linux-vm MAC_LINUX_DIR='/home/jagdeep/Projects/AutoPilot'</code></td></tr>
<tr><td><strong>Remote build rule</strong></td><td>Your Mac work must be committed. The script pushes unpushed commits automatically, then makes the remote checkout match that commit.</td></tr>
<tr><td><code>--no-tests</code></td><td>Skip the focused tests.</td></tr>
<tr><td><code>--remote</code></td><td>Reach the home server through Cloudflare instead of the local network. For artifact fetching: <code>private/scripts/desktop.sh --remote fetch all</code>.</td></tr>
</tbody>
</table>

## Fetch and verify build artifacts

All artifact copying belongs to this section. The commands copy installers to
`~/Downloads/Builds/AutoPilot` and verify each copied file against the SHA256 hash of the source.

### Fetch all existing remote artifacts

From the project root on the Mac:

```sh
private/scripts/desktop.sh fetch all
```

This copies every existing MSI from Legion and every DEB from the home server. It does not push,
pull, sync, build, install or delete anything. Build first when needed:

```sh
private/scripts/desktop.sh build legion
private/scripts/desktop.sh build server
```

For a Cloudflare connection to the home server, use:

```sh
private/scripts/desktop.sh --remote fetch all
```

Legion remains on its normal local SSH connection. If a source release folder is empty, the
command stops and identifies the machine that must build first.

### Fetch one target

Use the target-specific command when you only want one machine's artifact:

| Command | What it does |
|---|---|
| `private/scripts/desktop.sh fetch legion` | Copies all existing Legion MSIs to `~/Downloads/Builds/AutoPilot` and verifies every SHA256. It does not build or install. |
| `private/scripts/desktop.sh fetch server` | Syncs and builds the server DEB, then copies the newest DEB to `~/Downloads/Builds/AutoPilot` and verifies its SHA256. |
| `private/scripts/desktop.sh fetch server-vm-win` | Checks that the server Windows VM is running, syncs and builds its MSI, then copies it to `~/Downloads/Builds/AutoPilot` and verifies its SHA256. |
| `private/scripts/desktop.sh fetch mac` | Not available: the Mac app is built and installed locally; it is not copied to Downloads. Use `run mac` if you want to install it. |
| `private/scripts/desktop.sh fetch mac-vm-win` | Not available: the Mac Windows VM is a local build/install target. Use `run mac-vm-win` if you want to install it in the VM. |
| `private/scripts/desktop.sh fetch mac-vm-linux` | Not available: the Mac Linux VM is a local build/install target. Use `run mac-vm-linux` if you want to install it in the VM. |
| `private/scripts/desktop.sh fetch local-all` | Not available: local targets are installed where they are built. Use `run local-all` for local installation. |
| `private/scripts/desktop.sh fetch all` | Copies existing Legion MSIs and server DEBs. It does not build, sync, install, or fetch local-VM artifacts. |

For Legion, the command uses the existing release folder. If it is empty, build the MSI first:

```sh
private/scripts/desktop.sh build legion
private/scripts/desktop.sh fetch legion
```

Build the server Windows VM MSI and copy it to the Mac:

```sh
private/scripts/desktop.sh fetch server-vm-win
```

This requires the server Windows VM to be running. Start it first when necessary:

```sh
private/scripts/server-vm.sh start --headless
private/scripts/desktop.sh fetch server-vm-win
private/scripts/server-vm.sh stop
```

The copied files are suitable for testing only. Install an MSI on Legion with [03 — Install and test](03_INSTALL_AND_TEST_WINDOWS.md), or install a DEB on a Linux desktop with:

```sh
sudo apt install ./autopilot_<version>_amd64.deb
```

Do not install the DEB on the production home server.

### Manual fallback

Use these only when the one-command fetch is unavailable. The script is preferred because it
checks every transfer:

```sh
mkdir -p "$HOME/Downloads/Builds/AutoPilot"
scp 'home-legion:C:/Users/jagde/Projects/autopilot-cmp/desktopApp/build/compose/binaries/main-release/msi/*.msi' "$HOME/Downloads/Builds/AutoPilot/"
scp 'home-server:/srv/build/autopilot-cmp/desktopApp/build/compose/binaries/main-release/deb/*.deb' "$HOME/Downloads/Builds/AutoPilot/"
```

For the server Windows VM MSI, use the local or Cloudflare SSH host:

```sh
scp 'home-server-build:C:/Build/autopilot-cmp/desktopApp/build/compose/binaries/main-release/msi/*.msi' "$HOME/Downloads/Builds/AutoPilot/"
# Or: home-server-build-remote
```

Check a manually copied file with:

```sh
shasum -a 256 "$HOME/Downloads/Builds/AutoPilot/"*.msi
shasum -a 256 "$HOME/Downloads/Builds/AutoPilot/"*.deb
```

Use `private/scripts/desktop.sh build legion` for Legion builds.

## Sync machine data

This is a data-only operation. It copies each machine's local AutoPilot data to the Mac; it does
not check Git, push or pull source code, build, install or debug. Each remote machine has a short
SSH timeout. If a machine is offline or a folder cannot be reached, it is reported as skipped and
the next machine continues.

### What it copies

| Source on the machine | Saved under the Mac collection folder | Contents |
|---|---|---|
| `~/.autopilot` | `<machine>/Database` | AutoPilot settings, progress database and saved routine state. The hidden source folder is saved visibly as `Database`. |
| `~/Downloads/AutoPilot` | `<machine>/AutoPilot` under `~/Downloads/AutoPilot/Sync` | Downloaded course certificates and other AutoPilot downloads. |

### Sync every machine

Run this from the project root or any Mac Terminal folder:

```sh
private/scripts/desktop.sh sync-data all
```

The files are collected in:

```text
~/Downloads/AutoPilot/Sync/
```

The command creates or updates these machine folders:

```text
mac/
legion/
server/
server-vm-win/
mac-vm-win/
mac-vm-linux/
```

For the home server through Cloudflare, use:

```sh
private/scripts/desktop.sh --remote sync-data all
```

The `--remote` option changes only the home-server and server-VM connection to their Cloudflare
SSH aliases. Legion and the Mac-local VMs continue to use their normal SSH aliases.

### Sync one machine

```sh
private/scripts/desktop.sh sync-data mac
private/scripts/desktop.sh sync-data legion
private/scripts/desktop.sh sync-data server
private/scripts/desktop.sh sync-data server-vm-win
private/scripts/desktop.sh sync-data mac-vm-win
private/scripts/desktop.sh sync-data mac-vm-linux
private/scripts/desktop.sh sync-data local-all
```

### Folder layout

After a collection, the layout is:

```text
~/Downloads/AutoPilot/
├── mac/
│   ├── Database/
│   └── AutoPilot/
├── legion/
│   ├── Database/
│   └── AutoPilot/
├── server/
│   ├── Database/
│   └── AutoPilot/
├── server-vm-win/
│   ├── Database/
│   └── AutoPilot/
├── mac-vm-win/
│   ├── Database/
│   └── AutoPilot/
└── mac-vm-linux/
    ├── Database/
    └── AutoPilot/
```

Missing source folders are reported as skipped. This is expected on a headless server or a machine
that has not yet run AutoPilot or downloaded a certificate.

### Machine paths and overrides

| Machine | SSH host | AutoPilot data home |
|---|---|---|
| Mac | local | `/Users/jagdeep` |
| Legion | `home-legion` | `C:/Users/jagde` |
| Home server | `home-server` | `/home/jagdeep` |
| Server Windows VM | `home-server-build` | `C:/Users/builduser` |
| Mac Windows VM | `mac-windows-vm` | `C:/Users/Jagdeep` |
| Mac Linux VM | `mac-linux-vm` | `/home/jagdeep` |

If a local VM uses another account, override its data home for that command:

```sh
MAC_WINDOWS_HOME='C:\Users\OtherUser' private/scripts/desktop.sh sync-data mac-vm-win
MAC_LINUX_HOME='/home/otheruser' private/scripts/desktop.sh sync-data mac-vm-linux
```

### Safety

- Existing files in `~/Downloads/AutoPilot/Sync/<machine>` are updated; the script never deletes old files.
- A missing source folder is skipped instead of stopping the whole collection.
- The command uses the existing SSH configuration and never modifies a checkout.
- Certificate files may contain personal course information. Keep the collection folder private and do
  not commit it to GitHub.

## What each machine does

<table width="100%" style="width:100%; min-width:100%; table-layout:fixed;">
<colgroup>
<col width="16%" style="width:16%">
<col width="9%" style="width:9%">
<col width="21%" style="width:21%">
<col width="27%" style="width:27%">
<col width="27%" style="width:27%">
</colgroup>
<thead>
<tr><th>Machine</th><th>Target</th><th>Builds</th><th>Installs</th><th>Debug</th></tr>
</thead>
<tbody>
<tr><td>This Mac</td><td><code>mac</code></td><td>macOS app<br><code>createReleaseDistributable</code></td><td>Copies it to <code>/Applications/AutoPilot.app</code></td><td><code>gradlew :desktopApp:run --debug-jvm</code><br>Attach <strong>AutoPilot Mac (local)</strong></td></tr>
<tr><td>Legion laptop<br><code>home-legion</code></td><td><code>legion</code></td><td>Windows MSI<br><code>Build-Distribution.ps1</code></td><td>Silent update with <code>Install-Msi.ps1</code>, then checks it</td><td><code>AutoPilot-Debug</code> task and SSH tunnel<br>Attach <strong>AutoPilot Windows (SSH tunnel)</strong></td></tr>
<tr><td>Home server, Debian<br><code>home-server</code></td><td><code>server</code></td><td>Linux DEB<br><code>packageReleaseDeb</code><br>Low priority</td><td>No: headless website host.<br><code>desktop.sh fetch server</code> can copy its DEB; <code>desktop.sh run all</code> leaves it remote.</td><td>No</td></tr>
<tr><td>Home server Windows VM<br><code>home-server-build</code></td><td><code>server-vm-win</code></td><td>Windows MSI<br><code>Build-Distribution.ps1</code></td><td>No. Its MSI is copied to <code>~/Downloads/Builds/AutoPilot</code>; install it on Legion.</td><td>No</td></tr>
<tr><td>Windows VM on this Mac</td><td><code>mac-vm-win</code></td><td>Windows MSI<br><code>Build-Distribution.ps1</code></td><td><code>run mac-vm-win</code> runs <code>Install-Msi.ps1 -LaunchCheck -SkipIfInstalled</code> inside the VM.</td><td><code>debug mac-vm-win</code> starts <code>AutoPilot-Debug</code> and tunnels port 5005.</td></tr>
<tr><td>Linux VM on this Mac</td><td><code>mac-vm-linux</code></td><td>Linux DEB<br><code>packageReleaseDeb</code></td><td><code>run mac-vm-linux</code> installs the newest DEB with <code>sudo apt-get</code> inside the VM.</td><td><code>debug mac-vm-linux</code> runs <code>:desktopApp:run --debug-jvm</code> and tunnels port 5005; the VM needs a desktop session.</td></tr>
</tbody>
</table>

Install a copied DEB on a Linux desktop with `sudo apt install ./autopilot_<version>_amd64.deb`, not on the server.

## How it works

### 1. Git is the only way code reaches a build machine

The rule for every build machine, including the VMs on this Mac, is **push, pull, then build**. Nothing is copied over directly, and the script never builds from a side folder.

1. **Mac check.** The Mac must have no uncommitted changes, be on `main`, and not be behind GitHub. If any of these fails, the script stops and tells you what to do: commit, or pull on the Mac.
2. **Push.** Any unpushed Mac commits are pushed to GitHub.
3. **Pull on the target.** The target's checkout must be clean. The script runs `git pull --ff-only`, then checks that the target is on exactly the Mac's commit.
4. **Uncommitted changes are never overwritten.** If the target has local changes, the script stops. Compare them with GitHub and stash them as a backup (`git stash push -u -m "backup …"`), then run the script again.

The Mac target builds from your working folder and doesn't need a push. If you have uncommitted changes, it says so, because the version number only rises with a commit.

### 2. One version number per commit

Every desktop build uses `<major>.<minor>.<commit count>`, for example `1.0.30`. Major and minor come from `versionName` in `gradle/libs.versions.toml`; the third part is `git rev-list --count HEAD`. The same number goes on:

- the MSI, DMG and DEB (`packageVersion` in `desktopApp/build.gradle.kts`; the number itself is computed once in the root `build.gradle.kts`);
- the app's About screen and status bar (passed to the app as `-Dautopilot.version`).

Each commit's MSI is therefore newer than the last one, so Windows updates the installed app in place. Building the same commit twice gives the same version: `Install-Msi.ps1 -SkipIfInstalled` then reports *already installed* and moves on, instead of failing. Android and iOS keep `versionName` from the version catalog.

### 3. Talking to Windows over SSH

Windows commands go through `ssh` → the remote default shell (Command Prompt on Legion, PowerShell in the VM) → PowerShell. Quotes get mangled along that path. So the script sends each PowerShell block **base64-encoded** with `powershell -EncodedCommand`. Nothing in it is reinterpreted on the way, and both machines' default shells handle it the same way.

### 4. Build

- **Mac:** `./gradlew` with the focused tests, then `:desktopApp:createReleaseDistributable`.
- **Legion and the VM:** `private/scripts/windows/Build-Distribution.ps1`. It runs the focused tests and then `:desktopApp:packageReleaseMsi`, with two workers and a 2 GB heap. Packaging also patches the MSI so it installs and runs under Smart App Control: it removes jpackage's unsigned helper DLL and points the shortcuts at the signed `javaw.exe` (see [09](09_FIX_WINDOWS_INSTALLER.md)). The build output shows `Removed JpCaDll …` and `Pointed 2 shortcuts …`.
- **Server:** the same Gradle settings, run with `nice` and `ionice`, so the websites keep priority.

The focused tests are `core:automation`, the DGS study tests, the desktop data-path tests and the version test. They're quick checks, not the full suite.

### 5. Install

- **Mac:** stops if AutoPilot is running, since quitting it could lose a routine's progress. Otherwise it replaces `/Applications/AutoPilot.app` with the new build and checks its signature. `~/.autopilot` isn't touched.
- **Legion:** `private/scripts/windows/Install-Msi.ps1 -LaunchCheck -SkipIfInstalled`. It refuses an older MSI and closes a running copy. Then it installs silently and checks four things: the installed version, the signed `javaw.exe`, that both shortcuts point to it, and a 15-second launch from the desktop shortcut. It finishes by counting Smart App Control blocks. `%USERPROFILE%\.autopilot` isn't touched.
- **Mac Windows VM:** uses the same `Install-Msi.ps1 -LaunchCheck -SkipIfInstalled` path over SSH, with the VM's own `%USERPROFILE%\.autopilot` preserved.
- **Mac Linux VM:** installs the newest DEB found in the VM's release folder with `sudo apt-get install -y`; the VM's package database and user data stay in the VM.
- **Server and VM:** their explicit `fetch` action copies the newest installer to `~/Downloads/Builds/AutoPilot`, and the SHA256 of the copy must match the source. `desktop.sh run all` builds the server DEB but does not copy it. `desktop.sh fetch all` separately copies and checks every existing Legion MSI and server DEB.

### 6. Debug

Both debug runs build from source at the synced commit, so breakpoints match the code on the Mac.

- **Mac:** stops if AutoPilot is already running or port 5005 is taken. It then runs `./gradlew :desktopApp:run --debug-jvm`, which waits for the debugger. In Android Studio choose **AutoPilot Mac (local)** → Debug. Closing the app window ends the run.
- **Legion:** checks that the `AutoPilot-Debug` task exists and that the installed app isn't open, since both would share `.autopilot`. It starts the task on the signed-in desktop and waits up to 15 minutes until the debug run listens on Legion's port 5005. It then opens `ssh -N -L 127.0.0.1:5005:127.0.0.1:5005 home-legion` on the Mac. In Android Studio choose **AutoPilot Windows (SSH tunnel)** → Debug; the window appears on Legion. Control-C in the Terminal closes the tunnel. Port 5005 is never opened to the network.
- **Mac Windows VM:** uses the same `AutoPilot-Debug` scheduled task and opens an SSH tunnel to the VM's port 5005. Register the task once inside the VM with `private\scripts\windows\Register-DebugLauncher.ps1`, then use `private/scripts/desktop.sh debug mac-vm-win`.
- **Mac Linux VM:** runs `:desktopApp:run --debug-jvm` over SSH and forwards port 5005 to the Mac. The Linux VM must have an active desktop session available to the SSH user.

## Where the scripts live

All build, install and debug scripts are in `private/scripts/` (moved from `tools/` on 11 November 2026): `private/scripts/desktop.sh` for the Mac, and `private/scripts/windows/` for Windows. They're tracked in Git, because Gradle runs the two MSI patch scripts during packaging and the build machines need them. The `AutoPilot-Debug` task on Legion stores its launcher's path. `desktop.sh debug legion` re-registers it automatically when that path is out of date.

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
| *The AutoPilot-Debug task is not registered* | Run `private\scripts\windows\Register-DebugLauncher.ps1` once on the Legion desktop ([23](23_SET_UP_WINDOWS_DEBUG_LAUNCHER.md)) |
| *port 5005 is already in use* | Close the earlier tunnel or debug run (`lsof -nP -iTCP:5005`) |
| A build or test failure | Read the Gradle output above the message; nothing was installed |

## Files

| File | Runs on | Role |
| --- | --- | --- |
| `private/scripts/desktop.sh` | Mac | The single command: sync, build, install and debug; `all` leaves the server DEB remote |
| `private/scripts/desktop.sh fetch all` | Mac | Copy and SHA256-check every existing Legion MSI and server DEB |
| `private/scripts/server-vm.sh` | Mac | Start, stop, check and test the server's Windows VM |
| `private/scripts/windows/Build-Distribution.ps1` | Legion, VM | Focused tests and MSI build |
| `private/scripts/windows/Install-Msi.ps1` | Legion | Silent install or update, plus checks |
| `private/scripts/windows/Remove-UnsignedMsiHelper.ps1`, `Use-SignedJavaLauncher.ps1` | Legion, VM (during packaging) | Smart App Control fixes inside the MSI |
| `private/scripts/windows/Start-Debug.ps1` | Legion (via the `AutoPilot-Debug` task) | Debug run from source |
| `.run/AutoPilot-Mac-local.run.xml`, `.run/AutoPilot-Windows-SSH.run.xml` | Android Studio | Attach to `127.0.0.1:5005` |

## Recorded run — 11 November 2026

The earlier full-run invocation at commit `1a13630` (version 1.0.30), now expressed as `private/scripts/desktop.sh run all`:

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
| `private/scripts/desktop.sh build server-vm-win` | VM running check passed. Synced the VM to `ac90a44`. Focused tests and MSI: BUILD SUCCESSFUL in 6 min 2 s, with both Smart App Control fixes applied. Copied `~/Downloads/Builds/AutoPilot/AutoPilot-1.0.31.msi`; its SHA256 `e3ee33bd…817c22fd52` matches the VM. |
| `private/scripts/desktop.sh debug legion` | Synced Legion. Started `AutoPilot-Debug`; the debug run listened on port 5005. `jdb -attach 127.0.0.1:5005` through the tunnel attached to Legion's JVM 21.0.13, resumed the app and detached. |

The first debug test found a problem: the Mac's SSH config shares connections (`ControlMaster auto`, kept for 30 minutes). Through a shared connection, `ssh -N` opened a Windows prompt, and the 5005 forward stayed attached to the shared connection after the script ended. The tunnel now uses its own connection (`ssh -S none -N …`), so Control-C really closes it. If 5005 is ever stuck on the Mac, `ssh -O cancel -L 127.0.0.1:5005:127.0.0.1:5005 home-legion` releases it.

Ending a Legion debug session: closing the app window on Legion ends the run. `Stop-ScheduledTask AutoPilot-Debug` alone stops only the launcher's PowerShell; the app's JVM keeps running until its window is closed.

