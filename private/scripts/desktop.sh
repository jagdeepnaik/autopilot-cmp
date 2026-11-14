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
#     private/scripts/desktop.sh sync-data all
#     private/scripts/desktop.sh merge-answer-bank all
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
#            sync-data copy each machine's ~/.autopilot and ~/Downloads/AutoPilot to the Mac's
#                     ~/Downloads/AutoPilot/<machine> folder; it does not change Git or builds
#            merge-answer-bank merge every synced answer bank into ~/Downloads/AutoPilot;
#                               it does not change Git, build or install
#            build    sync, focused tests, then build the installer
#            run      build, then install/check the target
#            fetch    copy existing installers to ~/Downloads/Builds/AutoPilot   (all, legion); build then copy one installer (server, server-vm-win)
#            debug    sync, start a debug run waiting on port 5005   (mac, legion, mac-vm-win, mac-vm-linux)
#
#   --no-tests  skip the focused tests
#   --remote    force the home server through Cloudflare (home-server-remote, home-server-build-remote)
#               Server targets otherwise try local SSH first and fall back automatically.
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
SYNC_DATA_ROOT="${SYNC_DATA_ROOT:-$HOME/Downloads/AutoPilot/Sync}"
LEGION_HOME='C:/Users/jagde'
SERVER_HOME='/home/jagdeep'
VM_HOME='C:/Users/builduser'
MAC_WINDOWS_HOME="${MAC_WINDOWS_HOME:-C:\Users\Jagdeep}"
MAC_LINUX_HOME="${MAC_LINUX_HOME:-/home/jagdeep}"
DEBUG_PORT=5005
LOW_MEMORY=(--no-daemon --console=plain --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process')
# The Mac-local Linux VM may have less memory than the home server. Keep Gradle and the Kotlin
# compiler daemon inside a smaller envelope so the guest OS does not kill the build process.
LOCAL_LINUX_MEMORY=(--no-daemon --console=plain --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process' '-Dkotlin.daemon.jvm.options=-Xmx512m')
FOCUSED_TESTS=(:core:automation:jvmTest :feature:home:jvmTest --tests '*DgsStudy*' :shared:jvmTest --tests '*DesktopDataPathsTest*' --tests '*AppVersionTest*')

# The help text is the comment block at the top of this file, up to the first line of code.
usage() { awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "$SCRIPT"; }

POSITIONAL=()
TESTS=1
REMOTE_MODE=0
for arg in "$@"; do
    case "$arg" in
        --no-tests) TESTS=0 ;;
        --remote) REMOTE_MODE=1; SERVER_HOST=home-server-remote; VM_HOST=home-server-build-remote ;;
        -h|--help) usage; exit 0 ;;
        *) POSITIONAL+=("$arg") ;;
    esac
done

TARGET=''
ACTIONS=()
is_target() { case "$1" in mac|legion|server|server-vm-win|mac-vm-win|mac-vm-linux|local-all|all) return 0 ;; *) return 1 ;; esac; }
is_action() { case "$1" in sync|sync-data|merge-answer-bank|build|run|fetch|debug) return 0 ;; *) return 1 ;; esac; }

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

select_server_hosts() {
    [ "$REMOTE_MODE" -eq 1 ] && return 0
    if [[ "$TARGET" == server || "$TARGET" == all ]] && ! ssh -o BatchMode=yes -o ConnectTimeout=8 -o ConnectionAttempts=1 "$SERVER_HOST" true >/dev/null 2>&1; then
        echo "Home server local SSH is unavailable; using home-server-remote."
        SERVER_HOST=home-server-remote
    fi
    if [[ "$TARGET" == server-vm-win || "$TARGET" == all ]] && ! ssh -o BatchMode=yes -o ConnectTimeout=8 -o ConnectionAttempts=1 "$VM_HOST" true >/dev/null 2>&1; then
        echo "Server VM local SSH is unavailable; using home-server-build-remote."
        SERVER_HOST=home-server-remote
        VM_HOST=home-server-build-remote
    fi
}

case "$TARGET" in
    server|server-vm-win|all) select_server_hosts ;;
esac

# PowerShell on a Windows machine. The script is sent base64-encoded, so no quoting breaks between
# this shell, ssh, the remote default shell and PowerShell.
windows_ps() {
    local host="$1" dir="$2" body="$3" script encoded
    script="\$ErrorActionPreference = 'Stop'; \$ProgressPreference = 'SilentlyContinue'; Set-Location '$dir'
$body"
    encoded=$(printf '%s' "$script" | iconv -f UTF-8 -t UTF-16LE | base64 | tr -d '\n')
    ssh "$host" "powershell -NoProfile -ExecutionPolicy RemoteSigned -EncodedCommand $encoded"
}

# PowerShell on a Windows machine without assuming the project checkout exists. This is used for
# copying per-user AutoPilot data, which may still be available when the source checkout is missing.
windows_ps_raw() {
    local host="$1" body="$2" encoded
    encoded=$(printf '%s' "\$ErrorActionPreference = 'Stop'; \$ProgressPreference = 'SilentlyContinue'
$body" | iconv -f UTF-8 -t UTF-16LE | base64 | tr -d '\n')
    ssh "${DATA_SSH_OPTS[@]}" "$host" "powershell -NoProfile -ExecutionPolicy RemoteSigned -EncodedCommand $encoded"
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
    tasks+=(:desktopApp:createReleaseDistributable)
    step "Mac: $( [ "$TESTS" -eq 1 ] && echo 'focused tests, then ')build the app"
    ./gradlew --console=plain "${tasks[@]}" || fail "the Mac build failed (see above)."
    echo "Built: $ROOT/desktopApp/build/compose/binaries/main-release/app/AutoPilot.app"
}

mac_install() {
    local app="$ROOT/desktopApp/build/compose/binaries/main-release/app/AutoPilot.app"
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
    ./gradlew --console=plain :desktopApp:run --debug-jvm
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
    local tasks=("${LOCAL_LINUX_MEMORY[@]}")
    [ "$TESTS" -eq 1 ] && tasks+=("${FOCUSED_TESTS[@]}")
    tasks+=(:desktopApp:packageReleaseDeb)
    ssh "$MAC_LINUX_HOST" "cd '$MAC_LINUX_DIR' && ./gradlew $(printf "%q " "${tasks[@]}")" \
        || fail "the Mac Linux VM build failed (see above)."
}

local_linux_install() {
    step "Mac Linux VM: install the newest DEB and check"
    local deb
    deb=$(ssh "$MAC_LINUX_HOST" "find '$MAC_LINUX_DIR/desktopApp/build/compose/binaries/main-release/deb' -maxdepth 1 -type f -name '*.deb' -printf '%T@ %p\\n' | sort -nr | head -1 | cut -d' ' -f2-" | tr -d '\r') \
        || fail "couldn't find the Mac Linux VM DEB. Build it first."
    [ -n "$deb" ] || fail "no DEB found on the Mac Linux VM. Build it first."
    # Allocate a terminal so sudo can prompt the Linux VM user for its password.
    ssh -tt "$MAC_LINUX_HOST" "sudo apt-get install -y '$deb'" \
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
        "cd '$MAC_LINUX_DIR' && ./gradlew --console=plain :desktopApp:run --debug-jvm"
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
    tasks+=(:desktopApp:packageReleaseDeb)
    ssh "$SERVER_HOST" "cd $SERVER_DIR && nice -n 10 ionice -c 2 -n 7 ./gradlew $(printf "%q " "${tasks[@]}")" \
        || fail "the server build failed (see above)."
}

server_fetch() {
    step "Server: copy the DEB to the Mac"
    local deb name metadata package version deb_arch destination_name remote local_hash
    deb=$(ssh "$SERVER_HOST" "ls -t $SERVER_DIR/desktopApp/build/compose/binaries/main-release/deb/*.deb | head -1") || fail "no DEB on the server."
    name=$(basename "$deb")
    metadata=$(ssh "$SERVER_HOST" "dpkg-deb -f '$deb' Package Version Architecture" | tr '\n' '|') \
        || fail "couldn't read metadata for $name on the server."
    IFS='|' read -r package version deb_arch _ <<< "$metadata"
    [ -n "$package" ] && [ -n "$version" ] && [ -n "$deb_arch" ] \
        || fail "incomplete DEB metadata for $name on the server."
    destination_name="$(printf '%s' "$package" | tr '[:upper:]' '[:lower:]')_${version}_$(normalize_architecture "$deb_arch").deb"
    mkdir -p "$DOWNLOADS"
    scp -q "$SERVER_HOST:$deb" "$DOWNLOADS/$destination_name"
    remote=$(ssh "$SERVER_HOST" "sha256sum '$deb'" | cut -d' ' -f1)
    local_hash=$(shasum -a 256 "$DOWNLOADS/$destination_name" | cut -d' ' -f1)
    [ "$remote" = "$local_hash" ] || fail "the copied $destination_name doesn't match the server's (hash mismatch)."
    echo "Copied $DOWNLOADS/$destination_name (SHA256 $local_hash, matches the server)."
    echo "Install it on a Linux desktop, not on the server: sudo apt install ./$destination_name"
}

# --- Fetch every existing Legion MSI and server DEB ---------------------------------------------
# This is deliberately separate from a build: it never syncs Git, builds, installs or deletes.
safe_artifact_name() { [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*\.(msi|deb)$ ]]; }
normalize_architecture() {
    case "$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | tr -d '\r')" in
        amd64|x86_64|x64) echo amd64 ;;
        arm64|aarch64) echo arm64 ;;
        x86|i386|i686) echo x86 ;;
        *) fail "unsupported build architecture: $1" ;;
    esac
}
normalized_msi_name() {
    local source="$1" architecture="$2" version
    version=$(printf '%s' "$source" | sed -nE 's/^AutoPilot[-_]([0-9]+\.[0-9]+\.[0-9]+).*\.msi$/\1/p')
    [ -n "$version" ] || fail "couldn't read the version from MSI name: $source"
    echo "AutoPilot_${version}_${architecture}.msi"
}

fetch_artifact_group() {
    local label="$1" host="$2" remote_dir="$3" extension="$4"
    local names name remote_hash local_hash copied=0 destination_name architecture metadata package version deb_arch
    step "$label: find .$extension artifacts"
    if [ "$extension" = msi ]; then
        names=$(ssh "$host" "powershell -NoProfile -Command \"Get-ChildItem -LiteralPath '$remote_dir' -Filter '*.$extension' -File | Sort-Object Name | ForEach-Object { \$_.Name }\"" | tr -d '\r') \
            || fail "$label couldn't be reached."
        architecture=$(normalize_architecture "$(windows_ps_raw "$host" '$env:PROCESSOR_ARCHITECTURE' | tr -d '\r')")
    else
        names=$(ssh "$host" "find '$remote_dir' -maxdepth 1 -type f -name '*.$extension' -printf '%f\\n' | sort") \
            || fail "$label couldn't be reached."
    fi
    [ -n "$names" ] || fail "no .$extension artifacts found on $label. Build one there first."
    mkdir -p "$DOWNLOADS"

    while IFS= read -r name; do
        [ -n "$name" ] || continue
        safe_artifact_name "$name" || fail "$label returned an unsafe artifact name: $name"
        if [ "$extension" = msi ]; then
            destination_name=$(normalized_msi_name "$name" "$architecture")
        else
            metadata=$(ssh "$host" "dpkg-deb -f '$remote_dir/$name' Package Version Architecture" | tr '\n' '|') \
                || fail "couldn't read metadata for $name on $label."
            IFS='|' read -r package version deb_arch _ <<< "$metadata"
            [ -n "$package" ] && [ -n "$version" ] && [ -n "$deb_arch" ] \
                || fail "incomplete DEB metadata for $name on $label."
            destination_name="$(printf '%s' "$package" | tr '[:upper:]' '[:lower:]')_${version}_$(normalize_architecture "$deb_arch").deb"
        fi
        safe_artifact_name "$destination_name" || fail "$label produced an unsafe destination name: $destination_name"
        step "$label: copy $name as $destination_name"
        scp -q "$host:$remote_dir/$name" "$DOWNLOADS/$destination_name" \
            || fail "couldn't copy $name from $label."
        if [ "$extension" = msi ]; then
            remote_hash=$(ssh "$host" "powershell -NoProfile -Command \"(Get-FileHash -Algorithm SHA256 -LiteralPath '$remote_dir/$name').Hash\"" | tr -d '\r' | tr 'A-F' 'a-f') \
                || fail "couldn't read the hash for $name on $label."
        else
            remote_hash=$(ssh "$host" "sha256sum '$remote_dir/$name'" | awk '{print $1}') \
                || fail "couldn't read the hash for $name on $label."
        fi
        local_hash=$(shasum -a 256 "$DOWNLOADS/$destination_name" | awk '{print $1}')
        [ "$remote_hash" = "$local_hash" ] || fail "$destination_name doesn't match $label after copying (hash mismatch)."
        echo "Copied $DOWNLOADS/$destination_name (SHA256 $local_hash, matches $label)."
        copied=$((copied + 1))
    done <<< "$names"
    echo "$label: copied and verified $copied .$extension file(s)."
}

fetch_all_builds() {
    fetch_legion_builds
    fetch_artifact_group "Home server" "$SERVER_HOST" "$SERVER_DIR/desktopApp/build/compose/binaries/main-release/deb" deb
    echo "All available Legion MSIs and home-server DEBs are in $DOWNLOADS."
}

fetch_legion_builds() {
    fetch_artifact_group Legion "$LEGION_HOST" 'C:/Users/jagde/Projects/autopilot-cmp/desktopApp/build/compose/binaries/main-release/msi' msi
}

# --- Machine data sync --------------------------------------------------------------------------
# This copies app data, not source code: the hidden .autopilot folder contains local progress and
# settings, while Downloads/AutoPilot contains downloaded certificates. Existing files in the Mac
# Sync folder are updated; nothing is deleted. Data collection is best-effort: an offline machine
# is skipped after a short connection timeout and the next machine is processed.
DATA_SSH_OPTS=(-o ConnectTimeout=8 -o ConnectionAttempts=1)
copy_local_tree() {
    local source="$1" destination="$2"
    [ -d "$source" ] || { echo "Not present: $source (skipped)"; return 0; }
    mkdir -p "$destination"
    tar -C "$source" -cf - . | tar -C "$destination" -xf -
    echo "Copied $source -> $destination"
}

copy_local_downloads_tree() {
    local source="$1" destination="$2"
    [ -d "$source" ] || { echo "Not present: $source (skipped)"; return 0; }
    mkdir -p "$destination"
    # The per-machine folders live inside this source. Exclude them so a repeated sync cannot
    # recurse into its own previous output.
    tar -C "$source" \
        --exclude='./Sync' --exclude='./mac' --exclude='./legion' --exclude='./server' \
        --exclude='./server-vm-win' --exclude='./mac-vm-win' --exclude='./mac-vm-linux' \
        -cf - . | tar -C "$destination" -xf -
    echo "Copied $source (excluding machine folders) -> $destination"
}

copy_unix_tree() {
    local label="$1" host="$2" source="$3" destination="$4"
    if ! ssh "${DATA_SSH_OPTS[@]}" "$host" "test -d '$source'"; then
        echo "$label: not present: $source (skipped)"
        return 0
    fi
    mkdir -p "$destination"
    if ! ssh "${DATA_SSH_OPTS[@]}" "$host" "tar -C '$source' -cf - ." | tar -C "$destination" -xf -; then
        echo "$label: couldn't copy $source (skipped)"
        return 0
    fi
    echo "$label: copied $source -> $destination"
}

copy_windows_tree() {
    local label="$1" host="$2" source="$3" destination="$4" parent
    if ! windows_ps_raw "$host" "if (-not (Test-Path -LiteralPath '$source' -PathType Container)) { exit 3 }" >/dev/null; then
        echo "$label: not present: $source (skipped)"
        return 0
    fi
    parent=$(dirname "$destination")
    mkdir -p "$parent"
    if ! scp "${DATA_SSH_OPTS[@]}" -q -r "$host:$source" "$parent/"; then
        echo "$label: couldn't copy $source (skipped)"
        return 0
    fi
    echo "$label: copied $source -> $destination"
}

sync_data_local() {
    local machine="$1" home="$2" destination="$SYNC_DATA_ROOT/$1"
    step "$machine: copy AutoPilot data to Mac"
    copy_local_tree "$home/.autopilot" "$destination/Database"
    copy_local_downloads_tree "$home/Downloads/AutoPilot" "$destination/AutoPilot"
}

sync_data_windows() {
    local machine="$1" host="$2" home="$3" destination="$SYNC_DATA_ROOT/$1"
    step "$machine: copy AutoPilot data to Mac"
    copy_windows_tree "$machine" "$host" "$home/.autopilot" "$destination/Database"
    copy_windows_tree "$machine" "$host" "$home/Downloads/AutoPilot" "$destination/AutoPilot"
}

sync_data_linux() {
    local machine="$1" host="$2" home="$3" destination="$SYNC_DATA_ROOT/$1"
    step "$machine: copy AutoPilot data to Mac"
    copy_unix_tree "$machine" "$host" "$home/.autopilot" "$destination/Database"
    copy_unix_tree "$machine" "$host" "$home/Downloads/AutoPilot" "$destination/AutoPilot"
}

sync_data_all() {
    # Deliberately data-only: do not call mac_check_pushed or any source/Git sync helper here.
    # Progress databases, settings and certificates are local machine state and do not require
    # the project checkout to be committed or pushed.
    step "Copy AutoPilot data only (no Git sync)"
    sync_data_local mac "$HOME"
    sync_data_windows legion "$LEGION_HOST" "$LEGION_HOME"
    sync_data_linux server "$SERVER_HOST" "$SERVER_HOME"
    sync_data_windows server-vm-win "$VM_HOST" "$VM_HOME"
    sync_data_windows mac-vm-win "$MAC_WINDOWS_HOST" "$MAC_WINDOWS_HOME"
    sync_data_linux mac-vm-linux "$MAC_LINUX_HOST" "$MAC_LINUX_HOME"
    echo "Machine data is in $SYNC_DATA_ROOT"
}

merge_answer_bank() {
    [ "$TARGET" = all ] || fail "merge-answer-bank only supports the all target."
    local input_name='answer-banks.sagarmeinyog-ver-1.0.json'
    local output="${ANSWER_BANK_OUTPUT:-$HOME/Downloads/AutoPilot/$input_name}"
    step "Merge synced answer banks"
    mkdir -p "$(dirname "$output")"

    SYNC_DATA_ROOT="$SYNC_DATA_ROOT" OUTPUT="$output" INPUT_NAME="$input_name" python3 - <<'PY'
import json
import os
import sys
import unicodedata
from pathlib import Path

sync_root = Path(os.environ["SYNC_DATA_ROOT"])
output = Path(os.environ["OUTPUT"])
input_name = os.environ["INPUT_NAME"]
stored_names = {input_name, "sagarmeinyog-ver-1.0.json"}
sources = sorted(
    path for database in sync_root.glob("*/Database")
    if database.is_dir()
    for path in database.rglob("*.json")
    if path.is_file() and path.name in stored_names
)

if not sources:
    print(f"No synced answer banks found under {sync_root}/<machine>/Database/.", file=sys.stderr)
    print(f"Expected {input_name} or the app's stored sagarmeinyog-ver-1.0.json file.", file=sys.stderr)
    print("Run: private/scripts/desktop.sh sync-data all", file=sys.stderr)
    raise SystemExit(1)

def normalize(value):
    value = unicodedata.normalize("NFKC", str(value))
    return " ".join(value.split()).casefold()

def option_text(value):
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        for key in ("text", "label", "value"):
            if isinstance(value.get(key), str):
                return value[key]
    raise ValueError("option is not a string or a supported object")

merged = {}
seen = {}
module_names = {}
for source in sources:
    try:
        data = json.loads(source.read_text(encoding="utf-8"))
    except Exception as exc:
        raise SystemExit(f"Invalid JSON in {source}: {exc}")
    if not isinstance(data, dict):
        raise SystemExit(f"Invalid answer bank in {source}: the root must be an object.")
    for module, entries in data.items():
        if not isinstance(module, str) or not isinstance(entries, list):
            raise SystemExit(f"Invalid answer bank in {source}: module values must be arrays.")
        module_key = normalize(module)
        output_module = module_names.setdefault(module_key, module)
        merged.setdefault(output_module, [])
        for entry in entries:
            if not isinstance(entry, dict) or not isinstance(entry.get("question"), str):
                raise SystemExit(f"Invalid answer entry in {source} under module {module!r}.")
            options = entry.get("options")
            if not isinstance(options, list):
                raise SystemExit(f"Invalid options in {source} under module {module!r}.")
            options = [option_text(option) for option in options]
            answer = entry.get("answer")
            if answer is not None and not isinstance(answer, str):
                raise SystemExit(f"Invalid answer in {source} under module {module!r}.")
            key = (module_key, normalize(entry["question"]), tuple(sorted(normalize(option) for option in options)))
            answer_key = normalize(answer) if answer is not None else None
            previous = seen.get(key)
            if previous is not None:
                previous_answer, previous_source = previous
                if previous_answer != answer_key:
                    raise SystemExit(
                        "Conflicting answers for the same question/options:\n"
                        f"  {previous_source}: {previous_answer!r}\n"
                        f"  {source}: {answer_key!r}\n"
                        "Resolve the conflict in the synced files and run the merge again."
                    )
                continue
            seen[key] = (answer_key, source)
            merged[output_module].append({"question": entry["question"], "options": options, "answer": answer})

def sort_key(entry):
    return (normalize(entry["question"]), tuple(sorted(normalize(option) for option in entry["options"])))

ordered = {module: sorted(entries, key=sort_key) for module, entries in sorted(merged.items(), key=lambda item: normalize(item[0]))}
output.write_text(json.dumps(ordered, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"Read {len(sources)} machine file(s).")
print(f"Wrote {len(seen)} unique question/option set(s) to {output}.")
PY
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
    local msi destination_name architecture remote local_hash
    msi=$(windows_ps "$VM_HOST" "$VM_DIR" "(Get-ChildItem .\\desktopApp\\build\\compose\\binaries\\main-release\\msi\\*.msi | Sort-Object LastWriteTime | Select-Object -Last 1).Name" | tr -d '\r') \
        || fail "no MSI in the server VM."
    [ -n "$msi" ] || fail "no MSI in the server VM."
    architecture=$(normalize_architecture "$(windows_ps_raw "$VM_HOST" '$env:PROCESSOR_ARCHITECTURE' | tr -d '\r')")
    destination_name=$(normalized_msi_name "$msi" "$architecture")
    mkdir -p "$DOWNLOADS"
    scp -q "$VM_HOST:C:/Build/autopilot-cmp/desktopApp/build/compose/binaries/main-release/msi/$msi" "$DOWNLOADS/$destination_name"
    remote=$(windows_ps "$VM_HOST" "$VM_DIR" "(Get-FileHash .\\desktopApp\\build\\compose\\binaries\\main-release\\msi\\$msi).Hash" | tr -d '\r' | tr 'A-F' 'a-f')
    local_hash=$(shasum -a 256 "$DOWNLOADS/$destination_name" | cut -d' ' -f1)
    [ "$remote" = "$local_hash" ] || fail "the copied $destination_name doesn't match the VM's (hash mismatch)."
    echo "Copied $DOWNLOADS/$destination_name (SHA256 $local_hash, matches the VM)."
}

# --- Dispatch -----------------------------------------------------------------------------------
unsupported() { fail "'$1' isn't available for $TARGET. $2"; }

run_action() {
    local action="$1"
    case "$action:$TARGET" in
        sync:mac) mac_sync ;;
        sync-data:mac) sync_data_local mac "$HOME" ;;
        sync:legion) windows_sync Legion "$LEGION_HOST" "$LEGION_DIR" ;;
        sync-data:legion) sync_data_windows legion "$LEGION_HOST" "$LEGION_HOME" ;;
        sync:server) server_sync ;;
        sync-data:server) sync_data_linux server "$SERVER_HOST" "$SERVER_HOME" ;;
        sync:server-vm-win) windows_sync "Server VM" "$VM_HOST" "$VM_DIR" ;;
        sync-data:server-vm-win) sync_data_windows server-vm-win "$VM_HOST" "$VM_HOME" ;;
        sync:mac-vm-win) windows_sync "Mac Windows VM" "$MAC_WINDOWS_HOST" "$MAC_WINDOWS_DIR" ;;
        sync-data:mac-vm-win) sync_data_windows mac-vm-win "$MAC_WINDOWS_HOST" "$MAC_WINDOWS_HOME" ;;
        sync:mac-vm-linux) linux_sync "Mac Linux VM" "$MAC_LINUX_HOST" "$MAC_LINUX_DIR" ;;
        sync-data:mac-vm-linux) sync_data_linux mac-vm-linux "$MAC_LINUX_HOST" "$MAC_LINUX_HOME" ;;
        sync:local-all) local_all_sync ;;
        sync-data:local-all) sync_data_local mac "$HOME"; sync_data_windows mac-vm-win "$MAC_WINDOWS_HOST" "$MAC_WINDOWS_HOME"; sync_data_linux mac-vm-linux "$MAC_LINUX_HOST" "$MAC_LINUX_HOME" ;;
        sync:all) mac_check_pushed; windows_sync Legion "$LEGION_HOST" "$LEGION_DIR"; server_sync; windows_sync "Server VM" "$VM_HOST" "$VM_DIR"; windows_sync "Mac Windows VM" "$MAC_WINDOWS_HOST" "$MAC_WINDOWS_DIR"; linux_sync "Mac Linux VM" "$MAC_LINUX_HOST" "$MAC_LINUX_DIR" ;;
        sync-data:all) sync_data_all ;;
        merge-answer-bank:all) merge_answer_bank ;;

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
        fetch:legion) fetch_legion_builds ;;
        fetch:server) server_build; server_fetch ;;
        fetch:server-vm-win) vm_build; vm_fetch ;;
        fetch:mac|fetch:mac-vm-win|fetch:mac-vm-linux|fetch:local-all)
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
