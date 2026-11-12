# 28 — Set Up Windows and Linux Build VMs on the Mac

[All guides](00_INDEX.md) · [One-command builds](01_ONE_COMMAND_BUILDS.md#quick-reference) · [SSH setup](19_SET_UP_WINDOWS_SSH.md) · [Git guide](18_GIT_GUIDE.md)

This guide prepares the Mac to build AutoPilot locally on macOS, Windows 11 in a VM, and Linux in a VM. The build code remains in `private/scripts/desktop.sh`; the local VM targets use SSH.

## 0. Components checklist

Install these components before configuring the build script:

| Machine | Required components | Why it is needed |
| --- | --- | --- |
| Mac host | macOS, Xcode Command Line Tools, Git, JDK 21, SSH client, virtualization software | Runs the script and builds the macOS app |
| Windows VM | Windows 11 ARM64, Git for Windows, full JDK 21, OpenSSH Server, PowerShell, WiX if requested by Gradle | Builds and optionally installs the MSI |
| Linux VM | Debian 13 ARM64, Git, full JDK 21, OpenSSH Server, `unzip`, `fakeroot`, `dpkg-dev`, compiler/runtime libraries | Builds and tests the DEB |
| GitHub | Repository access from both VMs | Lets the script pull the exact Mac commit before building |

The project uses the Gradle wrapper in the repository; do not install a separate Gradle version unless a build error specifically requires it.

### 0.1 Mac host components

Install Apple’s command-line tools once:

```sh
xcode-select --install
```

Verify the tools already available on the Mac:

```sh
git --version
ssh -V
java -version
```

If JDK 21 is not installed, install Homebrew from [brew.sh](https://brew.sh/) and then install Temurin 21:

```sh
brew install --cask temurin@21
java -version
```

Install one virtualization product manually from its official site: [Parallels Desktop](https://www.parallels.com/products/desktop/), [VMware Fusion](https://support.broadcom.com/), or [UTM](https://utm.app/). Only one is required.

### 0.2 Windows VM components

Inside Windows, open **PowerShell as Administrator** and install OpenSSH Server:

```powershell
Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0
Start-Service sshd
Set-Service -Name sshd -StartupType Automatic
New-NetFirewallRule -Name sshd -DisplayName 'OpenSSH Server (sshd)' -Enabled True -Direction Inbound -Protocol TCP -Action Allow -LocalPort 22
```

Install Git and JDK 21 with `winget` when it is available:

```powershell
winget install --id Git.Git -e --source winget
winget install --id EclipseAdoptium.Temurin.21.JDK -e --source winget
```

If `winget` is unavailable, install Git from [git-scm.com](https://git-scm.com/download/win) and a full JDK 21 from [Adoptium](https://adoptium.net/temurin/releases/). Reopen PowerShell after installation so `PATH` is refreshed.

Verify the components in the same account used by SSH:

```powershell
git --version
java -version
Get-Service sshd
Get-Command powershell
```

The current MSI build normally uses `jpackage` from JDK 21. If Gradle reports that WiX is missing, install the WiX Toolset from [wixtoolset.org](https://wixtoolset.org/) and ensure its `bin` directory is on the system `PATH`, then reopen PowerShell.

### 0.3 Linux VM components

Use Debian 13 ARM64 for the closest match to the existing Debian build server. After installation, run:

```sh
sudo apt update
sudo apt install -y git openssh-server openjdk-21-jdk unzip zip fakeroot dpkg-dev build-essential ca-certificates curl libfontconfig1 libfreetype6 libx11-6 libxext6 libxi6 libxrender1 libxtst6
sudo systemctl enable --now ssh
```

Verify every required component:

```sh
git --version
java -version
ssh -V
dpkg-deb --version
unzip -v | head -1
```

If you want to open and test the Linux desktop app inside the VM, install a lightweight desktop environment as well:

```sh
sudo apt install -y task-xfce-desktop
```

For a build-only VM, a desktop environment is optional.

## 1. M5 architecture

An M5 Mac virtualizes ARM operating systems most efficiently. Use Windows 11 ARM64 and an ARM64 Linux distribution when possible. Windows 11 ARM can run many x64 applications through Microsoft’s built-in emulation, but an ARM64 JDK normally produces an ARM64 package. See [Microsoft’s Windows on Arm emulation guide](https://learn.microsoft.com/en-us/windows/arm/apps-on-arm-x86-emulation).

If the MSI must run on a normal x64 Legion laptop, verify the Windows VM’s JDK architecture before using it as the release builder. The physical Legion remains the safest x64 release builder until the VM has been verified.

**Windows PowerShell:**

```powershell
java -XshowSettings:properties -version 2>&1 | Select-String 'os.arch'
$env:PROCESSOR_ARCHITECTURE
```

**Linux:**

```sh
uname -m
java -XshowSettings:properties -version 2>&1 | grep 'os.arch'
```

`aarch64`/`ARM64` means ARM. `x86_64`/`amd64`/`AMD64` means x64.

## 2. Create the VMs

Use Parallels Desktop, VMware Fusion, or UTM. On Apple silicon, use ARM64 guest images for normal virtualization. Parallels documents Windows 11 ARM support on M-series Macs, including M5 models: [Windows 11 on Mac](https://www.parallels.com/products/desktop/microsoft-authorized-solution-windows-11-arm/).

Create one Windows 11 ARM64 VM and one Debian or Ubuntu ARM64 VM. Give each a stable network address, or configure a host-only/NAT port forward. The Mac must reach both VMs over SSH. Leave enough unified memory and storage for macOS.

## 3. Prepare the Windows VM

The complete Windows installation is in [Windows VM components](#02-windows-vm-components). After completing that section, verify the SSH account that `desktop.sh` will use:

```powershell
git --version
java -version
Get-Service sshd
Get-Command powershell
```

The JDK must be available in the SSH session, not only in an interactive desktop terminal.

## 4. Prepare the Linux VM

The complete Linux installation is in [Linux VM components](#03-linux-vm-components). After completing that section, verify the SSH account that `desktop.sh` will use:

```sh
git --version
java -version
ssh -V
unzip -v | head -1
dpkg-deb --version
```

If Gradle reports another missing native package, install the package named in the Gradle error and rerun the focused build.

## 5. Clone AutoPilot in both VMs

Use a dedicated clean checkout in each VM. The paths below are examples.

**Windows PowerShell:**

```powershell
New-Item -ItemType Directory -Force C:\Users\Jagdeep\Projects | Out-Null
Set-Location C:\Users\Jagdeep\Projects
git clone https://github.com/jagdeepnaik/autopilot-cmp.git autopilot-cmp
Set-Location C:\Users\Jagdeep\Projects\autopilot-cmp
git status --short
```

**Linux:**

```sh
mkdir -p "$HOME/Projects"
cd "$HOME/Projects"
git clone https://github.com/jagdeepnaik/autopilot-cmp.git AutoPilot
cd AutoPilot
git status --short
```

Use the repository’s normal Git authentication if required. Both checkouts must be clean before the automation pulls them.

## 6. Configure SSH aliases on the Mac

Edit `~/.ssh/config` and replace the addresses, ports, and users with your VM values:

```sshconfig
Host mac-windows-vm
    HostName 192.168.64.20
    User jagdeep
    Port 22
    IdentityFile ~/.ssh/id_ed25519

Host mac-linux-vm
    HostName 192.168.64.21
    User jagdeep
    Port 22
    IdentityFile ~/.ssh/id_ed25519
```

If the VM software forwards ports through localhost, use a different port for each VM, such as `2223` for Windows and `2224` for Linux.

Test the aliases:

```sh
ssh mac-windows-vm powershell -NoProfile -Command "hostname"
ssh mac-linux-vm 'hostname && uname -m'
```

Use the existing [password-free SSH guide](20_SET_UP_PASSWORD_FREE_SSH.md). Never put passwords or private keys in `desktop.sh`.

### 6.1 If the VM addresses in the aliases do not work

VM addresses can change when the virtualization product recreates its private network. On the
Mac, first check the active VM bridge addresses:

```sh
ifconfig | grep -E '^(bridge|vmenet)' -A5
```

Then check the SSH port on the candidate guest address:

```sh
nc -vz -G 3 172.16.201.129 22
nc -vz -G 3 172.16.201.130 22
```

On this Mac, the guests were found on 2026-11-13 as follows:

| Guest | Address | SSH banner | Result |
| --- | --- | --- | --- |
| Windows VM | `172.16.201.129` | `OpenSSH_for_Windows_9.5` | Port open; key authentication still needs to be installed |
| Debian VM | `172.16.201.130` | `OpenSSH_10.0p2 Debian-7+deb13u4` | Port open; key authentication still needs to be installed |

Update only the `HostName` values in `~/.ssh/config` when the addresses change. A reachable
port is not enough: the VM user must also have the Mac public key in its authorized-keys file.
Complete the key installation from [password-free SSH](20_SET_UP_PASSWORD_FREE_SSH.md), then
repeat:

```sh
ssh mac-windows-vm powershell -NoProfile -Command "hostname"
ssh mac-linux-vm 'hostname && uname -m'
```

Do not continue to build until both commands authenticate without asking for a password. If the
SSH user is not `jagdeep`, change the `User` line in the matching alias to the actual VM account.

### 6.2 Current connection test

On 2026-11-13 the Mac reached both active VM addresses and verified their host keys, but both
logins returned `Permission denied (publickey,password,keyboard-interactive)`. This means the
network and SSH servers are working; the Mac public key has not yet been authorized for the
`jagdeep` account in one or both guests. Follow [password-free SSH section 9.3](20_SET_UP_PASSWORD_FREE_SSH.md#93-append-without-losing-existing-keys)
on each guest, then run the two alias tests above again. Do not use `-o StrictHostKeyChecking=no`
as a permanent fix.

### 6.3 Complete password-free SSH setup

The VM account password is needed only once to authorize the Mac public key. The private key stays
on the Mac; only `~/.ssh/id_ed25519.pub` is copied to the VM.

On the Mac, copy the public key:

```sh
pbcopy < ~/.ssh/id_ed25519.pub
```

#### Windows administrator account

The `jagdeep` account on the Windows VM is an administrator, so Windows OpenSSH uses
`C:\ProgramData\ssh\administrators_authorized_keys`. Open **PowerShell as Administrator** in the
Windows VM console. These commands deliberately create the file before opening it in Notepad:

```powershell
$keyFile = "$env:ProgramData\ssh\administrators_authorized_keys"
$keyDir = Split-Path $keyFile
New-Item -ItemType Directory -Force -Path $keyDir | Out-Null
New-Item -ItemType File -Force -Path $keyFile | Out-Null
notepad $keyFile
```

Paste the one-line `ssh-ed25519 ...` public key copied from the Mac, save the file, and close
Notepad. Verify that the file was saved:

```powershell
Test-Path $keyFile
Get-Content $keyFile
```

Then apply the OpenSSH administrator-file permissions and restart the server:

```powershell
icacls.exe $keyFile /inheritance:r /grant:r '*S-1-5-32-544:F' '*S-1-5-18:F'
Restart-Service sshd
```

If `icacls` says the file does not exist, return to the `New-Item` commands and save the key in
Notepad before applying permissions. In PowerShell, use the commands as shown; a trailing `\` is
not a continuation character.

#### Debian user account

Open the Debian VM console as `jagdeep`, then paste the same public-key line into the user's
authorized-keys file:

```sh
mkdir -p ~/.ssh
chmod 700 ~/.ssh
nano ~/.ssh/authorized_keys
```

Save in Nano with `Ctrl+O`, `Enter`, then exit with `Ctrl+X`. Finish with:

```sh
chmod 600 ~/.ssh/authorized_keys
chown -R "$USER:$USER" ~/.ssh
sudo systemctl restart ssh
```

#### Verify without allowing password fallback

From the Mac, both commands must work without displaying a password prompt:

```sh
ssh -o BatchMode=yes -o PasswordAuthentication=no -o KbdInteractiveAuthentication=no mac-windows-vm powershell -NoProfile -Command "hostname; whoami"
ssh -o BatchMode=yes -o PasswordAuthentication=no -o KbdInteractiveAuthentication=no mac-linux-vm 'hostname && whoami && uname -m'
```

The command may still ask for the Mac key's passphrase if the key is protected. That is local key
protection, not the Windows or Debian account password. To keep the protected key in Apple's
Keychain for the current Mac user, run:

```sh
ssh-add --apple-use-keychain ~/.ssh/id_ed25519
```

## 7. Configure `desktop.sh`

In the Mac Terminal, set the aliases and exact checkout paths:

```sh
export MAC_WINDOWS_HOST='mac-windows-vm'
export MAC_WINDOWS_DIR='C:\Users\Jagdeep\Projects\autopilot-cmp'

export MAC_LINUX_HOST='mac-linux-vm'
export MAC_LINUX_DIR='/home/jagdeep/Projects/AutoPilot'
```

To reuse them, put the exports in a private file such as `~/.autopilot-local-vms`, then run:

```sh
source ~/.autopilot-local-vms
```

## 8. Verify tools through SSH

```sh
ssh "$MAC_WINDOWS_HOST" powershell -NoProfile -Command "Set-Location '$MAC_WINDOWS_DIR'; git status --short; java -version"
ssh "$MAC_LINUX_HOST" "cd '$MAC_LINUX_DIR' && git status --short && java -version && uname -m && dpkg-deb --version"
```

Both `git status --short` outputs should be empty.

## 9. Build on the Mac-local VMs

The standard command format is `private/scripts/desktop.sh <action> <target>`: action first,
target last. `build` syncs and creates the artifact; `run` builds, then installs/checks it; `debug`
starts one debug session and its port-5005 tunnel.

Test one VM at a time first:

```sh
private/scripts/desktop.sh sync mac-vm-win
private/scripts/desktop.sh build mac-vm-win
private/scripts/desktop.sh run mac-vm-win
```

For Windows VM debugging, register the scheduled launcher once from an interactive PowerShell
window inside the VM:

```powershell
Set-Location C:\Users\Jagdeep\Projects\autopilot-cmp
& .\private\scripts\windows\Register-DebugLauncher.ps1
```

Then start the debug run from the Mac. The command waits for port 5005 and keeps the SSH tunnel
open until you press `Control-C`:

```sh
private/scripts/desktop.sh debug mac-vm-win
```

Attach Android Studio to `127.0.0.1:5005` using the Windows debug configuration. Close the
Windows app normally when finished so its local progress is saved.

Build the Linux DEB in the Linux VM:

```sh
private/scripts/desktop.sh sync mac-vm-linux
private/scripts/desktop.sh build mac-vm-linux
private/scripts/desktop.sh run mac-vm-linux
```

Linux VM debugging requires a graphical desktop session for the SSH user:

```sh
private/scripts/desktop.sh debug mac-vm-linux
```

The command runs `:composeApp:run --debug-jvm`, forwards port 5005 to the Mac, and keeps the SSH
session open until `Control-C`. Install uses `sudo apt-get` inside the VM and may request the
Linux user's sudo password unless sudo is configured for unattended package installation.

Build macOS, Windows, and Linux from one Mac command:

```sh
private/scripts/desktop.sh build local-all
private/scripts/desktop.sh run local-all
```

The script requires committed Mac work, pushes it, and pulls the same commit into both VM checkouts before building. `run local-all` also installs the macOS app, Windows MSI and Linux DEB. Debug one VM at a time with its target above.

## 10. Recommended release workflow

1. Commit the Mac changes.
2. Run `private/scripts/desktop.sh sync local-all`.
3. Run `private/scripts/desktop.sh build local-all`.
4. Test the macOS app, MSI, and DEB in their respective environments.
5. Use Legion for the final x64 Windows MSI unless the local VM’s JDK and MSI architecture have been verified.

The local VMs build from Git. They must not contain uncommitted changes, and the script never overwrites a dirty checkout.

## Troubleshooting

| Problem | Check |
| --- | --- |
| `Could not resolve hostname` | The SSH alias is missing or the VM address changed. Test `ssh mac-windows-vm` or `ssh mac-linux-vm`. |
| VM has uncommitted changes | Enter the VM, inspect `git status`, and commit or stash the changes there before syncing. |
| `java: command not found` | Add the full JDK 21 `bin` directory to the system PATH visible to SSH. |
| MSI works in the VM but not on Legion | Check `os.arch`; an ARM64 MSI is not the same target as an x64 MSI. Build the final x64 MSI on Legion or with an x64 JDK/Windows environment. |
| Linux package is the wrong architecture | Compare `uname -m` with the intended desktop architecture. Use an x64 Linux builder for an x64 DEB. |
