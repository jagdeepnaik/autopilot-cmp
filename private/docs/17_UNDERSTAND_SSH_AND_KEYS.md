# 17 — Understand SSH Connections and Keys

[All guides](00_INDEX.md)

## 1. What this setup does

The Mac remains the development computer. The Windows laptop provides a real Windows environment for building the Windows installer and running the Windows app. SSH carries commands and debugger traffic between them.

There are three separate functions:

- **Remote commands:** the Mac asks Windows to run PowerShell, Git, Gradle, tests, and packaging.
- **Interactive app launch:** a Windows scheduled task starts AutoPilot on the signed-in user's desktop.
- **Remote debugging:** the Mac debugger controls the Windows JVM through a private SSH tunnel.

SSH does not show the Windows screen. A debugger can inspect application threads, variables, and execution; it does not operate the Windows desktop or Chrome interface. The user confirmed the app window directly on the laptop.


## 2. The actual computers and verified state

| Item | Recorded value |
| --- | --- |
| Mac checkout | `/Users/jagdeep/Projects/AndroidStudio/AutoPilot` |
| Windows edition | Windows 11 Home Single Language |
| Windows version | `10.0.26200`, 64-bit |
| Windows processor architecture | AMD x64 |
| Windows computer/account | `JAGDEEP\jagde` |
| Current Mac SSH shortcut | `ssh home-legion` |
| SSH login | `jagde@192.168.1.6` |
| Windows Wi-Fi address | `192.168.1.6` |
| Network | Same private Wi-Fi, `etHome` |
| Windows checkout | `C:\Users\jagde\Projects\autopilot-cmp` |
| Windows PowerShell | 5.1 |
| Windows JDK | Microsoft OpenJDK 21.0.13 |
| JDK directory | `C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot` |
| Git executable | `C:\Program Files\Git\cmd\git.exe` |
| Verified code revision | `9a4a180` on `main` |

An address such as `192.168.1.6` is private to the local network. DHCP can change it. Recheck the address after network changes and verify the host identity again when necessary. Neither laptop needs a public IP or router port forwarding for this setup.


## 3. SSH fundamentals

SSH means Secure Shell. On the Mac, `ssh` is the **client**. On Windows, the `sshd` service is the **server**, normally listening on TCP port 22.

A connection proceeds conceptually as follows:

1. The client reaches the server over TCP.
2. Both negotiate supported protocol algorithms.
3. A key-exchange algorithm establishes shared secret material.
4. The server proves possession of its host private key. The client checks the corresponding known host identity.
5. Both derive encryption and integrity keys for the connection.
6. The user authenticates, for example with a password or a signature from a user private key.
7. The authenticated connection opens channels for commands, file transfer, or forwarding.
8. These channels share the encrypted transport until it closes.

Long-term public/private keys establish identity. Efficient symmetric session keys protect the bulk traffic. Modern ephemeral key exchanges can provide forward secrecy: later theft of a long-term identity key should not, by itself, decrypt previously recorded sessions. The exact algorithms depend on negotiation; this project did not record the negotiated cipher suite. See [SSH architecture](https://www.rfc-editor.org/rfc/rfc4251) and [transport protocol](https://datatracker.ietf.org/doc/html/rfc4253).

A command channel runs under the Windows account's permissions. SSH is not an application sandbox. It also is not a whole-device VPN: only commands and explicitly forwarded connections use it.


## 4. Host keys, user keys, and session keys

| Item | Purpose | Stored where in this setup |
| --- | --- | --- |
| Server host private key | Proves which Windows server answered | Windows, `C:\ProgramData\ssh\ssh_host_ed25519_key` |
| Server host public key | Lets clients recognize that server | Windows `.pub` file; trusted copy in Mac known-hosts file |
| User private key | Lets the Mac prove the user's identity without a login password | Mac `~/.ssh/autopilot_windows_ed25519`, passphrase-protected |
| User public key | Authorizes the corresponding user private key | Windows administrator authorization file, under the verified Match rule |
| Session keys | Encrypt and protect one connection's traffic | Negotiated in memory |
| Fingerprint | Short hash used to compare a public key | Displayed for human verification |
| Control socket | Lets local commands reuse an authenticated connection | Mac `/tmp/autopilot-windows-ssh` |

A **public key** may be shared. A **private key** must remain private. Possessing the public key does not practically allow deriving a properly generated private key.

A key **passphrase** encrypts the private-key file at rest. It is distinct from the Windows account password and from a Windows Hello PIN. An SSH agent can perform signatures using an unlocked key, so repeated logins do not require typing its passphrase.

`known_hosts` answers “is this the expected server?” `authorized_keys` answers “may this user key log in?” They serve opposite sides of authentication. Neither is a password database. MSI signing certificates, Git signing keys, and Chrome extension identities are separate mechanisms.


## 5. Key algorithms and key generation

**Ed25519** is a suitable choice here because both computers support it. RSA can also be used with modern SHA-2 signatures, commonly with a 3072-bit or larger key when a compatibility requirement calls for it. RSA key support and the obsolete SHA-1 `ssh-rsa` signature algorithm are different matters. ECDSA is another supported family. Hardware-backed `*-sk` keys require compatible security keys and software; SSH certificates are useful for centrally managed fleets. Avoid obsolete DSA and SSH protocol version 1.

Generation uses secure randomness to create a private value and derives the public value mathematically. It is not generated from your account password. Algorithm key sizes are not directly comparable: Ed25519 does not take an RSA-style `-b 3072` setting. See [ssh-keygen](https://man.openbsd.org/ssh-keygen).

**Optional — Mac Terminal, only when ready to configure user keys:**

```sh
mkdir -p ~/.ssh
chmod 700 ~/.ssh
# First check that neither target file already exists.
ls -l ~/.ssh/autopilot_windows_ed25519*
ssh-keygen -t ed25519 -a 100 \
  -f ~/.ssh/autopilot_windows_ed25519 \
  -C "Mac-to-AutoPilot-Windows"
```

If `ls` says no matching file, that is expected for a new key. If a file exists, choose a different name; do not overwrite it. Enter a strong passphrase when prompted. The command produces a private file and a `.pub` public file. `-a 100` increases passphrase-derivation work for protecting the private file; it does not change network encryption strength.

```sh
chmod 600 ~/.ssh/autopilot_windows_ed25519
chmod 644 ~/.ssh/autopilot_windows_ed25519.pub
ssh-keygen -lf ~/.ssh/autopilot_windows_ed25519.pub
```

Never send the private file to Windows, put it in Git, or paste it into a chat. The `.pub` file is the one Windows needs.


## 23. Security and operational boundaries

- Keep the private key on the Mac, passphrase-protected, with restrictive permissions. Public keys are shareable; private keys and account passwords are not.
- Saving a passphrase in macOS Keychain relies on that Mac account's security. An unlocked agent can authorize signatures; protect access to the account and control socket.
- Keep host verification enabled. An IP address alone is not server identity.
- Keep debugger listeners bound to loopback and carry them through SSH. No public JDWP port or router forwarding is needed.
- Leave agent forwarding disabled unless there is a specific reviewed need. Windows does not need your Mac agent forwarded to build this project.
- SSH grants the logged-in Windows account's authority. Administrator account access has broader consequences than a limited app process.
- Encoded commands can contain readable secrets after decoding. Do not put passwords or private keys in them or in logs.
- A scheduled task with an interactive principal launches on the desktop; it does not provide remote screen control, bypass login, or guarantee background course operation.
- The release installer and the development debug launch are different outputs. A successful build and breakpoint are not proof of every production workflow.
- Keep personal databases, question banks, credentials, host details, and this guide out of the public push. The completed Windows setup did not transfer those personal files.

### Related Debian home-server Internet access

The Debian machine has a direct LAN shortcut, `ssh home-server`, and an existing Cloudflare SSH endpoint, `ssh home-server-remote`. The latter invokes `/opt/homebrew/bin/cloudflared access ssh --hostname %h` from the Mac SSH configuration. Cloudflare browser authentication is separate from the server's SSH-key authentication.

On 10 November 2026, after the owner completed Cloudflare Access sign-in, independent commands through the LAN and Cloudflare routes both succeeded with strict host-key checking and password prompts disabled. The hostname, Linux username, machine-identity hash and SSH host-key fingerprint matched exactly, confirming the same Debian destination. This verified the public Cloudflare route from the current network, rather than a separate mobile/external-network test. The complete commands, route-specific file copies, future Windows VM jump commands, and verification status are maintained in [the Debian build-server installation guide](22_SET_UP_HOME_SERVER_AND_WINDOWS_VM.md#internet-access-through-cloudflare).


## 24. References

Official references used for the principles and commands:

1. [SSH protocol architecture — RFC 4251](https://www.rfc-editor.org/rfc/rfc4251)
2. [SSH transport protocol — RFC 4253](https://datatracker.ietf.org/doc/html/rfc4253)
3. [SSH user authentication — RFC 4252](https://www.rfc-editor.org/rfc/rfc4252)
4. [OpenSSH ssh-keygen manual](https://man.openbsd.org/ssh-keygen)
5. [OpenSSH client configuration manual](https://man.openbsd.org/ssh_config)
6. [Microsoft: install and first use OpenSSH](https://learn.microsoft.com/en-us/windows-server/administration/openssh/openssh_install_firstuse)
7. [Microsoft: Windows OpenSSH key management](https://learn.microsoft.com/en-us/windows-server/administration/openssh/openssh_keymanagement)
8. [Win32-OpenSSH file permission protection](https://github.com/PowerShell/Win32-OpenSSH/wiki/Security-protection-of-various-files-in-win32-openssh)
9. [Apple: OpenSSH and Keychain configuration](https://developer.apple.com/library/archive/technotes/tn2449/_index.html)
10. [Apple ssh-add option compatibility](https://docs.github.com/en/authentication/troubleshooting-ssh/error-ssh-add-illegal-option----apple-use-keychain)
11. [Microsoft: powershell.exe command-line options](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_powershell_exe?view=powershell-7.4)
12. [Microsoft: execution policies](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_execution_policies?view=powershell-5.1)
13. [Microsoft: scheduled-task principals](https://learn.microsoft.com/en-us/powershell/module/scheduledtasks/new-scheduledtaskprincipal?view=win10-ps)
14. [Oracle: JVM debugger connection options](https://docs.oracle.com/en/java/javase/21/docs/specs/jpda/conninv.html)
15. [Gradle: JavaExec debugging](https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/JavaExec.html)
16. [Gradle: daemon JVM selection](https://docs.gradle.org/current/userguide/gradle_daemon.html)
17. [JetBrains: remote JVM debugging](https://www.jetbrains.com/help/idea/tutorial-remote-debug.html)
18. [Compose Multiplatform: native distributions](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)

The verified machine details, hashes, script behavior, and test results above come from this project's setup session and repository, rather than a generic installation example.
