# 24 — Set Up the Home Server and Windows Build VM

[All guides](00_INDEX.md)

**One-time preparation only. Already configured? Use [VM controls](12_START_STOP_WINDOWS_BUILD_VM.md), [Windows VM build](13_BUILD_WINDOWS_IN_SERVER_VM.md), or [Linux build](10_BUILD_LINUX_ON_SERVER.md).**

# Debian + Windows 11 Home/Pro: build-server installation

Prepared for Jagdeep, 10 November 2026. This document separates one-time server preparation from later build operations. Nothing requires a physical monitor on the server. Completed checks and unfinished builds are identified explicitly.

## Choose the correct part before running commands

| Part | When to use it | What it does |
| --- | --- | --- |
| **PART A — ONE-TIME PREPARATION** | Only for a new server, VM or app checkout | Install Windows/tools, configure SSH, clone the app, and check Gradle |
| **PART B — BUILD AND DOWNLOAD** | Every time you want a new installer | Start/connect, update source, build, and download the output |

**YOUR CURRENT POSITION: preparation is complete.** Both Windows and Linux start Gradle with JDK 21. Do not repeat the ISO download, disk creation, Windows installation, account/key setup, or clone commands.

**To build now, open [PART B — Build commands](27_BUILD_TASKS_QUICK_LINKS.md).** For Windows, start at **1.2** if the VM is already running, or **1.1** if it is off. For Linux, start at **2.1**.

**Current build results:** Linux tests and DEB packaging completed. The Windows MSI build was stopped at your request; no successful MSI result is claimed. No further builds or monitoring will run unless requested.

---

# PART A — ONE-TIME PREPARATION

**Sections 1–13 below are setup/reference steps. They are already completed for your current server and AutoPilot checkouts. Only repeat them when intentionally preparing something new.**

## Start here: which window do I use?

Use this guide in order. **Do not paste several command boxes together.** Copy one box, press Enter, wait for the result, then read the next instruction.

| Label | Where you type | Used for |
| --- | --- | --- |
| **Window 1 — Debian** | Mac Terminal after `ssh home-server` or `ssh home-server-remote` | Installing server tools and creating the VM |
| **Window 2 — Mac** | A new Mac Terminal window, not connected with SSH | Copying the ISO and downloading installers |
| **Window 3 — Mac tunnel** | Another new Mac Terminal window | Keeping the VM console connection open |
| **Windows VM console** | The Windows screen reached through Finder | Installing/configuring Windows |

At the start of Window 1, choose **one** connection: `home-server` for the local network, or `home-server-remote` for Cloudflare. Both reach the same Debian server. The guide shows both alternatives where needed; you never run both alternatives for one step.

**For a NEW installation only — your first action:** open Mac Terminal and run one connection command from section 2. Once connected, go to section 3.1. You do not need the ISO copy or console-tunnel command yet.

Only section 8 and the single fingerprint command in 9.1 need typing in the Windows VM screen. **Starting at 9.2, use SSH in Mac Terminal for all Windows setup, so large blocks can be pasted normally with Command-V.**

Some later code boxes create a script or configure one complete Windows component. Those boxes must be pasted in full as a single setup step; they are labelled by their purpose. Do not split a script at an `if`, brace, or quoted block. Normal command boxes otherwise contain one action at a time.

## 1. Target setup and boundaries

- Existing Debian server: `home-server`, `jagdeep@192.168.1.23`.
- Existing websites stay on Debian/Docker.
- Linux builds run directly on Debian.
- Windows builds run in a Windows 11 **Home or Pro** VM.
- Windows desktop/browser/UI tests stay on the laptop.
- This is a general-purpose build VM named `windows-build`, stored at `/srv/vms/windows-build`. It can build multiple apps.
- Put each Windows app checkout under `C:\Build\<project-name>` and each Debian checkout under `/srv/build/<project-name>`. AutoPilot paths later in this guide are one worked example, not a restriction on the server.
- VM: 4 virtual CPUs, 8 GB RAM, 100 GB dynamically allocated QCOW2 disk on `/srv`.
- Use low-memory Gradle commands below: this repository normally allows a 4 GB Gradle heap plus a separate 3 GB Kotlin heap, which is unnecessarily generous for this shared server.
- No Debian desktop, libvirt network bridge, router port forwarding, or public VNC listener is needed.
- The VM is started manually and consumes RAM only while running.

Use official Microsoft Windows 11 media and a valid license covering this VM. The ISO download is free; it is not a permanent free Windows license. Select Home or Pro to match your license. Home supports our SSH-based builds; Pro is optional for additional features such as Remote Desktop hosting. Do not assume the laptop’s license covers this extra Windows instance.

Official download: https://www.microsoft.com/en-us/software-download/windows11

For every pair labelled **Local network** and **Cloudflare / Internet**, run only the version you choose. Debian and Windows commands inside an established SSH session are the same for either route. Do not run both VNC tunnels together: they use the same local port.

Follow the sections in order. If a command fails, stop and send the error, without credentials. Do not substitute firewall resets or disable security settings.

## 2. Connect from the Mac

**Mac Terminal:**

**Local network:**

```sh
ssh home-server
```

**Cloudflare / Internet:**

```sh
ssh home-server-remote
```

If the saved key is locked, unlock it locally first:

**Local network:**

```sh
/usr/bin/ssh-add --apple-use-keychain ~/.ssh/id_ed25519
```

```sh
ssh home-server
```

**Cloudflare / Internet:**

```sh
/usr/bin/ssh-add --apple-use-keychain ~/.ssh/id_ed25519
```

```sh
ssh home-server-remote
```

Enter credentials only into your own Terminal. Do not send them in chat.

### Internet access through Cloudflare

There are two routes to the Debian machine:

| Where you connect from | SSH command | Route |
| --- | --- | --- |
| Home network | `ssh home-server` | Direct to `192.168.1.23` |
| Away from home, or explicitly through Cloudflare | `ssh home-server-remote` | Cloudflare Access/Tunnel |

**For everyday use, you only type this in Mac Terminal:**

```sh
ssh home-server-remote
```

The block below is a saved connection setting in the Mac file `~/.ssh/config`. It is **not** four commands to run. Keep it in that file; you do not type the long `ProxyCommand` yourself each time.

- `Host home-server-remote`: the simple shortcut and the original name both work.
- `HostName ssh.naikio.in`: the real Cloudflare address behind the shortcut.
- `User jagdeep`: the Debian login account.
- `ProxyCommand ...`: automatically carries SSH through Cloudflare.
- `IdentityFile ...`: selects your existing Mac SSH key; it is a file path, not the key contents.

If this block is already in the file, leave it there and do not add another copy. Its compact form is:

```sshconfig
Host home-server-remote
    HostName ssh.naikio.in
    User jagdeep
    ProxyCommand /opt/homebrew/bin/cloudflared access ssh --hostname %h
    IdentityFile ~/.ssh/id_ed25519
```

`cloudflared` is already installed at the path above. On a different computer, install it from Cloudflare's official distribution and use its actual executable path. Cloudflare Access may request a browser sign-in before SSH begins. This is separate from the Linux SSH key authentication. Do not disable either check or put Access tokens/passwords into this guide.

**Verified, 10 November 2026:** after the owner completed Cloudflare Access sign-in locally, independent SSH commands succeeded through both `home-server` and `ssh.naikio.in`, with password prompting disabled and strict host-key checking enabled. Hostname, Linux username, a hash of the machine identity, and the SSH host-key fingerprint matched exactly. Both routes reach the same Debian server. The verified LAN server host-key fingerprint is `SHA256:CGviJr4MkmDpGrhweYntWAyWDVtQbQ2l26STbw3zmOA` (Ed25519). Compare the Cloudflare destination against that identity; do not accept an unexpected key automatically. A test performed from home through the public Cloudflare endpoint verifies the Cloudflare route, not behavior on a separate external Wi-Fi/mobile network.

The numbered steps below show both connection choices. Run only one choice each time. File copies and the VM console tunnel belong to later steps; do not run them while merely connecting to Debian.

Official explanation: [Cloudflare SSH with client-side cloudflared](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/use-cases/ssh/ssh-cloudflared-authentication/).

## 3. Install Debian prerequisites

**Inside the Debian SSH session:**

### 3.1 Refresh the package list

**Window 1 — connected to Debian.** Paste this command, press Enter, and wait for it to finish:

```bash
sudo apt-get update
```

### 3.2 Install the required tools

**Window 1 — Debian.** Run this single command. It may take several minutes. If asked to continue, review the package list and answer `Y`:

```bash
sudo apt-get install --no-install-recommends qemu-system-x86 qemu-utils ovmf swtpm swtpm-tools libslirp0 openjdk-21-jdk git curl ca-certificates unzip zip fakeroot dpkg-dev binutils python3
```

### 3.2a If you installed the earlier package list

The Windows 11 guide added the software TPM packages. If you already installed the older tools, or the launcher reports `/usr/bin/swtpm: No such file or directory`, run this **in Window 1 — Debian**:

```bash
sudo apt-get install --no-install-recommends swtpm swtpm-tools
```

Wait for installation to finish successfully. Then check:

```bash
/usr/bin/swtpm --version
```

It should print a version. If installation fails, stop and send the error. Do not recreate the virtual disk or firmware. If you were already at section 6.4, return to section 6.4 and run the launcher again using its full path.

### 3.3 Allow your account to use virtualization

**Window 1 — Debian:**

```bash
sudo usermod -aG kvm jagdeep
```


Review the package transaction. This installs selected packages; it does not request a full system upgrade. If a package tool asks to restart unrelated website services, postpone those restarts and report the prompt. Do not reboot the production host just to complete these steps.

### 3.4 Return Window 1 to the Mac

**Window 1 — Debian.** This ends the SSH session; it does not shut down the server:

```bash
exit
```

**Mac Terminal:**

**Local network:**

```sh
ssh home-server
```

**Cloudflare / Internet:**

```sh
ssh home-server-remote
```

**Debian:**

### 3.5 Check the tools, one command at a time

**Window 1 — Debian, after reconnecting above.** Run each box separately and wait for its result:

```bash
id
```

```bash
ls -l /dev/kvm
```

```bash
qemu-system-x86_64 --version
```

```bash
qemu-img --version
```

```bash
java -version
```

```bash
javac -version
```

```bash
jpackage --version
```

Check for `kvm` in your groups and Java/JDK version 21. If the default Java is a different version, stop before changing a server-wide Java default; we can point the build at JDK 21 specifically instead.

### 3.6 Create the two build folders

If you previously created `/srv/vms/autopilot-winbuild`, do not delete or move it while a VM is running. The commands below create the new general-purpose folder; they do not migrate an existing VM or ISO. If the old folder already contains your ISO, virtual disk or TPM state, stop and tell me before continuing so those files can be moved safely without downloading or recreating them.

**Window 1 — Debian.** Run the first command, then the second:

```bash
sudo install -d -o jagdeep -g jagdeep -m 700 /srv/vms/windows-build
```

```bash
sudo install -d -o jagdeep -g jagdeep -m 755 /srv/build
```

## 4. Download and copy the Windows ISO

On the Mac, download the **Windows 11 multi-edition ISO for x64 devices** (not ARM64) from Microsoft's page above. Save/rename it to:

```text
~/Downloads/windows-11.iso
```

Use Microsoft's published SHA256, if provided for the selected ISO, to verify the download. Renaming it does not change its contents.

**Open Window 2 on the Mac** using Terminal → Shell → New Window (Command-N). Do not type the following commands in Window 1, which is connected to Debian. Run the hash command first; run the copy command second and wait for the transfer to finish:

**Local network:**

```sh
shasum -a 256 "$HOME/Downloads/windows-11.iso"
```

```sh
scp "$HOME/Downloads/windows-11.iso" home-server:/srv/vms/windows-build/windows-11.iso
```

**Cloudflare / Internet:**

```sh
shasum -a 256 "$HOME/Downloads/windows-11.iso"
```

```sh
scp "$HOME/Downloads/windows-11.iso" home-server-remote:/srv/vms/windows-build/windows-11.iso
```

**Debian:**

```bash
sha256sum /srv/vms/windows-build/windows-11.iso
```

The Mac and Debian hashes must match. This comparison verifies the transfer; comparison with Microsoft's published hash verifies the original download.

## 5. Create the VM disk and firmware state

**Debian:**

```bash
cd /srv/vms/windows-build
test -f windows-11.iso || { echo 'ISO missing'; exit 1; }
test -f /usr/share/OVMF/OVMF_CODE_4M.ms.fd || { echo 'OVMF code missing'; exit 1; }
test -f /usr/share/OVMF/OVMF_VARS_4M.ms.fd || { echo 'OVMF template missing'; exit 1; }
if [ ! -e windows11.qcow2 ]; then
  qemu-img create -f qcow2 windows11.qcow2 100G
else
  echo 'Existing VM disk preserved.'
fi
if [ ! -e OVMF_VARS_WIN11.fd ]; then
  cp /usr/share/OVMF/OVMF_VARS_4M.ms.fd OVMF_VARS_WIN11.fd
else
  echo 'Existing VM firmware settings preserved.'
fi
chmod 600 windows11.qcow2 OVMF_VARS_WIN11.fd
```

The virtual disk exposes a 100 GB capacity to Windows but uses host storage as blocks are written. Windows, updates, JDK, Gradle caches and installers still take real disk space. Do not recreate an existing disk or firmware file to retry installation.

This Windows 11 launcher uses UEFI Secure Boot firmware with Microsoft-enrolled keys and a persistent software TPM 2.0. Both are part of the normal Windows 11 installation configuration; no hardware-check bypass is included. The Windows 11 disk and firmware have distinct names, so an earlier Server Core disk is preserved. If an earlier VM is running, shut it down before using the same forwarded ports. Do not reuse an earlier plain OVMF variable store in place of the Microsoft-key template.

## 6. Create the manual VM launcher

The launcher is a file named `start.sh`. It stores the instructions for starting the Windows VM, including its memory, virtual disk, TPM and console connection. Creating this file does **not** start Windows.

**Where to type:** use **Window 1 — Debian**, the Mac Terminal window already connected to the server. Its prompt looks like `jagdeep@jagdeep:~$`. Do not use a fresh Mac Terminal window or Windows PowerShell for this section. Do not type the prompt itself.

**Before starting:** complete sections 3–5 first. The ISO, virtual disk and firmware files must already exist. If any step below reports an error, stop and send that error before continuing.

### 6.1 Create the launcher file

Copy the **entire code box below**, starting with `cat >` and ending with the final line `SH`. Paste it into Window 1 as one block, then press Enter.

This is one file-creation step. Do not run its lines separately, omit the last `SH`, or copy the Markdown backticks. The final `SH` tells the Terminal that the file contents are complete. During pasting you may see continuation prompts such as `>`; after the final `SH` and Enter, the normal Debian prompt should return.

**This step writes `/srv/vms/windows-build/start.sh`.** If you have already customized that file, stop before replacing it. Otherwise use the block below:

```bash
cat > /srv/vms/windows-build/start.sh <<'SH'
#!/bin/bash
set -euo pipefail
cd /srv/vms/windows-build
if [[ -f qemu.pid ]] && kill -0 "$(cat qemu.pid)" 2>/dev/null; then
  echo 'VM already running.'
  exit 0
fi
if [[ ! -r /dev/kvm || ! -w /dev/kvm ]]; then
  echo 'KVM access missing. Check kvm group membership and reconnect.'
  exit 1
fi
# macOS Screen Sharing requires VNC password authentication.
# Read it locally at each start; do not save it in a file or command argument.
while true; do
  read -r -s -p 'Choose an 8-character VNC password (letters/numbers only): ' VNC_PASSWORD
  printf '\n'
  [[ "$VNC_PASSWORD" =~ ^[A-Za-z0-9]{8}$ ]] && break
  echo 'Use exactly 8 letters/numbers; this is only the console password.'
done
umask 077
TPM_ROOT=/srv/vms/windows-build/tpm2
mkdir -p "$TPM_ROOT/state"
if [[ -f "$TPM_ROOT/swtpm.pid" ]] && kill -0 "$(cat "$TPM_ROOT/swtpm.pid")" 2>/dev/null; then
  echo 'A TPM emulator is already running. Stop here for review; do not erase TPM state.'
  exit 1
fi
/usr/bin/swtpm socket --tpm2 --tpmstate dir="$TPM_ROOT/state" \
  --ctrl type=unixio,path="$TPM_ROOT/swtpm.sock" \
  --pid file="$TPM_ROOT/swtpm.pid" --daemon --terminate
trap 'kill "$(cat "$TPM_ROOT/swtpm.pid")" 2>/dev/null || true' ERR
nice -n 10 ionice -c 2 -n 7 /usr/bin/qemu-system-x86_64 \
  -name windows-build \
  -machine q35,accel=kvm,smm=on \
  -cpu host \
  -smp 4 \
  -m 8192 \
  -global driver=cfi.pflash01,property=secure,value=on \
  -drive if=pflash,format=raw,readonly=on,file=/usr/share/OVMF/OVMF_CODE_4M.ms.fd \
  -drive if=pflash,format=raw,file=OVMF_VARS_WIN11.fd \
  -drive file=windows11.qcow2,format=qcow2,if=ide \
  -drive file=windows-11.iso,media=cdrom,readonly=on,if=ide \
  -boot order=c,menu=on \
  -netdev user,id=buildnet,hostfwd=tcp:127.0.0.1:2222-:22 \
  -device e1000e,netdev=buildnet \
  -chardev socket,id=chrtpm,path=/srv/vms/windows-build/tpm2/swtpm.sock \
  -tpmdev emulator,id=tpm0,chardev=chrtpm \
  -device tpm-tis,tpmdev=tpm0 \
  -device qemu-xhci \
  -device usb-tablet \
  -vga std \
  -display none \
  -vnc 127.0.0.1:5,password=on \
  -monitor unix:monitor.sock,server=on,wait=off \
  -pidfile qemu.pid \
  -daemonize
trap - ERR
printf '%s' "$VNC_PASSWORD" | python3 -c '
import socket,sys
password=sys.stdin.read()
s=socket.socket(socket.AF_UNIX);s.settimeout(2)
s.connect("/srv/vms/windows-build/monitor.sock")
s.recv(8192)
s.sendall(("set_password vnc "+password+"\n").encode())
reply=b""
try:
 while True:
  chunk=s.recv(8192)
  if not chunk:break
  reply+=chunk
except TimeoutError:pass
s.close()
if b"Error" in reply or b"failed" in reply.lower():
 raise SystemExit("Could not configure VNC password. No password was printed.")
'
unset VNC_PASSWORD
echo 'VM started. Connect from macOS Screen Sharing using the console password you just chose.'
SH
```

**Expected result:** the normal Debian prompt returns. No Windows screen appears and the VM has not started yet. If the Terminal stays at a `>` prompt, the closing `SH` may be missing; enter `SH` alone on a new line, then press Enter. If you are unsure what was pasted, stop and send the visible output.

### 6.2 Check that the file was created correctly

Still in **Window 1 — Debian**, run this single command:

```bash
bash -n /srv/vms/windows-build/start.sh
```

**Expected result:** no output and the normal prompt returns. This checks script syntax; it does not start the VM. If you see a syntax error, stop here.

### 6.3 Allow the launcher to run

Still in **Window 1 — Debian**, run:

```bash
chmod 700 /srv/vms/windows-build/start.sh
```

**Expected result:** no output and the prompt returns. This permits your account to run the file and keeps access private to your account. It does not start Windows.

### 6.4 Start the Windows VM

Still in **Window 1 — Debian**, run the **full path** below. Do not type just `start.sh`: Linux does not normally search the current folder for commands. If you are already in `/srv/vms/windows-build`, `./start.sh` also works, but the full path works from any folder:


```bash
/srv/vms/windows-build/start.sh
```

**Expected result:** the launcher first asks you to choose an **8-character console password using letters/numbers**. Type it privately; nothing appears while typing. Choose a separate password, not your Windows/account password. Standard VNC uses only eight characters, so SSH remains the protection for traffic over the network. The launcher sets this password in memory and does not save it to a file. After startup, the prompt returns without opening a window. Windows runs inside the VM in the background. `VM already running.` means you do not need to start another copy. If there is an error about KVM, firmware, the ISO, TPM, a port, or a missing file, stop here and send it. Do not delete the disk or TPM state to retry.

#### If startup reports that swtpm is missing

For this exact error:

```text
/usr/bin/swtpm: No such file or directory
```

Install the missing packages in the **same Debian Terminal**:

```bash
sudo apt-get install --no-install-recommends swtpm swtpm-tools
```

After installation finishes successfully, retry only the launcher:

```bash
/srv/vms/windows-build/start.sh
```

Do not rerun the file-creation or virtual-disk steps for this error. If another error appears, stop and send that error.

### 6.5 Check the console and SSH forwarding ports

After step 6.4 succeeds, run this one command in **Window 1 — Debian**:

```bash
ss -ltn | grep -E '127\.0\.0\.1:(2222|5905)'
```

**Expected result:** two listening entries, one for `127.0.0.1:2222` and one for `127.0.0.1:5905`. Port 2222 is reserved for future Windows SSH; seeing it now does not mean Windows SSH has been installed yet. Port 5905 provides the VM screen.

If either entry is missing, stop and send the result. If both are present, section 6 is complete. **Keep Window 1 open. Next go to section 7, which explicitly asks you to open Window 3 on the Mac.** Do not run the section 7 tunnel command inside this Debian session.

### What the launcher changes


QEMU's `:5` VNC display is TCP port **5905**. Both VNC and guest SSH forwarding listen only on Debian's loopback interface. Other local Debian users can potentially connect to those TCP listeners, so this assumes your server's local accounts are trusted. VNC requires the separate console password you choose each time you start the VM. macOS Screen Sharing needs this authentication mode; the earlier passwordless launcher caused the Mac connection to stall. The password is set through the private QEMU monitor socket and is not saved in the launcher or a password file. SSH protects the connection over the network, and neither listener is exposed on Wi-Fi or the Internet.

The virtual disk and network card use emulated devices to avoid a separate VirtIO driver installation during the first compatibility trial. If Windows has no network adapter after installation, stop and report that; we can add the official VirtIO driver rather than changing Debian's networking.

There are no changes to Docker networks or website reverse-proxy settings. Windows still uses some shared CPU/RAM/disk resources while running.

## 7. View the installer from the Mac

**Open Window 3 on the Mac** using Command-N in Terminal. This window is reserved for the console tunnel. Run just one of the following alternatives. A blank window after connecting is normal; leave it open. Do not run these commands inside the Debian SSH session:

**Local network:**

```sh
ssh -N -o ExitOnForwardFailure=yes -L 127.0.0.1:5905:127.0.0.1:5905 home-server
```

**Cloudflare / Internet:**

```sh
ssh -N -o ExitOnForwardFailure=yes -L 127.0.0.1:5905:127.0.0.1:5905 home-server-remote
```

On the Mac, use Finder → Go → Connect to Server and enter:

```text
vnc://127.0.0.1:5905
```

When Screen Sharing asks for a password, enter the **console password chosen when starting the VM**, not your Mac password, Debian password, Microsoft account password, or Windows Hello PIN. Keep credentials local.

This opens the VM's virtual screen, not the physical Debian desktop. If Screen Sharing stalls at a disabled password field and the VM was started using an earlier copy of this guide, the old launcher may still offer passwordless VNC. The updated section 6 launcher uses `password=on` and asks for a console password. Ask for help to update/restart that VM safely; do not recreate its disk, firmware or TPM. A maintained QEMU-compatible VNC viewer is another option. Retain the SSH tunnel. Do not change the listener to `0.0.0.0` to fix a viewer issue.

At first boot, the empty disk should fall through to the installer DVD. Press a key when asked to boot the DVD. If it opens the UEFI shell instead, restart the guest and press Escape for the firmware Boot Manager, then choose the virtual DVD drive. Do not reboot Debian.

### If Screen Sharing says to enable Sharing on the remote computer

That is a generic Mac error message. This remote computer is a QEMU Windows VM; do not enable macOS Screen Sharing or Remote Management on your Mac to fix it.

During live diagnosis, the VM was running with password authentication, but the Mac’s local tunnel was no longer listening on port 5905. Reopening the tunnel restored the VNC connection path. Keep Window 3 running until you finish installation. If you closed it, reopen it with one of the section 7 commands, then retry `vnc://127.0.0.1:5905` in Finder.

On 10 November 2026 the tunnel was restored and independently checked: the VM returned its VNC protocol banner and offered password authentication. This validates the connection path; entering the console password remains a user action. Do not recreate/restart the VM for a missing Mac tunnel.

### Windows installation choices

1. Select language and keyboard.
2. Select **Windows 11 Home** or **Windows 11 Pro**, matching the license you will use. Home is sufficient for SSH builds; Pro is not required for this guide.
3. Enter your product key when requested, or follow the installer’s available activation-later option if applicable. Skipping key entry does not grant a free permanent license.
4. Review and accept Microsoft’s terms yourself if you agree.
5. Choose the custom/new Windows installation on the approximately **100 GB virtual disk**.
6. Let Windows restart. Do not press a key to boot the DVD again.
7. Complete the standard Windows setup. Home and Pro for personal use require Internet connectivity and a Microsoft account during initial setup. Sign in yourself; do not share your credentials in chat.
8. Complete Windows Update and reboot the **guest** when asked. Keep Debian running.

Only the newly created virtual disk is exposed to the installer. The real Debian disks are not passed through. If setup reports that TPM, Secure Boot, CPU, or network requirements are not met, stop and send the message; do not use bypass commands.

## 8. Enable Windows SSH using short console commands

**Use the Windows VM screen only for this section and the fingerprint command in 9.1.** The VNC clipboard may not transfer large text blocks. Type each short command separately; after section 9 connects, you can paste the remaining setup blocks normally in Mac Terminal.

The route is **Mac → Debian’s existing SSH → Windows VM’s SSH**. Debian’s SSH is already configured and is not being replaced. Windows still needs its own SSH service to execute Windows commands. Do not enable Remote Desktop or change the server’s public ports for this.

**Where to type now:** inside the installed Windows VM desktop, right-click Start → **Terminal (Admin)**. Approve the prompt and select **Windows PowerShell**. Do not type the displayed prompt (`PS C:\Users\...>`); type only the contents of each command box.

### 8.1 Install Windows OpenSSH Server

Type this single command in the **Windows VM — Administrator PowerShell**:

```powershell
Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0
```

Wait for it to finish. If `RestartNeeded` is `True`, restart only Windows, reopen Administrator PowerShell, then continue. If it reports the capability is already installed, continue without reinstalling.

### 8.2 Make SSH start automatically

Same Windows window:

```powershell
Set-Service sshd -StartupType Automatic
```

### 8.3 Start SSH now

Same Windows window:

```powershell
Start-Service sshd
```

### 8.4 Check SSH

Same Windows window:

```powershell
Get-Service sshd
```

Expected: `Running`. If not, stop and send the error.

### 8.5 Create the local build account with a short command

Use a local account so Windows SSH does not depend on your Microsoft-account login or Windows Hello PIN. In the same **Administrator PowerShell**, type:

```powershell
net user builduser * /add
```

Windows asks for the new password twice. Type it privately; no characters appear. Keep it in your password manager. Do not put a password in the command or send it in chat. If it says `builduser` already exists, stop for review rather than changing its password or deleting it.

### 8.6 Allow this build account to administer the VM

The English Windows ISO used in this guide names the local group `Administrators`. In the same Windows window, type:

```powershell
net localgroup Administrators builduser /add
```

Expected: `The command completed successfully.` This grants administrator access **inside this dedicated Windows build VM**, not on Debian. If Windows was installed in another language and the group is not found, stop and ask for the localized command. Keep website secrets and personal data out of the VM. A limited build account can be introduced after setup.

### 8.7 Enable the installation’s SSH firewall rule

In the same Windows window, type:

```powershell
Set-NetFirewallRule -Name OpenSSH-Server-In-TCP -Enabled True -Profile Any
```

Expected: no error. This temporarily enables Windows’ normal SSH rule on the guest. The QEMU forwarding listener remains **Debian localhost:2222**, so this does not publish SSH to your Wi-Fi or the Internet. We restrict the guest rule to QEMU’s host address in section 9.5. If the named rule is missing, stop and send the error rather than disabling Windows Firewall.

**Stop typing installation blocks into VNC now.** Next, verify the host fingerprint and connect from a fresh Mac Terminal window.

## 9. Connect to Windows from Mac Terminal through Debian

### 9.1 Show the Windows VM’s host fingerprint

One last short command in the **Windows VM — Administrator PowerShell**:

```powershell
ssh-keygen -lf C:\ProgramData\ssh\ssh_host_ed25519_key.pub
```

Keep the fingerprint visible. This is a new Windows VM: its fingerprint is different from Debian’s and from the laptop’s. If `ssh-keygen` is not found, stop for help with its full executable path.

### 9.2 Open a new Mac Terminal window and choose one route

**Where to type:** a fresh **Mac Terminal** window showing your Mac prompt, not `jagdeep@jagdeep` and not Windows PowerShell. Keep the existing console tunnel window open if you still need to view Windows.

**Local network — run only this alternative:**

```sh
ssh -J home-server -p 2222 builduser@127.0.0.1
```

**Cloudflare / Internet — or run this alternative instead:**

```sh
ssh -J home-server-remote -p 2222 builduser@127.0.0.1
```

Compare the host fingerprint with the Windows console before accepting it. If they differ, stop. Enter the **builduser password created in 8.5**, not a Mac/Debian/Microsoft account password or Windows Hello PIN.

If the Mac already has a known-host entry for `[127.0.0.1]:2222` from another machine, do not delete it blindly. Stop so we can give this VM a distinct trusted identity entry.

### 9.3 Start PowerShell in the remote Windows session

The connection is now running Windows commands even though you are typing in Mac Terminal. If you see a Command Prompt such as `C:\Users\builduser>`, type:

```text
powershell.exe -NoProfile
```

A prompt beginning `PS C:\Users\builduser>` confirms Windows PowerShell. **Do not type that prompt itself.** From now on, copy the contents of each Windows code box and paste using **Command-V in this Mac Terminal window**. There is no VNC clipboard dependency.

### 9.4 Verify Secure Boot and TPM over SSH

**Mac Terminal connected to Windows PowerShell:**

```powershell
Confirm-SecureBootUEFI
```

Expected: `True`. Next:

```powershell
Get-Tpm
```

Check `TpmPresent` is `True`. If either command fails, stop and send the actual error. Do not paste PowerShell’s previous error output back as a command. If Windows uses device encryption/BitLocker, save recovery keys privately and preserve the VM’s disk, firmware and TPM state together.

### 9.5 Restrict the guest SSH firewall rule

**Mac Terminal connected to Windows PowerShell.** You can now paste this complete block instead of typing it through VNC:

```powershell
$ErrorActionPreference = 'Stop'
if (-not (Get-NetFirewallRule -Name 'Windows-Build-SSH' -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -Name 'Windows-Build-SSH' `
      -DisplayName 'SSH from QEMU host only' `
      -Direction Inbound -Action Allow -Protocol TCP `
      -LocalPort 22 -RemoteAddress 10.0.2.2 -Profile Any
}
```

**Wait for the `PS` prompt to return.** Output describing `Windows-Build-SSH` with `Enabled : True` and `PrimaryStatus : OK` means the new rule was created.

**Next, copy only this command, press Enter, and wait for the prompt again:**

```powershell
Disable-NetFirewallRule -Name 'OpenSSH-Server-In-TCP'
```

The dedicated rule is created before the default rule is disabled. QEMU normally provides Windows with a `10.0.2.x` address and uses `10.0.2.2` as its virtual host/gateway. No Debian firewall or Docker network changes are required.

**Finally, copy only this connectivity-test command and press Enter.** Do not paste it onto the end of the previous command. Each code box is a separate Terminal submission:

```powershell
Test-NetConnection github.com -Port 443
```

If you accidentally joined the commands and see `A positional parameter cannot be found that accepts argument 'Test-NetConnection'`, the disable command did not complete. Do not recreate the new rule. Run the disable command above by itself, press Enter, wait for the prompt, then run the connectivity test by itself.

Expected: `TcpTestSucceeded : True`. Do not disable Defender or Windows Update to speed up builds.

The remaining Windows setup, key authorization, Git/JDK installation and builds all use this **Mac Terminal → Windows PowerShell SSH session**. VNC is now optional for visual troubleshooting.

Optional computer rename, also from Windows PowerShell over SSH:

```powershell
Rename-Computer -NewName 'WINDOWS-BUILD' -Restart
```

This restarts only the Windows VM and disconnects SSH. Wait for the guest to boot, then reconnect with the same local or Cloudflare command from 9.2 and reopen PowerShell as in 9.3. Debian and its websites stay running. You do not need to sign into the Windows desktop for command-line builds after boot.

## 10. Enable protected-key SSH to the VM

Use the already passphrase-protected Mac key authorized for this server setup; do not copy its private half. The `builduser` account created with the short console command in 8.5 is a local administrator and uses the administrator key file under Windows OpenSSH’s default administrator Match rule. If you prefer a separate limited build account, stop here and hand over; we can configure that instead.

**Mac Terminal — separate window:**

**Local network:**

```sh
scp -o ProxyJump=home-server -P 2222 "$HOME/.ssh/id_ed25519.pub" builduser@127.0.0.1:C:/Users/builduser/autopilot_mac.pub
```

**Cloudflare / Internet:**

```sh
scp -o ProxyJump=home-server-remote -P 2222 "$HOME/.ssh/id_ed25519.pub" builduser@127.0.0.1:C:/Users/builduser/autopilot_mac.pub
```

**Mac Terminal connected to Windows PowerShell over SSH** (section 9). Paste this block into that window, not into VNC:

```powershell
$keyFile = Join-Path $env:ProgramData 'ssh\administrators_authorized_keys'
$publicKey = (Get-Content (Join-Path $env:USERPROFILE 'autopilot_mac.pub') -Raw).Trim()
if ($publicKey -notmatch '^ssh-ed25519\s+[A-Za-z0-9+/]+={0,3}(\s.*)?$') {
    throw 'Expected one Ed25519 public key.'
}
if (Test-Path $keyFile) {
    throw 'An administrator key file already exists. Stop for review; preserve its existing keys.'
}
Set-Content -Path $keyFile -Value $publicKey -Encoding ascii
icacls.exe $keyFile /inheritance:r `
  /grant:r '*S-1-5-32-544:F' '*S-1-5-18:F'
if ($LASTEXITCODE -ne 0) { throw 'Key permissions failed.' }
icacls.exe $keyFile
```

This authorizes a key for the Windows VM's administrator scope, not just one build command. Keep this VM dedicated to building; never place website secrets or personal data inside it. The key stays protected by its Mac passphrase/Keychain. We are not storing the Windows password in a script or disabling password recovery.

### 10.1 Open the Mac SSH configuration file

These edits happen **on your Mac**, not in the Windows PowerShell or Debian SSH session. Leave those sessions alone and open a **new Mac Terminal window** with Command-N. The prompt should identify your Mac, not `builduser@...` or `jagdeep@jagdeep`.

Run this command in that new Mac window:

```sh
nano ~/.ssh/config
```

Nano is a text editor. Use the Down Arrow to reach the end of the file. Keep every existing entry, including `home-server`, `ssh.naikio.in`, and the laptop’s SSH configuration. Add a blank line at the bottom.

### 10.2 Add the two build-VM shortcuts

Copy the complete configuration box below and paste it **at the bottom of the file inside nano**, using Command-V. These are file contents, not shell commands. Do not paste the Markdown backticks. If either `Host home-server-build` or `Host home-server-build-remote` is already present, stop for review instead of adding duplicate entries.

Both shortcuts refer to the same Windows VM. The first goes through local Debian SSH; the second goes through Cloudflare. Add both so you can choose either later:


```sshconfig
Host home-server-build
    HostName 127.0.0.1
    Port 2222
    User builduser
    ProxyJump home-server
    IdentityFile ~/.ssh/id_ed25519
    IdentitiesOnly yes
    ForwardAgent no
    StrictHostKeyChecking yes
    ServerAliveInterval 30
    ServerAliveCountMax 3

Host home-server-build-remote
    HostName 127.0.0.1
    Port 2222
    User builduser
    ProxyJump home-server-remote
    IdentityFile ~/.ssh/id_ed25519
    IdentitiesOnly yes
    ForwardAgent no
    StrictHostKeyChecking yes
    ServerAliveInterval 30
    ServerAliveCountMax 3
```

### 10.3 Save and close nano

1. Press **Control + O** (the letter O, not zero).
2. Nano shows the filename at the bottom. Press **Enter** to save to the same `~/.ssh/config` file.
3. Press **Control + X** to exit nano.
4. You should now be back at your **Mac Terminal prompt**.

Run this one command on the Mac to keep the configuration file private:

```sh
chmod 600 ~/.ssh/config
```

### 10.4 Test from the Mac

Do not run these tests inside Windows or Debian. Use the same Mac Terminal window after exiting nano.

Choose either alias: `home-server-build` uses the local network; `home-server-build-remote` uses Cloudflare. Both refer to the same future Windows VM and therefore must present the same Windows host identity.

Test a fresh key-only connection:

**Local network:**

```sh
ssh -o ControlPath=none -o ControlMaster=no -o BatchMode=yes -o PasswordAuthentication=no -o KbdInteractiveAuthentication=no home-server-build whoami
```

**Cloudflare / Internet:**

```sh
ssh -o ControlPath=none -o ControlMaster=no -o BatchMode=yes -o PasswordAuthentication=no -o KbdInteractiveAuthentication=no home-server-build-remote whoami
```

This must succeed without the Windows password. If the private-key passphrase is needed, unlock it on the Mac with Apple's `ssh-add` command in section 2. Never send the private key or passphrase in chat.

### 10.5 Make new SSH connections open PowerShell automatically — once only

**Where to run this:** your existing Windows SSH window in Mac Terminal. This single command works whether it currently shows Command Prompt (`builduser@JAGDEEP C:\Users\builduser>`) or PowerShell (`PS C:\Users\builduser>`). Do not run it in the Mac’s own shell or Debian.

Copy only the complete line below, paste it, then press Enter. It explicitly starts PowerShell to apply the setting, so no separate shell-switch command is needed:

```powershell
powershell.exe -NoProfile -Command "New-ItemProperty -Path 'HKLM:\SOFTWARE\OpenSSH' -Name DefaultShell -Value 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe' -PropertyType String -Force"
```

Expected: output listing `DefaultShell` and the PowerShell executable path. If you previously got `'New-ItemProperty' is not recognized`, that happened because it was run directly in Command Prompt. Run the complete command above, including `powershell.exe`, instead; do not paste the previous error messages or the prompt.

This changes the default shell for **new SSH sessions on this Windows VM** to Windows PowerShell. It does not change Debian or the laptop, reset passwords, or restart Windows. It requires the build account’s administrator rights. If access is denied, stop and send the error. The current session remains in its existing shell; test with a new Mac Terminal connection in 10.6.

This setting is documented by [Microsoft](https://learn.microsoft.com/en-us/windows-server/administration/openssh/openssh-server-configuration). Updating this guide does not itself apply the setting to the VM.

### 10.6 Your simple connection commands from now on

Open a **new Mac Terminal window**. Run only one alternative:

**Local network:**

```sh
ssh home-server-build
```

**Cloudflare / Internet:**

```sh
ssh home-server-build-remote
```

After step 10.5, either connection should open directly at a Windows PowerShell prompt such as `PS C:\Users\builduser>`. You no longer need to type `powershell.exe -NoProfile` after every login. If a new connection still opens Command Prompt, send its output before continuing.

For the remaining guide, **Windows SSH window** means this Mac Terminal window connected to Windows PowerShell. Paste each Windows code box there, one box at a time. **Do not type the displayed prompt.**

## 11. Install Windows JDK 21 and Git

**Windows SSH window** — connect using `ssh home-server-build` (local) or `ssh home-server-build-remote` (Cloudflare), as explained in 10.6:

```powershell
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
New-Item -ItemType Directory -Force C:\BuildTools | Out-Null
```

### JDK 21 archive, with its published checksum

```powershell
$assets = Invoke-RestMethod `
  'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
$package = @($assets)[0].binary.package
$archive = 'C:\BuildTools\temurin21.zip'
Invoke-WebRequest -UseBasicParsing -Uri $package.link -OutFile $archive
if ((Get-FileHash $archive -Algorithm SHA256).Hash -ne $package.checksum) {
    throw 'JDK checksum mismatch.'
}
if (Test-Path 'C:\BuildTools\jdk21') {
    throw 'JDK directory exists; stop rather than overwrite it.'
}
Expand-Archive -Path $archive -DestinationPath 'C:\BuildTools\jdk21'
$roots = @(Get-ChildItem 'C:\BuildTools\jdk21' -Directory | Where-Object {
    Test-Path (Join-Path $_.FullName 'bin\javac.exe')
})
if ($roots.Count -ne 1) { throw 'Expected one full JDK directory.' }
$jdkHome = $roots[0].FullName
[Environment]::SetEnvironmentVariable('JAVA_HOME', $jdkHome, 'Machine')
$machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
$jdkBin = Join-Path $jdkHome 'bin'
if (($machinePath -split ';') -notcontains $jdkBin) {
    [Environment]::SetEnvironmentVariable('Path', "$jdkBin;$machinePath", 'Machine')
}
$env:JAVA_HOME = $jdkHome
$env:Path = "$jdkBin;$env:Path"
java -version
javac -version
jpackage --version
```

### Git for Windows, official release

```powershell
$release = Invoke-RestMethod -Headers @{'User-Agent'='Windows-Build-Setup'} `
  'https://api.github.com/repos/git-for-windows/git/releases/latest'
$installer = @($release.assets | Where-Object { $_.name -match '^Git-.+-64-bit\.exe$' })
if ($installer.Count -ne 1) { throw 'Expected one Git x64 installer asset.' }
$gitInstaller = 'C:\BuildTools\git-installer.exe'
Invoke-WebRequest -UseBasicParsing -Uri $installer[0].browser_download_url -OutFile $gitInstaller
Get-AuthenticodeSignature $gitInstaller | Format-List Status,SignerCertificate
```

Inspect the signature result before installation. If it is not `Valid`, stop and report it. Then:

```powershell
$process = Start-Process -FilePath $gitInstaller `
  -ArgumentList '/VERYSILENT','/NORESTART','/SP-' -Wait -PassThru
if ($process.ExitCode -ne 0) { throw "Git installer failed: $($process.ExitCode)" }
$env:Path = 'C:\Program Files\Git\cmd;' + $env:Path
& 'C:\Program Files\Git\cmd\git.exe' --version
```

No Android Studio, Chrome, Chrome extension or desktop automation permissions are required inside this build-only VM.

## 12. Preparation: clone Windows source and check Gradle

**Reported checkpoint — 10 November 2026:** the Windows checkout is at `C:\Build\autopilot-cmp`. The owner successfully ran `gradlew.bat --version`: Gradle 9.6.0, Eclipse Adoptium JDK 21.0.13, Windows 11 amd64. The default SSH shell setting was also applied successfully. This confirms Gradle startup, not compilation, tests, or MSI packaging. This preparation step is complete. Continue to section 13 only when preparing the Linux checkout for the first time; otherwise use Part B.


**Windows SSH window** — connect using `ssh home-server-build` (local) or `ssh home-server-build-remote` (Cloudflare), as explained in 10.6:

```powershell
New-Item -ItemType Directory -Force C:\Build | Out-Null
git clone --branch main https://github.com/jagdeepnaik/autopilot-cmp.git C:\Build\autopilot-cmp
if ($LASTEXITCODE -ne 0) { throw 'Clone failed. Do not overwrite an existing checkout.' }
Set-Location C:\Build\autopilot-cmp
.\gradlew.bat --version
if ($LASTEXITCODE -ne 0) { throw 'Gradle startup failed.' }
```

**Windows preparation stops here.** A successful `--version` check confirms the tools start; it does not create an installer. Do not repeat this checkout setup for each build. The actual MSI build command is in [Part B, section 1.6](13_BUILD_WINDOWS_IN_SERVER_VM.md#16-build-the-msi).

## 13. Prepare the Linux build checkout

**Reported checkpoint — 10 November 2026:** the owner successfully cloned GitHub `main` to `/srv/build/autopilot-cmp` on Debian. Do not run the clone command again or delete this checkout. The owner then successfully ran `./gradlew --version`: Gradle 9.6.0, Debian JDK 21.0.13, Linux amd64. Both Gradle startup checks are complete. Linux focused tests and DEB packaging subsequently succeeded; the Windows MSI build was stopped at the owner’s request. Do not repeat this preparation step.


**Debian SSH session:**

```bash
git clone --branch main https://github.com/jagdeepnaik/autopilot-cmp.git /srv/build/autopilot-cmp
cd /srv/build/autopilot-cmp
chmod +x gradlew
java -version
javac -version
jpackage --version
./gradlew --version
```

---

# PREPARATION FINISHED — STOP SETUP HERE

You now have the VM, SSH connections, JDK/Git/Gradle, and separate Windows/Linux checkouts. **Do not start again at section 1 when you want another installer.**

# PART B — REPEATABLE BUILD AND DOWNLOAD

Use the separate **[Build commands guide](27_BUILD_TASKS_QUICK_LINKS.md)** from this point onward:

1. **Windows MSI:** section 1.1 to start the VM if off, otherwise section 1.2 to connect. The build itself is section 1.6.
2. **Linux DEB:** start at section 2.1. The build itself is section 2.5.
3. **Download installers:** section 3, after the build reports success.
4. **Optional tests:** section 4, when validating changes or preparing a release.
5. **Cancel/shut down:** section 5.

The remaining sections of this installation document are operational reference and historical results. For the step-by-step commands you use each time, follow the separate Part B guide above.

## 14. Download installers to the Mac

Create a destination:

**Mac Terminal:**

```sh
mkdir -p "$HOME/Downloads/AutoPilot-builds"
```

Linux installer:

**Local network:**

```sh
scp 'home-server:/srv/build/autopilot-cmp/desktopApp/build/compose/binaries/main-release/deb/*.deb' "$HOME/Downloads/AutoPilot-builds/"
```

**Cloudflare / Internet:**

```sh
scp 'home-server-remote:/srv/build/autopilot-cmp/desktopApp/build/compose/binaries/main-release/deb/*.deb' "$HOME/Downloads/AutoPilot-builds/"
```

Windows installer:

**Local network:**

```sh
scp 'home-server-build:C:/Build/autopilot-cmp/desktopApp/build/compose/binaries/main-release/msi/*.msi' "$HOME/Downloads/AutoPilot-builds/"
```

**Cloudflare / Internet:**

```sh
scp 'home-server-build-remote:C:/Build/autopilot-cmp/desktopApp/build/compose/binaries/main-release/msi/*.msi' "$HOME/Downloads/AutoPilot-builds/"
```

Compare downloaded hashes against the same source artifacts:

```sh
shasum -a 256 "$HOME/Downloads/AutoPilot-builds/"*.msi
```

```sh
shasum -a 256 "$HOME/Downloads/AutoPilot-builds/"*.deb
```

An MSI cannot be installed on the Mac. Transfer it to the Windows laptop for installation/UI/browser testing. The `.deb` is for a compatible Linux computer. These files are unsigned unless a separate signing step is configured.

## 15. Start, monitor and shut down

**For the clearer step-by-step SSH instructions, use [Part B, section 1.1](12_START_STOP_WINDOWS_BUILD_VM.md#11-windows-vm-connect-check-start-monitor-and-stop): connect to Debian, check/start the VM, monitor resources, shut down Windows through SSH, or disconnect without stopping it. Each action identifies the correct Terminal window. The commands below are supplementary reference.**


Start the VM when required, **Debian**:

```bash
/srv/vms/windows-build/start.sh
free -h
uptime
du -h /srv/vms/windows-build/windows11.qcow2
```

Shut Windows down cleanly, **Windows PowerShell over SSH**:

```powershell
Stop-Computer
```

The SSH connection will close. On Debian, QEMU should exit and release the VM's RAM. Check:

```bash
if [ -f /srv/vms/windows-build/qemu.pid ]; then
  ps -p "$(cat /srv/vms/windows-build/qemu.pid)" -o pid,comm,args
fi
ss -ltn | grep -E '127\.0\.0\.1:(2222|5905)' || true
```

Stop builds first. Do not use `kill -9` for normal shutdown, copy the live disk as a backup, or reboot Debian with the VM still writing its disk. The manual launcher intentionally does not provide automatic host-shutdown integration; that can be added after the basic compatibility trial succeeds. Back up the VM disk, `OVMF_VARS_WIN11.fd`, and the entire `tpm2/state` directory together, only after Windows, QEMU and swtpm have stopped. Losing/resetting the TPM or firmware can cause encrypted Windows disks to require a recovery key. Do not paste recovery keys into chat.

The software TPM uses `--terminate` so it exits when QEMU closes its control connection. Its persistent state stays on disk for the next boot. Do not erase that state.

The installation ISO remains attached read-only; the hard disk is first in boot order. We can remove the CD-ROM line after the trial is stable. Removing the ISO from the server while the launcher still references it will prevent VM startup.

Close the Mac's VNC SSH tunnel with Control-C when no longer needed. No physical monitor is needed for subsequent builds.

## First build verification — 10 November 2026

The assistant connected independently to Debian and the Windows VM using the configured SSH shortcuts. Both clean checkouts were at GitHub `main`, commit `9a4a180`. Recent uncommitted Mac UI changes were not part of this server baseline test.

**Linux completed successfully:** focused core automation, DGS study and desktop data-path tests ran: 30 tests, zero failures/errors/skips. `:desktopApp:packageReleaseDeb` produced `/srv/build/autopilot-cmp/desktopApp/build/compose/binaries/main-release/deb/autopilot_1.0.0_amd64.deb`. Package metadata reports `autopilot`, version `1.0.0`, architecture `amd64`.

- First cold build: 8 minutes 56 seconds, 79 executed tasks, including first-time dependency downloads.
- Artifact SHA256: `d0da4da86b92791fdba2ccffd10d0781bce6872a7e95d5d8be8e2e7aab0c0df3`.
- Log: `/srv/build/logs/autopilot-linux-first-build.log`.
- Gradle used two workers, a 2 GB heap and Kotlin compilation in-process. Linux ran at low CPU/I/O scheduling priority; website services were not restarted.

**Windows:** the initial MSI build was stopped at the owner’s request before completion was verified. No successful MSI result is claimed. Use the separate build guide to start a new manual build when wanted.

To seed the Windows first build, only Gradle’s documented portable `modules-2` dependency cache was copied, excluding `*.lock` and `gc.properties`; no credentials, project build outputs or platform-specific transform caches were transferred. Both platforms use Gradle 9.6.0. The archive’s SHA256 was verified after transfer. A temporary HTTP endpoint bound only to Debian localhost was used for the host-to-VM copy, then stopped. The Windows build reads the seeded cache at `C:\BuildCache` through its process-local `GRADLE_RO_DEP_CACHE` setting and writes new dependencies to its usual user cache. This is an optimization, not a requirement to install another service.

Reference: [Gradle dependency-cache portability](https://docs.gradle.org/current/userguide/dependency_caching.html).

## 16. Handover checklist

Send “ready” once these are true:

- Debian packages installed; existing websites still reachable.
- Windows 11 Home or Pro installed and updated; Secure Boot and TPM checks pass.
- You can run `ssh home-server-build whoami` (local), or `ssh home-server-build-remote whoami` (Cloudflare), from the Mac using the protected key.
- Windows reports Java/Javac/Jpackage 21 and a working Git executable.
- Windows checkout is `C:\Build\autopilot-cmp`.
- Linux checkout is `/srv/build/autopilot-cmp`.
- You kept all passwords/private keys local.

If any step fails, send its command and error text; do not proceed with the remaining dependent steps. I can then verify the setup, fix actual compatibility errors, validate MSI/DEB builds, and set up convenient build/download commands. This guide update does not claim that the new Windows 11 VM has been installed or tested yet.

## References

- Microsoft Windows 11 x64 media: https://www.microsoft.com/en-us/software-download/windows11
- Windows 11 setup requirements: https://www.microsoft.com/en-us/windows/windows-11-specifications
- Install Windows OpenSSH Server: https://learn.microsoft.com/en-us/windows-server/administration/openssh/openssh_install_firstuse
- Windows key authorization and ACLs: https://learn.microsoft.com/en-us/windows-server/administration/openssh/openssh_keymanagement
- QEMU networking and local forwarding: https://www.qemu.org/docs/master/system/devices/net.html
- QEMU invocation options: https://www.qemu.org/docs/master/system/invocation.html
- Debian OVMF paths: https://packages.debian.org/trixie/all/ovmf/filelist
- Adoptium archive installation: https://adoptium.net/installation/archives
- Git for Windows: https://git-scm.com/install/windows

- QEMU TPM configuration: https://qemu-project.gitlab.io/qemu/specs/tpm.html
- Debian swtpm options: https://manpages.debian.org/trixie/swtpm/swtpm.8.en.html
