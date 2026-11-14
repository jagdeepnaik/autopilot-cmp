# Set up Legion access through the home server and Cloudflare

Use this route when the Mac cannot reach Legion directly. SSH first reaches the home server
through Cloudflare, then the home server connects to Legion at `192.168.1.6` on the home LAN.

## How the route works

```text
Mac → Cloudflare SSH → home-server-remote → 192.168.1.6:22 → Legion
```

This works only when the home server is online, Cloudflare Access is authenticated, and the home
server can reach Legion over the LAN.

## One-time setup

The Mac SSH configuration contains these two aliases:

```sshconfig
Host home-server-remote
    HostName ssh.naikio.in
    User jagdeep
    ProxyCommand /opt/homebrew/bin/cloudflared access ssh --hostname %h
    IdentityFile ~/.ssh/id_ed25519
    IdentitiesOnly yes

Host home-legion-via-server
    HostName 192.168.1.6
    User jagde
    ProxyJump home-server-remote
    IdentityFile ~/.ssh/autopilot_windows_ed25519
    IdentitiesOnly yes
    ForwardAgent no
    StrictHostKeyChecking yes
    UserKnownHostsFile ~/.ssh/autopilot_windows_known_hosts
    ServerAliveInterval 30
    ServerAliveCountMax 3
```

Check the resolved configuration:

```sh
ssh -G home-legion-via-server | grep -E '^(hostname|user|port|proxyjump|identityfile) '
```

## Test in order

First test Cloudflare access to the home server:

```sh
ssh home-server-remote 'hostname; whoami'
```

Then test that the home server can see Legion's SSH port:

```sh
ssh home-server-remote 'nc -vz -w 5 192.168.1.6 22'
```

Finally test the complete route:

```sh
ssh home-legion-via-server powershell -NoProfile -Command "hostname; whoami"
```

Expected output includes the Legion computer name and a user such as `desktop-v912bak\jagdeep`.

## Use the routed Legion connection

From the Mac Terminal, connect directly to an interactive PowerShell session:

```sh
ssh -tt home-legion-via-server powershell -NoLogo -NoProfile
```

The prompt should be similar to:

```text
PS C:\Users\jagde>
```

Use `home-legion-via-server` for this route. The existing `home-legion` alias is the direct-LAN
connection and does not use Cloudflare.

Open a normal remote shell:

```sh
ssh home-legion-via-server
```

Run a Windows command:

```sh
ssh home-legion-via-server powershell -NoProfile -Command "Get-ComputerInfo -Property WindowsProductName"
```

Copy a file to Legion:

```sh
scp ./file.txt home-legion-via-server:'C:/Users/jagde/Downloads/file.txt'
```

The existing `home-legion` alias remains the direct-LAN route. Use `home-legion-via-server` only
when direct access is unavailable. This route does not require a private key on the home server;
the Mac's key is used for the final Legion connection through SSH forwarding.

If the interactive session does not open, run these checks in order:

```sh
ssh home-server-remote 'hostname; whoami'
ssh home-legion-via-server powershell -NoProfile -Command "hostname; whoami"
```

The first command confirms Cloudflare access to the home server. The second confirms the complete
home-server-to-Legion route without opening an interactive prompt.

## Troubleshooting

- `cloudflared` authentication errors: run `cloudflared access login https://ssh.naikio.in`, then
  repeat the Cloudflare test.
- The first connection may ask you to confirm the host key. Confirm only when the hostname and
  fingerprint match the machine you expect.
- `nc` reports connection refused or times out: Legion is offline, its IP changed, or Windows SSH
  is not running. Check Legion locally or through another available route.
- `Permission denied (publickey)`: confirm that the Mac has
  `~/.ssh/autopilot_windows_ed25519` and that its public key is installed for `jagdeep` on Legion.
- To force the route without the alias:

```sh
ssh -J home-server-remote home-legion powershell -NoProfile -Command "hostname; whoami"
```

