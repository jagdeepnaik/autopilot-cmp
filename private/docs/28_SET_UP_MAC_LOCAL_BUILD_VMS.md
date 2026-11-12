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

Open **PowerShell as Administrator** inside Windows:

```powershell
Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0
Start-Service sshd
Set-Service -Name sshd -StartupType Automatic
New-NetFirewallRule -Name sshd -DisplayName 'OpenSSH Server (sshd)' -Enabled True -Direction Inbound -Protocol TCP -Action Allow -LocalPort 22
```

Install Git for Windows and a full JDK 21. Verify:

```powershell
git --version
java -version
Get-Command powershell
```

The JDK must be available in the SSH session, not only in an interactive desktop terminal.

## 4. Prepare the Linux VM

On Debian or Ubuntu, install Git, JDK 21, and Debian packaging tools:

```sh
sudo apt update
sudo apt install -y git openjdk-21-jdk unzip fakeroot dpkg-dev
```

Verify:

```sh
git --version
java -version
dpkg-deb --version
```

If Gradle reports another missing native package, install the package named in the Gradle error and rerun the focused build.

## 5. Clone AutoPilot in both VMs

Use a dedicated clean checkout in each VM. The paths below are examples.

**Windows PowerShell:**

```powershell
New-Item -ItemType Directory -Force C:\Users\jagde\Projects | Out-Null
Set-Location C:\Users\jagde\Projects
git clone https://github.com/jagdeepnaik/autopilot-cmp.git autopilot-cmp
Set-Location C:\Users\jagde\Projects\autopilot-cmp
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

## 7. Configure `desktop.sh`

In the Mac Terminal, set the aliases and exact checkout paths:

```sh
export MAC_WINDOWS_HOST='mac-windows-vm'
export MAC_WINDOWS_DIR='C:\Users\jagde\Projects\autopilot-cmp'

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

Test one VM at a time first:

```sh
private/scripts/desktop.sh windows-local sync
private/scripts/desktop.sh windows-local build
private/scripts/desktop.sh windows-local install
```

Build the Linux DEB in the Linux VM:

```sh
private/scripts/desktop.sh linux-local sync
private/scripts/desktop.sh linux-local build
```

Build macOS, Windows, and Linux from one Mac command:

```sh
private/scripts/desktop.sh local-all build
```

The script requires committed Mac work, pushes it, and pulls the same commit into both VM checkouts before building.

## 10. Recommended release workflow

1. Commit the Mac changes.
2. Run `private/scripts/desktop.sh local-all sync`.
3. Run `private/scripts/desktop.sh local-all build`.
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
