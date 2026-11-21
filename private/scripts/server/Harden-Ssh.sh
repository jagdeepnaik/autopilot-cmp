#!/usr/bin/env bash
# Internal administrator helper. The owner runs it interactively via sudo after review.
# Usage: bash Harden-Ssh.sh USER MAC_IPV4 PROXY_IPV4 PROXY_INTERFACE PUBLIC_KEY_FILE
set -Eeuo pipefail
backup=''

die() {
    printf '%s\n' "$*" >&2
    if [[ -n $backup && -f $backup/rollback.sh && ! -e $backup/kept && ! -e $backup/rolled-back ]]; then
        /bin/bash "$backup/rollback.sh"
        systemctl stop "$unit.timer"
    fi
    exit 1
}
if [[ ${1:-} == --recover ]]; then
    [[ $EUID == 0 && $# == 7 ]] || die 'Recovery requires sudo, the backup path and the original five arguments.'
    recovery=$2
    [[ $recovery =~ ^/var/lib/ssh-access-backup\.[a-zA-Z0-9]+$ && -f $recovery/rollback.sh ]] || die 'Invalid recovery backup.'
    [[ ! -e $recovery/kept ]] || die 'Refusing to undo an already confirmed policy.'
    old_unit=ssh-access-rollback-$(basename "$recovery" | tr . -)
    systemctl stop "$old_unit.timer"
    /bin/bash "$recovery/rollback.sh"
    printf 'Prior unconfirmed attempt restored; old rollback timer stopped.\n'
    shift 2
fi
[[ $# == 5 ]] || die 'Expected USER MAC_IPV4 PROXY_IPV4 PROXY_INTERFACE PUBLIC_KEY_FILE.'
[[ $EUID == 0 ]] || die 'Run this reviewed helper with sudo in your own terminal.'
account=$1 mac_address=$2 proxy_address=$3 proxy_interface=$4 public_key=$5
[[ $account =~ ^[a-z_][a-z0-9_-]*$ ]] || die 'Invalid account name.'
[[ $proxy_interface =~ ^[a-zA-Z0-9_.:-]+$ ]] || die 'Invalid interface name.'
for address in "$mac_address" "$proxy_address"; do
    [[ $address =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || die 'Expected IPv4 addresses.'
    IFS=. read -r -a octets <<< "$address"
    for octet in "${octets[@]}"; do
        (( 10#$octet <= 255 )) || die 'Invalid IPv4 address.'
    done
done
[[ -t 0 ]] || die 'Use an interactive terminal so the change can be confirmed.'
home_directory=$(getent passwd "$account" | cut -d: -f6)
[[ -n $home_directory && -d $home_directory/.ssh ]] || die 'Account SSH directory not found.'
[[ -f $public_key && ! -L $public_key ]] || die 'Expected a regular public-key file.'
[[ $(wc -l < "$public_key") == 1 ]] || die 'The public-key file must contain exactly one line.'
grep -qE '^ssh-ed25519 [A-Za-z0-9+/]+=*( .*)?$' "$public_key" || die 'Expected one Ed25519 public key.'
/usr/bin/ssh-keygen -lf "$public_key"
[[ -d /sys/class/net/$proxy_interface ]] || die 'The expected proxy bridge is not present.'
/usr/sbin/ufw status | grep -q '^Status: active$' || die 'Expected the already-active UFW firewall.'
/usr/sbin/sshd -t
systemctl is-active --quiet ssh || die 'The expected ssh service is not active.'
grep -qE '^Include[[:space:]]+/etc/ssh/sshd_config.d/\*\.conf' /etc/ssh/sshd_config || die 'Expected the standard SSH drop-in include.'

policy=/etc/ssh/sshd_config.d/00-mac-key-only.conf
authorization=$home_directory/.ssh/authorized_keys_server
[[ ! -e $policy && ! -e $authorization ]] || die 'Managed files already exist; inspect the previous change before rerunning.'
[[ -f /etc/ufw/user.rules && -f /etc/ufw/user6.rules ]] || die 'Expected UFW rule files.'
printf '\nAccount: %s\nMac source: %s\nProxy source: %s on %s\n' "$account" "$mac_address" "$proxy_address" "$proxy_interface"
printf 'This will require this dedicated key, disable password/root SSH login, and restrict SSH sources.\n'
read -r -p 'Type APPLY to continue: ' answer
[[ $answer == APPLY ]] || die 'No policy changes made.'

backup=$(mktemp -d /var/lib/ssh-access-backup.XXXXXXXX)
chmod 700 "$backup"
cp -a /etc/ufw/user.rules "$backup/user.rules"
cp -a /etc/ufw/user6.rules "$backup/user6.rules"
printf '%s\n' "$policy" > "$backup/policy-path"
printf '%s\n' "$authorization" > "$backup/authorization-path"
cat > "$backup/rollback.sh" <<'ROLLBACK'
#!/usr/bin/env bash
set -euo pipefail
backup=$(cd -- "$(dirname -- "$0")" && pwd)
exec 9>"$backup/state.lock"
flock 9
[[ ! -e $backup/kept ]] || exit 0
policy=$(<"$backup/policy-path")
authorization=$(<"$backup/authorization-path")
rm -f -- "$policy" "$authorization"
cp -a "$backup/user.rules" /etc/ufw/user.rules
cp -a "$backup/user6.rules" /etc/ufw/user6.rules
/usr/sbin/sshd -t
systemctl reload ssh
/usr/sbin/ufw reload
touch "$backup/rolled-back"
ROLLBACK
chmod 700 "$backup/rollback.sh"
unit=ssh-access-rollback-$(basename "$backup" | tr . -)
systemd-run --unit="$unit" --on-active=15m /bin/bash "$backup/rollback.sh"
# Any failed operation after this point restores the prior rules immediately.
trap 'trap - ERR; /bin/bash "$backup/rollback.sh"; exit 1' ERR
trap '/bin/bash "$backup/rollback.sh"; exit 1' HUP INT TERM

install -m 600 -o "$account" -g "$(id -gn "$account")" "$public_key" "$authorization"
cat > "$policy" <<EOF
# Dedicated Mac key; explicit LAN and Cloudflare connector sources.
PubkeyAuthentication yes
AuthenticationMethods publickey
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitEmptyPasswords no
PermitRootLogin no
AllowUsers $account@$mac_address $account@$proxy_address
AuthorizedKeysFile $authorization
AuthorizedKeysCommand none
TrustedUserCAKeys none
HostbasedAuthentication no
GSSAPIAuthentication no
EOF
chmod 644 "$policy"
/usr/sbin/sshd -t
for address in "$mac_address" "$proxy_address"; do
    effective=$(/usr/sbin/sshd -T -C "user=$account,addr=$address,host=$address,laddr=192.168.1.23,lport=22")
    for setting in 'authenticationmethods publickey' 'passwordauthentication no' 'kbdinteractiveauthentication no' 'permitrootlogin no' "authorizedkeysfile $authorization"; do
        grep -qxF "$setting" <<< "$effective" || die "Unexpected effective setting: $setting"
    done
    expected_users=$(printf '%s\n' "$account@$mac_address" "$account@$proxy_address" | sort)
    actual_users=$(awk '$1 == "allowusers" { for (i=2; i<=NF; i++) print $i }' <<< "$effective" | sort)
    [[ $actual_users == "$expected_users" ]] || die 'Unexpected effective AllowUsers list.'
done

# Narrow only SSH rules; keep other service rules and TCP forwarding intact.
/usr/sbin/ufw allow from "$mac_address" to any port 22 proto tcp comment 'SSH from approved Mac'
/usr/sbin/ufw allow in on "$proxy_interface" from "$proxy_address" to any port 22 proto tcp comment 'SSH from Cloudflare connector'
/usr/sbin/ufw --force delete allow from 192.168.1.0/24 to any port 22 proto tcp
/usr/sbin/ufw --force delete allow in on "$proxy_interface" from 172.19.0.0/16 to any port 22 proto tcp
systemctl reload ssh
/usr/sbin/ufw status verbose
printf '\nAPPLIED. Automatic rollback remains armed for 15 minutes.\n'
printf 'Backup: %s\n' "$backup"
printf 'Keep this terminal open. Ask the assistant to verify fresh local/remote key logins,\npassword rejection and old-key rejection. Only after those checks pass, type KEEP.\n'
read -r -p 'Type KEEP to retain verified settings, or anything else to roll back: ' answer
if [[ $answer != KEEP ]]; then
    /bin/bash "$backup/rollback.sh"
    printf 'Previous policy restored.\n'
    exit 1
fi
exec 9>"$backup/state.lock"
flock 9
[[ ! -e $backup/rolled-back ]] || die 'The timer already rolled back this change; settings were not kept.'
touch "$backup/kept"
systemctl stop "$unit.timer"
trap - ERR HUP INT TERM
printf 'Verified settings retained. Backup preserved at %s\n' "$backup"
