const codeInput = document.querySelector("#access-code");
const statusTitle = document.querySelector("#status-title");
const playerCount = document.querySelector("#player-count");
const updatedAt = document.querySelector("#updated-at");
const logs = document.querySelector("#logs");
const overviewLogs = document.querySelector("#overview-logs");
const opsOutput = document.querySelector("#ops-output");
const opsInput = document.querySelector("#ops-command");
const opsButton = document.querySelector("#ops-button");
const accessFeedback = document.querySelector("#access-feedback");
const startButton = document.querySelector("#start-button");
const stopButton = document.querySelector("#stop-button");
const restartButton = document.querySelector("#restart-button");
const startCloudflareButton = document.querySelector("#start-cloudflare");
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
const fileList = document.querySelector("#file-list");
const breadcrumbs = document.querySelector("#breadcrumbs");
const fileFeedback = document.querySelector("#file-feedback");
const fileEditor = document.querySelector("#file-editor");
const editorName = document.querySelector("#editor-name");
const fileContent = document.querySelector("#file-content");
const saveFileButton = document.querySelector("#save-file");
const downloadFileButton = document.querySelector("#download-file");
const deleteFileButton = document.querySelector("#delete-file");
const renameFileButton = document.querySelector("#rename-file");

const savedCode = localStorage.getItem("panel-code") || sessionStorage.getItem("panel-code") || "";
if (savedCode) localStorage.setItem("panel-code", savedCode);
sessionStorage.removeItem("panel-code");
codeInput.value = savedCode;

let currentPage = "overview";
let currentDirectory = "";
let selectedFile = "";

function token() {
  return localStorage.getItem("panel-code") || "";
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

function setFeedback(element, message, kind = "") {
  element.textContent = message;
  element.className = `feedback${kind ? ` ${kind}` : ""}`;
}

function showPage(name) {
  currentPage = name;
  document.querySelectorAll("[data-page]").forEach((page) => {
    page.classList.toggle("active", page.dataset.page === name);
  });
  document.querySelectorAll("[data-nav]").forEach((item) => {
    item.classList.toggle("active", item.dataset.nav === name);
  });
  const titles = {
    overview: "Ringkasan",
    console: "Console",
    terminal: "Terminal",
    files: "File manager",
    players: "Pemain & moderasi",
    settings: "Akses & pengaturan",
  };
  document.querySelector("#page-title").textContent = titles[name] || "Server";
  if (name === "files") loadFiles(currentDirectory);
  if (name === "console") refreshLogs();
  if (name === "terminal") runOpsCommand("status");
}

document.querySelectorAll("[data-nav]").forEach((item) => {
  item.addEventListener("click", (event) => {
    event.preventDefault();
    showPage(item.dataset.nav);
  });
});

function setUnavailable(message) {
  statusTitle.textContent = message;
  document.querySelector("#stat-server").textContent = "Tidak tersedia";
  document.querySelector("#stat-tunnel").textContent = "Terputus";
  document.querySelector("#sidebar-state").textContent = "Tidak terhubung";
  document.querySelector("#connection-state").textContent = "Panel tidak terhubung";
  document.querySelector("#top-dot").className = "dot offline";
  document.querySelector("#sidebar-dot").className = "dot offline";
  playerCount.textContent = "Status belum tersedia";
  if (message.includes("Kode panel salah")) {
    setFeedback(accessFeedback, "Kode ditolak. Tempel ulang kode lengkap dari terminal Codespace.", "error");
  }
}

async function refreshStatus() {
  if (!token()) {
    setUnavailable("Masukkan kode panel di menu Akses & pengaturan");
    return;
  }
  try {
    const status = await request("/api/status");
    const players = status.players;
    const running = status.running && !status.ambiguous;
    statusTitle.textContent = status.ambiguous
      ? "Server tidak terdeteksi dengan aman"
      : running ? "Server sedang berjalan" : "Server berhenti";
    playerCount.textContent = players
      ? `${players.online}/${players.maximum} pemain online${players.names.length ? ` · ${players.names.join(", ")}` : ""}`
      : running ? "Jumlah pemain belum tersedia" : "Server sedang offline";
    document.querySelector("#stat-server").textContent = running ? "Online" : "Offline";
    document.querySelector("#stat-players").textContent = players
      ? `${players.online} / ${players.maximum}`
      : "—";
    document.querySelector("#stat-tunnel").textContent = "Terhubung";
    document.querySelector("#sidebar-state").textContent = running ? "Online" : "Offline";
    document.querySelector("#connection-state").textContent = "Cloudflare terhubung";
    document.querySelector("#top-dot").className = `dot ${running ? "online" : "offline"}`;
    document.querySelector("#sidebar-dot").className = `dot ${running ? "online" : "offline"}`;
    updatedAt.textContent = `Diperbarui ${new Date().toLocaleTimeString("id-ID")}`;
    setFeedback(accessFeedback, "Kode diterima dan tersimpan di browser ini.", "success");
  } catch (error) {
    setUnavailable(error.message);
  }
}

async function refreshLogs() {
  if (!token()) {
    if (logs) logs.textContent = "Masukkan kode panel untuk melihat log.";
    if (overviewLogs) overviewLogs.textContent = "Masukkan kode panel untuk melihat log.";
    return;
  }
  try {
    const result = await request("/api/log");
    const output = result.lines || "Belum ada log server.";
    if (logs) {
      logs.textContent = output;
      logs.scrollTop = logs.scrollHeight;
    }
    if (overviewLogs) {
      overviewLogs.textContent = output.split("\n").slice(-16).join("\n");
      overviewLogs.scrollTop = overviewLogs.scrollHeight;
    }
  } catch (error) {
    if (logs) logs.textContent = error.message;
    if (overviewLogs) overviewLogs.textContent = error.message;
  }
}

async function control(action, button, confirmation = "") {
  if (confirmation && !window.confirm(confirmation)) return;
  button.disabled = true;
  const original = button.textContent;
  button.textContent = "Memproses…";
  try {
    const result = await request("/api/control", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ action }),
    });
    if (logs) logs.textContent = result.message;
    if (overviewLogs) overviewLogs.textContent = result.message;
    await refreshStatus();
  } catch (error) {
    if (logs) logs.textContent = error.message;
    if (overviewLogs) overviewLogs.textContent = error.message;
    setUnavailable(error.message);
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
    if (logs) logs.textContent = result.message;
    await new Promise((resolve) => setTimeout(resolve, 350));
    await refreshLogs();
  } catch (error) {
    if (logs) logs.textContent = error.message;
  } finally {
    button.disabled = false;
    button.textContent = original;
  }
}

async function runOpsCommand(command) {
  const cleanCommand = command.trim();
  if (!cleanCommand || /[\r\n]/.test(cleanCommand)) return;
  if (!token()) {
    opsOutput.textContent = "Simpan kode panel di menu Akses & pengaturan untuk memakai terminal.";
    return;
  }
  opsButton.disabled = true;
  const original = opsButton.textContent;
  opsButton.textContent = "Menjalankan…";
  try {
    const result = await request("/api/ops", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ command: cleanCommand }),
    });
    opsOutput.textContent = `$ ${cleanCommand}\n${result.output || "Perintah selesai."}`;
    opsOutput.scrollTop = opsOutput.scrollHeight;
  } catch (error) {
    opsOutput.textContent = `$ ${cleanCommand}\n${error.message}`;
    opsOutput.scrollTop = opsOutput.scrollHeight;
  } finally {
    opsButton.disabled = false;
    opsButton.textContent = original;
  }
}

function makeFileButton(label, action, className = "") {
  const button = document.createElement("button");
  button.type = "button";
  button.textContent = label;
  button.className = className;
  button.addEventListener("click", action);
  return button;
}

function renderBreadcrumbs(path) {
  breadcrumbs.replaceChildren();
  const rootButton = makeFileButton("server", () => loadFiles(""));
  rootButton.className = "breadcrumb";
  breadcrumbs.append(rootButton);
  let accumulated = "";
  for (const part of path.split("/").filter(Boolean)) {
    accumulated = accumulated ? `${accumulated}/${part}` : part;
    const current = accumulated;
    const separator = document.createElement("span");
    separator.textContent = "/";
    separator.className = "breadcrumb-separator";
    breadcrumbs.append(separator);
    const button = makeFileButton(part, () => loadFiles(current));
    button.className = "breadcrumb";
    breadcrumbs.append(button);
  }
}

function showSelectedFile(path, editable) {
  selectedFile = path;
  editorName.textContent = path;
  fileContent.disabled = !editable;
  fileContent.value = "";
  saveFileButton.disabled = !editable;
  downloadFileButton.disabled = false;
  deleteFileButton.disabled = false;
  renameFileButton.disabled = false;
  fileEditor.classList.add("editor-open");
}

async function openFile(path) {
  try {
    const result = await request(`/api/files?path=${encodeURIComponent(path)}`);
    const editable = !result.downloadOnly && !path.startsWith("logs/");
    showSelectedFile(path, editable);
    if (path.startsWith("logs/")) {
      deleteFileButton.disabled = true;
      renameFileButton.disabled = true;
    }
    if (result.downloadOnly) {
      fileContent.placeholder = "File biner atau lebih dari 1 MB. Gunakan tombol Unduh untuk menyimpannya.";
      setFeedback(fileFeedback, "File hanya dapat diunduh (biner atau melebihi 1 MB).");
    } else {
      fileContent.placeholder = "";
      fileContent.value = result.content;
      setFeedback(fileFeedback, `Membuka ${path}`);
    }
  } catch (error) {
    setFeedback(fileFeedback, error.message, "error");
  }
}

async function loadFiles(path) {
  if (!token()) {
    fileList.innerHTML = '<tr><td colspan="4" class="empty-state">Simpan kode panel di menu Akses & pengaturan untuk membuka file.</td></tr>';
    return;
  }
  try {
    const result = await request(`/api/files?path=${encodeURIComponent(path)}`);
    currentDirectory = result.path;
    renderBreadcrumbs(result.path);
    fileList.replaceChildren();
    if (!result.entries.length) {
      const row = document.createElement("tr");
      const cell = document.createElement("td");
      cell.colSpan = 4;
      cell.className = "empty-state";
      cell.textContent = "Folder ini kosong.";
      row.append(cell);
      fileList.append(row);
    }
    for (const entry of result.entries) {
      const row = document.createElement("tr");
      const nameCell = document.createElement("td");
      const nameButton = makeFileButton(`${entry.directory ? "▰" : "▤"}  ${entry.name}`, () => {
        if (entry.directory) loadFiles(entry.path);
        else openFile(entry.path);
      }, "file-name");
      nameCell.append(nameButton);
      const typeCell = document.createElement("td");
      typeCell.textContent = entry.directory ? "Folder" : "File";
      const sizeCell = document.createElement("td");
      sizeCell.textContent = entry.directory ? "—" : formatBytes(entry.size);
      const actionCell = document.createElement("td");
      actionCell.className = "row-action";
      if (!entry.directory) {
        actionCell.append(makeFileButton("⋯", () => openFile(entry.path), "icon-button"));
      }
      row.append(nameCell, typeCell, sizeCell, actionCell);
      fileList.append(row);
    }
    if (selectedFile && result.path !== selectedFile.split("/").slice(0, -1).join("/")) {
      closeEditor();
    }
    setFeedback(fileFeedback, `${result.entries.length} item di ${result.path || "server"}`);
  } catch (error) {
    setFeedback(fileFeedback, error.message, "error");
  }
}

function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function closeEditor() {
  selectedFile = "";
  editorName.textContent = "Pilih file teks untuk diedit";
  fileContent.value = "";
  fileContent.placeholder = "Pilih file teks untuk melihat atau mengedit isinya.";
  fileContent.disabled = true;
  saveFileButton.disabled = true;
  downloadFileButton.disabled = true;
  deleteFileButton.disabled = true;
  renameFileButton.disabled = true;
  fileEditor.classList.remove("editor-open");
}

async function mutateFile(action, values) {
  try {
    const result = await request("/api/files", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ action, ...values }),
    });
    setFeedback(fileFeedback, result.message, "success");
    await loadFiles(currentDirectory);
    return true;
  } catch (error) {
    setFeedback(fileFeedback, error.message, "error");
    return false;
  }
}

async function downloadFile(path) {
  try {
    const response = await fetch(`/api/files/download?path=${encodeURIComponent(path)}`, {
      headers: { "X-Panel-Token": token() },
      cache: "no-store",
    });
    if (!response.ok) {
      const payload = await response.json();
      throw new Error(payload.error || `Unduhan gagal (${response.status}).`);
    }
    const blob = await response.blob();
    if (window.AndroidPanel && typeof window.AndroidPanel.saveDownload === "function") {
      const reader = new FileReader();
      reader.onload = () => {
        window.AndroidPanel.saveDownload(
          path.split("/").pop(),
          blob.type || "application/octet-stream",
          String(reader.result),
        );
      };
      reader.onerror = () => setFeedback(fileFeedback, "File tidak dapat disiapkan untuk diunduh.", "error");
      reader.readAsDataURL(blob);
      setFeedback(fileFeedback, `Menyiapkan unduhan ${path}`, "success");
      return;
    }
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = path.split("/").pop();
    link.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    setFeedback(fileFeedback, `Mengunduh ${path}`, "success");
  } catch (error) {
    setFeedback(fileFeedback, error.message, "error");
  }
}

saveButton.addEventListener("click", async () => {
  const value = codeInput.value.trim();
  if (value) localStorage.setItem("panel-code", value);
  else localStorage.removeItem("panel-code");
  sessionStorage.removeItem("panel-code");
  setFeedback(accessFeedback, "Memeriksa kode akses…");
  await refreshStatus();
  await refreshLogs();
  if (currentPage === "files") await loadFiles(currentDirectory);
});
codeInput.addEventListener("keydown", (event) => {
  if (event.key === "Enter") saveButton.click();
});
startButton.addEventListener("click", () => control("start", startButton));
stopButton.addEventListener("click", () => control("stop", stopButton, "Matikan server dan simpan dunia sekarang?"));
restartButton.addEventListener("click", () => control("restart", restartButton, "Restart server Minecraft? Pemain akan terputus sementara."));
startCloudflareButton.addEventListener("click", () => control("cloudflare-start", startCloudflareButton));
refreshButton.addEventListener("click", refreshLogs);
broadcastButton.addEventListener("click", () => {
  const message = broadcastInput.value.trim();
  if (!message || message.length > 160 || /[\r\n]/.test(message)) {
    if (logs) logs.textContent = "Pengumuman harus 1–160 karakter dalam satu baris.";
    return;
  }
  sendCommand(`say ${message}`, broadcastButton, "Kirim pengumuman ini ke semua pemain?");
});
playerActionButton.addEventListener("click", () => {
  const name = playerInput.value.trim();
  const action = playerAction.value;
  const reason = playerReason.value.trim();
  if (!/^[A-Za-z0-9_]{1,16}$/.test(name)) {
    if (logs) logs.textContent = "Masukkan nama pemain Minecraft yang valid.";
    return;
  }
  const command = `${action} ${name}${reason && !action.startsWith("whitelist") && action !== "pardon" ? ` ${reason}` : ""}`;
  sendCommand(command, playerActionButton, `Konfirmasi tindakan untuk pemain ${name}?`);
});
document.querySelectorAll(".quick-command").forEach((button) => {
  button.addEventListener("click", () => sendCommand(button.dataset.command || "", button));
});
consoleButton.addEventListener("click", () => {
  const command = consoleInput.value.trim();
  if (!command) return;
  const dangerous = /^(op|deop|ban|ban-ip|pardon|kick|kill|clear|tp|teleport|gamemode|gamerule|fill|setblock|clone|stop|restart)\b/i.test(command.replace(/^\/+/, ""));
  sendCommand(command, consoleButton, dangerous ? `Jalankan perintah admin ini?\n\n${command}` : "");
  consoleInput.value = "";
});
consoleInput.addEventListener("keydown", (event) => {
  if (event.key === "Enter") consoleButton.click();
});
opsButton.addEventListener("click", () => {
  const command = opsInput.value;
  opsInput.value = "";
  runOpsCommand(command);
});
opsInput.addEventListener("keydown", (event) => {
  if (event.key === "Enter") opsButton.click();
});
document.querySelectorAll(".quick-ops").forEach((button) => {
  button.addEventListener("click", () => runOpsCommand(button.dataset.opsCommand || ""));
});
document.querySelector("#refresh-ops").addEventListener("click", () => runOpsCommand("status"));

document.querySelector("#refresh-files").addEventListener("click", () => loadFiles(currentDirectory));
document.querySelector("#new-folder").addEventListener("click", async () => {
  if (currentDirectory === "logs") {
    setFeedback(fileFeedback, "Folder log hanya dapat dibaca.", "error");
    return;
  }
  const name = window.prompt("Nama folder baru");
  if (!name) return;
  const path = currentDirectory ? `${currentDirectory}/${name}` : name;
  await mutateFile("mkdir", { path });
});
document.querySelector("#new-file").addEventListener("click", async () => {
  if (currentDirectory === "logs") {
    setFeedback(fileFeedback, "Folder log hanya dapat dibaca.", "error");
    return;
  }
  const name = window.prompt("Nama file teks baru");
  if (!name) return;
  const path = currentDirectory ? `${currentDirectory}/${name}` : name;
  if (await mutateFile("save", { path, content: "" })) await openFile(path);
});
document.querySelector("#upload-file").addEventListener("change", async (event) => {
  const file = event.target.files[0];
  if (!file) return;
  if (currentDirectory === "logs") {
    setFeedback(fileFeedback, "Folder log hanya dapat dibaca.", "error");
    event.target.value = "";
    return;
  }
  if (file.size > 10_000_000) {
    setFeedback(fileFeedback, "Ukuran unggahan maksimal 10 MB.", "error");
    event.target.value = "";
    return;
  }
  try {
    const response = await fetch(`/api/files/upload?path=${encodeURIComponent(currentDirectory)}&name=${encodeURIComponent(file.name)}`, {
      method: "POST",
      headers: { "X-Panel-Token": token(), "Content-Type": "application/octet-stream" },
      body: file,
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || `Unggah gagal (${response.status}).`);
    setFeedback(fileFeedback, result.message, "success");
    await loadFiles(currentDirectory);
  } catch (error) {
    setFeedback(fileFeedback, error.message, "error");
  } finally {
    event.target.value = "";
  }
});
saveFileButton.addEventListener("click", async () => {
  if (!selectedFile) return;
  await mutateFile("save", { path: selectedFile, content: fileContent.value });
});
downloadFileButton.addEventListener("click", () => {
  if (selectedFile) downloadFile(selectedFile);
});
deleteFileButton.addEventListener("click", async () => {
  if (!selectedFile || !window.confirm(`Hapus ${selectedFile}? Folder hanya dapat dihapus jika kosong.`)) return;
  if (await mutateFile("delete", { path: selectedFile })) closeEditor();
});
renameFileButton.addEventListener("click", async () => {
  if (!selectedFile) return;
  const oldName = selectedFile.split("/").pop();
  const name = window.prompt("Nama baru", oldName);
  if (!name || name === oldName) return;
  const parent = selectedFile.split("/").slice(0, -1).join("/");
  const newPath = parent ? `${parent}/${name}` : name;
  if (await mutateFile("rename", { path: selectedFile, newPath })) {
    selectedFile = newPath;
    await openFile(newPath);
  }
});

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("/sw.js").catch(() => {});
}

refreshStatus();
refreshLogs();
setInterval(refreshStatus, 30_000);
setInterval(() => {
  if (currentPage === "overview" || currentPage === "console") refreshLogs();
}, 10_000);
