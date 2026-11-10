# 11 — Set Up Password-Free SSH with a Protected Key

[All guides](00_INDEX.md) · [SSH setup](10_SET_UP_WINDOWS_SSH.md) · [Key explanations](15_UNDERSTAND_SSH_AND_KEYS.md)

## 8. Password authentication and connection sharing

Initially, unattended authentication failed because no user key had been authorized. The user authenticated personally in Mac Terminal with the Windows **account password**, not a Hello PIN. The password was not sent in chat or stored in the project.

**Mac — Terminal; verified connection command:**

```sh
ssh -M -S /tmp/autopilot-windows-ssh \
  -o ControlPersist=30m \
  -o ServerAliveInterval=30 \
  -o ServerAliveCountMax=3 \
  -o UserKnownHostsFile=/tmp/autopilot-windows-hostkey \
  -o StrictHostKeyChecking=yes \
  -N jagde@192.168.1.6
```

A blank terminal after authentication is normal: `-N` requests no remote command. Leave it open and keep both laptops awake.

- `-M` creates a master connection; `-S` names its local control socket.
- Later SSH clients can open channels over that authenticated transport.
- `ControlPersist=30m` controls background persistence after clients exit; it is not a fixed 30-minute connection lifetime.
- Keepalives detect an unresponsive connection. They do not prevent laptop sleep or repair a lost Wi-Fi link.

**Mac — Terminal; reuse and inspect:**

```sh
ssh -S /tmp/autopilot-windows-ssh \
  -o BatchMode=yes \
  -o UserKnownHostsFile=/tmp/autopilot-windows-hostkey \
  -o StrictHostKeyChecking=yes \
  jagde@192.168.1.6 whoami

ssh -S /tmp/autopilot-windows-ssh -O check jagde@192.168.1.6
```

`BatchMode=yes` prevents a hidden password prompt. If the master disappears and there is no usable key, the command fails instead of waiting for input. Reusing a password-authenticated connection is **not permanent password-free authentication**. `/tmp` files may disappear on restart. See [OpenSSH client configuration](https://man.openbsd.org/ssh_config).


## 9. Password-free login with protected keys

### Current setup work — 10 November 2026

The owner has authorized a passphrase-protected, dedicated Ed25519 key saved through Apple's Keychain integration. The local helper is `private/misc/setup-windows-ssh-key.command`. Run it in Mac Terminal:

```sh
bash '/Users/jagdeep/Projects/AndroidStudio/AutoPilot/private/misc/setup-windows-ssh-key.command'
```

It preserves existing keys, rejects an unencrypted dedicated key, asks for the passphrase locally, calls Apple's `ssh-add --apple-use-keychain`, verifies the pinned Windows host fingerprint, appends only the public key to the administrator authorization file, checks its permissions, preserves existing SSH configuration, and tests a fresh key-only connection with password fallback disabled. The Windows account is an administrator and the server's default administrator Match rule uses the shared authorization file; the owner explicitly approved that protected-key scope. No Windows password is stored by the helper.

**Completed and independently verified:** the owner ran the helper and entered the credentials locally. A fresh `ssh` connection with `ControlPath=none`, `ControlMaster=no`, `BatchMode=yes`, `PasswordAuthentication=no`, and `KbdInteractiveAuthentication=no` returned `jagdeep\jagde`. The dedicated private key rejects an empty passphrase and its file permissions deny group/other access. The previous password-authenticated master is no longer needed.

Daily command:

```sh
ssh home-legion whoami
```

The dedicated user public-key fingerprint is `SHA256:pN8wpitqFJAmbilyXtM07XzXFLHrumzVKnXau1QIBfk` (different from the Windows host fingerprint).

The private key is `~/.ssh/autopilot_windows_ed25519`; its public half is the `.pub` file. The pinned server identity is `~/.ssh/autopilot_windows_known_hosts`. Connection-sharing sockets are under `~/.ssh/control/`. The SSH alias lives in `~/.ssh/config`. The helper called Apple's Keychain-enabled `ssh-add`; an unlocked Keychain/agent enables repeated logins without retyping credentials. A locked Keychain or a new login session can require local unlocking. No Windows password was saved.


This setup is now completed and verified. The detailed steps below remain a reference for rebuilding it. “Password-free” means signing in with a user key while keeping its private file protected by a passphrase and the Mac's Keychain/agent.

### 9.1 Generate and transfer only the public key

Generate the dedicated key as in section 5. **Mac — Terminal:**

```sh
/usr/bin/pbcopy < ~/.ssh/autopilot_windows_ed25519.pub
```

Paste the single public-key line on Windows. It begins with `ssh-ed25519`, followed by key data and an optional comment. Never paste a line beginning `-----BEGIN OPENSSH PRIVATE KEY-----`.

### 9.2 Choose the correct Windows authorization file

Windows OpenSSH normally distinguishes these cases:

| Account | Default public-key authorization location |
| --- | --- |
| Ordinary non-administrator user | `%USERPROFILE%\.ssh\authorized_keys` |
| Account in the Administrators group | `%ProgramData%\ssh\administrators_authorized_keys`, under the default administrator Match rule |

Check the actual `sshd_config` before assuming which rule applies. Running a terminal elevated is not the test of account membership. **Windows — PowerShell:**

```powershell
[System.Security.Principal.WindowsIdentity]::GetCurrent().Groups.Value `
  -contains 'S-1-5-32-544'
```

The administrator authorization file can affect multiple administrator accounts matched by that rule. Review that scope before adding a key. A dedicated ordinary account is a possible future least-privilege arrangement, but was not created for this project. See [Windows key management](https://learn.microsoft.com/en-us/windows-server/administration/openssh/openssh_keymanagement).

### 9.3 Append without losing existing keys

**Windows — PowerShell as the target ordinary user:**

```powershell
$keyDirectory = Join-Path $env:USERPROFILE '.ssh'
$keyFile = Join-Path $keyDirectory 'authorized_keys'
New-Item -ItemType Directory -Force -Path $keyDirectory | Out-Null
$publicKey = (Read-Host 'Paste the single public-key line').Trim()
if ($publicKey -notmatch '^ssh-ed25519\s+[A-Za-z0-9+/]+={0,3}(\s.*)?$') {
    throw 'Expected one Ed25519 public-key line.'
}
if (Test-Path $keyFile) {
    Copy-Item $keyFile ($keyFile + '.backup-' + (Get-Date -Format yyyyMMddHHmmss))
}
$existing = if (Test-Path $keyFile) { @(Get-Content $keyFile) } else { @() }
if ($existing -notcontains $publicKey) {
    if ((Test-Path $keyFile) -and (Get-Item $keyFile).Length -gt 0) {
        Add-Content -Path $keyFile -Value '' -Encoding ascii
    }
    Add-Content -Path $keyFile -Value $publicKey -Encoding ascii
}
icacls.exe $keyDirectory
icacls.exe $keyFile
```

Review ownership and permissions: another ordinary user must not be able to replace your authorization file. Use the [Windows OpenSSH permission guidance](https://github.com/PowerShell/Win32-OpenSSH/wiki/Security-protection-of-various-files-in-win32-openssh) for the account's exact ACLs. Do not run a blanket recursive permission reset over your profile.

For an administrator account, use Administrator PowerShell and set `$keyFile` to `Join-Path $env:ProgramData 'ssh\administrators_authorized_keys'` instead, then apply the append/backup logic to that file. Microsoft's recommended administrator-file ACL uses Administrators and SYSTEM:

```powershell
icacls.exe $keyFile /inheritance:r `
  /grant:r '*S-1-5-32-544:F' '*S-1-5-18:F'
icacls.exe $keyFile
```

The SID form avoids localized group-name differences. This removes inherited permissions and sets those grants; it does **not** remove unrelated, pre-existing explicit grants. Review and correct extra entries individually. Preserve existing authorized keys and their owners' access.

### 9.4 Unlock the Mac key using Keychain

**Mac — Terminal, using Apple's supplied ssh-add:**

```sh
/usr/bin/ssh-add --apple-use-keychain ~/.ssh/autopilot_windows_ed25519
/usr/bin/ssh-add -l
```

Enter the key passphrase locally. Apple's integration can save it in Keychain; it is not a plaintext Windows password file. A locked Keychain, a new session, or a hardware-backed key can still require user interaction. A Homebrew `ssh-add` may not support this Apple option. See [Apple Keychain integration](https://developer.apple.com/library/archive/technotes/tn2449/_index.html) and [Apple ssh-add option compatibility](https://docs.github.com/en/authentication/troubleshooting-ssh/error-ssh-add-illegal-option----apple-use-keychain).

### 9.5 Prove that a fresh connection uses the key

Do not test only through the old master: that could succeed using its earlier password authentication.

**Mac — Terminal, after installing the public key:**

```sh
ssh -o ControlPath=none -o ControlMaster=no \
  -o PasswordAuthentication=no \
  -o KbdInteractiveAuthentication=no \
  -o PreferredAuthentications=publickey \
  -o IdentitiesOnly=yes \
  -i ~/.ssh/autopilot_windows_ed25519 \
  -o UserKnownHostsFile=/tmp/autopilot-windows-hostkey \
  -o StrictHostKeyChecking=yes \
  jagde@192.168.1.6 whoami
```

A successful response proves fresh user-key authentication. Keep an existing recovery route while testing. Disabling server password authentication is a separate hardening decision; it is not necessary to achieve key-based login and has not been done here.
