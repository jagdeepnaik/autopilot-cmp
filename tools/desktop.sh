#!/usr/bin/env bash
# One command from the Mac to sync, build, install and debug AutoPilot on every desktop machine.
#
#   tools/desktop.sh <target> [action] [--no-tests] [--remote]
#
#   target   mac      this Mac: macOS app in /Applications
#            legion   Windows laptop (ssh home-legion): MSI, installed on Legion
#            server   Debian home server (ssh home-server): Linux DEB, copied to the Mac
#            vm       Windows VM on the home server (ssh home-server-build): MSI, copied to the Mac
#            all      mac, legion and server: build and install (or copy); no debugging
#
#   action   all      (default) every step the target supports, in order
#            sync     push the Mac's commits and pull them on the target
#            build    sync, focused tests, then the installer
#            install  build, then install and check   (mac, legion)
#            fetch    build, then copy the installer to ~/Downloads/Builds/AutoPilot   (server, vm)
#            debug    sync, start a debug run waiting on port 5005   (mac, legion)
#
#   --no-tests  skip the focused tests
#   --remote    reach the home server through Cloudflare (home-server-remote, home-server-build-remote)
#
# Remote machines always build from Git: the Mac's work must be committed; unpushed commits are
# pushed; the target pulls and must end on the same commit. Uncommitted changes on either side are
# never overwritten: the script stops and says what to do. See private/docs/01_ONE_COMMAND_BUILDS.md.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

LEGION_HOST="${LEGION_HOST:-home-legion}"
LEGION_DIR='C:\Users\jagde\Projects\autopilot-cmp'
SERVER_HOST="${SERVER_HOST:-home-server}"
SERVER_DIR='/srv/build/autopilot-cmp'
VM_HOST="${VM_HOST:-home-server-build}"
VM_DIR='C:\Build\autopilot-cmp'
DOWNLOADS="$HOME/Downloads/Builds/AutoPilot"
DEBUG_PORT=5005
LOW_MEMORY=(--no-daemon --console=plain --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process')
FOCUSED_TESTS=(:core:automation:jvmTest :feature:home:jvmTest --tests '*DgsStudy*' :composeApp:jvmTest --tests '*DesktopDataPathsTest*' --tests '*AppVersionTest*')

# The help text is the comment block at the top of this file, up to the first line of code.
usage() { awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "$0"; }

TARGET="${1:-}"
case "$TARGET" in -h|--help|help) usage; exit 0 ;; esac
[ -n "$TARGET" ] || { usage; exit 2; }
shift
ACTION=all
TESTS=1
for arg in "$@"; do
    case "$arg" in
        all|sync|build|install|fetch|debug) ACTION="$arg" ;;
        --no-tests) TESTS=0 ;;
        --remote) SERVER_HOST=home-server-remote; VM_HOST=home-server-build-remote ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $arg (see --help)" >&2; exit 2 ;;
    esac
done

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
fail() { printf '\n\033[31mStopped: %s\033[0m\n' "$*" >&2; exit 1; }

# PowerShell on a Windows machine. The script is sent base64-encoded, so no quoting breaks between
# this shell, ssh, the remote default shell and PowerShell.
windows_ps() {
    local host="$1" dir="$2" body="$3" script encoded
    script="\$ErrorActionPreference = 'Stop'; \$ProgressPreference = 'SilentlyContinue'; Set-Location '$dir'
$body"
    encoded=$(printf '%s' "$script" | iconv -f UTF-8 -t UTF-16LE | base64 | tr -d '\n')
    ssh "$host" "powershell -NoProfile -ExecutionPolicy RemoteSigned -EncodedCommand $encoded"
}

# --- Git sync -----------------------------------------------------------------------------------
MAC_HEAD=''
mac_check_pushed() {
    [ -z "$MAC_HEAD" ] || return 0
    step "Mac: check the work is committed and pushed"
    if [ -n "$(git status --porcelain)" ]; then
        git status --short
        fail "the Mac has uncommitted changes. Commit them (or say \"push\" to Claude), then run this again."
    fi
    git fetch -q origin
    [ "$(git rev-parse --abbrev-ref HEAD)" = main ] || fail "the Mac isn't on main; the build machines build main."
    local behind ahead
    behind=$(git rev-list --count HEAD..origin/main)
    ahead=$(git rev-list --count origin/main..HEAD)
    [ "$behind" -eq 0 ] || fail "GitHub has $behind commit(s) the Mac doesn't. Pull on the Mac first."
    if [ "$ahead" -gt 0 ]; then echo "Pushing $ahead commit(s)..."; git push -q origin main; fi
    MAC_HEAD=$(git rev-parse HEAD)
    echo "Mac and GitHub: $(git log -1 --format='%h %s')  (version $(desktop_version))"
}

desktop_version() { echo "$(sed -n 's/^versionName = "\([0-9]*\)\.\([0-9]*\)\..*/\1.\2/p' gradle/libs.versions.toml).$(git rev-list --count HEAD)"; }

windows_sync() {
    local name="$1" host="$2" dir="$3"
    mac_check_pushed
    step "$name: pull"
    windows_ps "$host" "$dir" "
\$dirty = git status --porcelain
if (\$dirty) { \$dirty; throw '$name has uncommitted changes. Compare them with GitHub and stash them as a backup before syncing.' }
git pull --ff-only -q
if (\$LASTEXITCODE -ne 0) { throw 'git pull failed.' }
if ((git rev-parse HEAD) -ne '$MAC_HEAD') { throw '$name is not on the Mac commit after pulling.' }
'$name: ' + (git log -1 --format='%h %s')
" || fail "$name couldn't be synced (see above)."
}

server_sync() {
    mac_check_pushed
    step "Server: pull"
    ssh "$SERVER_HOST" "set -e; cd $SERVER_DIR
if [ -n \"\$(git status --porcelain)\" ]; then git status --short; echo 'The server has uncommitted changes. Compare them with GitHub and stash them as a backup before syncing.' >&2; exit 1; fi
git pull --ff-only -q
[ \"\$(git rev-parse HEAD)\" = $MAC_HEAD ] || { echo 'The server is not on the Mac commit after pulling.' >&2; exit 1; }
echo \"Server: \$(git log -1 --format='%h %s')\"" || fail "the server couldn't be synced (see above)."
}

# --- Mac ----------------------------------------------------------------------------------------
mac_app_running() { pgrep -f '/AutoPilot.app/Contents/MacOS/AutoPilot' >/dev/null 2>&1 || pgrep -f 'com.jagdeep.autopilot.MainKt' >/dev/null 2>&1; }

mac_sync() {
    step "Mac: local build"
    if [ -n "$(git status --porcelain)" ]; then
        echo "Note: building uncommitted changes. The version stays $(desktop_version) until you commit."
    fi
    echo "Mac: $(git log -1 --format='%h %s')  (version $(desktop_version))"
}

mac_build() {
    mac_sync
    local tasks=()
    [ "$TESTS" -eq 1 ] && tasks+=("${FOCUSED_TESTS[@]}")
    tasks+=(:composeApp:createReleaseDistributable)
    step "Mac: $( [ "$TESTS" -eq 1 ] && echo 'focused tests, then ')build the app"
    ./gradlew --console=plain "${tasks[@]}" || fail "the Mac build failed (see above)."
    echo "Built: $ROOT/composeApp/build/compose/binaries/main-release/app/AutoPilot.app"
}

mac_install() {
    local app="$ROOT/composeApp/build/compose/binaries/main-release/app/AutoPilot.app"
    step "Mac: install to /Applications"
    [ -d "$app" ] || fail "no built app at $app."
    mac_app_running && fail "AutoPilot is running on the Mac. Quit it normally (so progress is saved), then run this again."
    rm -rf /Applications/AutoPilot.app
    ditto "$app" /Applications/AutoPilot.app
    codesign --verify --deep /Applications/AutoPilot.app || fail "the installed app's signature doesn't verify."
    local version
    version=$(sed -n 's/.*autopilot\.version=\([0-9.]*\).*/\1/p' /Applications/AutoPilot.app/Contents/app/AutoPilot.cfg | head -1)
    echo "Installed /Applications/AutoPilot.app, version $version. Open it from Applications or Launchpad."
}

mac_debug() {
    step "Mac: debug run on port $DEBUG_PORT"
    mac_app_running && fail "AutoPilot is running on the Mac. Quit it first; both copies share ~/.autopilot."
    lsof -nP -iTCP:"$DEBUG_PORT" -sTCP:LISTEN >/dev/null 2>&1 && fail "port $DEBUG_PORT is in use (a Legion tunnel or another debug run?)."
    echo "In Android Studio, choose \"AutoPilot Mac (local)\" and click Debug; the app opens once it attaches."
    echo "Close the app window to end the debug run."
    ./gradlew --console=plain :composeApp:run --debug-jvm
}

# --- Legion -------------------------------------------------------------------------------------
legion_build() {
    windows_sync Legion "$LEGION_HOST" "$LEGION_DIR"
    step "Legion: $( [ "$TESTS" -eq 1 ] && echo 'focused tests, then ')build the MSI"
    windows_ps "$LEGION_HOST" "$LEGION_DIR" "& .\\tools\\windows\\Build-Distribution.ps1$( [ "$TESTS" -eq 1 ] || echo ' -SkipTests')" \
        || fail "the Legion build failed (see above)."
}

legion_install() {
    step "Legion: install or update, then check"
    windows_ps "$LEGION_HOST" "$LEGION_DIR" "& .\\tools\\windows\\Install-Msi.ps1 -LaunchCheck -SkipIfInstalled" \
        || fail "the install or its checks failed (see above)."
}

legion_debug() {
    windows_sync Legion "$LEGION_HOST" "$LEGION_DIR"
    step "Legion: start the debug run"
    lsof -nP -iTCP:"$DEBUG_PORT" -sTCP:LISTEN >/dev/null 2>&1 && fail "port $DEBUG_PORT is already in use on the Mac. Close it and run again."
    windows_ps "$LEGION_HOST" "$LEGION_DIR" "
if (-not (Get-ScheduledTask -TaskName 'AutoPilot-Debug' -ErrorAction SilentlyContinue)) { throw 'The AutoPilot-Debug task is not registered. Run tools\\windows\\Register-DebugLauncher.ps1 once on the Legion desktop.' }
if (@(Get-Process javaw, AutoPilot -ErrorAction SilentlyContinue | Where-Object { \$_.Path -like 'C:\\Program Files\\AutoPilot\\*' })) { throw 'The installed AutoPilot is open on Legion. Close it normally first; both copies share the same data.' }
if (Get-NetTCPConnection -LocalPort $DEBUG_PORT -State Listen -ErrorAction SilentlyContinue) { 'A debug run is already waiting on port $DEBUG_PORT.'; return }
Start-ScheduledTask -TaskName 'AutoPilot-Debug'
'Started AutoPilot-Debug on the signed-in desktop. Waiting for it to build and listen on port $DEBUG_PORT...'
\$deadline = (Get-Date).AddMinutes(15)
while (-not (Get-NetTCPConnection -LocalPort $DEBUG_PORT -State Listen -ErrorAction SilentlyContinue)) {
    if ((Get-Date) -gt \$deadline) { throw 'The debug run did not listen within 15 minutes. Check the newest debug-*.log in %USERPROFILE%\\.autopilot\\logs.' }
    if ((Get-ScheduledTask -TaskName 'AutoPilot-Debug').State -ne 'Running') { throw 'The debug run stopped early. Check the newest debug-*.log in %USERPROFILE%\\.autopilot\\logs.' }
    Start-Sleep -Seconds 5
}
'The debug run is waiting for the debugger.'
" || fail "the debug run didn't start (see above)."
    step "Mac: debugger tunnel on 127.0.0.1:$DEBUG_PORT"
    echo "In Android Studio, choose \"AutoPilot Windows (SSH tunnel)\" and click Debug. The window appears on Legion."
    echo "Press Control-C here to close the tunnel."
    # Its own connection (-S none): through a shared ControlMaster connection, -N opens a shell and
    # the forward outlives Control-C.
    ssh -S none -N -o ExitOnForwardFailure=yes -L "127.0.0.1:$DEBUG_PORT:127.0.0.1:$DEBUG_PORT" "$LEGION_HOST"
}

# --- Home server (Debian DEB) -------------------------------------------------------------------
server_build() {
    server_sync
    step "Server: $( [ "$TESTS" -eq 1 ] && echo 'focused tests, then ')build the DEB (low priority, beside the websites)"
    local tasks=("${LOW_MEMORY[@]}")
    [ "$TESTS" -eq 1 ] && tasks+=("${FOCUSED_TESTS[@]}")
    tasks+=(:composeApp:packageReleaseDeb)
    ssh "$SERVER_HOST" "cd $SERVER_DIR && nice -n 10 ionice -c 2 -n 7 ./gradlew $(printf "%q " "${tasks[@]}")" \
        || fail "the server build failed (see above)."
}

server_fetch() {
    step "Server: copy the DEB to the Mac"
    local deb
    deb=$(ssh "$SERVER_HOST" "ls -t $SERVER_DIR/composeApp/build/compose/binaries/main-release/deb/*.deb | head -1") || fail "no DEB on the server."
    mkdir -p "$DOWNLOADS"
    scp -q "$SERVER_HOST:$deb" "$DOWNLOADS/"
    local name remote local_hash
    name=$(basename "$deb")
    remote=$(ssh "$SERVER_HOST" "sha256sum '$deb'" | cut -d' ' -f1)
    local_hash=$(shasum -a 256 "$DOWNLOADS/$name" | cut -d' ' -f1)
    [ "$remote" = "$local_hash" ] || fail "the copied $name doesn't match the server's (hash mismatch)."
    echo "Copied $DOWNLOADS/$name (SHA256 $local_hash, matches the server)."
    echo "Install it on a Linux desktop, not on the server: sudo apt install ./$name"
}

# --- Home server Windows VM (MSI) ---------------------------------------------------------------
vm_build() {
    step "Server VM: check it's running"
    ssh "$SERVER_HOST" "pgrep -f '^/usr/bin/qemu-system-x86_64 -name windows-build ' >/dev/null" \
        || fail "the Windows VM isn't running. Start it with: ssh -t $SERVER_HOST /srv/vms/windows-build/start.sh"
    windows_sync "Server VM" "$VM_HOST" "$VM_DIR"
    step "Server VM: $( [ "$TESTS" -eq 1 ] && echo 'focused tests, then ')build the MSI"
    windows_ps "$VM_HOST" "$VM_DIR" "& .\\tools\\windows\\Build-Distribution.ps1$( [ "$TESTS" -eq 1 ] || echo ' -SkipTests')" \
        || fail "the server VM build failed (see above)."
}

vm_fetch() {
    step "Server VM: copy the MSI to the Mac"
    local msi
    msi=$(windows_ps "$VM_HOST" "$VM_DIR" "(Get-ChildItem .\\composeApp\\build\\compose\\binaries\\main-release\\msi\\*.msi | Sort-Object LastWriteTime | Select-Object -Last 1).Name" | tr -d '\r') \
        || fail "no MSI in the server VM."
    [ -n "$msi" ] || fail "no MSI in the server VM."
    mkdir -p "$DOWNLOADS"
    scp -q "$VM_HOST:C:/Build/autopilot-cmp/composeApp/build/compose/binaries/main-release/msi/$msi" "$DOWNLOADS/"
    local remote local_hash
    remote=$(windows_ps "$VM_HOST" "$VM_DIR" "(Get-FileHash .\\composeApp\\build\\compose\\binaries\\main-release\\msi\\$msi).Hash" | tr -d '\r' | tr 'A-F' 'a-f')
    local_hash=$(shasum -a 256 "$DOWNLOADS/$msi" | cut -d' ' -f1)
    [ "$remote" = "$local_hash" ] || fail "the copied $msi doesn't match the VM's (hash mismatch)."
    echo "Copied $DOWNLOADS/$msi (SHA256 $local_hash, matches the VM)."
}

# --- Dispatch -----------------------------------------------------------------------------------
unsupported() { fail "'$ACTION' isn't available for $TARGET. $1"; }

case "$TARGET" in
    mac)
        case "$ACTION" in
            sync) mac_sync ;; build) mac_build ;; install) mac_build; mac_install ;; debug) mac_sync; mac_debug ;;
            all) mac_build; mac_install; mac_debug ;;
            fetch) unsupported "The Mac build stays on this Mac." ;;
        esac ;;
    legion)
        case "$ACTION" in
            sync) windows_sync Legion "$LEGION_HOST" "$LEGION_DIR" ;; build) legion_build ;;
            install) legion_build; legion_install ;; debug) legion_debug ;;
            all) legion_build; legion_install; legion_debug ;;
            fetch) unsupported "Legion installs its own MSI; to copy it, see private/docs/07_COPY_WINDOWS_INSTALLER.md." ;;
        esac ;;
    server)
        case "$ACTION" in
            sync) server_sync ;; build) server_build ;; fetch|all) server_build; server_fetch ;;
            install|debug) unsupported "The server is a headless website host; install and test the DEB on a Linux desktop." ;;
        esac ;;
    vm)
        case "$ACTION" in
            sync) windows_sync "Server VM" "$VM_HOST" "$VM_DIR" ;; build) vm_build ;; fetch|all) vm_build; vm_fetch ;;
            install|debug) unsupported "Install and test MSIs on Legion." ;;
        esac ;;
    all)
        case "$ACTION" in
            sync) mac_check_pushed; windows_sync Legion "$LEGION_HOST" "$LEGION_DIR"; server_sync ;;
            build) mac_check_pushed; mac_build; legion_build; server_build ;;
            all|install|fetch) mac_check_pushed; mac_build; mac_install; legion_build; legion_install; server_build; server_fetch ;;
            debug) unsupported "Debug one machine at a time: tools/desktop.sh mac debug, or tools/desktop.sh legion debug." ;;
        esac ;;
    *) fail "unknown target '$TARGET'. Use mac, legion, server, vm or all (see --help)." ;;
esac
step "Done"
