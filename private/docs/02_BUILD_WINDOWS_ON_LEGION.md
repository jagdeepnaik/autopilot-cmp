# 02 — Build AutoPilot for Windows on Legion

[All guides](00_INDEX.md) · [Git guide](19_GIT_GUIDE.md) · [Fetch and verify build artifacts](01_ONE_COMMAND_BUILDS.md#fetch-and-verify-build-artifacts) · [Install and test](03_INSTALL_AND_TEST_WINDOWS.md)

## ONE COMMAND — sync, build, install, debug

From the Mac: `./dev run legion`. It pushes, pulls on Legion, builds with tests, installs and checks. Use `sync legion`, `build legion` or `debug legion` for one part of the workflow. How it works, and the commands for the Mac and the home server: [01_ONE_COMMAND_BUILDS.md](01_ONE_COMMAND_BUILDS.md).

The manual steps below do the same thing one command at a time.

## Legion local Debian VMs

Legion currently runs two VirtualBox Debian guests:

| VM | Legion-only SSH forward | Mac connection route | Checkout |
| --- | ---: | --- | --- |
| Debian1 | `2201` | `ssh legion-debian1` | `/home/jagdeep/Projects/AutoPilot` |
| Debian2 | `2202` | `ssh legion-debian2` | `/home/jagdeep/Projects/AutoPilot` |

The forwards bind to Legion loopback only; they are not exposed directly to the LAN. The Mac uses
the dedicated `id_ed25519_mac_vms` key and `legion_debian_known_hosts` file. Password-free SSH was
verified on both guests on 21 November 2026:

```sh
ssh legion-debian1 'hostname; whoami; uname -m'
ssh legion-debian2 'hostname; whoami; uname -m'
```

These aliases are defined in the **Mac's** `~/.ssh/config`. Run them from a Mac terminal,
not from inside Debian1 or Debian2; `home-legion` is a Mac SSH alias and is not defined inside
the Debian guests.

### One-time Debian VM setup

Git and Java are not installed yet on either guest. Run the following **inside each Debian VM**
(or through the corresponding SSH connection). `sudo` will ask for that VM's Debian password:

```sh
sudo apt-get update
sudo apt-get install -y git openjdk-21-jdk unzip
mkdir -p ~/Projects
git clone https://github.com/jagdeepnaik/autopilot-cmp.git ~/Projects/AutoPilot
```

If the checkout already exists, use:

```sh
cd ~/Projects/AutoPilot
git status --short
git pull --ff-only
```

### Build on Debian1 or Debian2

From the Mac, use the matching alias:

```sh
ssh legion-debian1 \
  'cd ~/Projects/AutoPilot && ./gradlew --no-daemon --console=plain --max-workers=2 :desktopApp:packageReleaseDeb'

ssh legion-debian2 \
  'cd ~/Projects/AutoPilot && ./gradlew --no-daemon --console=plain --max-workers=2 :desktopApp:packageReleaseDeb'
```

The DEB is created under:

```text
~/Projects/AutoPilot/desktopApp/build/compose/binaries/main-release/deb/
```

Copy it to the Mac with:

```sh
scp legion-debian1:/home/jagdeep/Projects/AutoPilot/desktopApp/build/compose/binaries/main-release/deb/*.deb \
  "$HOME/Downloads/AutoPilot/Builds/"

scp legion-debian2:/home/jagdeep/Projects/AutoPilot/desktopApp/build/compose/binaries/main-release/deb/*.deb \
  "$HOME/Downloads/AutoPilot/Builds/"
```

The Debian VM package setup is currently blocked until the VM sudo password is entered once on
each guest.

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

Expected: `PS C:\Users\jagdeep>`. You are now in **Windows SSH PowerShell**. All remaining Part C commands run there.

### C2. Open AutoPilot

```powershell
Set-Location C:\Users\jagdeep\Projects\AutoPilot
```

Expected prompt: `PS C:\Users\jagdeep\Projects\AutoPilot>`. Stop if the folder is missing.

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
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\private\scripts\windows\Build-Distribution.ps1
```

This AutoPilot helper runs `core:automation` tests, DGS study tests, desktop data-path tests, and `:desktopApp:packageReleaseMsi`. The script permission applies only to this PowerShell process. Do not add `-SkipTests` for a release check.

Wait for **BUILD SUCCESSFUL**, the installer path, and SHA256 output. Stop on any error; do not distribute an older MSI as the new build. This builds the installer; it does not install or launch AutoPilot.

For changes to the desktop window/sidebar, also run:

```powershell
.\gradlew.bat :shared:jvmTest --tests '*WindowsWindowControlsTest' --tests '*DesktopShellUiTest' --console=plain
```

### C6. Locate the AutoPilot installer

```powershell
Get-ChildItem .\desktopApp\build\compose\binaries\main-release\msi\AutoPilot-*.msi
```

Record its hash separately if needed:

```powershell
Get-ChildItem .\desktopApp\build\compose\binaries\main-release\msi\AutoPilot-*.msi | Get-FileHash -Algorithm SHA256
```

The filename contains the MSI version, `<major>.<minor>.<commit count>`, for example `AutoPilot-1.0.22.msi` (commit `c11518c` is the 22nd). A newer version updates an installed copy in place. Use the actual output of this build. A matching hash verifies copying, not signing.
