#!/usr/bin/env bash
# Git project commands.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

usage() {
    cat <<'EOF'
Project Git script

Usage:
  private/scripts/git.sh <command> [arguments]

Command                         What it does
status                          Show the current branch and changed files.
check                           Fetch GitHub and show ahead/behind status.
fetch                           Download GitHub history without changing files.
pull                            Fast-forward clean main to the latest GitHub commit.
diff                            Show unstaged and staged changes.
history                         Show the latest 20 commits.
branches                        Show local branches and tracking state.
push-all                        Commit all non-ignored changes and push main.
push-all "Message"              Same as push-all with a custom commit message.

Safety:
  pull refuses to run with local changes.
  push-all only runs on main.
  This script has no reset --hard, clean -fd or force-push command.
EOF
}

COMMAND="${1:-}"
if [ -z "$COMMAND" ]; then
    usage >&2
    exit 2
fi
shift

cd "$ROOT"

require_main() {
    local branch
    branch="$(git branch --show-current)"
    if [ "$branch" != main ]; then
        echo "Stopped: this command only works on main; current branch is '$branch'." >&2
        exit 1
    fi
}

require_clean() {
    if [ -n "$(git status --porcelain)" ]; then
        git status --short
        echo "Stopped: the working tree has changes. Commit or stash them before pulling." >&2
        exit 1
    fi
}

case "$COMMAND" in
    status)
        git status -sb
        ;;
    check)
        git fetch --prune origin
        git status -sb
        ;;
    fetch)
        git fetch --prune origin
        echo "Fetched origin. Local files were not changed."
        ;;
    pull)
        require_main
        require_clean
        git pull --ff-only origin main
        ;;
    diff)
        git diff
        echo "--- staged changes ---"
        git diff --cached
        ;;
    history)
        git log --oneline --decorate -20
        ;;
    branches)
        git branch -vv
        ;;
    push-all)
        require_main
        MESSAGE="${*:-Update AutoPilot}"
        git add -A

        if git diff --cached --quiet; then
            echo "Nothing to commit. The working tree is already up to date."
            exit 0
        fi

        git commit -m "$MESSAGE"
        git push origin main
        ;;
    -h|--help|help)
        usage
        ;;
    *)
        echo "Unknown Git command: $COMMAND" >&2
        usage >&2
        exit 2
        ;;
esac
