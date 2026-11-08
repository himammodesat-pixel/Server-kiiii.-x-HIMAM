package com.himam.panel;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.browser.customtabs.CustomTabsIntent;

import java.io.OutputStream;
import java.net.URI;
import java.util.regex.Pattern;

public final class PanelActivity extends Activity {
    private static final int REQUEST_FILE_CHOOSER = 1;
    private static final int REQUEST_SAVE_DOWNLOAD = 2;
    private static final int MAX_DOWNLOAD_BYTES = 10_000_000;
    private static final Pattern MIME_TYPE = Pattern.compile("^[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+$");

    private WebView webView;
    private ProgressBar progress;
    private ValueCallback<Uri[]> fileChooserCallback;
    private byte[] pendingDownload;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(16, 24, 39));
        getWindow().setNavigationBarColor(Color.rgb(11, 17, 32));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(11, 17, 32));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), dp(6), dp(8), dp(6));
        toolbar.setBackgroundColor(Color.rgb(16, 24, 39));
        Button backButton = toolbarButton("‹  Aplikasi");
        toolbar.addView(backButton);
        backButton.setOnClickListener(view -> finish());
        TextView title = new TextView(this);
        title.setText("HIMAM SMP · Panel");
        title.setTextColor(Color.rgb(234, 240, 250));
        title.setTextSize(14);
        title.setGravity(Gravity.CENTER);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1f));
        Button reloadButton = toolbarButton("↻");
        toolbar.addView(reloadButton);
        reloadButton.setContentDescription("Muat ulang dashboard");
        reloadButton.setOnClickListener(view -> webView.reload());
        root.addView(toolbar);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(34, 197, 94)));
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3)));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(11, 17, 32));
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        webView.addJavascriptInterface(new PanelDownloadBridge(), "AndroidPanel");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams params
            ) {
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = callback;
                try {
                    startActivityForResult(
                            Intent.createChooser(params.createIntent(), "Pilih file untuk diunggah"),
                            REQUEST_FILE_CHOOSER
                    );
                    return true;
                } catch (ActivityNotFoundException error) {
                    fileChooserCallback = null;
                    callback.onReceiveValue(null);
                    Toast.makeText(PanelActivity.this, "Tidak ada aplikasi pemilih file.", Toast.LENGTH_LONG).show();
                    return false;
                }
            }
        });
        root.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(BuildConfig.PUBLIC_PANEL_URL);
        }
    }

    private Button toolbarButton(String label) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(12);
        button.setTextColor(Color.rgb(234, 240, 250));
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(32, 43, 64)));
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean handleNavigation(Uri uri) {
        if (isPanelUri(uri)) {
            return false;
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            try {
                new CustomTabsIntent.Builder()
                        .setShowTitle(true)
                        .setToolbarColor(Color.rgb(16, 24, 39))
                        .build()
                        .launchUrl(this, uri);
            } catch (ActivityNotFoundException error) {
                Toast.makeText(this, "Tidak ada browser yang bisa membuka tautan.", Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, "Tautan di luar panel diblokir.", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private boolean isPanelUri(Uri uri) {
        try {
            URI panel = URI.create(BuildConfig.PUBLIC_PANEL_URL);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && panel.getHost() != null
                    && panel.getHost().equalsIgnoreCase(uri.getHost())
                    && effectivePort(panel) == effectivePort(uri);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private int effectivePort(URI uri) {
        return uri.getPort() < 0 ? 443 : uri.getPort();
    }

    private int effectivePort(Uri uri) {
        return uri.getPort() < 0 ? 443 : uri.getPort();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FILE_CHOOSER) {
            if (fileChooserCallback != null) {
                Uri[] result = resultCode == RESULT_OK && data != null
                        ? WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                        : null;
                fileChooserCallback.onReceiveValue(result);
                fileChooserCallback = null;
            }
        } else if (requestCode == REQUEST_SAVE_DOWNLOAD) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingDownload != null) {
                try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
                    if (output == null) {
                        throw new IllegalStateException("Lokasi unduhan tidak bisa dibuka.");
                    }
                    output.write(pendingDownload);
                    Toast.makeText(this, "File tersimpan.", Toast.LENGTH_LONG).show();
                } catch (Exception error) {
                    Toast.makeText(this, "Gagal menyimpan file: " + error.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
            pendingDownload = null;
        }
    }

    private void beginSaveDownload(String filename, String mimeType, String dataUrl) {
        if (pendingDownload != null) {
            Toast.makeText(this, "Selesaikan penyimpanan file sebelumnya terlebih dahulu.", Toast.LENGTH_LONG).show();
            return;
        }
        if (filename == null || filename.isEmpty() || filename.length() > 200
                || filename.equals(".") || filename.equals("..")
                || filename.contains("/") || filename.contains("\\")
                || dataUrl == null || !dataUrl.startsWith("data:") || !dataUrl.contains(";base64,")) {
            Toast.makeText(this, "Nama atau isi file unduhan tidak valid.", Toast.LENGTH_LONG).show();
            return;
        }
        for (int index = 0; index < filename.length(); index++) {
            if (Character.isISOControl(filename.charAt(index))) {
                Toast.makeText(this, "Nama file unduhan tidak valid.", Toast.LENGTH_LONG).show();
                return;
            }
        }
        int separator = dataUrl.indexOf(";base64,");
        String encoded = dataUrl.substring(separator + ";base64,".length());
        if (encoded.length() > ((MAX_DOWNLOAD_BYTES + 2) / 3) * 4 + 8) {
            Toast.makeText(this, "File melebihi batas unduhan 10 MB.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            byte[] content = Base64.decode(encoded, Base64.DEFAULT);
            if (content.length > MAX_DOWNLOAD_BYTES) {
                Toast.makeText(this, "File melebihi batas unduhan 10 MB.", Toast.LENGTH_LONG).show();
                return;
            }
            pendingDownload = content;
            String safeMimeType = MIME_TYPE.matcher(mimeType == null ? "" : mimeType).matches()
                    ? mimeType
                    : "application/octet-stream";
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(safeMimeType);
            intent.putExtra(Intent.EXTRA_TITLE, filename);
            startActivityForResult(intent, REQUEST_SAVE_DOWNLOAD);
        } catch (IllegalArgumentException error) {
            pendingDownload = null;
            Toast.makeText(this, "Data unduhan tidak valid.", Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException error) {
            pendingDownload = null;
            Toast.makeText(this, "Tidak ada aplikasi untuk menyimpan file.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) {
            webView.saveState(outState);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidPanel");
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private final class PanelDownloadBridge {
        @JavascriptInterface
        public void saveDownload(String filename, String mimeType, String dataUrl) {
            runOnUiThread(() -> beginSaveDownload(filename, mimeType, dataUrl));
        }
    }
}
