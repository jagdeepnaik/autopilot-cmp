# 20 — Set Up AutoPilot SSH Access to Legion

[All guides](00_INDEX.md) · [Password-free login](21_SET_UP_PASSWORD_FREE_SSH.md) · [SSH basics](18_UNDERSTAND_SSH_AND_KEYS.md)

## PART A — MACHINE PREPARATION — already configured

### A1. Choose the right machine

| Command typed on the Mac | Destination |
| --- | --- |
| `ssh home-legion` | **Physical Legion Windows laptop — use this guide** |
| `ssh home-server` | Debian website/build host over the home network |
| `ssh home-server-remote` | The same Debian host through Cloudflare |
| `ssh home-server-build` | Separate Windows VM through Debian, local route |
| `ssh home-server-build-remote` | The same Windows VM through Cloudflare |

The laptop and VM are different build environments. This document's repeatable workflow uses **`home-legion`**, not either server/VM shortcut.

### A2. Existing Legion setup

- Mac shortcut: `home-legion`; configured address `192.168.1.6`, user `jagde`.
- AutoPilot checkout on Legion: `C:\Users\jagde\Projects\autopilot-cmp`.
- Git and full JDK 21 were installed for the original setup.
- Protected-key SSH and its host fingerprint were verified during that setup. The shortcut was checked in the Mac config for this documentation update; current online status was not retested.
- Existing Mac key filename: `~/.ssh/autopilot_windows_ed25519`; existing pinned-host file: `~/.ssh/autopilot_windows_known_hosts`. These historical filenames identify the already-configured connection, not an app restriction. **Keep their names/contents unchanged for compatibility.** You can build other products using this same connection.
- Use the AutoPilot paths and task names shown below for this project. SSH principles also apply to other projects.

No new account, key, tool installation or clone is required for an existing app. If Git/JDK is missing or the laptop IP changes, troubleshoot that prerequisite before building; do not blindly repeat the original setup.

### A3. Open Windows PowerShell through SSH

In a **fresh Mac Terminal**:

```sh
ssh home-legion
```

If the connection opens Windows Command Prompt, run this **inside that connected window**:

```text
powershell.exe -NoProfile
```

Expected: the prompt starts with `PS C:\Users\jagde>`. We call this the **Windows SSH window** throughout the guide.

If the SSH private key is locked, unlock it in a separate **Mac Terminal**, then reconnect:

```sh
/usr/bin/ssh-add --apple-use-keychain ~/.ssh/autopilot_windows_ed25519
```

Keep passwords and passphrases local. No automated builds or background monitoring are enabled by these instructions.

## 6. Preparing Windows OpenSSH

These steps were performed on the laptop. **Windows — Administrator PowerShell:**

```powershell
Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0
Set-Service sshd -StartupType Automatic
Start-Service sshd
Set-NetFirewallRule -Name OpenSSH-Server-In-TCP `
  -Enabled True -Profile Private -RemoteAddress LocalSubnet
Get-Service sshd
Get-NetConnectionProfile
```

The service reported `Running`, and Wi-Fi was `Private`. The rule allows SSH from the local subnet on private networks. It does not limit access to the Mac alone; optionally narrow it to the Mac's verified address when stable addressing is available. Do not expose port 22 through the router merely to use the same Wi-Fi.

**Windows — PowerShell, to identify the machine:**

```powershell
Get-CimInstance Win32_OperatingSystem |
  Select-Object Caption, Version, OSArchitecture
whoami
Get-NetIPAddress -AddressFamily IPv4 |
  Where-Object {
    $_.IPAddress -notlike '127.*' -and
    $_.IPAddress -notlike '169.254.*'
  } |
  Select-Object InterfaceAlias, IPAddress
```

The server configuration is normally `C:\ProgramData\ssh\sshd_config`. Configuration edits require appropriate Windows permissions and usually an `sshd` restart. None are needed just to repeat the verified workflow. See [Microsoft OpenSSH installation](https://learn.microsoft.com/en-us/windows-server/administration/openssh/openssh_install_firstuse).


## 7. Verifying the Windows host fingerprint

Before trusting the connection, we compared the server public-key fingerprint using two independent views.

**Windows — PowerShell on the laptop itself:**

```powershell
& "$env:WINDIR\System32\OpenSSH\ssh-keygen.exe" `
  -lf "C:\ProgramData\ssh\ssh_host_ed25519_key.pub"
```

The explicit executable path worked after the shorter command appeared unresponsive. Recorded fingerprint:

```text
SHA256:gdhYF7ebI/XYGQmc01TUolmuf8kxlcZMpvE562zhkLc
```

**Mac — Terminal:**

```sh
ssh-keyscan -T 5 -t ed25519 192.168.1.6 > /tmp/autopilot-windows-hostkey
ssh-keygen -lf /tmp/autopilot-windows-hostkey
```

We compared the result with the laptop's output before using that file. `ssh-keyscan` collects whatever key answers at an address; scanning alone does not establish trust. A matching fingerprint from the actual laptop is the important check.

Future connections use `StrictHostKeyChecking=yes` and this verified known-hosts file. If the fingerprint changes, investigate a reinstall, regenerated host keys, a changed address, or an unexpected machine. Do not automatically discard the old identity and accept a replacement.


## 10. Configuring the SSH client on the Mac

The completed setup uses a private directory rather than `/tmp`. The commands below show how it was configured.

**Optional — Mac Terminal:**

```sh
mkdir -p ~/.ssh/control
chmod 700 ~/.ssh ~/.ssh/control
```

Preserve the verified host entry in `~/.ssh/autopilot_windows_known_hosts`. If that destination does not exist, copy the verified temporary file there. If it exists, review and merge rather than overwrite it. If the temporary file is gone, repeat the independent fingerprint verification first.

Add this entry to `~/.ssh/config` **after** configuring the key in section 9:

```sshconfig
Host home-legion
    HostName 192.168.1.6
    User jagde
    IdentityFile ~/.ssh/autopilot_windows_ed25519
    IdentitiesOnly yes
    AddKeysToAgent yes
    UseKeychain yes
    ForwardAgent no
    StrictHostKeyChecking yes
    UserKnownHostsFile ~/.ssh/autopilot_windows_known_hosts
    ServerAliveInterval 30
    ServerAliveCountMax 3
    ControlMaster auto
    ControlPath ~/.ssh/control/%C
    ControlPersist 30m
```

`%C` gives the control socket a connection-specific name. Protect `~/.ssh/config` with `chmod 600 ~/.ssh/config`. With the completed setup, `ssh home-legion whoami` replaces the long command. The alias is local to the Mac; it does not rename Windows or alter DNS. See [ssh_config](https://man.openbsd.org/ssh_config).


## 11. Running PowerShell from the Mac

SSH starts `powershell.exe` on Windows. This does not require WinRM, a PowerShell remoting endpoint, or extra ports such as 5985. We used Windows PowerShell 5.1, not an assumed PowerShell 7 installation.

Commands cross several parsers: the Mac shell, SSH command handling, and Windows PowerShell. To avoid losing quotes, backslashes, and `$env` variables, we encoded the PowerShell program as UTF-16LE Base64 and passed it as one command argument. Base64 is **encoding, not encryption**; SSH protects the network traffic.

**Mac — Terminal; copyable example using the existing temporary master:**

```sh
python3 <<'PY'
import base64
import subprocess

script = r'''
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-Location (Join-Path $env:USERPROFILE 'Projects\autopilot-cmp')
git log -1 --oneline
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
'''
encoded = base64.b64encode(script.encode('utf-16le')).decode('ascii')
command = (
    'powershell.exe -NoProfile -NonInteractive -OutputFormat Text '
    '-EncodedCommand ' + encoded
)
subprocess.run([
    'ssh', '-S', '/tmp/autopilot-windows-ssh',
    '-o', 'BatchMode=yes',
    '-o', 'StrictHostKeyChecking=yes',
    '-o', 'UserKnownHostsFile=/tmp/autopilot-windows-hostkey',
    'jagde@192.168.1.6', command,
], check=True)
PY
```

The quoted heredoc prevents the Mac shell expanding PowerShell variables. Python passes an argument array without `shell=True`. `-NoProfile` avoids personal startup scripts; `-NonInteractive` prevents invisible prompts. Native tools require checking `$LASTEXITCODE`; PowerShell's `$ErrorActionPreference='Stop'` does not replace that check. Some PowerShell error/progress output may still appear as CLIXML: inspect the error and exit code rather than treating formatting alone as a failure. See [powershell.exe parameters](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_powershell_exe?view=powershell-7.4).

### Process-only script permission

Windows initially blocked the project's `.ps1` helpers. With explicit user approval, we used:

```text
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File <script-path>
```

For encoded helper invocations, `-ExecutionPolicy RemoteSigned` was likewise added to that PowerShell process. This does not set a permanent machine-wide policy or change the registry policy. RemoteSigned allows local unsigned scripts while applying signature requirements to scripts marked as downloaded. Organization policies can take precedence. We did not use a blanket Bypass setting or remove download markings to evade policy. See [execution-policy scopes](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_execution_policies?view=powershell-5.1).
