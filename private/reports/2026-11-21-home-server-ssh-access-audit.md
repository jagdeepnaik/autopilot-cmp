# Home server SSH access audit — 21 November 2026

Requested guarantee: SSH login only by the owner's Mac key, with passwords and other
clients blocked. This was a read-only audit; SSH, key and firewall rules were not changed.

## Result

The requested guarantee is **not established**. Password authentication remains advertised
on both routes. The working key is the Mac's general `id_ed25519`, rather than its dedicated
`id_ed25519_server`. Network-source/device restrictions need administrator and gateway review.

| Check | Observed evidence |
| --- | --- |
| Local route | Fresh public-key login to `home-server` succeeded as `jagdeep` |
| Remote route | Fresh public-key login through `home-server-remote` succeeded as `jagdeep` |
| Accepted key | `id_ed25519`, fingerprint `SHA256:gypo8/2TOD0LrpcZu3qNKPg8dBVuv/U8m6qkGvJQEew` |
| Dedicated key | `id_ed25519_server`, fingerprint `SHA256:y/HY+MW7aUyzomceh61u0QRZPjZImCJjkmm+ho5wZbA`, was offered and rejected in an isolated local-route test |
| Account authorized_keys | One key matching the general key, without a source `from=` option; no authorized_keys2 file found |
| Password policy probe | Both routes advertised `publickey,password`; no password was submitted |
| Readable SSH configuration | `KbdInteractiveAuthentication no`, `UsePAM yes`; PasswordAuthentication is commented; drop-in directory empty |
| File permissions | Account home and `.ssh` are mode 700; authorized_keys is mode 600 |
| Local source as seen by SSH | `192.168.1.16`, destination `192.168.1.23:22` |
| Remote source as seen by SSH | `172.19.0.6`, destination `172.19.0.1:22`, consistent with the proxy/container route |
| Listener | Port 22 listens on all IPv4 and IPv6 addresses; this alone does not establish firewall reachability |
| Firewall saved defaults | UFW configured enabled, IPv6 enabled, default incoming DROP; active allow rules not inspected |
| Effective privileged policy/firewall | Unverified: sudo requires the owner's password; it was not requested or supplied |

No inspection of other users' authorization files, trusted CAs, router exposure or Cloudflare
device policies was completed. No test from an unrelated device was performed. Therefore
neither exclusive-key access for every account nor Mac-only reachability is confirmed.

## Next verification and remediation

Use the [repeatable audit procedure](../docs/common/03_UNDERSTAND_SSH_AND_KEYS.md#20-verify-key-only-and-client-restricted-access)
to inspect `sudo /usr/sbin/sshd -T -C ...` for both source routes and the active firewall.
Administrator authorization is needed for these checks and any policy changes.

If hardening is requested, install and verify the intended dedicated key before removing
the old authorization; enforce public-key-only authentication and the intended account;
review LAN source restrictions and the gateway's device/identity policy. Preserve approved
VM/Legion forwarding and recovery access. A portable private key or fixed source IP alone
does not prove which physical computer is using it.

## Follow-up hardening preparation

The owner subsequently requested hardening. The dedicated server public key was appended
to the account's existing authorization file, preserving the old key during preparation.
After the owner unlocked the dedicated key locally, isolated fresh logins using only that
key succeeded over LAN and Cloudflare. The previously recorded dedicated-key rejection
describes the pre-install state and is no longer current.

The reviewed helper and public key were staged in the account's `.ssh` directory. The
helper was syntax-checked and its uploaded SHA256 matched the local file. The owner was
given the sudo command and instructed to leave the session at the KEEP prompt for testing.
At this preparation checkpoint, root policy/firewall application and final confirmation
had not yet been observed; password blocking and source restrictions must not be claimed.

The [hardening procedure](../docs/project/42_HOME_SERVER_SSH_ACCESS.md) describes the
dedicated authorization file, account/source policy, timed rollback and final verification.

## Applied policy verification

The first application stopped before SSH reload because the helper expected AllowUsers on
one output line; this OpenSSH version prints one line per source. The helper was corrected,
the owner restored that unconfirmed attempt and stopped its timer through recovery mode,
then applied a new attempt with its own 15-minute rollback timer.

Fresh checks after the new policy loaded:

| Check | Result |
| --- | --- |
| Dedicated key, LAN and Cloudflare | Both succeeded |
| Old general key, LAN and Cloudflare | Both rejected with publickey-only authentication |
| Password probes, LAN and Cloudflare | Only publickey advertised; no password submitted |
| Root with the dedicated key | Rejected |
| Parsed policy | AuthenticationMethods publickey; PasswordAuthentication no; KbdInteractiveAuthentication no; PermitRootLogin no |
| Allowed users/sources | Only jagdeep@192.168.1.16 and jagdeep@172.19.0.6 |
| Active authorization file | /home/jagdeep/.ssh/authorized_keys_server |
| Other LAN device | Legion Test-NetConnection to 192.168.1.23:22 returned False |
| Approved jump forwarding | home-legion-remote returned LEGION successfully |
| Local client aliases | Old general-key fallback removed from home-server and home-server-remote only |

The owner confirmed KEEP after these checks. A subsequent inspection found no remaining
rollback timer, both aliases selected only id_ed25519_server, and fresh LAN and Cloudflare
logins succeeded. The key/account/source restrictions are retained. Original authorization
data and root-owned rollback backups were preserved for recovery. Cloudflare physical-device
policy and router DHCP reservation remain unverified; possession of the portable private
key through an approved source is not cryptographic proof of the physical Mac.
