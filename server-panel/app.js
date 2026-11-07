const codeInput = document.querySelector("#access-code");
const statusTitle = document.querySelector("#status-title");
const statusDot = document.querySelector("#status-dot");
const playerCount = document.querySelector("#player-count");
const updatedAt = document.querySelector("#updated-at");
const logs = document.querySelector("#logs");
const accessFeedback = document.querySelector("#access-feedback");
const startButton = document.querySelector("#start-button");
const stopButton = document.querySelector("#stop-button");
const saveButton = document.querySelector("#save-code");
const refreshButton = document.querySelector("#refresh-logs");
const broadcastInput = document.querySelector("#broadcast-message");
const broadcastButton = document.querySelector("#broadcast-button");
const playerInput = document.querySelector("#player-name");
const playerAction = document.querySelector("#player-action");
const playerReason = document.querySelector("#player-reason");
const playerActionButton = document.querySelector("#player-action-button");
const consoleInput = document.querySelector("#console-command");
const consoleButton = document.querySelector("#console-button");

codeInput.value = sessionStorage.getItem("panel-code") || "";

function token() {
  return sessionStorage.getItem("panel-code") || "";
}

async function request(path, options = {}) {
  const response = await fetch(path, {
    cache: "no-store",
    ...options,
    headers: {
      "X-Panel-Token": token(),
      ...(options.headers || {}),
    },
  });
  const payload = await response.json();
  if (!response.ok) throw new Error(payload.error || `Permintaan gagal (${response.status}).`);
  return payload;
}

function setUnavailable(message) {
  statusTitle.textContent = message;
  statusDot.className = "status-dot";
  playerCount.textContent = "Status belum tersedia";
  if (message.includes("Kode panel salah")) {
    accessFeedback.textContent = "Kode ditolak. Salin ulang kode lengkap dari terminal Codespace, tanpa tanda kutip/spasi, lalu tekan Simpan.";
    accessFeedback.className = "muted error";
  }
}

async function refreshStatus() {
  if (!token()) {
    setUnavailable("Masukkan kode akses");
    return;
  }
  try {
    const status = await request("/api/status");
    statusTitle.textContent = status.ambiguous
      ? "Server tidak terdeteksi dengan aman"
      : status.running ? "Server aktif" : "Server mati";
    statusDot.className = `status-dot ${status.running ? "online" : "offline"}`;
    if (status.players) {
      const names = status.players.names.length ? ` · ${status.players.names.join(", ")}` : "";
      playerCount.textContent = `${status.players.online}/${status.players.maximum} pemain${names}`;
    } else {
      playerCount.textContent = status.running ? "Jumlah pemain belum tersedia" : "Tidak ada pemain online";
    }
    updatedAt.textContent = `Diperbarui ${new Date().toLocaleTimeString("id-ID")}`;
    accessFeedback.textContent = "Kode akses diterima. Kode hanya disimpan di sesi browser ini.";
    accessFeedback.className = "muted success";
  } catch (error) {
    setUnavailable(error.message);
  }
}

async function refreshLogs() {
  if (!token()) {
    logs.textContent = "Masukkan kode akses untuk melihat log.";
    return;
  }
  try {
    const result = await request("/api/log");
    logs.textContent = result.lines || "Belum ada log server.";
    logs.scrollTop = logs.scrollHeight;
  } catch (error) {
    logs.textContent = error.message;
  }
}

async function control(action, button) {
  if (action === "stop" && !window.confirm("Matikan server Minecraft dan simpan dunia sekarang?")) return;
  button.disabled = true;
  const original = button.textContent;
  button.textContent = "Tunggu…";
  try {
    const result = await request("/api/control", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ action }),
    });
    logs.textContent = result.message;
    await refreshStatus();
  } catch (error) {
    logs.textContent = error.message;
  } finally {
    button.disabled = false;
    button.textContent = original;
  }
}

async function sendCommand(command, button, confirmation = "") {
  const cleanCommand = command.trim();
  if (!cleanCommand) return;
  if (confirmation && !window.confirm(confirmation)) return;
  button.disabled = true;
  const original = button.textContent;
  button.textContent = "Mengirim…";
  try {
    const result = await request("/api/command", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ command: cleanCommand }),
    });
    logs.textContent = result.message;
    await new Promise((resolve) => setTimeout(resolve, 350));
    await refreshLogs();
  } catch (error) {
    logs.textContent = error.message;
  } finally {
    button.disabled = false;
    button.textContent = original;
  }
}

saveButton.addEventListener("click", async () => {
  const value = codeInput.value.trim();
  if (value) sessionStorage.setItem("panel-code", value);
  else sessionStorage.removeItem("panel-code");
  accessFeedback.textContent = "Memeriksa kode akses…";
  accessFeedback.className = "muted";
  await refreshStatus();
  await refreshLogs();
});
codeInput.addEventListener("keydown", (event) => {
  if (event.key === "Enter") saveButton.click();
});
startButton.addEventListener("click", () => control("start", startButton));
stopButton.addEventListener("click", () => control("stop", stopButton));
refreshButton.addEventListener("click", refreshLogs);
broadcastButton.addEventListener("click", () => {
  const message = broadcastInput.value.trim();
  if (!message) {
    logs.textContent = "Tulis pengumuman terlebih dahulu.";
    return;
  }
  sendCommand(`say ${message}`, broadcastButton, "Kirim pengumuman ini ke semua pemain?");
});
broadcastInput.addEventListener("keydown", (event) => {
  if (event.key === "Enter") broadcastButton.click();
});
playerActionButton.addEventListener("click", () => {
  const name = playerInput.value.trim();
  const action = playerAction.value;
  const reason = playerReason.value.trim();
  if (!/^[A-Za-z0-9_]{1,16}$/.test(name)) {
    logs.textContent = "Masukkan nama pemain Minecraft yang valid (1–16 huruf, angka, atau garis bawah).";
    return;
  }
  const command = `${action} ${name}${reason && !action.startsWith("whitelist") && action !== "pardon" ? ` ${reason}` : ""}`;
  const label = action === "whitelist add" ? "tambahkan ke" : action === "whitelist remove" ? "hapus dari" : action;
  sendCommand(command, playerActionButton, `Konfirmasi ${label} untuk pemain ${name}?`);
});
playerReason.addEventListener("keydown", (event) => {
  if (event.key === "Enter") playerActionButton.click();
});
document.querySelectorAll(".quick-command").forEach((button) => {
  button.addEventListener("click", () => sendCommand(button.dataset.command || "", button));
});
consoleButton.addEventListener("click", () => {
  const command = consoleInput.value.trim();
  const dangerous = /^(op|deop|ban|ban-ip|pardon|kick|kill|clear|tp|teleport|gamemode|gamerule|fill|setblock|clone|stop|restart)\b/i.test(command.replace(/^\/+/, ""));
  const confirmation = dangerous
    ? `Jalankan perintah admin ini?\n\n${command}`
    : "";
  sendCommand(command, consoleButton, confirmation);
});
consoleInput.addEventListener("keydown", (event) => {
  if (event.key === "Enter") consoleButton.click();
});

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("/sw.js").catch(() => {});
}

refreshStatus();
refreshLogs();
setInterval(refreshStatus, 30_000);
