#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
cd -- "$SCRIPT_DIR"

FAILURES=0

start_service() {
    local script=$1

    if "$SCRIPT_DIR/$script"; then
        return 0
    fi

    printf 'Service startup failed: %s\n' "$script" >&2
    FAILURES=1
}

start_service start-server-background.sh
start_service start-server-panel.sh
start_service start-cloudflare-tunnel.sh

exit "$FAILURES"
