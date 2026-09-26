package com.expenseos.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.expenseos.R;
import com.expenseos.util.DownloadsSaver;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Generic PDF preview screen for GENERATED reports (not DB attachments —
 * see AttachmentPreviewActivity for that one). Takes the path to an
 * already-written PDF file (report generators write to the cache dir
 * first) and renders it with PDF.js inside a WebView — real pinch zoom
 * (WebView's built-in zoom controls), crisper +/- zoom buttons that
 * re-render the page at a higher resolution, and an actual text layer so
 * the user can select & copy text out of the report, same as any normal
 * PDF viewer app.
 * <p>
 * This replaces the earlier PdfRenderer + Bitmap + RecyclerView approach:
 * that one only ever produces a picture of each page, so nothing on it can
 * be selected or copied — fine for pinch-zooming a scanned page, not
 * enough for a real "PDF viewer" experience on a text report like this one.
 * <p>
 * Requires the pdf.js assets bundled at
 * app/src/main/assets/pdfjs/pdf_viewer.html (+ pdf.min.js, pdf.worker.min.js)
 * shipped alongside this file — everything is loaded from local assets, no
 * network access needed.
 * <p>
 * Intent extras:
 * pdfPath           — absolute path to the source PDF (required)
 * suggestedFileName — file name to use when saving to Downloads (required)
 * title             — navbar title (optional, defaults to "PDF Preview")
 */
public class ReportPdfPreviewActivity extends AppCompatActivity {

    private String pdfPath;
    private String suggestedFileName;
    private String pdfBase64 = "";
    private WebView webView;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_report_pdf_preview);

        pdfPath = getIntent().getStringExtra("pdfPath");
        suggestedFileName = getIntent().getStringExtra("suggestedFileName");
        String title = getIntent().getStringExtra("title");

        if (pdfPath == null || suggestedFileName == null) {
            Toast.makeText(this, "Nothing to preview", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        if (title != null && !title.isBlank())
            ((TextView) findViewById(R.id.tvPdfPreviewTitle)).setText(title);

        findViewById(R.id.btnPdfPreviewBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnPdfPreviewSave).setOnClickListener(v -> saveToDownloads());
        findViewById(R.id.btnPdfPreviewShare).setOnClickListener(v -> shareReport());
        findViewById(R.id.btnZoomIn).setOnClickListener(v -> {
            if (webView != null) webView.evaluateJavascript("zoomIn();", null);
        });
        findViewById(R.id.btnZoomOut).setOnClickListener(v -> {
            if (webView != null) webView.evaluateJavascript("zoomOut();", null);
        });

        setupWebView();
        loadPdfFile();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        webView = findViewById(R.id.wvPdfViewer);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        // pdf.js runs its parsing on a Web Worker (pdf.worker.min.js) that it
        // loads relative to the page's own file:// URL. WebView blocks that
        // by default for local files, so it needs to be explicitly allowed —
        // everything stays inside the app's own bundled assets, nothing
        // reaches out to the network.
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        //noinspection deprecation — still required for file:// worker loads on WebView
        settings.setAllowFileAccessFromFileURLs(true);
        //noinspection deprecation
        settings.setAllowUniversalAccessFromFileURLs(true);

        // Native pinch-to-zoom (two-finger gesture) on top of the JS
        // zoomIn()/zoomOut() buttons, which re-render at a sharper resolution.
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(true);

// wideViewport ON so WebView doesn't clamp local content to its fake
// ~980px "ideal viewport"; overview mode OFF because we compute the exact
// fit-to-width scale ourselves in JS using the real device width below —
// letting WebView ALSO auto-zoom on top of that is what was fighting our
// calculation and cropping/scrolling the page.
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                findViewById(R.id.pdfPreviewPlaceholder).setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                findViewById(R.id.zoomControls).setVisibility(View.VISIBLE);
            }
        });
    }

    private void loadPdfFile() {
        exec.execute(() -> {
            try {
                File file = new File(pdfPath);
                if (!file.exists() || file.length() == 0) throw new Exception("File empty");

                try (FileInputStream fis = new FileInputStream(file);
                     ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
                    pdfBase64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP);
                }

                mainHandler.post(() -> webView.loadUrl("file:///android_asset/pdfjs/pdf_viewer.html"));
            } catch (Exception e) {
                mainHandler.post(() -> {
                    Toast.makeText(this, "Failed to load PDF: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        });
    }

    /**
     * Bridge exposed to pdf_viewer.html as `AndroidBridge`.
     */
    public class AndroidBridge {
        @JavascriptInterface
        public String getPdfBase64() {
            return pdfBase64;
        }

        @JavascriptInterface
        public void onPageChanged(int current, int total) {
            mainHandler.post(() -> {
                TextView pageIndicator = findViewById(R.id.tvPdfPreviewPageIndicator);
                pageIndicator.setVisibility(total > 1 ? View.VISIBLE : View.GONE);
                pageIndicator.setText("Page " + current + " / " + total);
            });
        }

        @JavascriptInterface
        public void onError(String error) {
            mainHandler.post(() ->
                    Toast.makeText(ReportPdfPreviewActivity.this, "PDF Error: " + error, Toast.LENGTH_SHORT).show()
            );
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) {
            webView.destroy();
        }
        exec.shutdown();
    }

    private void saveToDownloads() {
        exec.execute(() -> {
            try {
                DownloadsSaver.Result r = DownloadsSaver.save(this, suggestedFileName, "application/pdf",
                        out -> {
                            try (FileInputStream fis = new FileInputStream(pdfPath)) {
                                byte[] buf = new byte[8192];
                                int n;
                                while ((n = fis.read(buf)) > 0) out.write(buf, 0, n);
                            }
                        });
                mainHandler.post(() -> Toast.makeText(this, "Saved to " + r.displayLocation, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void shareReport() {
        try {
            File shareDir = new File(getCacheDir(), "report_shares");
            if (!shareDir.exists()) shareDir.mkdirs();
            File shareFile = new File(shareDir, suggestedFileName);
            try (FileInputStream fis = new FileInputStream(pdfPath); FileOutputStream fos = new FileOutputStream(shareFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) > 0) fos.write(buf, 0, n);
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", shareFile);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/pdf");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Share report"));
        } catch (Exception e) {
            Toast.makeText(this, "Share failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @JavascriptInterface
    public float getViewportWidthDp() {
        // Real, unambiguous device width — sidesteps WebView's viewport
        // heuristics entirely instead of trusting document.clientWidth.
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        return dm.widthPixels / dm.density;
    }


}