#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
cd -- "$SCRIPT_DIR"

for command_name in nohup setsid; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        printf 'Required command not found: %s\n' "$command_name" >&2
        exit 1
    fi
done

mkdir -p -- logs
LOG_FILE="$SCRIPT_DIR/logs/server-manager.log"

nohup setsid "$SCRIPT_DIR/start-server.sh" >>"$LOG_FILE" 2>&1 </dev/null &
LAUNCHER_PID=$!

sleep 0.2
if ! kill -0 "$LAUNCHER_PID" 2>/dev/null; then
    printf 'The detached launcher exited before starting. Recent log output:\n' >&2
    tail -n 30 "$LOG_FILE" >&2 || true
    exit 1
fi

printf 'Started the detached Minecraft server launcher (PID %s).\n' "$LAUNCHER_PID"
printf 'Launcher output: %s\n' "$LOG_FILE"
