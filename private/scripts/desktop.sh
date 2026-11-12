#!/usr/bin/env bash
# One command from the Mac to sync, build, install and debug AutoPilot on every desktop machine.
#
#   private/scripts/desktop.sh [--no-tests] [--remote] <action> <target>
#
#   Standard action-first examples:
#     private/scripts/desktop.sh sync all
#     private/scripts/desktop.sh build all
#     private/scripts/desktop.sh run all
#     private/scripts/desktop.sh fetch all
#
#   target   mac      this Mac: macOS app in /Applications
#            legion   Windows laptop (ssh home-legion): MSI, installed on Legion
#            server   Debian home server (ssh home-server): Linux DEB, copied to the Mac
#            server-vm-win Windows VM on the home server (ssh home-server-build): MSI, copied to the Mac
#            mac-vm-win  Windows VM on this Mac (SSH host/path supplied by environment variables)
#            mac-vm-linux  Linux VM on this Mac (SSH host/path supplied by environment variables)
#            local-all      this Mac plus its local Windows and Linux VMs
#            all      every configured desktop target: mac, Legion, server and both VM pairs
#
#   action   sync     push the Mac's commits and pull them on the target
#            build    sync, focused tests, then build the installer
#            run      build, then install/check the target
#            fetch    copy existing installers to ~/Downloads/Builds/AutoPilot   (all); build then copy one installer (server, server-vm-win)
#            debug    sync, start a debug run waiting on port 5005   (mac, legion, mac-vm-win, mac-vm-linux)
#
#   --no-tests  skip the focused tests
#   --remote    reach the home server through Cloudflare (home-server-remote, home-server-build-remote)
#
#   Local VM configuration (defaults are examples; set these to your SSH aliases and checkout paths):
#   MAC_WINDOWS_HOST, MAC_WINDOWS_DIR, MAC_LINUX_HOST, MAC_LINUX_DIR
#
# Remote machines always build from Git: the Mac's work must be committed; unpushed commits are
# pushed; the target pulls and must end on the same commit. Uncommitted changes on either side are
# never overwritten: the script stops and says what to do. See private/docs/01_ONE_COMMAND_BUILDS.md.
set -euo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"   # absolute, so it still works after cd
ROOT="$(cd "$(dirname "$SCRIPT")/../.." && pwd)"
cd "$ROOT"

LEGION_HOST="${LEGION_HOST:-home-legion}"
LEGION_DIR='C:\Users\jagde\Projects\autopilot-cmp'
SERVER_HOST="${SERVER_HOST:-home-server}"
SERVER_DIR='/srv/build/autopilot-cmp'
VM_HOST="${VM_HOST:-home-server-build}"
VM_DIR='C:\Build\autopilot-cmp'
MAC_WINDOWS_HOST="${MAC_WINDOWS_HOST:-mac-windows-vm}"
MAC_WINDOWS_DIR="${MAC_WINDOWS_DIR:-C:\Users\Jagdeep\Projects\AutoPilot}"
MAC_LINUX_HOST="${MAC_LINUX_HOST:-mac-linux-vm}"
MAC_LINUX_DIR="${MAC_LINUX_DIR:-/home/jagdeep/Projects/AutoPilot}"
DOWNLOADS="$HOME/Downloads/Builds/AutoPilot"
DEBUG_PORT=5005
LOW_MEMORY=(--no-daemon --console=plain --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process')
FOCUSED_TESTS=(:core:automation:jvmTest :feature:home:jvmTest --tests '*DgsStudy*' :composeApp:jvmTest --tests '*DesktopDataPathsTest*' --tests '*AppVersionTest*')

# The help text is the comment block at the top of this file, up to the first line of code.
usage() { awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "$SCRIPT"; }

POSITIONAL=()
TESTS=1
for arg in "$@"; do
    case "$arg" in
        --no-tests) TESTS=0 ;;
        --remote) SERVER_HOST=home-server-remote; VM_HOST=home-server-build-remote ;;
        -h|--help) usage; exit 0 ;;
        *) POSITIONAL+=("$arg") ;;
    esac
done

TARGET=''
ACTIONS=()
is_target() { case "$1" in mac|legion|server|server-vm-win|mac-vm-win|mac-vm-linux|local-all|all) return 0 ;; *) return 1 ;; esac; }
is_action() { case "$1" in sync|build|run|fetch|debug) return 0 ;; *) return 1 ;; esac; }

if [ "${#POSITIONAL[@]}" -eq 2 ] && is_action "${POSITIONAL[0]}" && is_target "${POSITIONAL[1]}"; then
    ACTIONS=("${POSITIONAL[0]}")
    TARGET="${POSITIONAL[1]}"
else
    echo "Use exactly: private/scripts/desktop.sh <action> <target> (see --help)." >&2
    usage
    exit 2
fi

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

# Sync a Linux VM using the same clean-check and exact-commit rule as the home server.
linux_sync() {
    local name="$1" host="$2" dir="$3"
    mac_check_pushed
    step "$name: pull"
    ssh "$host" "set -e; cd '$dir'; dirty=\$(git status --porcelain); if [ -n \"\$dirty\" ]; then git status --short; echo '$name has uncommitted changes. Compare them with GitHub and stash them as a backup before syncing.' >&2; exit 1; fi; git pull --ff-only -q; [ \"\$(git rev-parse HEAD)\" = '$MAC_HEAD' ] || { echo '$name is not on the Mac commit after pulling.' >&2; exit 1; }; echo \"$name: \$(git log -1 --format='%h %s')\"" \
        || fail "$name couldn't be synced (see above)."
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
    windows_ps "$LEGION_HOST" "$LEGION_DIR" "& .\\private\\scripts\\windows\\Build-Distribution.ps1$( [ "$TESTS" -eq 1 ] || echo ' -SkipTests')" \
        || fail "the Legion build failed (see above)."
}

legion_install() {
    step "Legion: install or update, then check"
    windows_ps "$LEGION_HOST" "$LEGION_DIR" "& .\\private\\scripts\\windows\\Install-Msi.ps1 -LaunchCheck -SkipIfInstalled" \
        || fail "the install or its checks failed (see above)."
}

legion_debug() {
    windows_sync Legion "$LEGION_HOST" "$LEGION_DIR"
    step "Legion: start the debug run"
    lsof -nP -iTCP:"$DEBUG_PORT" -sTCP:LISTEN >/dev/null 2>&1 && fail "port $DEBUG_PORT is already in use on the Mac. Close it and run again."
    windows_ps "$LEGION_HOST" "$LEGION_DIR" "
\$task = Get-ScheduledTask -TaskName 'AutoPilot-Debug' -ErrorAction SilentlyContinue
if (-not \$task) { throw 'The AutoPilot-Debug task is not registered. Run private\\scripts\\windows\\Register-DebugLauncher.ps1 once on the Legion desktop.' }
# The task stores the launcher's path; after the scripts move, point it at the current one.
if (-not ((\$task.Actions | ForEach-Object Arguments) -like '*private\\scripts\\windows\\Start-Debug.ps1*')) {
    & .\\private\\scripts\\windows\\Register-DebugLauncher.ps1
}
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

# --- Mac-local Windows and Linux VMs ------------------------------------------------------------
local_windows_build() {
    windows_sync "Mac Windows VM" "$MAC_WINDOWS_HOST" "$MAC_WINDOWS_DIR"
    step "Mac Windows VM: $( [ "$TESTS" -eq 1 ] && echo 'focused tests, then ')build the MSI"
    windows_ps "$MAC_WINDOWS_HOST" "$MAC_WINDOWS_DIR" "& .\\private\\scripts\\windows\\Build-Distribution.ps1$( [ "$TESTS" -eq 1 ] || echo ' -SkipTests')" \
        || fail "the Mac Windows VM build failed (see above)."
}

local_windows_install() {
    step "Mac Windows VM: install or update, then check"
    windows_ps "$MAC_WINDOWS_HOST" "$MAC_WINDOWS_DIR" "& .\\private\\scripts\\windows\\Install-Msi.ps1 -LaunchCheck -SkipIfInstalled" \
        || fail "the Mac Windows VM install or its checks failed (see above)."
}

local_linux_build() {
    linux_sync "Mac Linux VM" "$MAC_LINUX_HOST" "$MAC_LINUX_DIR"
    step "Mac Linux VM: $( [ "$TESTS" -eq 1 ] && echo 'focused tests, then ')build the DEB"
    local tasks=("${LOW_MEMORY[@]}")
    [ "$TESTS" -eq 1 ] && tasks+=("${FOCUSED_TESTS[@]}")
    tasks+=(:composeApp:packageReleaseDeb)
    ssh "$MAC_LINUX_HOST" "cd '$MAC_LINUX_DIR' && ./gradlew $(printf "%q " "${tasks[@]}")" \
        || fail "the Mac Linux VM build failed (see above)."
}

local_linux_install() {
    step "Mac Linux VM: install the newest DEB and check"
    local deb
    deb=$(ssh "$MAC_LINUX_HOST" "find '$MAC_LINUX_DIR/composeApp/build/compose/binaries/main-release/deb' -maxdepth 1 -type f -name '*.deb' -printf '%T@ %p\\n' | sort -nr | head -1 | cut -d' ' -f2-" | tr -d '\r') \
        || fail "couldn't find the Mac Linux VM DEB. Build it first."
    [ -n "$deb" ] || fail "no DEB found on the Mac Linux VM. Build it first."
    ssh "$MAC_LINUX_HOST" "sudo apt-get install -y '$deb'" \
        || fail "the Mac Linux VM DEB install failed (see above)."
    echo "Installed $deb on the Mac Linux VM."
}

local_windows_debug() {
    windows_sync "Mac Windows VM" "$MAC_WINDOWS_HOST" "$MAC_WINDOWS_DIR"
    step "Mac Windows VM: start the debug run"
    lsof -nP -iTCP:"$DEBUG_PORT" -sTCP:LISTEN >/dev/null 2>&1 && fail "port $DEBUG_PORT is already in use on the Mac. Close it and run again."
    windows_ps "$MAC_WINDOWS_HOST" "$MAC_WINDOWS_DIR" "
\$task = Get-ScheduledTask -TaskName 'AutoPilot-Debug' -ErrorAction SilentlyContinue
if (-not \$task) { throw 'The AutoPilot-Debug task is not registered. Run private\\scripts\\windows\\Register-DebugLauncher.ps1 once inside the Windows VM desktop.' }
if (-not ((\$task.Actions | ForEach-Object Arguments) -like '*private\\scripts\\windows\\Start-Debug.ps1*')) { & .\\private\\scripts\\windows\\Register-DebugLauncher.ps1 }
if (Get-NetTCPConnection -LocalPort $DEBUG_PORT -State Listen -ErrorAction SilentlyContinue) { 'A debug run is already waiting on port $DEBUG_PORT.'; return }
Start-ScheduledTask -TaskName 'AutoPilot-Debug'
'Started AutoPilot-Debug in the Mac Windows VM. Waiting for port $DEBUG_PORT...'
\$deadline = (Get-Date).AddMinutes(15)
while (-not (Get-NetTCPConnection -LocalPort $DEBUG_PORT -State Listen -ErrorAction SilentlyContinue)) {
    if ((Get-Date) -gt \$deadline) { throw 'The debug run did not listen within 15 minutes. Check the newest debug-*.log in %USERPROFILE%\\.autopilot\\logs.' }
    if ((Get-ScheduledTask -TaskName 'AutoPilot-Debug').State -ne 'Running') { throw 'The debug run stopped early. Check the newest debug-*.log in %USERPROFILE%\\.autopilot\\logs.' }
    Start-Sleep -Seconds 5
}
'The Mac Windows VM debug run is waiting for the debugger.'
" || fail "the Mac Windows VM debug run didn't start (see above)."
    step "Mac: debugger tunnel on 127.0.0.1:$DEBUG_PORT"
    echo "In Android Studio, attach the Windows debug configuration to the Mac Windows VM. Press Control-C here to close the tunnel."
    ssh -S none -N -o ExitOnForwardFailure=yes -L "127.0.0.1:$DEBUG_PORT:127.0.0.1:$DEBUG_PORT" "$MAC_WINDOWS_HOST"
}

local_linux_debug() {
    linux_sync "Mac Linux VM" "$MAC_LINUX_HOST" "$MAC_LINUX_DIR"
    lsof -nP -iTCP:"$DEBUG_PORT" -sTCP:LISTEN >/dev/null 2>&1 && fail "port $DEBUG_PORT is already in use on the Mac. Close it and run again."
    step "Mac Linux VM: start the debug run and tunnel port $DEBUG_PORT"
    echo "The Linux VM must have a desktop session available to the SSH user. Press Control-C to stop the run and close the tunnel."
    ssh -t -S none -o ExitOnForwardFailure=yes -L "127.0.0.1:$DEBUG_PORT:127.0.0.1:$DEBUG_PORT" "$MAC_LINUX_HOST" \
        "cd '$MAC_LINUX_DIR' && ./gradlew --console=plain :composeApp:run --debug-jvm"
}

local_all_sync() {
    mac_check_pushed
    windows_sync "Mac Windows VM" "$MAC_WINDOWS_HOST" "$MAC_WINDOWS_DIR"
    linux_sync "Mac Linux VM" "$MAC_LINUX_HOST" "$MAC_LINUX_DIR"
}

local_all_build() {
    mac_check_pushed
    mac_build
    local_windows_build
    local_linux_build
}

local_all_install() {
    local_all_build
    mac_install
    local_windows_install
    local_linux_install
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

# --- Fetch every existing Legion MSI and server DEB ---------------------------------------------
# This is deliberately separate from a build: it never syncs Git, builds, installs or deletes.
safe_artifact_name() { [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*\.(msi|deb)$ ]]; }

fetch_artifact_group() {
    local label="$1" host="$2" remote_dir="$3" extension="$4"
    local names name remote_hash local_hash copied=0
    step "$label: find .$extension artifacts"
    if [ "$extension" = msi ]; then
        names=$(ssh "$host" "powershell -NoProfile -Command \"Get-ChildItem -LiteralPath '$remote_dir' -Filter '*.$extension' -File | Sort-Object Name | ForEach-Object { \$_.Name }\"" | tr -d '\r') \
            || fail "$label couldn't be reached."
    else
        names=$(ssh "$host" "find '$remote_dir' -maxdepth 1 -type f -name '*.$extension' -printf '%f\\n' | sort") \
            || fail "$label couldn't be reached."
    fi
    [ -n "$names" ] || fail "no .$extension artifacts found on $label. Build one there first."
    mkdir -p "$DOWNLOADS"

    while IFS= read -r name; do
        [ -n "$name" ] || continue
        safe_artifact_name "$name" || fail "$label returned an unsafe artifact name: $name"
        step "$label: copy $name"
        scp -q "$host:$remote_dir/$name" "$DOWNLOADS/$name" \
            || fail "couldn't copy $name from $label."
        if [ "$extension" = msi ]; then
            remote_hash=$(ssh "$host" "powershell -NoProfile -Command \"(Get-FileHash -Algorithm SHA256 -LiteralPath '$remote_dir/$name').Hash\"" | tr -d '\r' | tr 'A-F' 'a-f') \
                || fail "couldn't read the hash for $name on $label."
        else
            remote_hash=$(ssh "$host" "sha256sum '$remote_dir/$name'" | awk '{print $1}') \
                || fail "couldn't read the hash for $name on $label."
        fi
        local_hash=$(shasum -a 256 "$DOWNLOADS/$name" | awk '{print $1}')
        [ "$remote_hash" = "$local_hash" ] || fail "$name doesn't match $label after copying (hash mismatch)."
        echo "Copied $DOWNLOADS/$name (SHA256 $local_hash, matches $label)."
        copied=$((copied + 1))
    done <<< "$names"
    echo "$label: copied and verified $copied .$extension file(s)."
}

fetch_all_builds() {
    fetch_artifact_group Legion "$LEGION_HOST" 'C:/Users/jagde/Projects/autopilot-cmp/composeApp/build/compose/binaries/main-release/msi' msi
    fetch_artifact_group "Home server" "$SERVER_HOST" "$SERVER_DIR/composeApp/build/compose/binaries/main-release/deb" deb
    echo "All available Legion MSIs and home-server DEBs are in $DOWNLOADS."
}

# --- Home server Windows VM (MSI) ---------------------------------------------------------------
vm_build() {
    step "Server VM: check it's running"
    ssh "$SERVER_HOST" "pgrep -f '^/usr/bin/qemu-system-x86_64 -name windows-build ' >/dev/null" \
        || fail "the Windows VM isn't running. Start it with: private/scripts/server-vm.sh start --headless"
    windows_sync "Server VM" "$VM_HOST" "$VM_DIR"
    step "Server VM: $( [ "$TESTS" -eq 1 ] && echo 'focused tests, then ')build the MSI"
    windows_ps "$VM_HOST" "$VM_DIR" "& .\\private\\scripts\\windows\\Build-Distribution.ps1$( [ "$TESTS" -eq 1 ] || echo ' -SkipTests')" \
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
unsupported() { fail "'$1' isn't available for $TARGET. $2"; }

run_action() {
    local action="$1"
    case "$action:$TARGET" in
        sync:mac) mac_sync ;;
        sync:legion) windows_sync Legion "$LEGION_HOST" "$LEGION_DIR" ;;
        sync:server) server_sync ;;
        sync:server-vm-win) windows_sync "Server VM" "$VM_HOST" "$VM_DIR" ;;
        sync:mac-vm-win) windows_sync "Mac Windows VM" "$MAC_WINDOWS_HOST" "$MAC_WINDOWS_DIR" ;;
        sync:mac-vm-linux) linux_sync "Mac Linux VM" "$MAC_LINUX_HOST" "$MAC_LINUX_DIR" ;;
        sync:local-all) local_all_sync ;;
        sync:all) mac_check_pushed; windows_sync Legion "$LEGION_HOST" "$LEGION_DIR"; server_sync; windows_sync "Server VM" "$VM_HOST" "$VM_DIR"; windows_sync "Mac Windows VM" "$MAC_WINDOWS_HOST" "$MAC_WINDOWS_DIR"; linux_sync "Mac Linux VM" "$MAC_LINUX_HOST" "$MAC_LINUX_DIR" ;;

        build:mac) mac_build ;;
        build:legion) legion_build ;;
        build:server) server_build ;;
        build:server-vm-win) vm_build ;;
        build:mac-vm-win) local_windows_build ;;
        build:mac-vm-linux) local_linux_build ;;
        build:local-all) local_all_build ;;
        build:all) mac_check_pushed; mac_build; legion_build; server_build; vm_build; local_windows_build; local_linux_build ;;

        run:mac) mac_build; mac_install ;;
        run:legion) legion_build; legion_install ;;
        run:server) server_build ;;
        run:server-vm-win) vm_build; vm_fetch ;;
        run:mac-vm-win) local_windows_build; local_windows_install ;;
        run:mac-vm-linux) local_linux_build; local_linux_install ;;
        run:local-all) local_all_install ;;
        run:all) mac_check_pushed; mac_build; mac_install; legion_build; legion_install; server_build; vm_build; vm_fetch; local_windows_build; local_windows_install; local_linux_build; local_linux_install ;;

        fetch:all) fetch_all_builds ;;
        fetch:server) server_build; server_fetch ;;
        fetch:server-vm-win) vm_build; vm_fetch ;;
        fetch:mac|fetch:legion|fetch:mac-vm-win|fetch:mac-vm-linux|fetch:local-all)
            unsupported fetch "This target does not copy an installer to the Mac." ;;

        debug:mac) mac_sync; mac_debug ;;
        debug:legion) legion_debug ;;
        debug:mac-vm-win) local_windows_debug ;;
        debug:mac-vm-linux) local_linux_debug ;;
        debug:server|debug:server-vm-win|debug:local-all|debug:all)
            unsupported debug "Debug one desktop at a time: use mac, legion, mac-vm-win or mac-vm-linux." ;;

        *) fail "'$action' isn't available for $TARGET. See --help." ;;
    esac
}

for action in "${ACTIONS[@]}"; do
    run_action "$action"
done
step "Done"
