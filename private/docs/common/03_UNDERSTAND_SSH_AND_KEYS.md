# 03 — SSH Tutorial: Theory, Commands, Keys and Configuration

[All documentation](../00_INDEX.md) · [Common guides](00_INDEX.md) · [Project machine guides](../project/00_INDEX.md)

This guide teaches SSH independently of any project. Commands marked **client** run on the
computer you connect from; **server** commands run on the destination. Example addresses,
users and aliases are placeholders. Use your project's machine guide for actual values.

**On this page:**
[1 Basics](#1-what-ssh-does) · [2 Communication](#2-how-the-connection-works) ·
[3 Addresses](#3-addresses-ports-and-localhost) · [4 Files](#4-files-and-who-owns-them) ·
[5 First connection](#5-your-first-connection) · [6 Keys](#6-public-key-authentication) ·
[7 Password-free use](#7-password-free-use-and-the-agent) ·
[8 Config](#8-understanding-sshconfig) · [9 Config examples](#9-config-examples) ·
[10 Options](#10-command-options-explained) · [11 Remote commands](#11-remote-commands-and-quoting) ·
[12 Transfers](#12-copying-files) · [13 Jump hosts](#13-jump-hosts-and-vm-connections) ·
[14 Tunnels](#14-port-forwarding-and-tunnels) · [15 Reuse](#15-connection-reuse-and-keepalives) ·
[16 Server setup](#16-server-setup-and-administration) ·
[17 Troubleshooting](#17-troubleshooting-in-order) · [18 Recipes](#18-everyday-recipes) ·
[19 Reference](#19-official-reference) ·
[20 Verify key-only access](#20-verify-key-only-and-client-restricted-access)

## 1. What SSH does

SSH means Secure Shell. It carries a remote terminal, commands and supported file transfers
over an encrypted connection. The program `ssh` is the client; `sshd` is the server service.
Installing the client alone does not enable incoming SSH connections.

A terminal session acts as the remote account. For example, after connecting from a Mac to
Debian, `pwd`, `ls` and `sudo` execute on Debian. `exit` returns to the previous shell. If you
connect again from that remote shell, the new connection originates there.

SSH does not display a desktop. GUI applications, screen sharing, remote debuggers and SSH
are different tools. Starting an app through SSH may run it in a session without a visible
desktop, particularly on Windows.

## 2. How the connection works

```text
Client                         Server
Resolve destination address
Open TCP connection ----------> SSH listener, usually port 22
Exchange protocol and algorithm preferences
Perform key exchange; authenticate server host key
Verify expected server identity using known_hosts
Authenticate remote user -----> Check allowed authentication methods and user key
Open encrypted channels <----> Shell, commands, SFTP or forwarded TCP traffic
```

Key exchange establishes shared secrets. The host key identifies the server. User
authentication establishes which account may log in. These are separate steps. A public-key
login uses a signature to prove possession of a private key; the private key stays on the
client. Symmetric session keys protect the traffic. Ephemeral key exchange can provide
forward secrecy against later theft of long-term keys. Specific algorithms depend on the
versions and policies at both ends. [Protocol architecture](https://www.rfc-editor.org/rfc/rfc4251).

Encryption begins after transport negotiation; the initial protocol identification is visible.
Encryption protects traffic in transit, while the logged-in account's permissions govern
what commands can do. A firewall, routing, authentication and filesystem permissions each
control a different part of the connection.

## 3. Addresses, ports and localhost

| Term | Meaning | Example |
| --- | --- | --- |
| Hostname | A name resolved to a network address | `build.example.net` |
| IP address | Destination network address | `192.168.1.50` |
| Private address | Reachable through an appropriate local/private network | `192.168.x.x`, `10.x.x.x`, `172.16.x.x`–`172.31.x.x` |
| Port | Service endpoint on a machine | SSH commonly uses `22` |
| `localhost` / `127.0.0.1` | The machine making that particular connection | Mac from Mac; jump server from a jump server |
| SSH alias | A client configuration label | `build-linux` |

An alias need not exist in DNS. `Host build-linux` can map it to an IP in your client config.
DHCP may change an IP; it does not change a correctly preserved server host key. A VM's NAT
address may be inaccessible from another computer, requiring a host port forward or jump host.

## 4. Files and who owns them

| File | Location/owner | Purpose |
| --- | --- | --- |
| `~/.ssh/config` | Client user | Aliases and connection preferences |
| `id_ed25519_build` | Client user | Private user key; protect this file |
| `id_ed25519_build.pub` | Client; copied to server | Public user key used to authorize login |
| `known_hosts` | Client user | Previously trusted server public keys |
| `authorized_keys` | Remote account, usually `~/.ssh/` | Public user keys permitted to authenticate |
| `ssh_host_*_key` | Server administrator | Private server identity keys |
| `sshd_config` | Server administrator | Incoming connection policy |
| `control/` | Client user, optional | Local sockets for connection reuse |

`known_hosts` asks “is this the server I intended?” `authorized_keys` asks “is this user key
allowed to log in?” Neither file stores account passwords.

On macOS/Linux, `~` means the home of the current account and leading-dot folders are hidden.
Windows user configuration normally lives under `%USERPROFILE%\.ssh`; Windows server
configuration normally lives under `C:\ProgramData\ssh`.

## 5. Your first connection

**Client, using example values:**

```sh
ssh alex@192.168.1.50
ssh -p 2222 alex@192.168.1.50
```

The first form uses the default SSH port; `-p 2222` changes it. `alex` must be an account on
the destination. It is not necessarily the client computer's username.

On first contact, compare the offered host-key fingerprint with a trusted value obtained
through the server console or administrator. Accept it only after verification. Subsequent
connections compare against the stored key. `ssh-keyscan` collects a key but does not prove
who supplied it. A changed-key warning after reinstalling a machine requires verification
before removing its old trusted entry.

After login:

```sh
hostname
whoami
pwd
exit
```

These identify the remote computer, account and directory, then close its shell. A different
terminal prompt is a useful clue, but `hostname` and `whoami` are clearer checks.

## 6. Public-key authentication

Create a key pair **on the client**, using a fresh filename:

```sh
mkdir -p ~/.ssh
chmod 700 ~/.ssh
ssh-keygen -t ed25519 -a 100 -f ~/.ssh/id_ed25519_build -C "laptop-to-build"
```

| Part | Meaning |
| --- | --- |
| `-t ed25519` | Selects the key type |
| `-a 100` | Increases private-file passphrase derivation work |
| `-f ...` | Selects the key filename |
| `-C ...` | Adds a descriptive public-key comment |

Choose another filename if it already exists. Enter a passphrase when prompted. The command
creates the private file and its `.pub` counterpart. The comment is a label, not a username
or authorization rule. `-a` changes protection of the saved private file, not network encryption.

```sh
chmod 600 ~/.ssh/id_ed25519_build
chmod 644 ~/.ssh/id_ed25519_build.pub
ssh-keygen -lf ~/.ssh/id_ed25519_build.pub
```

`600` permits only the owner to read/write; `644` makes the public file readable by others.
The last command prints its fingerprint. Keep the private file on the client; install only
the public file on the server. [Key utility reference](https://man.openbsd.org/ssh-keygen).

For a Linux server, if `ssh-copy-id` is installed on your client:

```sh
ssh-copy-id -i ~/.ssh/id_ed25519_build.pub alex@192.168.1.50
```

This normally requires an existing login method once. Otherwise append the public key as
one line in the remote account's `~/.ssh/authorized_keys`. Preserve existing keys. On the
server, typical owner permissions are `700` for `.ssh` and `600` for `authorized_keys`, with
correct ownership of both. Permissions alone cannot fix a key installed for the wrong user.

For Windows, ordinary accounts normally use their profile's `authorized_keys`. The default
administrators Match rule commonly uses `C:\ProgramData\ssh\administrators_authorized_keys`;
its ACL must be restricted appropriately. Inspect the actual server rule before choosing
the file. Windows Hello PINs and SSH passwords are different. Follow the
[Windows key setup guide](https://learn.microsoft.com/en-us/windows-server/administration/openssh/openssh_keymanagement)
and the project's [Windows setup instructions](04_SET_UP_WINDOWS_SSH.md).

## 7. Password-free use and the agent

Three prompts are often confused:

| Prompt | What is requesting it? |
| --- | --- |
| Remote account password | Server login authentication |
| Private-key passphrase | Local unlocking of a protected key |
| `[sudo] password for ...` | Privilege escalation after login |

Key authentication removes the remote password prompt. It does not grant password-free sudo.
An SSH agent can hold an unlocked identity and sign authentication requests:

```sh
ssh-add ~/.ssh/id_ed25519_build
ssh-add -l
ssh-add -d ~/.ssh/id_ed25519_build
```

The first loads a key; `-l` lists loaded fingerprints; `-d` unloads that key without deleting
its file. The agent must be available through `SSH_AUTH_SOCK`. Use your desktop's existing
agent where available. `eval "$(ssh-agent -s)"` starts one for a shell when none is available;
starting agents repeatedly creates unnecessary processes. [Agent commands](https://man.openbsd.org/ssh-add).

Apple's bundled OpenSSH also supports Keychain integration:

```sh
/usr/bin/ssh-add --apple-use-keychain ~/.ssh/id_ed25519_build
```

That option is macOS-specific. See the installed `man ssh-add` for your macOS version.
Keep `UseKeychain` in Mac client configuration; Linux clients may reject it.

To test without an interactive password fallback or reused session:

```sh
ssh -S none -o BatchMode=yes -o PasswordAuthentication=no \
  -o KbdInteractiveAuthentication=no build-linux 'hostname; whoami'
```

The key must already be usable, including any required local passphrase unlocking. This test
does not install a key or disable password login on the server.

## 8. Understanding ~/.ssh/config

The client reads this file for each new connection. You do not restart `sshd` after editing
it. Existing connections may retain their earlier settings.

```sshconfig
Host build-linux
    HostName 192.168.1.50
    User alex
    Port 22
    IdentityFile ~/.ssh/id_ed25519_build
    IdentitiesOnly yes
    ForwardAgent no
    ConnectTimeout 10
    ServerAliveInterval 30
    ServerAliveCountMax 3

Host *
    AddKeysToAgent yes
```

`ssh build-linux` now selects that destination and identity. `Host` matches the alias typed;
`HostName` is the real destination. Indentation aids reading; it does not define scope.
A block continues until the next `Host` or `Match` directive.

### Configuration fields

| Field | Meaning |
| --- | --- |
| `Host` | Alias or matching pattern; multiple names may share a block |
| `HostName` | Actual network hostname/address |
| `User` | Remote login account |
| `Port` | Destination SSH port |
| `IdentityFile` | User identity file; multiple entries accumulate |
| `IdentitiesOnly yes` | Limits identities offered to configured identities |
| `ProxyJump` | Intermediate SSH host(s) |
| `ProxyCommand` | Command supplying the destination connection |
| `UserKnownHostsFile` | Client file for trusted server keys |
| `StrictHostKeyChecking yes` | Requires an already trusted matching server key |
| `AddKeysToAgent yes` | Allows loading a successfully used key into the agent |
| `UseKeychain yes` | Apple's passphrase Keychain integration |
| `ForwardAgent no` | Does not expose the local agent through this connection |
| `RequestTTY force` | Requests a terminal even without local terminal input |
| `RemoteCommand` | Command selected automatically on login |
| `ConnectTimeout` | Connection/initial handshake timeout in seconds |
| `ServerAliveInterval` | Interval for SSH-level liveness checks |
| `ServerAliveCountMax` | Unanswered checks tolerated before disconnecting |
| `ControlMaster`, `ControlPath`, `ControlPersist` | Connection sharing; see section 15 |

Most options use the first value found: command line, user config, then system config.
Put specific blocks before broad defaults. Multiple matching blocks can contribute values;
`Host *` applies broadly rather than acting as an else branch. `IdentityFile` is a notable
additive option. Prefer `ProxyJump` or `ProxyCommand` for a route, rather than combining both.
[Client configuration reference](https://man.openbsd.org/ssh_config).

On macOS/Linux, protect the config with `chmod 600 ~/.ssh/config`. It is a text file named
`config`, with no `.txt` extension. Keep passwords and private-key contents out of it.

## 9. Config examples

The following examples are templates; replace the sample addresses and users.

### Direct host, VM and interactive PowerShell

```sshconfig
Host build-host
    HostName 192.168.1.50
    User alex
    IdentityFile ~/.ssh/id_ed25519_build
    IdentitiesOnly yes

Host build-host-debian1
    HostName 127.0.0.1
    Port 2201
    User alex
    ProxyJump build-host
    IdentityFile ~/.ssh/id_ed25519_guest
    IdentitiesOnly yes

Host build-host-ps
    HostName 192.168.1.50
    User alex
    IdentityFile ~/.ssh/id_ed25519_build
    IdentitiesOnly yes
    RequestTTY force
    RemoteCommand powershell.exe -NoLogo -NoProfile
    ControlPath none
```

The VM example assumes the host forwards its loopback port `2201` to the guest's SSH port.
The host and guest keys may be different. Use the PowerShell alias for interactive sessions;
use a plain alias for scripts and file transfers, because `RemoteCommand` can conflict with
an explicit command or subsystem request. See [SSH command behavior](https://man.openbsd.org/ssh).

### Explicit remote route

```sshconfig
Host build-host-remote
    HostName build.example.net
    User alex
    IdentityFile ~/.ssh/id_ed25519_build
    IdentitiesOnly yes
```

This assumes the hostname is reachable through your organization's approved setup. A DNS
record alone does not make a private machine reachable. An access gateway may instead require
a vendor-provided `ProxyCommand` and separate gateway authentication before SSH authentication.
Keep local and remote aliases distinct when route selection must be explicit.

### Inspect the resolved configuration

```sh
ssh -G build-host-debian1
ssh -F ./example-ssh-config -G build-host-debian1
```

`-G` prints effective settings and exits without connecting. `-F` selects an alternate
configuration file. This is a useful way to review examples before using them.

## 10. Command options explained

```sh
ssh -i ~/.ssh/id_ed25519_build -p 2222 -o ConnectTimeout=10 alex@192.168.1.50
```

| Option | Use |
| --- | --- |
| `-i file` | Identity to use |
| `-p number` | SSH destination port |
| `-l user` | Login user; alternative to `user@host` |
| `-o Key=Value` | A configuration option for this invocation |
| `-J host` | Jump through another SSH host |
| `-t` / `-tt` | Request / force terminal allocation |
| `-T` | Disable terminal allocation |
| `-N` | No remote command, typically for a tunnel |
| `-v`, `-vv`, `-vvv` | Increasing diagnostic detail |
| `-G` | Print effective settings |
| `-S none` | Bypass connection-sharing socket reuse |
| `-4` / `-6` | Force IPv4 / IPv6 |

Options generally precede the destination; words after it form the remote command.
`ssh build-linux -p 2222` can therefore mean a remote command rather than changing the port.
Check the installed `man ssh` when using options from a newer online manual.

## 11. Remote commands and quoting

```sh
ssh build-linux 'hostname; whoami'
ssh build-linux 'cd ~/Projects/Example && git status --short'
```

The client sends the command for execution by the remote shell. `&&` runs the next command
only after success; `;` runs the next command regardless. A failed `cd` followed by `;` can
run a build in the wrong directory. Prefer `&&` for dependent steps.

Local single quotes preserve literal `$variables`, `*` and command text until the remote
shell interprets them. Local double quotes can expand client variables first:

```sh
ssh build-linux 'printf "%s\n" "$HOME"'
```

Here `$HOME` belongs to the remote shell. The remote command's exit code normally becomes
the SSH command's exit code; SSH connection errors commonly return `255`.

### Mac to Windows PowerShell

Windows SSH may start `cmd.exe` by default. A pipe intended for PowerShell can be interpreted
by that outer shell and produce “Select-Object is not recognized.” For simple checks:

```sh
ssh build-host powershell.exe -NoProfile -Command hostname
```

For complex scripts, create a reviewed `.ps1` on the Windows machine and invoke `-File`, or
send a UTF-16LE Base64 `-EncodedCommand` generated from known script text. Base64 solves
transport quoting; it does not encrypt secrets. See Microsoft's
[PowerShell command-line reference](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_powershell_exe).

For an interactive PowerShell session:

```sh
ssh -tt build-host powershell.exe -NoLogo -NoProfile
```

## 12. Copying files

`scp` uses the same SSH aliases and authentication. Current OpenSSH uses SFTP for scp file
transfer. Syntax is `scp source destination`; `host:path` identifies the remote side.

```sh
scp ./report.txt build-linux:/home/alex/Downloads/
scp build-linux:/home/alex/report.txt ./
scp -r build-linux:/home/alex/reports ./
scp -P 2222 ./report.txt alex@192.168.1.50:/home/alex/Downloads/
scp 'build-linux:/home/alex/builds/*.deb' ./
```

In order: upload, download, recursive folder copy, custom port, and matching remote files.
Quote remote wildcards so the local shell does not expand or reject them. A destination
file can be overwritten by scp; it is not a skip-existing or synchronization tool.
`scp -P` sets the port; lowercase `scp -p` preserves timestamps and mode.
[File-copy reference](https://man.openbsd.org/scp).

For interactive transfers:

```sh
sftp build-linux
```

| SFTP command | Meaning |
| --- | --- |
| `pwd`, `ls`, `cd` | Remote directory operations |
| `lpwd`, `lls`, `lcd` | Local directory operations |
| `get report.txt` | Download a file |
| `put report.txt` | Upload a file |
| `reget large.zip` | Resume downloading; verify the partial local file belongs to the same remote file |
| `bye` | Close SFTP |

SFTP is separate from FTP/FTPS despite the similar name.
[SFTP reference](https://man.openbsd.org/sftp).

For integrity checks, compare a source SHA256 with the downloaded file using `sha256sum`
on Linux, `shasum -a 256` on macOS, or `Get-FileHash -Algorithm SHA256` on PowerShell.
A matching checksum verifies content equality; it is not a publisher signature.

## 13. Jump hosts and VM connections

```text
Mac SSH client -> build host SSH -> host loopback port 2201 -> Debian guest port 22
```

```sh
ssh -J build-host -p 2201 alex@127.0.0.1
ssh build-host-debian1
```

These represent the same example route when section 9's config exists. In this route,
`127.0.0.1` is reached from the jump host. The client authenticates both the host and guest;
the guest's host key is checked separately. Agent forwarding is unnecessary for this pattern.

A lower-level equivalent for the transport is:

```sh
ssh -o 'ProxyCommand=ssh -W %h:%p build-host' -p 2201 alex@127.0.0.1
```

`-W` forwards the proxy SSH process's standard input/output to the requested host/port.
`%h` and `%p` expand to the final destination and port. A jump host must permit forwarding
and reach the destination. Working SSH to the host alone does not prove guest reachability.

An alias only exists on the client whose config defines it. Exit an existing guest session
before running another Mac alias, or open a new Mac terminal tab.

## 14. Port forwarding and tunnels

A tunnel carries selected connections through SSH; it is not automatically a whole-device VPN.

### Local forward: reach a remote-only service

```sh
ssh -N -o ExitOnForwardFailure=yes -L 127.0.0.1:5005:127.0.0.1:5005 build-linux
```

The first address/port is the client's listener. The second destination is reached from the
server. Connect your local debugger to `127.0.0.1:5005`; SSH carries it to the server's port
5005. The remote debugger must already be listening. Leave this terminal running and press
Ctrl+C to close the tunnel. `ExitOnForwardFailure` detects failure to establish the forwarding
listener; it does not guarantee that each later destination connection succeeds.

### Remote forward: expose a client service through the server

```sh
ssh -N -R 127.0.0.1:8080:127.0.0.1:3000 build-linux
```

The server listens on its loopback port 8080 and traffic travels to the client's port 3000.
Server forwarding policy governs allowed listeners. Bind examples to loopback so access
stays on the relevant machine.

### Dynamic forward

```sh
ssh -N -D 127.0.0.1:1080 build-linux
```

This creates a local SOCKS proxy. Only applications configured to use that proxy send their
traffic through it; DNS handling depends on the application's proxy settings.

## 15. Connection reuse and keepalives

```sh
mkdir -p ~/.ssh/control
chmod 700 ~/.ssh/control
```

This folder stores local connection-sharing sockets. It is needed only if your `ControlPath`
uses it; creating it does not open a network port or grant remote access.

```sshconfig
Host build-linux
    ControlMaster auto
    ControlPath ~/.ssh/control/%C
    ControlPersist 10m
```

`ControlMaster auto` reuses or creates a shared connection. `%C` gives a compact hashed socket
name. `ControlPersist 10m` retains an idle master after the last session closes. Later
commands may reuse its authentication rather than performing a fresh login.

```sh
ssh -O check build-linux
ssh -O exit build-linux
ssh -S none build-linux
```

These inspect the master, close it, or make a fresh connection. Closing a master can end its
active channels. A broken-pipe error can result from a stale master; test with `-S none`.

`ServerAliveInterval 30` and `ServerAliveCountMax 3` detect a silent connection after roughly
90 seconds without replies. They cannot prevent VM reboots, repair routing, or resume killed
builds. Use a managed job or a persistent terminal session for long-running remote work.

## 16. Server setup and administration

For a new Debian server, run **on that server**:

```sh
sudo apt-get update
sudo apt-get install openssh-server
sudo systemctl enable --now ssh
systemctl status ssh
```

The client machine does not need the server package just to make outgoing connections.
The SSH server must listen on the expected interface, and the network/firewall must permit
the route. Windows OpenSSH Server setup uses Windows services and firewall rules instead;
see the [project Windows setup guide](04_SET_UP_WINDOWS_SSH.md).

Client config affects outgoing connections; server `sshd_config` affects incoming ones:

| Server setting | Controls |
| --- | --- |
| `Port`, `ListenAddress` | Listening port and interfaces |
| `PubkeyAuthentication` | Acceptance of user public-key authentication |
| `AuthorizedKeysFile` | Where allowed user public keys are read |
| `PasswordAuthentication` | Password authentication method |
| `KbdInteractiveAuthentication` | Separate interactive authentication method |
| `AllowUsers` | Which accounts may log in |
| `AllowTcpForwarding` | SSH TCP forwarding |
| `Match` | Conditional settings, such as per-user rules |

Before applying Linux server edits, validate syntax with `sudo sshd -t`. Keep a working
session open and verify a second fresh login after reloading the service. Disabling only
`PasswordAuthentication` may leave keyboard-interactive authentication enabled. Sudo policy
is separate and belongs in reviewed sudoers rules. [Server configuration](https://man.openbsd.org/sshd_config).

## 17. Troubleshooting in order

Start with configuration, then reachability, host verification, authentication, and finally
the remote command. This avoids changing keys to fix an unrelated network failure.

```sh
ssh -G build-linux
ssh -vv -S none -o BatchMode=yes -o ConnectTimeout=10 build-linux hostname
```

| Symptom | Likely area and next check |
| --- | --- |
| Could not resolve hostname | Alias absent on this client, typo or DNS failure; inspect `ssh -G` |
| Connection timed out | Offline guest, wrong IP, firewall drop or route; confirm power/address/port |
| Connection refused | Often no listener or an active rejection; inspect server service and forwarding |
| Banner exchange timeout | Gateway/proxy or handshake stalled; test the route in stages |
| Host identification changed | Verify the host fingerprint after reinstall/address changes before updating trust |
| Permission denied (publickey) | Wrong account, identity, authorization file or permissions; inspect offered key and server logs |
| Too many authentication failures | Too many offered identities; set the intended IdentityFile and IdentitiesOnly |
| Private-key passphrase prompt | Unlock that local key through the agent |
| Sudo asks for password | Login succeeded; sudo requires separate authorization |
| Address already in use | Local forward port is occupied; choose another local port |
| Broken pipe | Connection ended or a reused socket is stale; test fresh with `-S none` |
| Works interactively, fails in script | Noninteractive PATH, profile, quoting, prompts or terminal requirements |

Linux server logs are available through `sudo journalctl -u ssh`; Windows exposes OpenSSH
operational events in Event Viewer. Diagnostic output can reveal usernames, addresses and
paths, so review it before sharing.

After independently verifying a legitimate host-key replacement, remove only the affected
entry, then reconnect and compare the new fingerprint:

```sh
ssh-keygen -F '[127.0.0.1]:2201' -f ~/.ssh/known_hosts
ssh-keygen -R '[127.0.0.1]:2201' -f ~/.ssh/known_hosts
```

Use the actual configured `UserKnownHostsFile`. Deleting every known host entry loses trust
records for unrelated machines.

## 18. Everyday recipes

| Goal | Command from the client |
| --- | --- |
| Remote shell | `ssh build-linux` |
| Identify destination/account | `ssh build-linux 'hostname; whoami'` |
| Inspect configuration without connecting | `ssh -G build-linux` |
| Test fresh unattended login | `ssh -S none -o BatchMode=yes -o ConnectTimeout=10 build-linux hostname` |
| Copy one file down | `scp build-linux:/home/alex/report.txt ./` |
| Copy a folder down | `scp -r build-linux:/home/alex/reports ./` |
| Guest shell | `ssh build-host-debian1` |
| Interactive Windows PowerShell | `ssh -tt build-host powershell.exe -NoLogo -NoProfile` |
| Local manual | `man ssh`, `man ssh_config`, `man scp` |

For this project's real connections, use the [Legion guide](../project/21_BUILD_WINDOWS_ON_LEGION.md),
[Mac VM setup](10_SET_UP_MAC_LOCAL_BUILD_VMS.md),
[home server setup](09_SET_UP_HOME_SERVER_AND_WINDOWS_VM.md) and
[remote Legion route](11_SETUP_LEGION_VIA_HOME_SERVER_CLOUDFLARE.md).

## 19. Official reference

Reviewed on 21 November 2026. Commands here are explained examples, not a report that new
servers, keys or tunnels were configured. Client versions vary; local manuals describe the
features actually installed on your machine.

- [SSH architecture, RFC 4251](https://www.rfc-editor.org/rfc/rfc4251)
- [OpenSSH client](https://man.openbsd.org/ssh)
- [Client configuration](https://man.openbsd.org/ssh_config)
- [Server configuration](https://man.openbsd.org/sshd_config)
- [Key generation and fingerprints](https://man.openbsd.org/ssh-keygen)
- [Authentication agent commands](https://man.openbsd.org/ssh-add)
- [File copying](https://man.openbsd.org/scp)
- [Interactive SFTP](https://man.openbsd.org/sftp)
- [Windows key management](https://learn.microsoft.com/en-us/windows-server/administration/openssh/openssh_keymanagement)
- [PowerShell command-line options](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_powershell_exe)

## 20. Verify key-only and client-restricted access

Use this procedure after setup, a reinstall, a key change, or a network/gateway change.
It is an audit: the commands below do not alter authentication or firewall rules.
Replace `build-linux`, `alex` and example addresses with the actual values.

### 20.1 Define what must be restricted

| Requirement | Evidence needed |
| --- | --- |
| Key-only login | Effective server authentication rules plus fresh connection tests |
| Only the intended key | Authorized-key fingerprints, alternative key sources and tests with isolated identities |
| Only the intended account | Effective user/group/root login restrictions, including Match rules |
| Only the Mac network source | Firewall/access rules for every SSH route, IPv4 and IPv6, and tests from another source |
| Only the physical Mac | Device-bound credentials or verified device access policy, beyond a reusable private-key file |

A successful key login does not establish that passwords are disabled. A named key such as
`id_ed25519_mac` is still a portable credential: its filename, comment or owning computer's
hostname does not bind it to hardware. A fixed source IP is a network restriction, not proof
of device identity. Record the precise guarantee you tested.

### 20.2 Inventory the client and each route

On the Mac:

```sh
ssh -G build-linux
ssh-keygen -lf ~/.ssh/id_ed25519_build.pub
ssh -S none -o BatchMode=yes -o ConnectTimeout=10 build-linux \
  'hostname; whoami; printf "%s\n" "$SSH_CONNECTION"'
```

Record `HostName`, `Port`, `User`, every `IdentityFile`, `ProxyJump`/`ProxyCommand`, and the
known-hosts file. Multiple identities may be configured; an old accepted key can hide the
fact that a new dedicated key is rejected. `SSH_CONNECTION` reports the source address/port
and server address/port for that connection.

Repeat for the explicit remote alias. Through a gateway, SSH may see a proxy/container IP
instead of the Mac's IP. A source rule for only the Mac LAN address can break remote access;
allowing the shared gateway IP alone cannot distinguish the devices behind it.

### 20.3 Inspect the effective server policy with administrator access

In a server shell, replacing the example account and connection addresses:

```sh
sudo /usr/sbin/sshd -t
sudo /usr/sbin/sshd -T -C \
  user=alex,addr=192.0.2.10,host=client.example,laddr=192.0.2.20,lport=22
```

`-t` validates the configuration; `-T` prints effective settings. `-C` evaluates conditional
Match rules for a supplied connection. Repeat for LAN and proxy sources and relevant
accounts. Inspect service startup overrides and included configuration files too. A parsed
file can differ from the running daemon until it is reloaded; fresh protocol tests provide
additional evidence. [Server test-mode reference](https://man.openbsd.org/sshd).

Review these settings together:

```text
pubkeyauthentication
authenticationmethods
passwordauthentication
kbdinteractiveauthentication
permitemptypasswords
permitrootlogin
allowusers / allowgroups / denyusers / denygroups
authorizedkeysfile
authorizedkeyscommand
trustedusercakeys
hostbasedauthentication
gssapiauthentication
```

A key-only design commonly requires public-key authentication and disables password and
keyboard-interactive methods. An `AuthenticationMethods publickey` requirement makes the
intended method explicit. Check account restrictions and all key/certificate sources before
claiming that only one account or key is permitted. Commented lines are not active policy;
client options such as `PasswordAuthentication=no` do not disable server password login.
[Authentication configuration](https://man.openbsd.org/sshd_config).

If `sudo -n` requires a password, record the administrator inspection as **unverified**.
Use your own terminal to authorize the read-only command; never put the password in scripts
or chat. A non-root `sshd -T` error about inaccessible host keys does not mean the running
service has no keys.

### 20.4 Inspect authorized keys and restrictions

On the server, for the relevant account:

```sh
ssh-keygen -lf ~/.ssh/authorized_keys
ls -ld ~ ~/.ssh ~/.ssh/authorized_keys
```

Compare fingerprints with the Mac's public keys. Review each key line locally for options
such as `from="..."`; fingerprints alone do not show these options. Inspect other files,
commands or trusted certificate authorities named in the effective policy, including other
allowed accounts. Never dump private keys for an audit.

An authorized key with no `from=` restriction does not itself limit source addresses, but a
firewall or account rule may still do so. Evaluate all layers together. See the
[authorized_keys format](https://man.openbsd.org/sshd#AUTHORIZED_KEYS_FILE_FORMAT).

### 20.5 Test fresh key authentication and isolate the identity

```sh
ssh -vv -S none -o BatchMode=yes -o ConnectTimeout=10 \
  -o PasswordAuthentication=no -o KbdInteractiveAuthentication=no \
  build-linux hostname
```

Look for the offered/accepted fingerprint and `Authenticated ... using "publickey"`.
This is a positive test of key login only. A shared control connection must not supply the
authentication for this test.

For a direct route, bypass client config to test exactly one intended identity:

```sh
ssh -F /dev/null -S none -o BatchMode=yes -o ConnectTimeout=10 \
  -o StrictHostKeyChecking=yes -o IdentitiesOnly=yes \
  -i ~/.ssh/id_ed25519_build -p 22 alex@192.0.2.20 hostname
```

Use the correct known-hosts file with `-o UserKnownHostsFile=...` if needed. The identity must
already be unlocked/usable. Restore the gateway settings explicitly when testing a proxy
route; a direct example cannot test that route. Client configuration normally accumulates
IdentityFile entries, so adding `-i` to a configured alias alone may still offer other keys.

### 20.6 Check whether password methods are offered

These probes submit no password:

```sh
ssh -vv -S none -o BatchMode=yes -o ConnectTimeout=10 \
  -o PubkeyAuthentication=no -o PreferredAuthentications=password \
  build-linux hostname

ssh -vv -S none -o BatchMode=yes -o ConnectTimeout=10 \
  -o PubkeyAuthentication=no -o PreferredAuthentications=keyboard-interactive \
  build-linux hostname
```

Inspect `Authentications that can continue`. If it includes `password`, password
authentication is still offered for that account/route. The same applies to
`keyboard-interactive`. **Permission denied alone is not a pass:** BatchMode prevents an
interactive password submission even when the server permits it. Do not try guessed
passwords. Evaluate advertised methods alongside effective AuthenticationMethods and Match
rules, then repeat on every supported route.

### 20.7 Check the network and gateway layers

On a Linux server, use the tools actually installed:

```sh
ss -lnt
sudo /usr/sbin/ufw status verbose
sudo /usr/sbin/nft list ruleset
```

Inspect active rules, IPv4/IPv6 scope, alternate SSH ports and forwarded paths. Listening on
`0.0.0.0` or `::` does not by itself mean unrestricted access; a firewall can filter it.
A saved `ENABLED=yes` setting is not proof of the active rules. Check router/NAT exposure
and any access-gateway identity/device policies separately.

With authorization, repeat a connection test from another device. Distinguish “TCP port
blocked” from “port reachable but login denied.” You cannot confirm all other sources are
blocked merely by testing from the allowed Mac. Through a shared gateway, a device policy
or device-bound credential is needed for a stronger Mac-only guarantee.

### 20.8 Record the result and plan changes separately

Record the date, routes/accounts checked, accepted fingerprint, advertised authentication
methods, effective rules and firewall evidence. Mark inaccessible checks **unverified**.
Use a project report for actual machine details and keep this tutorial reusable.

If changes are needed, first prepare the reviewed policy, a working administrator session,
an out-of-band recovery route and a rollback plan. Authorize the change, validate syntax,
reload, and test fresh allowed and denied connections before closing recovery access.
Do not remove the only working key while installing a replacement. Keep required jump-host
forwarding available for build VMs and other approved destinations.

For a worked change with an owner-authorized sudo step, a dedicated key file, active-rule
checks and a timed rollback, see the [project server hardening procedure](../project/42_HOME_SERVER_SSH_ACCESS.md).
Its helper is specific to the reviewed server and firewall layout; adapt and review it
before using it elsewhere. Never treat a staged policy file as proof that a running daemon
has loaded it: repeat fresh positive and negative connection tests.
