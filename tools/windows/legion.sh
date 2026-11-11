#!/usr/bin/env bash
# One command from the Mac: sync with Git, then build, install and/or debug AutoPilot on Legion.
#
#   tools/windows/legion.sh            # all: sync, build (with tests), install, then debug
#   tools/windows/legion.sh build      # sync, build the MSI
#   tools/windows/legion.sh install    # sync, build, install and check
#   tools/windows/legion.sh debug      # sync, start the debug run, open the debugger tunnel
#   tools/windows/legion.sh sync       # only make Legion's checkout match the pushed Mac commit
#   add --no-tests to skip the focused tests during the build
#
# Legion always builds from Git: the Mac's work must be committed. Unpushed commits are pushed first.
# Nothing is copied to Legion except through GitHub, and no uncommitted change on either side is
# overwritten; the script stops and says what to do instead.
#
# Environment overrides: LEGION_HOST (default home-legion), LEGION_DIR (Legion checkout).
set -euo pipefail

HOST="${LEGION_HOST:-home-legion}"
DIR="${LEGION_DIR:-C:\\Users\\jagde\\Projects\\autopilot-cmp}"
DEBUG_PORT=5005
ACTION=all
TESTS=1
for arg in "$@"; do
    case "$arg" in
        all|sync|build|install|debug) ACTION="$arg" ;;
        --no-tests) TESTS=0 ;;
        -h|--help) sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown option: $arg (see --help)" >&2; exit 2 ;;
    esac
done

step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
fail() { printf '\n\033[31mStopped: %s\033[0m\n' "$*" >&2; exit 1; }

# Runs PowerShell on Legion. The script is sent base64-encoded, so no quoting survives or breaks
# between this shell, ssh, cmd.exe and PowerShell.
remote_ps() {
    local script="\$ErrorActionPreference = 'Stop'; \$ProgressPreference = 'SilentlyContinue'; Set-Location '$DIR'
$1"
    local encoded
    encoded=$(printf '%s' "$script" | iconv -f UTF-8 -t UTF-16LE | base64 | tr -d '\n')
    ssh "$HOST" "powershell -NoProfile -ExecutionPolicy RemoteSigned -EncodedCommand $encoded"
}

sync() {
    step "Mac: check the work is committed and pushed"
    cd "$(git rev-parse --show-toplevel)"
    if [ -n "$(git status --porcelain)" ]; then
        git status --short
        fail "the Mac has uncommitted changes. Commit them (or say \"push\" to Claude), then run this again."
    fi
    git fetch -q origin
    local branch ahead behind
    branch=$(git rev-parse --abbrev-ref HEAD)
    [ "$branch" = main ] || fail "the Mac is on branch $branch; Legion builds main."
    behind=$(git rev-list --count HEAD..origin/main)
    ahead=$(git rev-list --count origin/main..HEAD)
    [ "$behind" -eq 0 ] || fail "GitHub has $behind commits the Mac doesn't. Pull on the Mac first."
    if [ "$ahead" -gt 0 ]; then
        echo "Pushing $ahead commit(s)..."
        git push -q origin main
    fi
    MAC_HEAD=$(git rev-parse HEAD)
    echo "Mac and GitHub: $(git log -1 --format='%h %s')"

    step "Legion: pull"
    remote_ps "
\$dirty = git status --porcelain
if (\$dirty) { \$dirty; throw 'Legion has uncommitted changes. Compare them with GitHub and stash them as a backup before syncing.' }
git pull --ff-only -q
if (\$LASTEXITCODE -ne 0) { throw 'git pull failed on Legion.' }
\$head = git rev-parse HEAD
if (\$head -ne '$MAC_HEAD') { throw \"Legion is on \$head, not $MAC_HEAD.\" }
'Legion: ' + (git log -1 --format='%h %s')
" || fail "Legion couldn't be synced (see above)."
}

build() {
    step "Legion: build the MSI$( [ "$TESTS" -eq 1 ] && echo ' and run focused tests')"
    local skip=''
    [ "$TESTS" -eq 1 ] || skip=' -SkipTests'
    remote_ps "& .\\tools\\windows\\Build-Distribution.ps1$skip" || fail "the Legion build failed (see above)."
}

install() {
    step "Legion: install or update, then check"
    remote_ps "& .\\tools\\windows\\Install-Msi.ps1 -LaunchCheck -SkipIfInstalled" || fail "the install or its checks failed (see above)."
}

debug() {
    step "Legion: start the debug run"
    if lsof -nP -iTCP:"$DEBUG_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
        fail "port $DEBUG_PORT is already in use on the Mac (an earlier tunnel?). Close it and run again."
    fi
    remote_ps "
if (-not (Get-ScheduledTask -TaskName 'AutoPilot-Debug' -ErrorAction SilentlyContinue)) { throw 'The AutoPilot-Debug task is not registered. Run tools\\windows\\Register-DebugLauncher.ps1 once on the Legion desktop.' }
\$running = @(Get-Process javaw, AutoPilot -ErrorAction SilentlyContinue | Where-Object { \$_.Path -like 'C:\\Program Files\\AutoPilot\\*' })
if (\$running) { throw 'The installed AutoPilot is open on Legion. Close it normally first; both copies share the same data.' }
if (Get-NetTCPConnection -LocalPort $DEBUG_PORT -State Listen -ErrorAction SilentlyContinue) { 'A debug run is already waiting on port $DEBUG_PORT.'; return }
Start-ScheduledTask -TaskName 'AutoPilot-Debug'
'Started AutoPilot-Debug on the signed-in desktop. Waiting for it to build and listen on port $DEBUG_PORT...'
\$deadline = (Get-Date).AddMinutes(15)
while (-not (Get-NetTCPConnection -LocalPort $DEBUG_PORT -State Listen -ErrorAction SilentlyContinue)) {
    if ((Get-Date) -gt \$deadline) { throw 'The debug run did not start listening within 15 minutes. Check the newest debug-*.log in %USERPROFILE%\\.autopilot\\logs.' }
    if ((Get-ScheduledTask -TaskName 'AutoPilot-Debug').State -ne 'Running') { throw 'The debug run stopped before the debugger could attach. Check the newest debug-*.log in %USERPROFILE%\\.autopilot\\logs.' }
    Start-Sleep -Seconds 5
}
'The debug run is waiting for the debugger.'
" || fail "the debug run didn't start (see above)."

    step "Mac: debugger tunnel on 127.0.0.1:$DEBUG_PORT"
    echo "In Android Studio, choose \"AutoPilot Windows (SSH tunnel)\" and click Debug."
    echo "The app window appears on Legion once the debugger attaches. Press Control-C here to close the tunnel."
    ssh -N -o ExitOnForwardFailure=yes -L "127.0.0.1:$DEBUG_PORT:127.0.0.1:$DEBUG_PORT" "$HOST"
}

sync
case "$ACTION" in
    sync) ;;
    build) build ;;
    install) build; install ;;
    debug) debug ;;
    all) build; install; debug ;;
esac
step "Done"
