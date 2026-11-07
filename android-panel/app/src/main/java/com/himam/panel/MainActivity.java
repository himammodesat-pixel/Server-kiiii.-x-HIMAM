package com.himam.panel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ArrayAdapter;

import androidx.browser.customtabs.CustomTabsIntent;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class MainActivity extends Activity {
    private static final String PREFERENCES = "panel_settings";
    private static final String CODESPACE_KEY = "codespace_name";
    private static final String TOKEN_KEY = "github_token_encrypted";
    private static final String PANEL_TOKEN_KEY = "panel_token_encrypted";
    private static final String KEY_ALIAS = "himam_github_access_token";
    private static final String DEVICE_CODE_URL = "https://github.com/login/device/code";
    private static final String ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String API_URL = "https://api.github.com";
    private static final String OAUTH_SCOPE = "codespace";
    private static final Pattern CODESPACE_NAME_PATTERN =
            Pattern.compile("^[a-zA-Z0-9-]{3,100}$");

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText codespaceInput;
    private TextView status;
    private TextView panelStatus;
    private EditText panelTokenInput;
    private EditText broadcastInput;
    private EditText playerInput;
    private EditText reasonInput;
    private EditText commandInput;
    private Spinner playerActionInput;
    private Button connectButton;
    private Button startButton;
    private Button savePanelTokenButton;
    private Button refreshButton;
    private Button startServerButton;
    private Button stopServerButton;
    private Button broadcastButton;
    private Button playerActionButton;
    private Button commandButton;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(16, 26, 23));
        getWindow().setNavigationBarColor(Color.rgb(16, 26, 23));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(28), dp(24), dp(24));
        page.setBackgroundColor(Color.rgb(16, 26, 23));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(page);
        setContentView(scroll);

        TextView eyebrow = text("MINECRAFT SERVER", 12, 0xFF91B69D, true);
        eyebrow.setLetterSpacing(0.12f);
        page.addView(eyebrow);
        page.addView(text("HIMAM SMP", 30, 0xFFEEF4ED, true), margin(0, 5, 0, 8));
        page.addView(text(
                "Kontrol Codespace, server, dan Cloudflare Tunnel langsung dari aplikasi. Ketika Codespace berhenti, tekan Nyalakan; server dan tunnel akan ikut hidup saat container mulai.",
                14, 0xFFC0CEC5, false
        ), margin(0, 0, 0, 18));

        status = text("Periksa koneksi GitHub.", 14, 0xFF9AABA1, false);
        status.setPadding(dp(13), dp(12), dp(13), dp(12));
        status.setBackground(rounded(0xFF172720, 0xFF2D4439, 12));
        page.addView(status, margin(0, 0, 0, 16));

        TextView label = text("Nama Codespace", 14, 0xFFEEF4ED, true);
        page.addView(label, margin(0, 0, 0, 8));
        codespaceInput = new EditText(this);
        codespaceInput.setSingleLine(true);
        codespaceInput.setTextSize(14);
        codespaceInput.setTextColor(Color.rgb(238, 244, 237));
        codespaceInput.setHintTextColor(Color.rgb(132, 151, 139));
        codespaceInput.setHint("nama-codespace");
        codespaceInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        codespaceInput.setAutofillHints();
        codespaceInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        codespaceInput.setBackground(rounded(0xFF0E1713, 0xFF3B5949, 10));
        page.addView(codespaceInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));

        connectButton = button("Hubungkan GitHub (device login)", 0xFF20362B, 0xFF3B5949, 0xFFEEF4ED);
        page.addView(connectButton, margin(0, 14, 0, 8));
        connectButton.setOnClickListener(view -> beginDeviceLogin());

        startButton = button("▶  Nyalakan Codespace", 0xFF72C982, 0xFF83C88E, 0xFF102015);
        page.addView(startButton, margin(0, 0, 0, 8));
        startButton.setOnClickListener(view -> confirmAndStart());

        page.addView(text("KONTROL SERVER", 12, 0xFF91B69D, true), margin(0, 12, 0, 8));
        panelStatus = text("Simpan kode panel untuk melihat status.", 13, 0xFF9AABA1, false);
        panelStatus.setPadding(dp(13), dp(12), dp(13), dp(12));
        panelStatus.setBackground(rounded(0xFF172720, 0xFF2D4439, 12));
        page.addView(panelStatus, margin(0, 0, 0, 10));

        LinearLayout tokenRow = new LinearLayout(this);
        tokenRow.setOrientation(LinearLayout.HORIZONTAL);
        tokenRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        panelTokenInput = editText("Kode panel (dimasukkan sekali)", true);
        tokenRow.addView(panelTokenInput, new LinearLayout.LayoutParams(0, dp(50), 1f));
        savePanelTokenButton = button("Simpan", 0xFF20362B, 0xFF3B5949, 0xFFEEF4ED);
        tokenRow.addView(savePanelTokenButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(50)));
        page.addView(tokenRow, margin(0, 0, 0, 8));
        savePanelTokenButton.setOnClickListener(view -> savePanelAccessCode());

        refreshButton = button("↻  Perbarui status", 0xFF20362B, 0xFF3B5949, 0xFFEEF4ED);
        page.addView(refreshButton, margin(0, 0, 0, 8));
        refreshButton.setOnClickListener(view -> refreshPanelStatus());

        LinearLayout serverActions = new LinearLayout(this);
        serverActions.setOrientation(LinearLayout.HORIZONTAL);
        startServerButton = button("▶  Start server", 0xFF72C982, 0xFF83C88E, 0xFF102015);
        stopServerButton = button("■  Stop server", 0xFF382723, 0xFF654540, 0xFFFFD2CA);
        serverActions.addView(startServerButton, new LinearLayout.LayoutParams(0, dp(50), 1f));
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, dp(50), 1f);
        stopParams.setMargins(dp(8), 0, 0, 0);
        serverActions.addView(stopServerButton, stopParams);
        page.addView(serverActions, margin(0, 0, 0, 12));
        startServerButton.setOnClickListener(view -> sendControl("start", startServerButton));
        stopServerButton.setOnClickListener(view -> confirmStopServer());

        page.addView(text("Pengumuman", 14, 0xFFEEF4ED, true), margin(0, 0, 0, 6));
        LinearLayout broadcastRow = new LinearLayout(this);
        broadcastRow.setOrientation(LinearLayout.HORIZONTAL);
        broadcastInput = editText("Pesan untuk semua pemain", false);
        broadcastRow.addView(broadcastInput, new LinearLayout.LayoutParams(0, dp(50), 1f));
        broadcastButton = button("Kirim", 0xFF20362B, 0xFF3B5949, 0xFFEEF4ED);
        broadcastRow.addView(broadcastButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(50)));
        page.addView(broadcastRow, margin(0, 0, 0, 12));
        broadcastButton.setOnClickListener(view -> sendBroadcast());

        page.addView(text("Moderasi pemain", 14, 0xFFEEF4ED, true), margin(0, 0, 0, 6));
        playerInput = editText("Nama pemain", false);
        page.addView(playerInput, margin(0, 0, 0, 8));
        playerActionInput = new Spinner(this);
        String[] moderationActions = {"Kick", "Ban", "Unban", "Whitelist +", "Whitelist −"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, moderationActions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        playerActionInput.setAdapter(adapter);
        page.addView(playerActionInput, margin(0, 0, 0, 8));
        reasonInput = editText("Alasan (opsional)", false);
        page.addView(reasonInput, margin(0, 0, 0, 8));
        playerActionButton = button("Jalankan aksi pemain", 0xFF20362B, 0xFF3B5949, 0xFFEEF4ED);
        page.addView(playerActionButton, margin(0, 0, 0, 12));
        playerActionButton.setOnClickListener(view -> moderatePlayer());

        page.addView(text("Console Minecraft", 14, 0xFFEEF4ED, true), margin(0, 0, 0, 6));
        LinearLayout commandRow = new LinearLayout(this);
        commandRow.setOrientation(LinearLayout.HORIZONTAL);
        commandInput = editText("Contoh: time set day", false);
        commandRow.addView(commandInput, new LinearLayout.LayoutParams(0, dp(50), 1f));
        commandButton = button("Kirim", 0xFF20362B, 0xFF3B5949, 0xFFEEF4ED);
        commandRow.addView(commandButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(50)));
        page.addView(commandRow, margin(0, 0, 0, 8));
        commandButton.setOnClickListener(view -> sendConsoleCommand());
        page.addView(text(
                "Kode panel disimpan terenkripsi di Android. Console memberi akses admin penuh ke Minecraft; gunakan hanya perintah yang kamu pahami.",
                12, 0xFF9AABA1, false
        ), margin(0, 0, 0, 18));

        TextView info = text(
                "Login memakai GitHub Device Flow dengan izin Codespaces. Token disimpan terenkripsi di Android Keystore. Jangan bagikan kode login. Codespace berhenti saat timeout GitHub; batas hidup GitHub tetap berlaku. Cloudflare Tunnel juga offline saat Codespace berhenti.",
                12, 0xFF9AABA1, false
        );
        page.addView(info);

        SharedPreferences preferences = getPreferences(MODE_PRIVATE);
        String savedName = preferences.getString(CODESPACE_KEY, BuildConfig.CODESPACE_NAME);
        if (savedName != null) {
            codespaceInput.setText(savedName);
        }
        String savedPanelCode = getEncryptedPanelCode();
        if (!savedPanelCode.isEmpty()) {
            panelTokenInput.setText(savedPanelCode);
            panelStatus.setText("Kode panel tersimpan aman. Ketuk Perbarui status.");
        }
        refreshConnectionStatus();
    }

    private void beginDeviceLogin() {
        if (busy) {
            return;
        }
        if (BuildConfig.GITHUB_OAUTH_CLIENT_ID.trim().isEmpty()) {
            setStatus("APK belum dikonfigurasi dengan GitHub OAuth Client ID.", 0xFFFFB4A8);
            return;
        }
        setBusy(true);
        setStatus("Meminta kode login dari GitHub…", 0xFFC0CEC5);
        executor.execute(() -> {
            try {
                JSONObject request = new JSONObject();
                request.put("client_id", BuildConfig.GITHUB_OAUTH_CLIENT_ID);
                request.put("scope", OAUTH_SCOPE);
                JSONObject result = postForm(DEVICE_CODE_URL, request);
                String deviceCode = result.getString("device_code");
                String userCode = result.getString("user_code");
                String verificationUri = result.optString("verification_uri", "https://github.com/login/device");
                int expiresIn = result.optInt("expires_in", 900);
                int interval = Math.max(5, result.optInt("interval", 5));

                runOnUiThread(() -> showDeviceCode(userCode, verificationUri));
                long deadline = System.currentTimeMillis() + expiresIn * 1000L;
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(interval * 1000L);
                    JSONObject tokenResponse = postForm(ACCESS_TOKEN_URL, new JSONObject()
                            .put("client_id", BuildConfig.GITHUB_OAUTH_CLIENT_ID)
                            .put("device_code", deviceCode)
                            .put("grant_type", "urn:ietf:params:oauth:grant-type:device_code"));
                    String error = tokenResponse.optString("error", "");
                    if ("authorization_pending".equals(error)) {
                        continue;
                    }
                    if ("slow_down".equals(error)) {
                        interval += 5;
                        continue;
                    }
                    if (!error.isEmpty()) {
                        throw new IllegalStateException(oauthError(error));
                    }
                    String accessToken = tokenResponse.optString("access_token", "");
                    if (accessToken.isEmpty()) {
                        throw new IllegalStateException("GitHub tidak mengembalikan access token.");
                    }
                    saveEncryptedToken(accessToken);
                    runOnUiThread(() -> {
                        setBusy(false);
                        setStatus("GitHub terhubung. Tekan Nyalakan Codespace.", 0xFF9CE0A7);
                    });
                    return;
                }
                throw new IllegalStateException("Kode login kedaluwarsa. Mulai Hubungkan GitHub lagi.");
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    setStatus(error.getMessage() == null ? "Login GitHub gagal." : error.getMessage(), 0xFFFFB4A8);
                });
            }
        });
    }

    private void showDeviceCode(String userCode, String verificationUri) {
        setStatus("Masukkan kode " + userCode + " di GitHub untuk menyetujui akses Codespaces.", 0xFFFFD37A);
        new AlertDialog.Builder(this)
                .setTitle("Selesaikan login GitHub")
                .setMessage("Kode sekali pakai:\n\n" + userCode + "\n\nBuka github.com/login/device, masukkan kode ini, lalu setujui. Jangan kirim kode ini ke siapa pun.")
                .setPositiveButton("Buka GitHub", (dialog, which) -> openUrl(verificationUri))
                .setNegativeButton("Nanti", null)
                .show();
    }

    private void confirmAndStart() {
        String codespaceName = codespaceInput.getText().toString().trim();
        if (!CODESPACE_NAME_PATTERN.matcher(codespaceName).matches()) {
            setStatus("Nama Codespace tidak valid.", 0xFFFFB4A8);
            return;
        }
        if (getEncryptedToken().isEmpty()) {
            setStatus("Hubungkan GitHub terlebih dahulu.", 0xFFFFB4A8);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Nyalakan Codespace?")
                .setMessage("GitHub akan menyalakan Codespace “" + codespaceName + "”. Selama aktif, penggunaan compute dapat ditagihkan sesuai kuota dan billing akunmu.")
                .setPositiveButton("Nyalakan", (dialog, which) -> startCodespace(codespaceName))
                .setNegativeButton("Batal", null)
                .show();
    }

    private void startCodespace(String name) {
        if (busy) {
            return;
        }
        getPreferences(MODE_PRIVATE).edit().putString(CODESPACE_KEY, name).apply();
        setBusy(true);
        setStatus("Meminta GitHub menyalakan Codespace…", 0xFFC0CEC5);
        executor.execute(() -> {
            try {
                String token = getEncryptedToken();
                String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8.name()).replace("+", "%20");
                String endpoint = API_URL + "/user/codespaces/" + encodedName;
                ApiResponse response = githubRequest(endpoint + "/start", "POST", token, "{}");
                if (response.statusCode == 401 || response.statusCode == 403) {
                    clearEncryptedToken();
                    throw new IllegalStateException("GitHub menolak izin Codespaces. Hubungkan ulang GitHub dan pastikan OAuth app meminta izin codespace.");
                }
                if (response.statusCode < 200 || response.statusCode >= 300) {
                    throw apiError(response);
                }

                long deadline = System.currentTimeMillis() + 180_000L;
                String state = "";
                while (System.currentTimeMillis() < deadline) {
                    ApiResponse statusResponse = githubRequest(endpoint, "GET", token, null);
                    if (statusResponse.statusCode == 401 || statusResponse.statusCode == 403) {
                        clearEncryptedToken();
                        throw new IllegalStateException("Sesi GitHub perlu dihubungkan ulang.");
                    }
                    if (statusResponse.statusCode < 200 || statusResponse.statusCode >= 300) {
                        throw apiError(statusResponse);
                    }
                    state = statusResponse.json.optString("state", "");
                    String currentState = state;
                    runOnUiThread(() -> setStatus("Status Codespace: " + currentState, 0xFFC0CEC5));
                    if ("Available".equalsIgnoreCase(state)) {
                        break;
                    }
                    if ("Failed".equalsIgnoreCase(state) || "Deleted".equalsIgnoreCase(state)) {
                        throw new IllegalStateException("Codespace berstatus " + state + ".");
                    }
                    Thread.sleep(5000L);
                }
                if (!"Available".equalsIgnoreCase(state)) {
                    throw new IllegalStateException("GitHub belum menyatakan Codespace aktif setelah 3 menit. Periksa status Codespaces di GitHub.");
                }
                long panelDeadline = System.currentTimeMillis() + 300_000L;
                boolean panelReady = false;
                while (System.currentTimeMillis() < panelDeadline) {
                    int responseCode = probePanel();
                    if (responseCode >= 200 && responseCode < 400) {
                        panelReady = true;
                        break;
                    }
                    runOnUiThread(() -> setStatus(
                            "Codespace aktif; menunggu Cloudflare Tunnel dan panel mulai…",
                            0xFFC0CEC5
                    ));
                    Thread.sleep(5000L);
                }
                if (!panelReady) {
                    throw new IllegalStateException("Codespace aktif, tetapi panel belum terjangkau. Tunggu sebentar lalu ketuk Perbarui status.");
                }
                runOnUiThread(() -> {
                    setBusy(false);
                    setStatus("Codespace, server, dan Cloudflare Tunnel aktif. Kontrol server tersedia di aplikasi.", 0xFF9CE0A7);
                    refreshPanelStatus();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    setStatus(error.getMessage() == null ? "Tidak dapat menyalakan Codespace." : error.getMessage(), 0xFFFFB4A8);
                });
            }
        });
    }

    private void openUrl(String address) {
        try {
            CustomTabsIntent browser = new CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .setToolbarColor(Color.rgb(16, 26, 23))
                    .build();
            browser.launchUrl(this, Uri.parse(address));
        } catch (Exception error) {
            Intent openLink = new Intent(Intent.ACTION_VIEW, Uri.parse(address));
            if (openLink.resolveActivity(getPackageManager()) != null) {
                startActivity(openLink);
            } else {
                Toast.makeText(this, "Tidak ada browser yang bisa membuka tautan.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private EditText editText(String hint, boolean password) {
        EditText result = new EditText(this);
        result.setSingleLine(true);
        result.setTextSize(14);
        result.setTextColor(Color.rgb(238, 244, 237));
        result.setHintTextColor(Color.rgb(132, 151, 139));
        result.setHint(hint);
        int inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL;
        if (password) {
            inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        }
        result.setInputType(inputType);
        result.setAutofillHints();
        result.setPadding(dp(12), dp(10), dp(12), dp(10));
        result.setBackground(rounded(0xFF0E1713, 0xFF3B5949, 10));
        return result;
    }

    private void savePanelAccessCode() {
        String code = panelTokenInput.getText().toString().trim();
        if (code.length() < 32) {
            setPanelStatus("Kode panel tidak valid. Salin kode lengkap dari Codespace.", 0xFFFFB4A8);
            return;
        }
        try {
            saveEncryptedValue(PANEL_TOKEN_KEY, code);
            panelTokenInput.setText("");
            hideKeyboard();
            setPanelStatus("Kode disimpan terenkripsi. Memeriksa tunnel…", 0xFFC0CEC5);
            refreshPanelStatus();
        } catch (Exception error) {
            setPanelStatus("Gagal menyimpan kode secara aman.", 0xFFFFB4A8);
        }
    }

    private void refreshPanelStatus() {
        String panelCode = getEncryptedPanelCode();
        if (panelCode.isEmpty()) {
            setPanelStatus("Masukkan kode panel satu kali untuk mengaktifkan kontrol.", 0xFFFFD37A);
            return;
        }
        refreshButton.setEnabled(false);
        setPanelStatus("Memeriksa server dan Cloudflare Tunnel…", 0xFFC0CEC5);
        executor.execute(() -> {
            try {
                ApiResponse response = panelRequest("/api/status", "GET", panelCode, null);
                if (response.statusCode == 401) {
                    runOnUiThread(() -> setPanelStatus("Kode panel ditolak. Masukkan ulang kode yang benar.", 0xFFFFB4A8));
                    return;
                }
                if (response.statusCode < 200 || response.statusCode >= 300) {
                    throw panelError(response);
                }
                JSONObject payload = response.json;
                boolean running = payload.optBoolean("running", false);
                JSONObject players = payload.optJSONObject("players");
                String playerText = players == null
                        ? "jumlah pemain belum tersedia"
                        : players.optInt("online", 0) + "/" + players.optInt("maximum", 0) + " pemain";
                String message = running
                        ? "Server ONLINE · " + playerText + " · Cloudflare tersambung"
                        : "Server OFFLINE · Cloudflare tersambung";
                runOnUiThread(() -> setPanelStatus(message, running ? 0xFF9CE0A7 : 0xFFFFD37A));
            } catch (Exception error) {
                String message = error.getMessage() == null
                        ? "Tidak dapat menghubungi panel Cloudflare."
                        : error.getMessage();
                runOnUiThread(() -> setPanelStatus(message, 0xFFFFB4A8));
            } finally {
                runOnUiThread(() -> refreshButton.setEnabled(true));
            }
        });
    }

    private int probePanel() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(panelBaseUrl()).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("User-Agent", mobileUserAgent());
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            return connection.getResponseCode();
        } catch (Exception ignored) {
            return 0;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void sendControl(String action, Button source) {
        if ("stop".equals(action)) {
            confirmStopServer();
            return;
        }
        sendPanelRequest("/api/control", "POST", jsonObject("action", action), source, null);
    }

    private void confirmStopServer() {
        new AlertDialog.Builder(this)
                .setTitle("Matikan server Minecraft?")
                .setMessage("Pemain akan terputus dan dunia akan disimpan sebelum server berhenti.")
                .setPositiveButton("Matikan", (dialog, which) ->
                        sendPanelRequest("/api/control", "POST", jsonObject("action", "stop"), stopServerButton, null))
                .setNegativeButton("Batal", null)
                .show();
    }

    private void sendBroadcast() {
        String message = broadcastInput.getText().toString().trim();
        if (message.isEmpty() || message.length() > 160 || containsLineBreak(message)) {
            setPanelStatus("Pengumuman harus 1–160 karakter dalam satu baris.", 0xFFFFB4A8);
            return;
        }
        confirmCommand("Kirim pengumuman ini ke semua pemain?", "say " + message, broadcastButton);
    }

    private void moderatePlayer() {
        String name = playerInput.getText().toString().trim();
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            setPanelStatus("Nama pemain harus 1–16 huruf, angka, atau garis bawah.", 0xFFFFB4A8);
            return;
        }
        String[] commands = {"kick", "ban", "pardon", "whitelist add", "whitelist remove"};
        String[] labels = {"kick", "ban", "unban", "tambahkan ke whitelist", "hapus dari whitelist"};
        int selected = playerActionInput.getSelectedItemPosition();
        String command = commands[selected] + " " + name;
        String reason = reasonInput.getText().toString().trim();
        if (selected <= 1 && !reason.isEmpty()) {
            if (containsLineBreak(reason)) {
                setPanelStatus("Alasan harus dalam satu baris.", 0xFFFFB4A8);
                return;
            }
            command += " " + reason.substring(0, Math.min(reason.length(), 100));
        }
        confirmCommand("Konfirmasi " + labels[selected] + " untuk " + name + "?", command, playerActionButton);
    }

    private void sendConsoleCommand() {
        String command = commandInput.getText().toString().trim();
        if (command.isEmpty() || command.length() > 256 || containsLineBreak(command)) {
            setPanelStatus("Perintah harus satu baris dan maksimal 256 karakter.", 0xFFFFB4A8);
            return;
        }
        String normalized = command.replaceFirst("^/+", "");
        if (normalized.matches("(?i)^(stop|restart|minecraft:stop|paper:restart)(\\s.*)?$")) {
            setPanelStatus("Gunakan tombol Stop server agar dunia disimpan dengan aman.", 0xFFFFD37A);
            return;
        }
        if (normalized.matches("(?i)^(op|deop|ban|ban-ip|pardon|kick|kill|clear|tp|teleport|gamemode|gamerule|fill|setblock|clone)(\\s.*)?$")) {
            confirmCommand("Perintah ini memberi dampak admin:\n\n" + command, command, commandButton);
            return;
        }
        sendMinecraftCommand(command, commandButton);
    }

    private void confirmCommand(String message, String command, Button source) {
        new AlertDialog.Builder(this)
                .setTitle("Konfirmasi perintah")
                .setMessage(message)
                .setPositiveButton("Jalankan", (dialog, which) -> sendMinecraftCommand(command, source))
                .setNegativeButton("Batal", null)
                .show();
    }

    private void sendMinecraftCommand(String command, Button source) {
        sendPanelRequest("/api/command", "POST", jsonObject("command", command), source, command);
    }

    private JSONObject jsonObject(String key, String value) {
        JSONObject object = new JSONObject();
        try {
            object.put(key, value);
            return object;
        } catch (Exception error) {
            throw new IllegalStateException("Tidak dapat membuat permintaan kontrol.", error);
        }
    }

    private void sendPanelRequest(String path, String method, JSONObject body, Button source, String sentCommand) {
        String panelCode = getEncryptedPanelCode();
        if (panelCode.isEmpty()) {
            setPanelStatus("Simpan kode panel untuk mengaktifkan kontrol.", 0xFFFFD37A);
            return;
        }
        source.setEnabled(false);
        executor.execute(() -> {
            try {
                ApiResponse response = panelRequest(path, method, panelCode, body);
                if (response.statusCode == 401) {
                    throw new IllegalStateException("Kode panel ditolak. Simpan ulang kode akses yang benar.");
                }
                if (response.statusCode < 200 || response.statusCode >= 300) {
                    throw panelError(response);
                }
                String message = response.json.optString("message", "Perintah berhasil dikirim.");
                if (sentCommand != null) {
                    runOnUiThread(() -> commandInput.setText(""));
                }
                runOnUiThread(() -> setPanelStatus(message, 0xFF9CE0A7));
                Thread.sleep(500L);
                refreshPanelStatus();
            } catch (Exception error) {
                String message = error.getMessage() == null
                        ? "Kontrol gagal. Pastikan Codespace dan Cloudflare Tunnel aktif."
                        : error.getMessage();
                runOnUiThread(() -> setPanelStatus(message, 0xFFFFB4A8));
            } finally {
                runOnUiThread(() -> source.setEnabled(true));
            }
        });
    }

    private ApiResponse panelRequest(String path, String method, String token, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(panelBaseUrl() + path).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", mobileUserAgent());
            connection.setRequestProperty("X-Panel-Token", token);
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int statusCode = connection.getResponseCode();
            JSONObject json = readResponseJson(connection, statusCode);
            return new ApiResponse(statusCode, json);
        } finally {
            connection.disconnect();
        }
    }

    private String panelBaseUrl() {
        String address = BuildConfig.PUBLIC_PANEL_URL;
        if (address == null || !address.startsWith("https://")) {
            throw new IllegalStateException("URL Cloudflare panel belum dikonfigurasi.");
        }
        return address.endsWith("/") ? address.substring(0, address.length() - 1) : address;
    }

    private String mobileUserAgent() {
        return "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    }

    private JSONObject readResponseJson(HttpURLConnection connection, int statusCode) throws Exception {
        InputStream input = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (input == null) {
            return new JSONObject();
        }
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                if (output.size() > 16_384) {
                    throw new IllegalStateException("Respons server terlalu besar.");
                }
            }
            String content = output.toString(StandardCharsets.UTF_8.name());
            if (content.trim().isEmpty()) {
                return new JSONObject();
            }
            try {
                return new JSONObject(content);
            } catch (Exception ignored) {
                String safeMessage = content.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                if (safeMessage.length() > 180) {
                    safeMessage = safeMessage.substring(0, 180);
                }
                return new JSONObject().put("message", safeMessage.isEmpty()
                        ? "Cloudflare menolak permintaan atau panel sedang offline."
                        : safeMessage);
            }
        }
    }

    private IllegalStateException panelError(ApiResponse response) {
        String message = response.json.optString(
                "error",
                response.json.optString("message", "Panel mengembalikan HTTP " + response.statusCode)
        );
        return new IllegalStateException(message + " (HTTP " + response.statusCode + ").");
    }

    private void saveEncryptedValue(String preferenceKey, String value) throws Exception {
        SecretKey key = getOrCreateEncryptionKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        ByteBuffer combined = ByteBuffer.allocate(1 + iv.length + ciphertext.length);
        combined.put((byte) iv.length).put(iv).put(ciphertext);
        String encrypted = Base64.encodeToString(combined.array(), Base64.NO_WRAP);
        getPreferences(MODE_PRIVATE).edit().putString(preferenceKey, encrypted).apply();
    }

    private String decryptValue(String preferenceKey) {
        String encrypted = getPreferences(MODE_PRIVATE).getString(preferenceKey, "");
        if (encrypted == null || encrypted.isEmpty()) {
            return "";
        }
        try {
            ByteBuffer data = ByteBuffer.wrap(Base64.decode(encrypted, Base64.NO_WRAP));
            int ivLength = data.get() & 0xFF;
            byte[] iv = new byte[ivLength];
            data.get(iv);
            byte[] ciphertext = new byte[data.remaining()];
            data.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateEncryptionKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception error) {
            getPreferences(MODE_PRIVATE).edit().remove(preferenceKey).apply();
            return "";
        }
    }

    private String getEncryptedPanelCode() {
        return decryptValue(PANEL_TOKEN_KEY);
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused == null) {
            return;
        }
        InputMethodManager keyboard = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) {
            keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
        focused.clearFocus();
    }

    private boolean containsLineBreak(String text) {
        return text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
    }

    private void setPanelStatus(String message, int color) {
        panelStatus.setText(message);
        panelStatus.setTextColor(color);
    }

    private JSONObject postForm(String endpoint, JSONObject values) throws Exception {
        StringBuilder form = new StringBuilder();
        java.util.Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (form.length() > 0) {
                form.append('&');
            }
            form.append(URLEncoder.encode(key, StandardCharsets.UTF_8.name()))
                    .append('=')
                    .append(URLEncoder.encode(values.getString(key), StandardCharsets.UTF_8.name()));
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(15_000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setDoOutput(true);
            byte[] body = form.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            return readJson(connection);
        } finally {
            connection.disconnect();
        }
    }

    private ApiResponse githubRequest(String endpoint, String method, String token, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setRequestProperty("User-Agent", "HIMAM-SMP-Panel/1.0.1");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            JSONObject json = readJson(connection);
            return new ApiResponse(connection.getResponseCode(), json);
        } finally {
            connection.disconnect();
        }
    }

    private JSONObject readJson(HttpURLConnection connection) throws Exception {
        int statusCode = connection.getResponseCode();
        InputStream input = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (input == null) {
            return new JSONObject();
        }
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            String content = output.toString(StandardCharsets.UTF_8.name());
            return content.trim().isEmpty() ? new JSONObject() : new JSONObject(content);
        }
    }

    private IllegalStateException apiError(ApiResponse response) {
        String message = response.json.optString("message", "GitHub API mengembalikan HTTP " + response.statusCode);
        return new IllegalStateException(message + " (HTTP " + response.statusCode + ").");
    }

    private String oauthError(String error) {
        if ("access_denied".equals(error)) {
            return "Login dibatalkan. Coba hubungkan GitHub lagi jika kamu ingin melanjutkan.";
        }
        if ("expired_token".equals(error)) {
            return "Kode login kedaluwarsa. Mulai Hubungkan GitHub lagi.";
        }
        return "GitHub OAuth gagal: " + error;
    }

    private void saveEncryptedToken(String token) throws Exception {
        SecretKey key = getOrCreateEncryptionKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        ByteBuffer combined = ByteBuffer.allocate(1 + iv.length + ciphertext.length);
        combined.put((byte) iv.length).put(iv).put(ciphertext);
        String encrypted = Base64.encodeToString(combined.array(), Base64.NO_WRAP);
        getPreferences(MODE_PRIVATE).edit().putString(TOKEN_KEY, encrypted).apply();
    }

    private String getEncryptedToken() {
        String encrypted = getPreferences(MODE_PRIVATE).getString(TOKEN_KEY, "");
        if (encrypted == null || encrypted.isEmpty()) {
            return "";
        }
        try {
            ByteBuffer data = ByteBuffer.wrap(Base64.decode(encrypted, Base64.NO_WRAP));
            int ivLength = data.get() & 0xFF;
            byte[] iv = new byte[ivLength];
            data.get(iv);
            byte[] ciphertext = new byte[data.remaining()];
            data.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateEncryptionKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception error) {
            clearEncryptedToken();
            return "";
        }
    }

    private SecretKey getOrCreateEncryptionKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private void clearEncryptedToken() {
        getPreferences(MODE_PRIVATE).edit().remove(TOKEN_KEY).apply();
    }

    private void refreshConnectionStatus() {
        if (getEncryptedToken().isEmpty()) {
            setStatus("Belum terhubung ke GitHub. Client ID perlu dikonfigurasi di APK.", 0xFFFFD37A);
        } else {
            setStatus("GitHub sudah terhubung. Siap menyalakan Codespace.", 0xFF9CE0A7);
        }
    }

    private void setStatus(String message, int color) {
        status.setText(message);
        status.setTextColor(color);
    }

    private void setBusy(boolean value) {
        busy = value;
        connectButton.setEnabled(!value);
        startButton.setEnabled(!value);
        connectButton.setText(value ? "Sedang diproses…" : "Hubungkan GitHub (device login)");
        startButton.setText(value ? "Menunggu GitHub…" : "▶  Nyalakan Codespace");
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView result = new TextView(this);
        result.setText(value);
        result.setTextSize(size);
        result.setTextColor(color);
        if (bold) {
            result.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return result;
    }

    private Button button(String value, int fill, int stroke, int foreground) {
        Button result = new Button(this);
        result.setAllCaps(false);
        result.setText(value);
        result.setTextSize(14);
        result.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        result.setTextColor(foreground);
        result.setPadding(dp(10), dp(4), dp(10), dp(4));
        result.setBackground(rounded(fill, stroke, 12));
        return result;
    }

    private GradientDrawable rounded(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams margin(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private static final class ApiResponse {
        final int statusCode;
        final JSONObject json;

        ApiResponse(int statusCode, JSONObject json) {
            this.statusCode = statusCode;
            this.json = json;
        }
    }
}
