#!/usr/bin/env python3
import hmac
import json
import os
import re
import secrets
import stat
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parent.parent
TOKEN_FILE = ROOT / ".paper" / "server-panel-token"
WEB_ROOT = Path(__file__).resolve().parent
PORT = int(os.environ.get("SERVER_PANEL_PORT", "8765"))
LOG_LIMIT = 12_000
PLAYER_RESULT = re.compile(
    r"There are (\d+)(?: of a max of (\d+)| out of maximum (\d+)) players online(?:: ?(.*))?"
)
CONTROL_LOCK = threading.Lock()


def load_or_create_token():
    TOKEN_FILE.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        descriptor = os.open(TOKEN_FILE, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        token = TOKEN_FILE.read_text(encoding="utf-8").strip()
        if len(token) < 32:
            raise RuntimeError(f"Panel token file is invalid: {TOKEN_FILE}")
        return token
    with os.fdopen(descriptor, "w", encoding="utf-8") as token_file:
        token = secrets.token_urlsafe(32)
        token_file.write(token + "\n")
    return token


def server_processes():
    matches = []
    for entry in Path("/proc").iterdir():
        if not entry.name.isdigit():
            continue
        try:
            arguments = (entry / "cmdline").read_bytes().split(b"\0")
            arguments = [argument.decode(errors="replace") for argument in arguments if argument]
            process_root = (entry / "cwd").resolve()
        except (FileNotFoundError, PermissionError, ProcessLookupError, OSError):
            continue

        if (
            len(arguments) == 6
            and Path(arguments[0]).name == "java"
            and arguments[1:3] == ["-Xmx16G", "-Xms14G"]
            and arguments[3:] == ["-jar", "paper.jar", "--nogui"]
            and process_root == ROOT
        ):
            matches.append(int(entry.name))
    return matches


def read_log_tail():
    log_path = ROOT / "logs" / "latest.log"
    try:
        with log_path.open("rb") as log_file:
            log_file.seek(0, os.SEEK_END)
            size = log_file.tell()
            log_file.seek(max(0, size - LOG_LIMIT))
            content = log_file.read().decode("utf-8", errors="replace")
    except FileNotFoundError:
        return ""
    return "\n".join(content.splitlines()[-80:])


def current_players():
    fifo = ROOT / ".paper" / "server-console"
    log_path = ROOT / "logs" / "latest.log"
    try:
        with log_path.open("rb") as log_file:
            log_file.seek(0, os.SEEK_END)
            previous_size = log_file.tell()
            fifo_fd = os.open(fifo, os.O_WRONLY | os.O_NONBLOCK)
            try:
                os.write(fifo_fd, b"list\n")
            finally:
                os.close(fifo_fd)
    except (FileNotFoundError, OSError):
        return None

    deadline = time.monotonic() + 2.5
    while time.monotonic() < deadline:
        try:
            with log_path.open("rb") as log_file:
                log_file.seek(previous_size)
                lines = log_file.read().decode("utf-8", errors="replace").splitlines()
        except FileNotFoundError:
            return None
        for line in lines:
            match = PLAYER_RESULT.search(line)
            if match:
                maximum = match.group(2) or match.group(3)
                return {
                    "online": int(match.group(1)),
                    "maximum": int(maximum),
                    "names": [name.strip() for name in (match.group(4) or "").split(",") if name.strip()],
                }
        time.sleep(0.1)
    return None


def send_console_command(command):
    if not isinstance(command, str):
        return 400, {"error": "Perintah harus berupa teks."}

    command = command.strip()
    if command.startswith("/"):
        command = command[1:]
    if not command or len(command) > 256 or any(ord(char) < 32 for char in command):
        return 400, {"error": "Perintah kosong, terlalu panjang, atau berisi karakter yang tidak diizinkan."}

    base_command = command.split(maxsplit=1)[0].lower()
    if base_command in {"stop", "restart", "minecraft:stop", "paper:restart"}:
        return 400, {"error": "Gunakan tombol Matikan agar server berhenti dengan aman."}

    pids = server_processes()
    if len(pids) != 1:
        return 409, {"error": "Server tidak aktif atau prosesnya ambigu."}

    fifo = ROOT / ".paper" / "server-console"
    try:
        if not stat.S_ISFIFO(fifo.lstat().st_mode):
            return 500, {"error": "Console server tidak tersedia dengan aman."}
        fifo_fd = os.open(fifo, os.O_WRONLY | os.O_NONBLOCK)
        try:
            os.write(fifo_fd, command.encode("utf-8") + b"\n")
        finally:
            os.close(fifo_fd)
    except OSError as error:
        return 503, {"error": f"Tidak dapat mengirim perintah ke console: {error.strerror or error}"}

    return 200, {"message": "Perintah dikirim ke console Minecraft."}


class PanelServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


class Handler(BaseHTTPRequestHandler):
    server_version = "MinecraftPanel"
    sys_version = ""

    def log_message(self, format_string, *args):
        super().log_message(format_string, *args)

    def respond(self, status, payload, content_type="application/json; charset=utf-8"):
        body = payload if isinstance(payload, bytes) else json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'self'; connect-src 'self'; style-src 'self'; script-src 'self'; manifest-src 'self'; img-src 'self' data:")
        self.end_headers()
        self.wfile.write(body)

    def authorized(self):
        supplied = self.headers.get("X-Panel-Token", "")
        return hmac.compare_digest(supplied, self.server.panel_token)

    def do_GET(self):
        path = urlparse(self.path).path
        if path in ("/api/status", "/api/log"):
            if not self.authorized():
                self.respond(401, {"error": "Kode panel salah atau belum diisi."})
                return
            if path == "/api/status":
                pids = server_processes()
                players = current_players() if pids else None
                self.respond(200, {
                    "running": bool(pids),
                    "ambiguous": len(pids) > 1,
                    "players": players,
                })
            else:
                self.respond(200, {"lines": read_log_tail()})
            return

        files = {
            "/": ("index.html", "text/html; charset=utf-8"),
            "/app.js": ("app.js", "text/javascript; charset=utf-8"),
            "/style.css": ("style.css", "text/css; charset=utf-8"),
            "/manifest.webmanifest": ("manifest.webmanifest", "application/manifest+json"),
            "/sw.js": ("sw.js", "text/javascript; charset=utf-8"),
            "/icon.svg": ("icon.svg", "image/svg+xml"),
        }
        target = files.get(path)
        if target is None:
            self.respond(404, {"error": "Tidak ditemukan."})
            return
        filename, content_type = target
        self.respond(200, (WEB_ROOT / filename).read_bytes(), content_type)

    def do_POST(self):
        path = urlparse(self.path).path
        if path not in ("/api/control", "/api/command"):
            self.respond(404, {"error": "Tidak ditemukan."})
            return
        if not self.authorized():
            self.respond(401, {"error": "Kode panel salah atau belum diisi."})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length > 1024:
                raise ValueError("Permintaan terlalu besar.")
            body = json.loads(self.rfile.read(length))
            if not isinstance(body, dict):
                raise ValueError("Bentuk permintaan tidak valid.")
        except (ValueError, json.JSONDecodeError, AttributeError):
            self.respond(400, {"error": "Permintaan tidak valid."})
            return

        if path == "/api/command":
            status, result = send_console_command(body.get("command"))
            self.respond(status, result)
            return

        action = body.get("action")
        scripts = {
            "start": (ROOT / "start-server-background.sh", 45),
            "stop": (ROOT / "stop-server.sh", 150),
        }
        if action not in scripts:
            self.respond(400, {"error": "Aksi tidak dikenal."})
            return
        if not CONTROL_LOCK.acquire(blocking=False):
            self.respond(409, {"error": "Perintah lain sedang berjalan."})
            return
        try:
            script, timeout = scripts[action]
            result = subprocess.run(
                [str(script)],
                cwd=ROOT,
                capture_output=True,
                text=True,
                timeout=timeout,
                check=False,
            )
            output = (result.stdout + result.stderr).strip()
            if result.returncode:
                self.respond(500, {"error": output[-2000:] or f"Skrip gagal ({result.returncode})."})
                return
            self.respond(200, {"message": output[-2000:] or "Perintah berhasil."})
        except subprocess.TimeoutExpired:
            self.respond(504, {"error": "Perintah melewati batas waktu; periksa log server."})
        except OSError as error:
            self.respond(500, {"error": f"Tidak dapat menjalankan skrip: {error}"})
        finally:
            CONTROL_LOCK.release()


def main():
    token = load_or_create_token()
    server = PanelServer(("0.0.0.0", PORT), Handler)
    server.panel_token = token
    print(f"Panel siap di port {PORT}. Port Codespaces wajib Private.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
