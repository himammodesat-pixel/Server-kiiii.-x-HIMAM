#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
cd -- "$SCRIPT_DIR"

TUNNEL_ID="b3723ba3-3b73-4c92-963a-d620d755dbd4"
CONFIG_FILE="$SCRIPT_DIR/cloudflare-tunnel.yml"
CREDENTIALS_FILE="$SCRIPT_DIR/.paper/cloudflare-tunnel-credentials.json"
PID_FILE="$SCRIPT_DIR/.paper/cloudflare-tunnel.pid"
LOG_FILE="$SCRIPT_DIR/logs/cloudflare-tunnel.log"

mkdir -p -- "$SCRIPT_DIR/.paper" "$SCRIPT_DIR/logs"

for command_name in base64 cloudflared python3; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        printf 'Required command not found: %s\n' "$command_name" >&2
        exit 1
    fi
done

if [[ -n "${CLOUDFLARE_TUNNEL_CREDENTIALS_B64:-}" ]]; then
    umask 077
    printf '%s' "$CLOUDFLARE_TUNNEL_CREDENTIALS_B64" | base64 --decode >"$CREDENTIALS_FILE"
    chmod 600 "$CREDENTIALS_FILE"
    unset CLOUDFLARE_TUNNEL_CREDENTIALS_B64
elif [[ ! -s "$CREDENTIALS_FILE" && -r "$HOME/.cloudflared/cert.pem" ]]; then
    umask 077
    cloudflared tunnel token --cred-file "$CREDENTIALS_FILE" "$TUNNEL_ID" >/dev/null
    chmod 600 "$CREDENTIALS_FILE"
elif [[ ! -s "$CREDENTIALS_FILE" ]]; then
    printf 'Cloudflare Tunnel is not configured. Add the Codespaces secret CLOUDFLARE_TUNNEL_CREDENTIALS_B64.\n' >&2
    exit 0
fi

if [[ ! -r "$CONFIG_FILE" ]]; then
    printf 'Cloudflare Tunnel configuration is missing: %s\n' "$CONFIG_FILE" >&2
    exit 1
fi

if ! python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d.get("TunnelID") == sys.argv[2] and d.get("TunnelSecret")' "$CREDENTIALS_FILE" "$TUNNEL_ID" >/dev/null 2>&1; then
    printf 'Cloudflare Tunnel credentials do not match the configured tunnel.\n' >&2
    exit 1
fi

if ! command -v cloudflared >/dev/null 2>&1; then
    printf 'cloudflared is not installed. Install it using Cloudflare official instructions, then reopen this workspace.\n' >&2
    exit 1
fi

if [[ -r "$PID_FILE" ]]; then
    EXISTING_PID="$(<"$PID_FILE")"
    if [[ "$EXISTING_PID" =~ ^[1-9][0-9]*$ ]] \
        && kill -0 "$EXISTING_PID" 2>/dev/null \
        && [[ -r "/proc/$EXISTING_PID/cmdline" ]] \
        && tr '\0' ' ' <"/proc/$EXISTING_PID/cmdline" | grep -Fq -- "cloudflared tunnel" \
        && tr '\0' ' ' <"/proc/$EXISTING_PID/cmdline" | grep -Fq -- "$CONFIG_FILE"; then
        printf 'Cloudflare Tunnel is already running (PID %s).\n' "$EXISTING_PID"
        exit 0
    fi
fi

nohup cloudflared tunnel --config "$CONFIG_FILE" --no-autoupdate run "$TUNNEL_ID" >>"$LOG_FILE" 2>&1 </dev/null &
TUNNEL_PID=$!
printf '%s\n' "$TUNNEL_PID" >"$PID_FILE"

sleep 1
if ! kill -0 "$TUNNEL_PID" 2>/dev/null; then
    rm -f -- "$PID_FILE"
    printf 'Cloudflare Tunnel exited during startup. Recent log output:\n' >&2
    tail -n 30 "$LOG_FILE" >&2 || true
    exit 1
fi

printf 'Started Cloudflare Tunnel (PID %s). Check %s for connection status.\n' "$TUNNEL_PID" "$LOG_FILE"
