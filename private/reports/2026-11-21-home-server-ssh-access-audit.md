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
