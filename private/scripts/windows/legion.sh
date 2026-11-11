#!/usr/bin/env bash
# Shortcut for: private/scripts/desktop.sh legion [all|sync|build|install|debug] [--no-tests]
exec "$(cd "$(dirname "$0")/.." && pwd)/desktop.sh" legion "$@"
