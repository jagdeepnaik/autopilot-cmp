# 42 — Home Server SSH Access Restrictions

[Project guides](00_INDEX.md) · [SSH audit tutorial](../common/03_UNDERSTAND_SSH_AND_KEYS.md#20-verify-key-only-and-client-restricted-access) · [Initial audit](../../reports/2026-11-21-home-server-ssh-access-audit.md)

## Intended policy

Only account `jagdeep`, using the dedicated `id_ed25519_server` key, may start new SSH
sessions. Password authentication, keyboard-interactive login and root login are disabled
when the hardening helper's change is applied and confirmed. LAN source is the Mac's
current `192.168.1.16`; remote source is Cloudflare connector `172.19.0.6` on bridge
`br-e6ab669669a4`. Other SSH sources/users/keys are rejected by the managed policy.

These values are machine configuration, not portable defaults. Reserve the Mac's address
in the router and review the connector address after Docker/network changes. The policy
does not cryptographically identify the physical Mac: the key is portable, LAN addresses
can change, and remote traffic shares the connector source. Cloudflare device restrictions
require separate gateway administration and verification.

## Apply with recovery

Internal helper: `private/scripts/server/Harden-Ssh.sh`. Use it only for this reviewed
server layout. It requires the standard ssh service/drop-in include and an active UFW setup.
It preserves the existing general authorization file, creates a dedicated authorization
file, adds source-specific SSH firewall rules and removes the two audited broad SSH rules.
It does not reset unrelated firewall rules or disable TCP forwarding used by VM/Legion routes.

Before applying, install the dedicated public key alongside the existing one and test it
on both routes in fresh sessions. The owner must unlock the key locally and authorize sudo.
The helper/public key must be staged and checksum-verified before the following command:

```sh
ssh -t home-server 'sudo bash /home/jagdeep/.ssh/harden-server-ssh.sh jagdeep 192.168.1.16 172.19.0.6 br-e6ab669669a4 /home/jagdeep/.ssh/server-key.pending.pub'
```

Review the displayed account/key/sources and type `APPLY`. The helper saves the prior UFW
rules under `/var/lib/ssh-access-backup.*` and arms a 15-minute rollback timer before editing
policy. It validates effective settings and reloads SSH. Keep the terminal open at the
`KEEP` prompt while fresh allowed/denied connections are tested from another Mac terminal.
Type `KEEP` only after tests pass; any other answer restores the previous settings. If the
session is lost, leave the timer to restore access. The original broad rules are restored
on rollback, so repeat the audit after a failed attempt.

The helper refuses to overwrite managed files from an earlier attempt. Review prior backup
and state before rerunning; do not delete those files to suppress an unexplained failure.
For an unconfirmed failed attempt, pass `--recover /var/lib/ssh-access-backup.<actual-id>`
before the five normal arguments. This stops that backup's timer and runs its restoration
before starting a new attempt. It refuses recovery of a policy already marked kept.

The effective-policy check accepts OpenSSH's per-line AllowUsers output as well as a
space-separated list, and rejects additional user/source entries. Validation failures
restore staged policy immediately; the independent timer also protects against session loss.

## Verify before KEEP

Fresh dedicated-key LAN login, without the SSH config's old fallback identity:

```sh
ssh -F /dev/null -S none -o BatchMode=yes -o ConnectTimeout=10 \
  -o StrictHostKeyChecking=yes -o IdentitiesOnly=yes \
  -i ~/.ssh/id_ed25519_server jagdeep@192.168.1.23 'hostname; whoami'
```

Fresh dedicated-key Cloudflare login:

```sh
ssh -F /dev/null -S none -o BatchMode=yes -o ConnectTimeout=10 \
  -o StrictHostKeyChecking=yes -o IdentitiesOnly=yes \
  -i ~/.ssh/id_ed25519_server \
  -o 'ProxyCommand=/opt/homebrew/bin/cloudflared access ssh --hostname %h' \
  jagdeep@ssh.naikio.in 'hostname; whoami'
```

Repeat with `-i ~/.ssh/id_ed25519` and expect rejection. Probe advertised authentication
methods as described in the common audit tutorial: only `publickey` should remain. Confirm
root/other-user rejection and active UFW rules. A fresh connection to a VM through the
server verifies that required forwarding remains available.

After `KEEP`, retest both routes once more. Client aliases should then use only
`IdentityFile ~/.ssh/id_ed25519_server`; remove the old fallback entry from these two aliases,
but preserve the general private key for unrelated hosts. Keep the recovery backups.

## Verification state — 21 November 2026

The dedicated key passed isolated fresh LAN and Cloudflare login tests. After the owner
applied the helper, fresh checks rejected the old general key on both routes and rejected
root login; password probes advertised only publickey. Legion could no longer reach the
server's LAN SSH port, while the approved Cloudflare-to-server-to-Legion route still worked.
The client aliases now select only the dedicated key. The owner confirmed KEEP; inspection
found no remaining rollback timer, and both aliases passed fresh login tests afterward.
See the audit report for details and the remaining device-policy/DHCP limitations.
