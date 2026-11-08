#!/usr/bin/env python3
import hmac
import json
import os
import re
import secrets
import shlex
import stat
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, urlparse


ROOT = Path(__file__).resolve().parent.parent
TOKEN_FILE = ROOT / ".paper" / "server-panel-token"
WEB_ROOT = Path(__file__).resolve().parent
PORT = int(os.environ.get("SERVER_PANEL_PORT", "8765"))
LOG_LIMIT = 12_000
FILE_TEXT_LIMIT = 1_000_000
FILE_TRANSFER_LIMIT = 10_000_000
FILE_DIRECTORIES = {"config", "logs", "plugins", "world", "world_nether", "world_the_end"}
FILE_ROOT_FILES = {
    "banned-ips.json", "banned-players.json", "bukkit.yml", "commands.yml",
    "ops.json", "server.properties", "spigot.yml", "wepif.yml", "whitelist.json",
}
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


def read_ops_log(name):
    log_paths = {
        "server": ROOT / "logs" / "latest.log",
        "panel": ROOT / "logs" / "server-panel.log",
        "startup": ROOT / "logs" / "server-manager.log",
        "cloudflare": ROOT / "logs" / "cloudflare-tunnel.log",
    }
    log_path = log_paths.get(name)
    if log_path is None:
        return None
    try:
        with log_path.open("rb") as log_file:
            log_file.seek(0, os.SEEK_END)
            size = log_file.tell()
            log_file.seek(max(0, size - LOG_LIMIT))
            content = log_file.read().decode("utf-8", errors="replace")
    except FileNotFoundError:
        return f"Log {name} belum tersedia."
    return "\n".join(content.splitlines()[-80:]) or f"Log {name} masih kosong."


def cloudflare_process_running():
    pid_file = ROOT / ".paper" / "cloudflare-tunnel.pid"
    try:
        pid_text = pid_file.read_text(encoding="utf-8").strip()
        if not re.fullmatch(r"[1-9][0-9]*", pid_text):
            return False
        arguments = [
            item.decode(errors="replace")
            for item in (Path("/proc") / pid_text / "cmdline").read_bytes().split(b"\0")
            if item
        ]
    except (FileNotFoundError, PermissionError, ProcessLookupError, OSError):
        return False
    return (
        len(arguments) >= 3
        and Path(arguments[0]).name == "cloudflared"
        and arguments[1:3] == ["tunnel", "--config"]
        and str(ROOT / "cloudflare-tunnel.yml") in arguments
    )


def run_ops_command(command):
    if not isinstance(command, str) or len(command) > 160 or any(ord(char) < 32 for char in command):
        return 400, {"error": "Perintah terminal tidak valid."}
    try:
        arguments = shlex.split(command)
    except ValueError:
        return 400, {"error": "Format perintah tidak valid."}

    if arguments == ["help"]:
        return 200, {
            "output": (
                "Perintah yang diizinkan:\n"
                "help\nstatus\nlogs server|panel|startup|cloudflare\n"
                "start server|cloudflare\nstop server|cloudflare\nrestart server"
            )
        }
    if arguments == ["status"]:
        server_pids = server_processes()
        return 200, {
            "output": (
                f"Codespace: panel merespons di dalam workspace\n"
                f"Server Minecraft: {'berjalan' if len(server_pids) == 1 else 'tidak berjalan' if not server_pids else 'proses ambigu'}\n"
                f"Cloudflare Tunnel: {'berjalan' if cloudflare_process_running() else 'tidak berjalan'}"
            )
        }
    if len(arguments) == 2 and arguments[0] == "logs" and arguments[1] in {
        "server", "panel", "startup", "cloudflare"
    }:
        return 200, {"output": read_ops_log(arguments[1])}

    scripts = {
        ("start", "server"): (ROOT / "start-server-background.sh", 45),
        ("stop", "server"): (ROOT / "stop-server.sh", 150),
        ("restart", "server"): (ROOT / "restart-server.sh", 210),
        ("start", "cloudflare"): (ROOT / "cloudflare-start.sh", 45),
        ("stop", "cloudflare"): (ROOT / "cloudflare-stop.sh", 20),
    }
    selected = scripts.get(tuple(arguments))
    if selected is None:
        return 400, {
            "error": (
                "Perintah tidak diizinkan. Ketik help untuk melihat daftar perintah. "
                "Terminal ini bukan shell Linux bebas."
            )
        }
    if not CONTROL_LOCK.acquire(blocking=False):
        return 409, {"error": "Perintah pengelolaan lain sedang berjalan."}
    try:
        script, timeout = selected
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
            return 500, {"error": output[-2000:] or f"Skrip gagal ({result.returncode})."}
        return 200, {"output": output[-6000:] or "Perintah berhasil."}
    except subprocess.TimeoutExpired:
        return 504, {"error": "Perintah melewati batas waktu; periksa log layanan."}
    except OSError as error:
        return 500, {"error": f"Tidak dapat menjalankan skrip: {error}"}
    finally:
        CONTROL_LOCK.release()


def resolve_file_path(relative_path, for_write=False):
    if not isinstance(relative_path, str) or not relative_path or len(relative_path) > 512:
        raise ValueError("Jalur file tidak valid.")
    if relative_path.startswith("/") or "\\" in relative_path or "\0" in relative_path:
        raise ValueError("Jalur file tidak valid.")
    parts = relative_path.split("/")
    if any(part in ("", ".", "..") for part in parts):
        raise ValueError("Jalur file tidak valid.")
    if parts[0] not in FILE_DIRECTORIES | FILE_ROOT_FILES:
        raise ValueError("Akses file dibatasi ke folder data server.")
    if parts[0] in FILE_ROOT_FILES and len(parts) != 1:
        raise ValueError("Jalur file tidak valid.")
    if for_write and parts[0] == "logs":
        raise ValueError("Folder log hanya dapat dibaca.")

    target = ROOT
    for part in parts:
        target = target / part
        try:
            if stat.S_ISLNK(target.lstat().st_mode):
                raise ValueError("Akses ke symbolic link tidak diizinkan.")
        except FileNotFoundError:
            pass
    try:
        target.relative_to(ROOT)
    except ValueError as error:
        raise ValueError("Jalur file berada di luar folder server.") from error
    return target


def atomic_write_file(target, content):
    if target.exists() and not target.is_file():
        raise ValueError("Hanya file biasa yang dapat ditulis.")
    parent = target.parent
    if not parent.is_dir():
        raise ValueError("Folder tujuan tidak ditemukan.")
    old_mode = stat.S_IMODE(target.stat().st_mode) if target.exists() else 0o600
    descriptor, temporary_name = tempfile.mkstemp(prefix=".panel-upload-", dir=parent)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary_name, old_mode)
        os.replace(temporary_name, target)
    except Exception:
        try:
            os.unlink(temporary_name)
        except FileNotFoundError:
            pass
        raise


def file_entries(directory):
    if directory == ROOT:
        names = sorted(FILE_DIRECTORIES) + sorted(FILE_ROOT_FILES)
        paths = [ROOT / name for name in names]
    else:
        paths = list(directory.iterdir())
        def sort_key(path):
            try:
                is_directory = stat.S_ISDIR(path.lstat().st_mode)
            except FileNotFoundError:
                is_directory = False
            return (not is_directory, path.name.casefold())
        paths.sort(key=sort_key)

    entries = []
    for path in paths[:500]:
        try:
            info = path.lstat()
        except FileNotFoundError:
            continue
        if stat.S_ISLNK(info.st_mode):
            continue
        if not (stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode)):
            continue
        entries.append({
            "name": path.name,
            "path": path.relative_to(ROOT).as_posix(),
            "directory": stat.S_ISDIR(info.st_mode),
            "size": info.st_size,
            "modified": int(info.st_mtime),
        })
    return entries


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

    def serve_resource_pack(self, include_body):
        pack_path = ROOT / "himam-smp-resource-pack.zip"
        try:
            pack = pack_path.read_bytes()
        except FileNotFoundError:
            self.respond(404, {"error": "Resource pack tidak ditemukan."})
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/zip")
        self.send_header("Content-Length", str(len(pack)))
        self.send_header("Cache-Control", "public, max-age=3600")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        if include_body:
            self.wfile.write(pack)

    def authorized(self):
        supplied = self.headers.get("X-Panel-Token", "")
        return hmac.compare_digest(supplied, self.server.panel_token)

    def request_file_api(self, parsed_url):
        query = parse_qs(parsed_url.query, keep_blank_values=True)
        relative_path = query.get("path", [""])[0]
        try:
            target = ROOT if relative_path == "" else resolve_file_path(relative_path)
            if target == ROOT:
                self.respond(200, {"path": "", "parent": None, "entries": file_entries(ROOT)})
                return
            if target.is_dir():
                parent = target.relative_to(ROOT).parent.as_posix()
                if parent == ".":
                    parent = ""
                self.respond(200, {
                    "path": target.relative_to(ROOT).as_posix(),
                    "parent": parent,
                    "entries": file_entries(target),
                })
                return
            if not target.is_file():
                self.respond(404, {"error": "File tidak ditemukan."})
                return
            if target.stat().st_size > FILE_TEXT_LIMIT:
                self.respond(200, {"path": relative_path, "downloadOnly": True})
                return
            with target.open("rb") as source:
                content = source.read(FILE_TEXT_LIMIT + 1)
            if len(content) > FILE_TEXT_LIMIT:
                self.respond(200, {"path": relative_path, "downloadOnly": True})
                return
            try:
                text = content.decode("utf-8")
            except UnicodeDecodeError:
                self.respond(200, {"path": relative_path, "downloadOnly": True})
                return
            self.respond(200, {"path": relative_path, "content": text})
        except ValueError as error:
            self.respond(400, {"error": str(error)})
        except FileNotFoundError:
            self.respond(404, {"error": "Folder atau file tidak ditemukan."})
        except OSError as error:
            self.respond(500, {"error": f"Tidak dapat membaca file: {error.strerror or error}"})

    def download_server_file(self, parsed_url):
        query = parse_qs(parsed_url.query)
        relative_path = query.get("path", [""])[0]
        try:
            target = resolve_file_path(relative_path)
            if not target.is_file():
                self.respond(400, {"error": "Pilih file untuk diunduh."})
                return
            descriptor = os.open(target, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
            with os.fdopen(descriptor, "rb") as source:
                info = os.fstat(source.fileno())
                if not stat.S_ISREG(info.st_mode) or info.st_size > FILE_TRANSFER_LIMIT:
                    self.respond(413, {"error": "File bukan file biasa atau melebihi batas unduhan 10 MB."})
                    return
                content = source.read(FILE_TRANSFER_LIMIT + 1)
            if len(content) > FILE_TRANSFER_LIMIT:
                self.respond(413, {"error": "File melebihi batas unduhan 10 MB."})
                return
            self.respond_file(content, target.name)
        except ValueError as error:
            self.respond(400, {"error": str(error)})
        except FileNotFoundError:
            self.respond(404, {"error": "File tidak ditemukan."})
        except OSError as error:
            self.respond(500, {"error": f"Tidak dapat mengunduh file: {error.strerror or error}"})

    def respond_file(self, content, filename):
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Content-Disposition", f'attachment; filename="{quote(filename, safe="")}"')
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(content)

    def update_server_files(self):
        if not self.authorized():
            self.respond(401, {"error": "Kode panel salah atau belum diisi."})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self.respond(400, {"error": "Ukuran permintaan tidak valid."})
            return
        if length < 0:
            self.respond(400, {"error": "Ukuran permintaan tidak valid."})
            return
        if length > FILE_TRANSFER_LIMIT:
            self.respond(413, {"error": "File terlalu besar (maksimal 10 MB)."})
            return

        parsed_url = urlparse(self.path)
        if parsed_url.path == "/api/files/upload":
            query = parse_qs(parsed_url.query, keep_blank_values=True)
            directory = query.get("path", [""])[0]
            filename = query.get("name", [""])[0]
            if not filename or filename in (".", "..") or "/" in filename or "\\" in filename or "\0" in filename:
                self.respond(400, {"error": "Nama file unggahan tidak valid."})
                return
            try:
                parent = ROOT if not directory else resolve_file_path(directory, for_write=True)
                if not parent.is_dir():
                    raise ValueError("Folder tujuan tidak ditemukan.")
                target = resolve_file_path(f"{directory}/{filename}" if directory else filename, for_write=True)
                atomic_write_file(target, self.rfile.read(length))
                self.respond(200, {"message": f"File {filename} berhasil diunggah."})
            except ValueError as error:
                self.respond(400, {"error": str(error)})
            except OSError as error:
                self.respond(500, {"error": f"Unggah gagal: {error.strerror or error}"})
            return

        if parsed_url.path != "/api/files":
            self.respond(404, {"error": "Tidak ditemukan."})
            return
        if length > FILE_TEXT_LIMIT + 2048:
            self.respond(413, {"error": "File teks terlalu besar (maksimal 1 MB)."})
            return
        try:
            body = json.loads(self.rfile.read(length))
            if not isinstance(body, dict):
                raise ValueError("Bentuk permintaan tidak valid.")
        except (ValueError, json.JSONDecodeError):
            self.respond(400, {"error": "Permintaan tidak valid."})
            return

        action = body.get("action")
        try:
            relative_path = body.get("path", "")
            if action == "save":
                content = body.get("content")
                if not isinstance(content, str):
                    raise ValueError("Isi file harus berupa teks.")
                data = content.encode("utf-8")
                if len(data) > FILE_TEXT_LIMIT:
                    self.respond(413, {"error": "File teks terlalu besar (maksimal 1 MB)."})
                    return
                target = resolve_file_path(relative_path, for_write=True)
                atomic_write_file(target, data)
                self.respond(200, {"message": "File berhasil disimpan."})
            elif action == "mkdir":
                target = resolve_file_path(relative_path, for_write=True)
                target.mkdir(mode=0o700)
                self.respond(200, {"message": "Folder berhasil dibuat."})
            elif action == "delete":
                target = resolve_file_path(relative_path, for_write=True)
                if target.is_dir():
                    target.rmdir()
                elif target.is_file():
                    target.unlink()
                else:
                    raise FileNotFoundError
                self.respond(200, {"message": "File atau folder kosong berhasil dihapus."})
            elif action == "rename":
                destination = body.get("newPath", "")
                source_path = resolve_file_path(relative_path, for_write=True)
                target_path = resolve_file_path(destination, for_write=True)
                if source_path.parent != target_path.parent:
                    raise ValueError("File hanya dapat diganti nama di folder yang sama.")
                if target_path.exists():
                    raise ValueError("Nama tujuan sudah digunakan.")
                source_path.rename(target_path)
                self.respond(200, {"message": "Nama berhasil diubah."})
            else:
                self.respond(400, {"error": "Aksi file tidak dikenal."})
        except FileNotFoundError:
            self.respond(404, {"error": "File atau folder tidak ditemukan."})
        except FileExistsError:
            self.respond(409, {"error": "File atau folder sudah ada."})
        except ValueError as error:
            self.respond(400, {"error": str(error)})
        except OSError as error:
            self.respond(500, {"error": f"Operasi file gagal: {error.strerror or error}"})

    def do_GET(self):
        parsed_url = urlparse(self.path)
        path = parsed_url.path
        if path == "/resource-pack.zip":
            self.serve_resource_pack(include_body=True)
            return
        if path in ("/api/files", "/api/files/download"):
            if not self.authorized():
                self.respond(401, {"error": "Kode panel salah atau belum diisi."})
                return
            if path == "/api/files":
                self.request_file_api(parsed_url)
            else:
                self.download_server_file(parsed_url)
            return
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

    def do_HEAD(self):
        if urlparse(self.path).path == "/resource-pack.zip":
            self.serve_resource_pack(include_body=False)
            return
        self.send_error(404, "Tidak ditemukan.")

    def do_POST(self):
        path = urlparse(self.path).path
        if path == "/api/ops":
            if not self.authorized():
                self.respond(401, {"error": "Kode panel salah atau belum diisi."})
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length <= 0 or length > 1024:
                    raise ValueError("Ukuran permintaan tidak valid.")
                body = json.loads(self.rfile.read(length))
                if not isinstance(body, dict):
                    raise ValueError("Bentuk permintaan tidak valid.")
            except (ValueError, json.JSONDecodeError, AttributeError):
                self.respond(400, {"error": "Permintaan terminal tidak valid."})
                return
            status, result = run_ops_command(body.get("command"))
            self.respond(status, result)
            return
        if path in ("/api/files", "/api/files/upload"):
            self.update_server_files()
            return
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
            "restart": (ROOT / "restart-server.sh", 210),
            "stop": (ROOT / "stop-server.sh", 150),
            "cloudflare-start": (ROOT / "cloudflare-start.sh", 45),
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
