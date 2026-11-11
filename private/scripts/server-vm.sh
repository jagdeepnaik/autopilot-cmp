#!/usr/bin/env bash
# Start, stop, check or test the Windows build VM on the home server, from the Mac, in one line.
#
#   private/scripts/server-vm.sh status            # is the VM running, and does Windows answer?
#   private/scripts/server-vm.sh test              # sign in to Windows and check the build tools
#   private/scripts/server-vm.sh start             # start it; you type the VNC console password
#   private/scripts/server-vm.sh start --headless  # start it with a random console password (no prompt)
#   private/scripts/server-vm.sh stop              # shut Windows down cleanly and wait for the VM to end
#   add --remote to go through Cloudflare (home-server-remote, home-server-build-remote)
#
# Only the Windows guest starts or stops; Debian and the websites are never touched. A stop is
# refused while a Gradle build is running in the VM, and the VM is never force-killed.
set -euo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
SERVER_HOST=home-server
VM_HOST=home-server-build
VM_DIR='/srv/vms/windows-build'
WINDOWS_CHECKOUT='C:\Build\autopilot-cmp'

usage() { awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "$SCRIPT"; }
ACTION="${1:-}"
case "$ACTION" in status|test|start|stop) shift ;; -h|--help|help) usage; exit 0 ;; '') usage; exit 2 ;; *) echo "Unknown action: $ACTION (see --help)" >&2; exit 2 ;; esac
HEADLESS=0
for arg in "$@"; do
    case "$arg" in
        --headless) HEADLESS=1 ;;
        --remote) SERVER_HOST=home-server-remote; VM_HOST=home-server-build-remote ;;
        *) echo "Unknown option: $arg (see --help)" >&2; exit 2 ;;
    esac
done

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
fail() { printf '\n\033[31mStopped: %s\033[0m\n' "$*" >&2; exit 1; }
ok() { printf '\033[32m%s\033[0m\n' "$*"; }

vm_running() { ssh "$SERVER_HOST" "pgrep -f '^/usr/bin/qemu-system-x86_64 -name windows-build ' >/dev/null"; }
# A fresh connection each time (-S none), so a shared one can't hide that Windows went away.
windows_answers() { ssh -S none -o BatchMode=yes -o ConnectTimeout=8 "$VM_HOST" "echo ready" 2>/dev/null | grep -q ready; }
windows_ps() {
    local encoded
    encoded=$(printf '%s' "\$ErrorActionPreference = 'Stop'; \$ProgressPreference = 'SilentlyContinue'
$1" | iconv -f UTF-8 -t UTF-16LE | base64 | tr -d '\n')
    ssh -S none -o ConnectTimeout=10 "$VM_HOST" "powershell -NoProfile -EncodedCommand $encoded"
}

status() {
    step "Server: $SERVER_HOST"
    ssh -o ConnectTimeout=10 "$SERVER_HOST" "echo \"Debian up: \$(uptime -p)\"; free -h | awk '/^Mem:/ {print \"Host memory: \" \$3 \" used, \" \$7 \" available of \" \$2}'" \
        || fail "can't reach $SERVER_HOST. Check the network, or add --remote."
    step "Windows VM"
    if vm_running; then
        ok "VM process: running"
        ssh "$SERVER_HOST" "ss -ltn | grep -qE '127\.0\.0\.1:2222\b' && echo 'SSH forward: listening on 127.0.0.1:2222' || echo 'SSH forward: not listening yet'"
        if windows_answers; then ok "Windows SSH: answering"; else echo "Windows SSH: not answering yet (still booting, or updating)"; fi
    else
        echo "VM process: stopped"
    fi
}

test_connection() {
    vm_running || fail "the VM is stopped. Start it with: $0 start --headless"
    step "Windows VM: sign in and check the build tools"
    windows_ps "
'Signed in as:   ' + (whoami)
'Computer:       ' + \$env:COMPUTERNAME
'Windows uptime: ' + ((Get-Date) - (Get-CimInstance Win32_OperatingSystem).LastBootUpTime).ToString('d\\.hh\\:mm')
'Java:           ' + (cmd /c 'java -version 2>&1' | Select-Object -First 1)
'Git:            ' + (git --version)
if (Test-Path '$WINDOWS_CHECKOUT') {
    Set-Location '$WINDOWS_CHECKOUT'
    'Checkout:       ' + (git log -1 --format='%h %s')
    \$dirty = @(git status --porcelain).Count
    'Local changes:  ' + \$(if (\$dirty) { \"\$dirty file(s)\" } else { 'none' })
} else { 'Checkout:       missing at $WINDOWS_CHECKOUT' }
\$free = (Get-PSDrive C).Free / 1GB
'Free on C:      ' + [math]::Round(\$free, 1) + ' GB'
" || fail "the connection test failed (see above)."
    ok "Connection test passed."
}

start() {
    if vm_running; then
        echo "The VM is already running."
    else
        step "Server: start the Windows VM"
        if [ "$HEADLESS" -eq 1 ]; then
            # A random console password, piped to start.sh's prompt and never shown or stored.
            { LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 8; echo; } | ssh "$SERVER_HOST" "$VM_DIR/start.sh" \
                || fail "start.sh failed (see above)."
            echo "Started headless: the VNC console password was random. Run 'start' without --headless next time to use the console."
        else
            echo "start.sh asks for an 8-character VNC console password (letters/numbers). It's only for Screen Sharing."
            ssh -t "$SERVER_HOST" "$VM_DIR/start.sh" || fail "start.sh failed (see above)."
        fi
    fi
    step "Waiting for Windows to answer over SSH (up to 10 minutes)"
    local waited=0
    until windows_answers; do
        [ "$waited" -ge 600 ] && fail "Windows didn't answer within 10 minutes. It may be installing updates: check it with Screen Sharing, or run '$0 status' later."
        sleep 10; waited=$((waited + 10)); printf '.'
    done
    [ "$waited" -gt 0 ] && echo
    ok "Windows is up after about ${waited}s."
    test_connection
}

stop() {
    vm_running || { echo "The VM is already stopped."; return 0; }
    step "Windows VM: check nothing is building"
    if windows_answers; then
        local busy
        busy=$(windows_ps "@(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'org\.gradle' }).Count" | tr -d '\r' || echo 0)
        [ "${busy:-0}" -eq 0 ] || fail "a Gradle build is running in the VM ($busy process(es)). Let it finish, or press Control-C in its window, then stop again."
        echo "No build running."
        step "Windows VM: shut down"
        windows_ps "Stop-Computer -Force" >/dev/null 2>&1 || true
    else
        fail "the VM is running but Windows doesn't answer over SSH, so it can't be shut down cleanly from here. Use Screen Sharing (Start → Power → Shut down); the VM is never force-killed."
    fi
    local waited=0
    until ! vm_running; do
        [ "$waited" -ge 300 ] && fail "Windows hasn't finished shutting down after 5 minutes (updates?). Check it with Screen Sharing; don't kill the VM."
        sleep 5; waited=$((waited + 5)); printf '.'
    done
    [ "$waited" -gt 0 ] && echo
    ok "The VM has stopped after about ${waited}s; its memory is free on the server."
}

case "$ACTION" in
    status) status ;;
    test) test_connection ;;
    start) start ;;
    stop) stop ;;
esac
