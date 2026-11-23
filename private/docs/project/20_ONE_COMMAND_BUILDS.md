# 20 — One Command: Sync, Build, Install and Debug on Every Desktop Machine

[All guides](../00_INDEX.md) · [Manual Legion build](21_BUILD_WINDOWS_ON_LEGION.md) · [Manual install over SSH](../common/06_INSTALL_AND_TEST_WINDOWS.md#part-s--install-or-update-over-ssh--from-the-mac) · [Manual server build](26_BUILD_LINUX_ON_SERVER.md)

**On this page:** [Commit and push everything](#commit-and-push-everything) · [Release a version](#release-a-version) · [Quick reference](#quick-reference) · [Sync machine data](#sync-machine-data) · [Merge answer banks](#merge-answer-banks) · [What each machine does](#what-each-machine-does) · [How it works](#how-it-works) · [Server VM start/stop](#server-windows-vm--start-and-stop) · [When it stops](#when-it-stops) · [Files](#files) · [Recorded runs](#recorded-run--11-november-2026)

**Related:** [Git guide](../common/02_GIT_GUIDE.md) · [Fetch and verify build artifacts](#fetch-and-verify-build-artifacts) · [Start/stop/test the server VM](28_START_STOP_WINDOWS_BUILD_VM.md) · [Install over SSH](../common/06_INSTALL_AND_TEST_WINDOWS.md#part-s--install-or-update-over-ssh--from-the-mac) · [Fix installer errors](../common/08_FIX_WINDOWS_INSTALLER.md)

`./dev` runs the build chain for one machine, or all of them, from a Mac Terminal. The only supported form puts one action first and the target last. Every step stops with a plain reason when something isn't right.

## Quick action reference

| Command | What it does |
| --- | --- |
| `./dev sync <target> [--remote]` | Pushes committed Mac changes to GitHub, then pulls the same commit onto the selected target. |
| `./dev sync-database <target>` | Copies progress databases and settings to `Sync/<machine>/Database`; it does not use Git or build anything. |
| `./dev sync-certificates <target>` | Copies user certificates to `Sync/<machine>/AutoPilot/Profile` and skips certificate files already present at the destination. |
| `./dev build <target> [--no-tests] [--remote]` | Syncs the target, runs focused tests unless skipped, and builds its installer or app. It does not install it. |
| `./dev run <target> [--no-tests] [--remote]` | Builds, installs and checks the target where installation is supported. |
| `./dev fetch <target> [--no-tests] [--remote]` | Copies and verifies existing artifacts for `all` and `legion`; `home-server` and `home-server-vm-win` build first. Output: `~/Downloads/AutoPilot/Builds`. Other fetch targets are unsupported. |
| `./dev debug <target> [--remote]` | Syncs the target, starts its debug run and prepares the port-5005 debug connection. |
| `./dev merge-answer-bank all` | Merges legacy JSON answer-bank exports from synced machine folders; it does not merge SQLite databases. |

`--remote` and `--no-tests` may appear anywhere in the command. The preferred style is flag-last,
for example `./dev build home-server-vm-win --remote`.

## Commit and push everything

From the project root in Mac Terminal, run the project Git command:

```sh
./dev git push-all
```

It stages all non-ignored changes, creates a commit, and pushes `main`. To provide a commit message, add it after the script path:

```sh
./dev git push-all "Describe the change"
```

The equivalent one-line Git command is:

```sh
cd /Users/jagdeep/Projects/AndroidStudio/AutoPilot && git add -A && git commit -m "Update AutoPilot" && git push origin main
```

### Project Git script commands

| Command | What it does |
| --- | --- |
| `./dev git status` | Show the current branch and changed files. |
| `./dev git check` | Fetch GitHub and show whether this checkout is ahead or behind. |
| `./dev git fetch` | Download GitHub history without changing project files. |
| `./dev git pull` | Fast-forward clean `main` to the latest GitHub commit. Refuses local changes. |
| `./dev git diff` | Show unstaged and staged changes. |
| `./dev git history` | Show the latest 20 commits. |
| `./dev git branches` | Show local branches and tracking state. |
| `./dev git push-all` | Commit all non-ignored changes and push `main`. |
| `./dev git push-all "Message"` | Same as `push-all`, with a custom commit message. |

The script intentionally has no destructive `reset --hard`, `clean -fd` or force-push command.

Only run this when all current changes belong in the same commit. Files ignored by Git, including `private/plans/`, `private/signing/`, `private/secrets/` and `private/misc/`, are not included. Remote builds require this step before `sync`, `build` or `run` on another machine.

## Release a version

Use one deliberate command to raise the Semantic Version number, commit it, tag it, and push it:

```sh
./dev version patch    # 1.1.0 -> 1.1.1: bug fixes
./dev version minor    # 1.1.1 -> 1.2.0: new features
./dev version major    # 1.2.4 -> 2.0.0: breaking or major release
```

It requires a clean, up-to-date `main` branch. The command updates `versionName`, creates a `Release <version>` commit and annotated `v<version>` tag, then pushes both. It never guesses a release type from ordinary commits. The Git build number rises on every commit and never resets.

## Run from the project root

**Mac Terminal:**

```sh
cd /Users/jagdeep/Projects/AndroidStudio/AutoPilot
```

Run every user-facing project command through the root script, for example:

```sh
./dev build legion
```

The script finds the project root from its own location. From another folder, use its absolute path:

```sh
/Users/jagdeep/Projects/AndroidStudio/AutoPilot/dev git status
```

From the project root, use `./dev …`, as in the table below.


## Server Windows VM — start and stop

`./dev build home-server-vm-win` needs the server's Windows VM running. Start, stop and test it in one line with `./dev home-server-vm-win start --headless`, `./dev home-server-vm-win stop`, `./dev home-server-vm-win status` or `./dev home-server-vm-win test`. The full guide, with the manual commands, is [28 — Start, stop and test the server VM](28_START_STOP_WINDOWS_BUILD_VM.md#one-line-with-the-script).

## Home server — status and power

These commands control the Debian home server itself. They never start or stop the Windows VM:

```sh
./dev home-server status
./dev home-server start
./dev home-server stop
./dev home-server restart
```

`status` reports the hostname, operating system, kernel, uptime, load, CPU, memory, disk usage,
network addresses, SSH and Docker service state, failed systemd units, Docker containers, listening
ports, Windows VM state and the AutoPilot checkout commit. `stop` and `restart` refuse while the
Windows build VM is running. If the server is powered off, `start` can use Wake-on-LAN when
`SERVER_MAC` is set and `wakeonlan` is installed on the Mac.

Use `--remote` explicitly when the local network route is unavailable:

```sh
./dev home-server status --remote
./dev home-server restart --remote
```

The same explicit flag selects the Cloudflare route for desktop workflows. It applies to the home
server, its Windows VM and Legion through `home-legion-remote`:

```sh
./dev sync legion --remote
./dev build home-server --remote
./dev run home-server-vm-win --remote
```

Without `--remote`, these commands use `home-legion` and `home-server`; the server Windows VM is reached by an explicit SSH jump through `home-server` on port `2222`.
They stop with the connection error if the local route is unavailable.

## Quick reference

From the project root, use `./dev` for every user-facing project command:

<table style="width:100%; table-layout:fixed;">
<colgroup><col style="width:32%"><col style="width:68%"></colgroup>
<thead><tr><th>Command or topic</th><th>How it works</th></tr></thead>
<tbody>
<tr><td><strong>Standard syntax</strong></td><td>Write the action first and the machine target last:<br><code>./dev &lt;action&gt; &lt;target&gt;</code></td></tr>
<tr><td colspan="2"><strong>Sync — push and pull only</strong></td></tr>
<tr><td><code>./dev sync all</code></td><td><strong>Sync only:</strong> pushes committed Mac changes to GitHub, then pulls the same commit onto Legion, the home server, the server Windows VM, and both Mac-local VMs.</td></tr>
<tr><td><code>./dev sync legion</code></td><td>Push and pull Legion only.</td></tr>
<tr><td><code>./dev sync home-server</code></td><td>Push and pull the home server only.</td></tr>
<tr><td><code>./dev sync mac-vm-win</code></td><td>Push and pull the Windows VM running on this Mac.</td></tr>
<tr><td><code>./dev sync mac-vm-linux</code></td><td>Push and pull the Linux VM running on this Mac.</td></tr>
<tr><td><code>./dev sync-database all</code></td><td>Copy each machine's <code>~/.autopilot</code> progress database and settings to <code>~/Downloads/AutoPilot/Sync/&lt;machine&gt;/Database</code>. It does not change Git, build or install.</td></tr>
<tr><td><code>./dev sync-certificates all</code></td><td>Copy all user certificate files to <code>~/Downloads/AutoPilot/Sync/&lt;machine&gt;/AutoPilot/Profile</code>. Existing destination files are skipped, never overwritten.</td></tr>
<tr><td><code>./dev merge-answer-bank all</code></td><td>Merge every synced <code>answer-banks.sagarmeinyog-ver-1.0.json</code> under <code>Sync/&lt;machine&gt;/Database</code> into <code>~/Downloads/AutoPilot/Misc</code>. It does not change Git, build or install. Run <code>sync-database all</code> first.</td></tr>
<tr><td colspan="2"><strong>Build — sync, test and create an installer</strong></td></tr>
<tr><td><code>./dev build all</code></td><td><strong>Build only:</strong> syncs first, then builds every configured desktop artifact: macOS app, Legion MSI, server DEB, server Windows VM MSI, Mac-local Windows MSI and Mac-local Linux DEB. It does not install or copy.</td></tr>
<tr><td><code>./dev build legion</code></td><td>Sync Legion, then build its MSI without installing.</td></tr>
<tr><td><code>./dev build home-server</code></td><td>Sync and build the home-server Linux DEB. The server is headless, so it is not installed there.</td></tr>
<tr><td><code>./dev build home-server-vm-win</code></td><td>Sync and build the server Windows VM MSI. Use <code>fetch home-server-vm-win</code> to copy it to the Mac.</td></tr>
<tr><td><code>./dev build mac-vm-win</code></td><td>Sync and build the Windows VM running on this Mac. Use <code>run mac-vm-win</code> to build/install/check it, or <code>debug mac-vm-win</code> to debug.</td></tr>
<tr><td><code>./dev build mac-vm-linux</code></td><td>Sync and build the Linux VM running on this Mac. Use <code>run mac-vm-linux</code> to build/install it, or <code>debug mac-vm-linux</code> to debug.</td></tr>
<tr><td><code>./dev build local-all</code></td><td>Build macOS, then sync and build the Mac-local Windows and Linux VMs. Use <code>run local-all</code> to build/install/check all supported local builds.</td></tr>
<tr><td colspan="2"><strong>Run — build, install and check</strong></td></tr>
<tr><td><code>./dev run all</code></td><td>Build all configured desktop artifacts, install/check the Mac, Legion and Mac-local VM builds, and copy the server Windows VM MSI. The headless server DEB stays on the server.</td></tr>
<tr><td><code>./dev run legion</code></td><td>Build, install and check the Legion MSI.</td></tr>
<tr><td><code>./dev run mac</code></td><td>Build, install and check the macOS app.</td></tr>
<tr><td><code>./dev run mac-vm-win</code></td><td>Build, install and check the Mac-local Windows VM MSI.</td></tr>
<tr><td><code>./dev run mac-vm-linux</code></td><td>Build, install and check the Mac-local Linux DEB.</td></tr>
<tr><td><code>./dev run local-all</code></td><td>Build and install/check macOS, the local Windows VM and the local Linux VM.</td></tr>
<tr><td colspan="2"><strong>Fetch — copy already-built remote artifacts</strong></td></tr>
<tr><td><code>./dev fetch all</code></td><td>Copies existing Legion MSIs and server DEBs to <code>~/Downloads/AutoPilot/Builds</code>, checking each SHA256. It does not sync, build or install.</td></tr>
<tr><td><code>./dev fetch legion</code></td><td>Copies every existing Legion MSI to <code>~/Downloads/AutoPilot/Builds</code>, checking each SHA256. It does not sync, build or install.</td></tr>
<tr><td><code>./dev fetch home-server-vm-win</code></td><td>Builds the server Windows VM MSI, then copies it to <code>~/Downloads/AutoPilot/Builds</code>.</td></tr>
<tr><td colspan="2"><strong>Debug — sync and start one debug session</strong></td></tr>
<tr><td><code>./dev debug mac</code></td><td>Sync, then start the Mac debug run on port 5005.</td></tr>
<tr><td><code>./dev debug legion</code></td><td>Sync, start the Legion debug run, and tunnel port 5005.</td></tr>
<tr><td><code>./dev debug mac-vm-win</code></td><td>Sync, start the Mac-local Windows VM debug run, and tunnel port 5005.</td></tr>
<tr><td><code>./dev debug mac-vm-linux</code></td><td>Sync, start the Mac-local Linux VM debug run, and tunnel port 5005.</td></tr>
<tr><td><strong>Targets</strong></td><td><code>mac</code>, <code>legion</code>, <code>home-server</code>, <code>home-server-vm-win</code>, <code>mac-vm-win</code>, <code>mac-vm-linux</code>, <code>local-all</code> and <code>all</code>.</td></tr>
<tr><td><strong>Mac-local VM setup</strong></td><td>Use your SSH aliases and checkout paths, for example:<br><code>MAC_WINDOWS_HOST=mac-vm-windows MAC_WINDOWS_DIR='C:\Users\Jagdeep\Projects\AutoPilot'</code><br><code>MAC_LINUX_HOST=mac-vm-debian MAC_LINUX_DIR='/home/jagdeep/Projects/AutoPilot'</code></td></tr>
<tr><td><strong>Remote build rule</strong></td><td>Your Mac work must be committed. The script pushes unpushed commits automatically, then makes the remote checkout match that commit.</td></tr>
<tr><td><code>--no-tests</code></td><td>Skip the focused tests.</td></tr>
<tr><td><code>--remote</code></td><td>Explicitly use Cloudflare for the home server and Legion via <code>home-server-remote</code> and <code>home-legion-remote</code>. The server VM uses an explicit jump through that server on port <code>2222</code>; no VM SSH alias is required. Without it, local-network routes are used and a connection failure is reported. For artifact fetching: <code>./dev fetch all --remote</code>.</td></tr>
</tbody>
</table>

## Fetch and verify build artifacts

All artifact copying belongs to this section. The commands copy installers to
`~/Downloads/AutoPilot/Builds` and verify each copied file against the SHA256 hash of the source.
The copied filename is normalized to include the release version and build architecture. Linux
DEBs use `autopilot_<version>_<architecture>.deb` (for example
`autopilot_1.0.48_amd64.deb`); Windows MSIs use `AutoPilot_<version>_<architecture>.msi` (for
example `AutoPilot_1.0.48_amd64.msi`). The architecture names are `amd64` for x64/Intel-AMD,
`arm64` for ARM, and `x86` for 32-bit Intel. The source filename is not trusted or retained, and
the SHA256 check is performed after copying under the normalized name.

### Fetch all existing remote artifacts

From the project root on the Mac:

```sh
./dev fetch all
```

This copies every existing MSI from Legion and every DEB from the home server. It does not push,
pull, sync, build, install or delete anything. Build first when needed:

```sh
./dev build legion
./dev build home-server
```

For a Cloudflare connection to the home server, use:

```sh
./dev fetch all --remote
```

Legion remains on its normal local SSH connection. If a source release folder is empty, the
command stops and identifies the machine that must build first.

### Fetch one target

Use the target-specific command when you only want one machine's artifact:

| Command | What it does |
|---|---|
| `./dev fetch legion` | Copies all existing Legion MSIs as `AutoPilot_<version>_<architecture>.msi` to `~/Downloads/AutoPilot/Builds` and verifies every SHA256. It does not build or install. |
| `./dev fetch home-server` | Syncs and builds the server DEB, then copies the newest DEB with its version and architecture in the filename to `~/Downloads/AutoPilot/Builds` and verifies its SHA256. |
| `./dev fetch home-server-vm-win` | Checks that the server Windows VM is running, syncs and builds its MSI, then copies it as `AutoPilot_<version>_<architecture>.msi` to `~/Downloads/AutoPilot/Builds` and verifies its SHA256. |
| `./dev fetch mac` | Not available: the Mac app is built and installed locally; it is not copied to Downloads. Use `run mac` if you want to install it. |
| `./dev fetch mac-vm-win` | Not available: the Mac Windows VM is a local build/install target. Use `run mac-vm-win` if you want to install it in the VM. |
| `./dev fetch mac-vm-linux` | Not available: the Mac Linux VM is a local build/install target. Use `run mac-vm-linux` if you want to install it in the VM. |
| `./dev fetch local-all` | Not available: local targets are installed where they are built. Use `run local-all` for local installation. |
| `./dev fetch all` | Copies existing Legion MSIs and server DEBs with normalized version/architecture names. It does not build, sync, install, or fetch local-VM artifacts. |

For Legion, the command uses the existing release folder. If it is empty, build the MSI first:

```sh
./dev build legion
./dev fetch legion
```

Build the server Windows VM MSI and copy it to the Mac:

```sh
./dev fetch home-server-vm-win
```

This requires the server Windows VM to be running. Start it first when necessary:

```sh
./dev home-server-vm-win start --headless
./dev fetch home-server-vm-win
./dev home-server-vm-win stop
```

The copied files are suitable for testing only. Install an MSI on Legion with [06 — Install and test](../common/06_INSTALL_AND_TEST_WINDOWS.md), or install a DEB on a Linux desktop with the normalized filename:

```sh
sudo apt install ./autopilot_<version>_<architecture>.deb
```

Do not install the DEB on the production home server.

### Manual fallback

Use these only when the one-command fetch is unavailable. The script is preferred because it
checks every transfer:

```sh
mkdir -p "$HOME/Downloads/AutoPilot/Builds"
scp 'home-legion:C:/Users/jagde/Projects/autopilot-cmp/desktopApp/build/compose/binaries/main-release/msi/*.msi' "$HOME/Downloads/AutoPilot/Builds/"
scp 'home-server:/srv/build/autopilot-cmp/desktopApp/build/compose/binaries/main-release/deb/*.deb' "$HOME/Downloads/AutoPilot/Builds/"
```

For the server Windows VM MSI, use the local or Cloudflare SSH host:

```sh
scp -o ProxyJump=home-server -P 2222 'builduser@127.0.0.1:C:/Build/autopilot-cmp/desktopApp/build/compose/binaries/main-release/msi/*.msi' "$HOME/Downloads/AutoPilot/Builds/"
# Or use -o ProxyJump=home-server-remote
```

Check a manually copied file with:

```sh
shasum -a 256 "$HOME/Downloads/AutoPilot/Builds/"*.msi
shasum -a 256 "$HOME/Downloads/AutoPilot/Builds/"*.deb
```

Use `./dev build legion` for Legion builds.

## Sync machine data

This is a data-only operation. It copies each machine's local AutoPilot data to the Mac; it does
not check Git, push or pull source code, build, install or debug. Each remote machine has a short
SSH timeout. If a machine is offline or a folder cannot be reached, it is reported as skipped and
the next machine continues.

### What it copies

| Source on the machine | Saved under the Mac collection folder | Contents |
|---|---|---|
| `~/.autopilot` | `<machine>/Database` | AutoPilot settings, progress database and saved routine state. The hidden source folder is saved visibly as `Database`. |
| `~/Downloads/AutoPilot` | `<machine>/AutoPilot` under `~/Downloads/AutoPilot/Sync` | Certificate profiles and legacy profile folders. The copy excludes `Builds`, `Sync` and `Misc` so installers, scratch exports and prior sync results do not get copied again. |

AutoPilot now saves certificates and result records under `~/Downloads/AutoPilot/Profile/<profile>/`. Existing certificates in the older `~/Downloads/AutoPilot/<profile>/` location remain valid; new certificates go into `Profile/`.

### Sync every machine

Run this from the project root or any Mac Terminal folder:

```sh
./dev sync-database all
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
home-server-vm-win/
mac-vm-win/
mac-vm-linux/
```

To copy databases through Cloudflare, use:

```sh
./dev sync-database all --remote
```

The `--remote` option changes only the home-server and server-VM connection to their Cloudflare
SSH aliases. Legion and the Mac-local VMs continue to use their normal SSH aliases.

To copy certificates, use the separate command. Existing certificate files in the destination are
preserved:

```sh
./dev sync-certificates all
./dev sync-certificates all --remote
```

### Sync one machine

```sh
./dev sync-database mac
./dev sync-database legion
./dev sync-database home-server
./dev sync-database home-server-vm-win
./dev sync-database mac-vm-win
./dev sync-database mac-vm-linux
./dev sync-database local-all

./dev sync-certificates mac
./dev sync-certificates legion
./dev sync-certificates home-server
./dev sync-certificates home-server-vm-win
./dev sync-certificates mac-vm-win
./dev sync-certificates mac-vm-linux
./dev sync-certificates local-all
```

### Folder layout

The Mac keeps its working files and machine snapshots together under one `AutoPilot` folder:

```text
~/Downloads/AutoPilot/
├── Builds/                       # downloaded MSI and DEB installers
├── Misc/                         # merged answer-bank export and scratch files
├── Profile/<profile>/            # certificates and profile completion records
└── Sync/<machine>/               # collected machine data
    ├── Database/                 # copied from that machine's ~/.autopilot
    └── AutoPilot/                # copied certificate/profile folders
        └── Profile/<profile>/
```

Each machine folder in `Sync` also keeps any older profile folders in their original layout. The Mac's own certificates remain in `Profile/`; the sync copy is a separate snapshot.

Missing source folders are reported as skipped. This is expected on a headless server or a machine
that has not yet run AutoPilot or downloaded a certificate.

## Merge answer banks

After collecting machine data, merge all machine copies of the Sagar Mein Yog answer bank into one
shared Mac Downloads file:

```sh
./dev sync-database all
./dev merge-answer-bank all
```

The merge reads either the requested generated filename or the app's actual stored filename. The
stored file is normally nested inside the copied database folder:

```text
~/Downloads/AutoPilot/Sync/<machine>/Database/answer-banks/sagarmeinyog-ver-1.0.json
```

It also accepts this direct legacy/requested location:

```text
~/Downloads/AutoPilot/Sync/<machine>/Database/answer-banks.sagarmeinyog-ver-1.0.json
```

and writes:

```text
~/Downloads/AutoPilot/Misc/answer-banks.sagarmeinyog-ver-1.0.json
```

Entries are considered duplicates when their module, question and option set match after whitespace
and case normalization. The first spelling is retained, and identical repeated answers are ignored.
If the same question/options have conflicting answers, the command stops and names both source files
so the conflict can be resolved before producing a merged bank. The generated file is deterministic:
modules and questions are written in stable order.

### Machine paths and overrides

| Machine | SSH host | AutoPilot data home |
|---|---|---|
| Mac | local | `/Users/jagdeep` |
| Legion | `home-legion` | `C:/Users/jagde` |
| Home server | `home-server` | `/home/jagdeep` |
| Server Windows VM | explicit `ssh -J home-server -p 2222 builduser@127.0.0.1` | `C:/Users/builduser` |
| Mac Windows VM | `mac-vm-windows` | `C:/Users/Jagdeep` |
| Mac Linux VM | `mac-vm-debian` | `/home/jagdeep` |

If a local VM uses another account, override its data home for that command:

```sh
MAC_WINDOWS_HOME='C:\Users\OtherUser' ./dev sync-database mac-vm-win
MAC_LINUX_HOME='/home/otheruser' ./dev sync-database mac-vm-linux
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
<tr><td>Home server, Debian<br><code>home-server</code></td><td><code>home-server</code></td><td>Linux DEB<br><code>packageReleaseDeb</code><br>Low priority</td><td>No: headless website host.<br><code>dev fetch home-server</code> can copy its DEB; <code>dev run all</code> leaves it remote.</td><td>No</td></tr>
<tr><td>Home server Windows VM<br><code>ssh -J home-server -p 2222 builduser@127.0.0.1</code></td><td><code>home-server-vm-win</code></td><td>Windows MSI<br><code>Build-Distribution.ps1</code></td><td>No. Its MSI is copied to <code>~/Downloads/AutoPilot/Builds</code>; install it on Legion.</td><td>No</td></tr>
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

### 2. Semantic version, build number and commit

The release version in `gradle/libs.versions.toml` follows Semantic Versioning (`MAJOR.MINOR.PATCH`), for example `1.1.0`. Desktop builds show a compact version and retain an immutable technical identity:

```text
Visible:   1.1.0 (104 · 4614d49)
Technical: 1.1.0+build.104.g4614d49
```

The build number is `git rev-list --count HEAD`, and the commit is the short Git SHA. Together they identify the build sequence and exact source commit. The compact value is passed to the app as `-Dautopilot.version`, so it appears in the About screen and status bar. The technical SemVer build identity is passed separately as `-Dautopilot.buildIdentity` for diagnostics.

Windows MSI, DMG and DEB package metadata must remain numeric. The packaging version is therefore derived separately as `<major>.<minor>.<build number>` (for example `1.1.104`) so Windows can detect and upgrade a newer MSI. The app displays the compact version. Android and iOS keep the release `versionName` from the version catalog.

Building the same commit twice produces the same build identity. `Install-Msi.ps1 -SkipIfInstalled` then reports *already installed* and moves on instead of failing.

### 3. Talking to Windows over SSH

Windows commands go through `ssh` → the remote default shell (Command Prompt on Legion, PowerShell in the VM) → PowerShell. Quotes get mangled along that path. So the script sends each PowerShell block **base64-encoded** with `powershell -EncodedCommand`. Nothing in it is reinterpreted on the way, and both machines' default shells handle it the same way.

### 4. Build

- **Mac:** `./gradlew` with the focused tests, then `:desktopApp:createReleaseDistributable`.
- **Legion and the VM:** `private/scripts/windows/Build-Distribution.ps1`. It runs the focused tests and then `:desktopApp:packageReleaseMsi`, with two workers and a 2 GB heap. Packaging also patches the MSI so it installs and runs under Smart App Control: it removes jpackage's unsigned helper DLL and points the shortcuts at the signed `javaw.exe` (see [09](../common/08_FIX_WINDOWS_INSTALLER.md)). The build output shows `Removed JpCaDll …` and `Pointed 2 shortcuts …`.
- **Server:** the same Gradle settings, run with `nice` and `ionice`, so the websites keep priority.

The focused tests are `core:automation`, the DGS study tests, the desktop data-path tests and the version test. They're quick checks, not the full suite.

### 5. Install

- **Mac:** stops if AutoPilot is running, since quitting it could lose a routine's progress. Otherwise it replaces `/Applications/AutoPilot.app` with the new build and checks its signature. `~/.autopilot` isn't touched.
- **Legion:** `private/scripts/windows/Install-Msi.ps1 -LaunchCheck -SkipIfInstalled`. It refuses an older MSI and closes a running copy. Then it installs silently and checks four things: the installed version, the signed `javaw.exe`, that both shortcuts point to it, and a 15-second launch from the desktop shortcut. It finishes by counting Smart App Control blocks. `%USERPROFILE%\.autopilot` isn't touched.
- **Mac Windows VM:** uses the same `Install-Msi.ps1 -LaunchCheck -SkipIfInstalled` path over SSH, with the VM's own `%USERPROFILE%\.autopilot` preserved.
- **Mac Linux VM:** installs the newest DEB found in the VM's release folder with `sudo apt-get install -y`; the VM's package database and user data stay in the VM.
- **Server and VM:** their explicit `fetch` action copies the newest installer to `~/Downloads/AutoPilot/Builds`, and the SHA256 of the copy must match the source. `dev run all` builds the server DEB but does not copy it. `dev fetch all` separately copies and checks every existing Legion MSI and server DEB.

### 6. Debug

Both debug runs build from source at the synced commit, so breakpoints match the code on the Mac.

- **Mac:** stops if AutoPilot is already running or port 5005 is taken. It then runs `./gradlew :desktopApp:run --debug-jvm`, which waits for the debugger. In Android Studio choose **desktopApp [attach :5005]** → Debug. Closing the app window ends the run.
- **Legion:** checks that the `AutoPilot-Debug` task exists and that the installed app isn't open, since both would share `.autopilot`. It starts the task on the signed-in desktop and waits up to 15 minutes until the debug run listens on Legion's port 5005. It then opens `ssh -N -L 127.0.0.1:5005:127.0.0.1:5005 home-legion` on the Mac. In Android Studio choose **desktopApp [attach :5005]** → Debug; the window appears on Legion. Control-C in the Terminal closes the tunnel. Port 5005 is never opened to the network.
- **Mac Windows VM:** uses the same `AutoPilot-Debug` scheduled task and opens an SSH tunnel to the VM's port 5005. Register the task once inside the VM with `private\scripts\windows\Register-DebugLauncher.ps1`, then use `./dev debug mac-vm-win`.
- **Mac Linux VM:** runs `:desktopApp:run --debug-jvm` over SSH and forwards port 5005 to the Mac. The Linux VM must have an active desktop session available to the SSH user.

## Where the scripts live

The root `./dev` is the single user-facing command (moved from `tools/` on 11 November 2026). Its tested implementations remain in `private/scripts/`, and the Windows PowerShell support files remain in `private/scripts/windows/` because Gradle and the Windows build machines invoke them during packaging, installation and debugging. The `AutoPilot-Debug` task on Legion stores its launcher's path. `./dev debug legion` re-registers it automatically when that path is out of date.

## When it stops

| Message | What to do |
| --- | --- |
| *the Mac has uncommitted changes* | Commit (or ask Claude to push), then rerun |
| *GitHub has N commit(s) the Mac doesn't* | `git pull --ff-only` on the Mac, then rerun |
| *Legion / the server has uncommitted changes* | Compare with GitHub, stash as a backup, rerun |
| *AutoPilot is running on the Mac* | Quit it normally, rerun |
| *The installed AutoPilot is open on Legion* | Close it normally on Legion, rerun |
| *Version … is not newer than the installed …* | Commit a change and rerun, or uninstall first ([03, S6](../common/06_INSTALL_AND_TEST_WINDOWS.md)) |
| *the Windows VM isn't running* | `./dev home-server-vm-win start --headless`, then rerun |
| *The AutoPilot-Debug task is not registered* | Run `private\scripts\windows\Register-DebugLauncher.ps1` once on the Legion desktop ([23](35_SET_UP_WINDOWS_DEBUG_LAUNCHER.md)) |
| *port 5005 is already in use* | Close the earlier tunnel or debug run (`lsof -nP -iTCP:5005`) |
| A build or test failure | Read the Gradle output above the message; nothing was installed |

## Files

| File | Runs on | Role |
| --- | --- | --- |
| `./dev` | Mac | The single command: sync, build, install and debug; `all` leaves the server DEB remote |
| `./dev fetch all` | Mac | Copy and SHA256-check every existing Legion MSI and server DEB |
| `./dev home-server-vm-win` | Mac | Start, stop, check and test the server's Windows VM |
| `private/scripts/windows/Build-Distribution.ps1` | Legion, VM | Focused tests and MSI build |
| `private/scripts/windows/Install-Msi.ps1` | Legion | Silent install or update, plus checks |
| `private/scripts/windows/Remove-UnsignedMsiHelper.ps1`, `Use-SignedJavaLauncher.ps1` | Legion, VM (during packaging) | Smart App Control fixes inside the MSI |
| `private/scripts/windows/Start-Debug.ps1` | Legion (via the `AutoPilot-Debug` task) | Debug run from source |
| `.run/desktopApp-attach-debugger.run.xml` | Android Studio | Attach to `127.0.0.1:5005` |

## Recorded run — 11 November 2026

The earlier full-run invocation at commit `1a13630` (version 1.0.30), now expressed as `./dev run all`:

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
| Server copy | `~/Downloads/AutoPilot/Builds/autopilot_1.0.30_amd64.deb`, SHA256 `37a14674…1133635`, matching the server |

Also tested on 11 November 2026, at commit `ac90a44` (version 1.0.31):

| Command | Result |
| --- | --- |
| `./dev build home-server-vm-win` | VM running check passed. Synced the VM to `ac90a44`. Focused tests and MSI: BUILD SUCCESSFUL in 6 min 2 s, with both Smart App Control fixes applied. Copied `~/Downloads/AutoPilot/Builds/AutoPilot-1.0.31.msi`; its SHA256 `e3ee33bd…817c22fd52` matches the VM. |
| `./dev debug legion` | Synced Legion. Started `AutoPilot-Debug`; the debug run listened on port 5005. `jdb -attach 127.0.0.1:5005` through the tunnel attached to Legion's JVM 21.0.13, resumed the app and detached. |

The first debug test found a problem: the Mac's SSH config shares connections (`ControlMaster auto`, kept for 30 minutes). Through a shared connection, `ssh -N` opened a Windows prompt, and the 5005 forward stayed attached to the shared connection after the script ended. The tunnel now uses its own connection (`ssh -S none -N …`), so Control-C really closes it. If 5005 is ever stuck on the Mac, `ssh -O cancel -L 127.0.0.1:5005:127.0.0.1:5005 home-legion` releases it.

Ending a Legion debug session: closing the app window on Legion ends the run. `Stop-ScheduledTask AutoPilot-Debug` alone stops only the launcher's PowerShell; the app's JVM keeps running until its window is closed.

