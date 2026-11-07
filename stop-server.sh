#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
cd -- "$SCRIPT_DIR"

CONSOLE_FIFO="$SCRIPT_DIR/.paper/server-console"
LOCK_FILE="$SCRIPT_DIR/.paper/start-server.lock"
SERVER_PATTERN='(^|/)java -Xmx(16G -Xms14G|8G -Xms2G) -jar paper\.jar --nogui$'
SHUTDOWN_TIMEOUT="${STOP_SERVER_TIMEOUT:-120}"
LOCK_RELEASE_TIMEOUT=10

for command_name in flock pgrep ps readlink sleep timeout; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        printf 'Required command not found: %s\n' "$command_name" >&2
        exit 1
    fi
done

if ! [[ "$SHUTDOWN_TIMEOUT" =~ ^[1-9][0-9]*$ ]]; then
    printf 'STOP_SERVER_TIMEOUT must be a positive integer number of seconds.\n' >&2
    exit 1
fi

process_matches_server() {
    local pid=$1
    local process_cwd
    local -a argv

    [[ -r "/proc/$pid/cmdline" ]] || return 1
    mapfile -d '' -t argv < "/proc/$pid/cmdline" || return 1

    [[ "${#argv[@]}" -eq 6 ]] \
        && [[ "${argv[0]##*/}" == java ]] \
        && [[ "${argv[3]}" == -jar ]] \
        && [[ "${argv[4]}" == paper.jar ]] \
        && [[ "${argv[5]}" == --nogui ]] || return 1

    [[ "${argv[1]} ${argv[2]}" == "-Xmx16G -Xms14G" \
        || "${argv[1]} ${argv[2]}" == "-Xmx8G -Xms2G" ]] || return 1

    process_cwd="$(readlink -f -- "/proc/$pid/cwd" 2>/dev/null)" || return 1
    [[ "$process_cwd" == "$SCRIPT_DIR" ]]
}

process_start_time() {
    local stat_line
    local stat_fields

    stat_line="$(<"/proc/$1/stat")" || return 1
    stat_line="${stat_line##*) }"
    read -r -a stat_fields <<< "$stat_line"
    [[ "${#stat_fields[@]}" -ge 20 ]] || return 1
    printf '%s\n' "${stat_fields[19]}"
}

process_state() {
    ps -o stat= -p "$1" 2>/dev/null
}

SERVER_PID=""
MATCHING_SERVER_COUNT=0
while IFS= read -r candidate_pid; do
    if process_matches_server "$candidate_pid"; then
        SERVER_PID="$candidate_pid"
        ((MATCHING_SERVER_COUNT += 1))
    fi
done < <(pgrep -f -- "$SERVER_PATTERN" || true)

if [[ "$MATCHING_SERVER_COUNT" -eq 0 ]]; then
    printf 'Paper is not running from %s.\n' "$SCRIPT_DIR"
    exit 0
fi

if [[ "$MATCHING_SERVER_COUNT" -gt 1 ]]; then
    printf 'Found %s matching Paper processes in %s; refusing to stop an ambiguous server.\n' \
        "$MATCHING_SERVER_COUNT" "$SCRIPT_DIR" >&2
    exit 1
fi

if [[ ! -p "$CONSOLE_FIFO" || -L "$CONSOLE_FIFO" ]]; then
    printf 'Paper is running (PID %s), but the expected console pipe is missing or is not a real named pipe: %s\n' \
        "$SERVER_PID" "$CONSOLE_FIFO" >&2
    exit 1
fi

SERVER_START_TIME="$(process_start_time "$SERVER_PID")" || {
    printf 'Could not verify the identity of Paper (PID %s); no command was sent.\n' "$SERVER_PID" >&2
    exit 1
}
STDIN_TARGET="$(readlink -- "/proc/$SERVER_PID/fd/0" 2>/dev/null || true)"
if [[ "$STDIN_TARGET" != "$CONSOLE_FIFO" ]]; then
    printf 'Paper is running (PID %s), but is not attached to %s.\n' "$SERVER_PID" "$CONSOLE_FIFO" >&2
    printf 'Refusing to send a command to an unverified server process.\n' >&2
    exit 1
fi

if ! process_matches_server "$SERVER_PID" \
    || [[ "$(process_start_time "$SERVER_PID" 2>/dev/null || true)" != "$SERVER_START_TIME" ]]; then
    printf 'Paper (PID %s) changed or stopped before shutdown could be requested; no command was sent.\n' \
        "$SERVER_PID" >&2
    exit 1
fi

if ! timeout 5s bash -c 'printf "stop\n" > "$1"' _ "$CONSOLE_FIFO"; then
    printf 'Could not send the graceful stop command to Paper (PID %s). No process signal was sent.\n' \
        "$SERVER_PID" >&2
    exit 1
fi

printf 'Sent graceful stop to Paper (PID %s); waiting up to %s seconds for worlds to save.\n' \
    "$SERVER_PID" "$SHUTDOWN_TIMEOUT"

for ((elapsed = 0; elapsed < SHUTDOWN_TIMEOUT; elapsed += 1)); do
    CURRENT_STATE="$(process_state "$SERVER_PID" || true)"
    if [[ -z "$CURRENT_STATE" || "$CURRENT_STATE" == Z* ]]; then
        break
    fi

    CURRENT_START_TIME="$(process_start_time "$SERVER_PID" 2>/dev/null || true)"
    if [[ "$CURRENT_START_TIME" != "$SERVER_START_TIME" ]]; then
        break
    fi
    sleep 1
done

CURRENT_STATE="$(process_state "$SERVER_PID" || true)"
CURRENT_START_TIME="$(process_start_time "$SERVER_PID" 2>/dev/null || true)"
if [[ -n "$CURRENT_STATE" && "$CURRENT_STATE" != Z* \
    && "$CURRENT_START_TIME" == "$SERVER_START_TIME" ]]; then
    printf 'Paper (PID %s) is still running after %s seconds. It was not force-killed; check the server log.\n' \
        "$SERVER_PID" "$SHUTDOWN_TIMEOUT" >&2
    exit 1
fi

if [[ ! -e "$LOCK_FILE" ]]; then
    printf 'Paper shut down cleanly and its launcher exited.\n'
    exit 0
fi

exec {LOCK_FD}>"$LOCK_FILE"
for ((elapsed = 0; elapsed < LOCK_RELEASE_TIMEOUT * 5; elapsed += 1)); do
    if flock -n "$LOCK_FD"; then
        printf 'Paper shut down cleanly; worlds are saved and the launcher has exited.\n'
        exit 0
    fi
    sleep 0.2
done

printf 'Paper shut down cleanly, but its launcher did not release the lock within %s seconds.\n' \
    "$LOCK_RELEASE_TIMEOUT" >&2
exit 1
