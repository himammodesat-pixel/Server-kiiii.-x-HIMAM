#!/usr/bin/env bash
set -Eeuo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

PLAYIT_SECRET_FILE="${PLAYIT_SECRET_FILE:-${HOME}/.config/playit/playit.toml}"
PLAYIT_SOCKET_PATH="${PLAYIT_SOCKET_PATH:-/tmp/playitd.sock}"
PLAYIT_LOG_PATH="${PLAYIT_LOG_PATH:-${HOME}/.config/playit/playitd-minecraft.log}"
LOCK_FILE=".paper/start-server.lock"
CONSOLE_FIFO=".paper/server-console"
LOCK_FD=""
CONSOLE_FD=""
PLAYITD_PID=""
SERVER_PID=""

for command_name in chmod dirname flock java mkdir mkfifo pgrep playit playitd ps readlink sleep; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        printf 'Required command not found: %s\n' "$command_name" >&2
        exit 1
    fi
done

mkdir -p -- "$(dirname -- "$LOCK_FILE")"
exec {LOCK_FD}>"$LOCK_FILE"
if ! flock -n "$LOCK_FD"; then
    printf 'The start-server launcher is already running; not starting a duplicate.\n'
    exit 0
fi

if [[ -e "$CONSOLE_FIFO" || -L "$CONSOLE_FIFO" ]]; then
    if [[ ! -p "$CONSOLE_FIFO" ]]; then
        printf 'Expected a named pipe at %s; refusing to replace another file type.\n' "$CONSOLE_FIFO" >&2
        exit 1
    fi
else
    mkfifo -m 600 -- "$CONSOLE_FIFO"
fi
chmod 600 -- "$CONSOLE_FIFO"
exec {CONSOLE_FD}<>"$CONSOLE_FIFO"

cleanup() {
    local exit_code=$?
    trap - EXIT INT TERM

    if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
        kill -TERM "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
    fi

    stop_playit

    exit "$exit_code"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

server_child_is_running() {
    local running_pid

    while IFS= read -r running_pid; do
        if [[ "$running_pid" == "$SERVER_PID" ]]; then
            return 0
        fi
    done < <(jobs -pr)

    return 1
}

process_is_running() {
    local process_state

    process_state="$(ps -o stat= -p "$1" 2>/dev/null)" || return 1
    [[ -n "$process_state" && "$process_state" != Z* ]]
}

stop_playit() {
    if [[ -z "$PLAYITD_PID" ]]; then
        return 0
    fi

    if kill -0 "$PLAYITD_PID" 2>/dev/null; then
        kill -TERM "$PLAYITD_PID" 2>/dev/null || true
    fi
    wait "$PLAYITD_PID" 2>/dev/null || true
    PLAYITD_PID=""
}

playit_is_running() {
    local status
    status="$(playit --socket-path "$PLAYIT_SOCKET_PATH" status 2>&1)" || return 1
    [[ "$status" == *"Phase: running"* ]]
}

start_playit() {
    stop_playit

    playitd \
        --secret-path "$PLAYIT_SECRET_FILE" \
        --socket-path "$PLAYIT_SOCKET_PATH" \
        --log-path "$PLAYIT_LOG_PATH" &
    PLAYITD_PID=$!

    for _ in {1..30}; do
        if ! kill -0 "$PLAYITD_PID" 2>/dev/null; then
            wait "$PLAYITD_PID" || true
            PLAYITD_PID=""
            printf 'Playit agent exited before becoming ready. Check %s\n' "$PLAYIT_LOG_PATH" >&2
            return 1
        fi
        if playit_is_running; then
            printf 'Playit agent is running.\n'
            return 0
        fi
        sleep 1
    done

    printf 'Playit agent did not become ready within 30 seconds. Check %s\n' "$PLAYIT_LOG_PATH" >&2
    return 1
}

if [[ ! -r paper.jar ]]; then
    printf 'paper.jar not found in %s\n' "$PWD" >&2
    exit 1
fi

ONLINE_MODE="$(awk -F= '$1 == "online-mode" { value = $2 } END { print value }' server.properties)"
if [[ "$ONLINE_MODE" == "true" ]]; then
    printf 'Online mode is enabled; starting with Playit.\n'
elif [[ "$ONLINE_MODE" == "false" ]]; then
    printf 'WARNING: Offline mode is enabled; players can impersonate accounts on a public Playit tunnel.\n' >&2
    printf 'Starting with Playit anyway, as configured.\n'
else
    printf 'Could not determine online-mode from server.properties.\n' >&2
    exit 1
fi

existing_server_pid="$(pgrep -fo -- '(^|/)java -Xmx16G -Xms14G -jar paper\.jar --nogui$' || true)"

if playit_is_running; then
    printf 'Using the running Playit agent.\n'
else
    if [[ ! -r "$PLAYIT_SECRET_FILE" ]]; then
        printf 'Playit agent is not claimed or its secret is unreadable: %s\n' "$PLAYIT_SECRET_FILE" >&2
        exit 1
    fi

    mkdir -p -- "$(dirname -- "$PLAYIT_LOG_PATH")"
    start_playit
fi

if [[ -n "$existing_server_pid" ]]; then
    stdin_target="$(readlink "/proc/$existing_server_pid/fd/0" 2>/dev/null || true)"
    if [[ "$stdin_target" != "$PWD/$CONSOLE_FIFO" ]]; then
        printf 'Paper is already running (PID %s), but is not attached to %s.\n' "$existing_server_pid" "$CONSOLE_FIFO" >&2
        printf 'Refusing to monitor a server that cannot be stopped safely.\n' >&2
        exit 1
    fi

    printf 'Paper is already running (PID %s); monitoring it without starting a duplicate.\n' "$existing_server_pid"
    while process_is_running "$existing_server_pid"; do
        if ! playit_is_running; then
            printf 'Playit agent stopped; restarting it.\n' >&2
            start_playit
        fi
        sleep 5
    done
    exit 0
fi

MINECRAFT_START_SERVER_LAUNCHER=1 java -Xmx16G -Xms14G -jar paper.jar --nogui <&"$CONSOLE_FD" &
SERVER_PID=$!
while server_child_is_running; do
    if ! playit_is_running; then
        printf 'Playit agent stopped; restarting it.\n' >&2
        start_playit
    fi
    sleep 5
done
wait "$SERVER_PID"
