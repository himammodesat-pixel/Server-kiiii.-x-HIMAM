#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PID_FILE="$SCRIPT_DIR/.paper/cloudflare-tunnel.pid"
CONFIG_FILE="$SCRIPT_DIR/cloudflare-tunnel.yml"

python3 - "$PID_FILE" "$CONFIG_FILE" <<'PY'
import os
import signal
import sys
import time
from pathlib import Path

pid_file = Path(sys.argv[1])
config_file = str(Path(sys.argv[2]).resolve())

if not pid_file.is_file():
    print("Cloudflare Tunnel is not running (no PID file).")
    raise SystemExit(0)

try:
    pid = int(pid_file.read_text(encoding="utf-8").strip())
except (OSError, ValueError) as error:
    raise SystemExit(f"Cannot read Cloudflare Tunnel PID file: {error}")

if pid <= 1:
    raise SystemExit("Cloudflare Tunnel PID file contains an unsafe process ID.")

proc_dir = Path("/proc") / str(pid)
if not proc_dir.is_dir():
    pid_file.unlink(missing_ok=True)
    print("Removed stale Cloudflare Tunnel PID file.")
    raise SystemExit(0)

try:
    arguments = [
        item.decode(errors="replace")
        for item in (proc_dir / "cmdline").read_bytes().split(b"\0")
        if item
    ]
except OSError as error:
    raise SystemExit(f"Cannot verify the process in the Cloudflare Tunnel PID file: {error}")

if (
    len(arguments) < 3
    or Path(arguments[0]).name != "cloudflared"
    or arguments[1:3] != ["tunnel", "--config"]
    or config_file not in arguments
):
    raise SystemExit("PID file does not point to this workspace's Cloudflare Tunnel; no process was stopped.")

os.kill(pid, signal.SIGTERM)
deadline = time.monotonic() + 10
while proc_dir.is_dir() and time.monotonic() < deadline:
    time.sleep(0.2)

if proc_dir.is_dir():
    raise SystemExit(f"Cloudflare Tunnel process {pid} did not stop within 10 seconds.")

pid_file.unlink(missing_ok=True)
print(f"Stopped Cloudflare Tunnel (PID {pid}). The Minecraft server and panel remain running.")
PY
