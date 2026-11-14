#!/usr/bin/env bash
# The single user-facing command for this project.
#
#   ./dev.sh <desktop-action> <target> [options]
#   ./dev.sh git <git-command> [arguments]
#   ./dev.sh vm <status|test|start|stop> [options]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
DESKTOP="$ROOT/private/scripts/desktop.sh"
GIT="$ROOT/private/scripts/git.sh"
SERVER_VM="$ROOT/private/scripts/server-vm.sh"

usage() {
    cat <<'EOF'
AutoPilot project command

Desktop workflows:
  ./dev.sh sync all
  ./dev.sh build legion
  ./dev.sh run mac
  ./dev.sh fetch all
  ./dev.sh debug legion
  ./dev.sh sync-data all
  ./dev.sh merge-answer-bank all

Git:
  ./dev.sh git status
  ./dev.sh git push-all "Describe the change"
  ./dev.sh git pull

Server Windows VM:
  ./dev.sh vm status
  ./dev.sh vm start --headless
  ./dev.sh vm test
  ./dev.sh vm stop

Pass --no-tests and --remote to desktop workflows, or --remote to VM commands.
Use ./dev.sh <command> --help for the detailed command help.
EOF
}

command="${1:-}"
case "$command" in
    git)
        shift
        exec "$GIT" "$@"
        ;;
    vm|server-vm)
        shift
        exec "$SERVER_VM" "$@"
        ;;
    sync|sync-data|merge-answer-bank|build|run|fetch|debug|--no-tests|--remote)
        exec "$DESKTOP" "$@"
        ;;
    -h|--help|help|'')
        usage
        ;;
    *)
        echo "Unknown command: $command" >&2
        usage >&2
        exit 2
        ;;
esac
