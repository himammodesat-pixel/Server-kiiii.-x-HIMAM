#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
cd -- "$SCRIPT_DIR"

mkdir -p -- logs
LOG_FILE="$SCRIPT_DIR/logs/server-panel.log"
PID_FILE="$SCRIPT_DIR/.paper/server-panel.pid"
mkdir -p -- "$SCRIPT_DIR/.paper"

if [[ -r "$PID_FILE" ]]; then
    EXISTING_PID="$(<"$PID_FILE")"
    if [[ "$EXISTING_PID" =~ ^[1-9][0-9]*$ ]] && kill -0 "$EXISTING_PID" 2>/dev/null; then
        printf 'The server panel is already running (PID %s).\n' "$EXISTING_PID"
        exit 0
    fi
fi

nohup python3 "$SCRIPT_DIR/server-panel/server.py" >>"$LOG_FILE" 2>&1 </dev/null &
PANEL_PID=$!
printf '%s\n' "$PANEL_PID" >"$PID_FILE"

sleep 0.2
if ! kill -0 "$PANEL_PID" 2>/dev/null; then
    rm -f -- "$PID_FILE"
    printf 'The panel exited before starting. Recent log output:\n' >&2
    tail -n 30 "$LOG_FILE" >&2 || true
    exit 1
fi

printf 'Started the Minecraft control panel (PID %s).\n' "$PANEL_PID"
printf 'Panel log: %s\n' "$LOG_FILE"
printf 'Get its access code with: cat .paper/server-panel-token\n'
