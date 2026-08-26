package com.expenseos.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.util.DownloadsSaver;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Generic PDF preview screen for GENERATED reports (not DB attachments —
 * see AttachmentPreviewActivity for that one). Takes the path to an
 * already-written PDF file (report generators write to the cache dir
 * first) and renders every page via Android's built-in PdfRenderer, same
 * approach as AttachmentPreviewActivity.renderAllPdfPages(). "Save to
 * Downloads" copies the same bytes via DownloadsSaver; "Share" hands it
 * off via FileProvider.
 * <p>
 * Intent extras:
 * pdfPath           — absolute path to the source PDF (required)
 * suggestedFileName — file name to use when saving to Downloads (required)
 * title             — navbar title (optional, defaults to "PDF Preview")
 */
public class ReportPdfPreviewActivity extends AppCompatActivity {

    private String pdfPath;
    private String suggestedFileName;
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

        renderAllPages();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdown();
    }

    private void renderAllPages() {
        View placeholder = findViewById(R.id.pdfPreviewPlaceholder);
        RecyclerView rv = findViewById(R.id.rvPdfPreviewPages);
        TextView pageIndicator = findViewById(R.id.tvPdfPreviewPageIndicator);

        new Thread(() -> {
            List<Bitmap> pages = new ArrayList<>();
            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(new File(pdfPath), ParcelFileDescriptor.MODE_READ_ONLY);
                 PdfRenderer renderer = new PdfRenderer(pfd)) {

                int count = renderer.getPageCount();
                if (count <= 0) throw new IllegalStateException("Empty PDF");

                for (int i = 0; i < count; i++) {
                    try (PdfRenderer.Page page = renderer.openPage(i)) {
                        int width = page.getWidth() * 2;
                        int height = page.getHeight() * 2;
                        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        bitmap.eraseColor(Color.WHITE);
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        pages.add(bitmap);
                    }
                }

                int totalPages = pages.size();
                runOnUiThread(() -> {
                    rv.setLayoutManager(new LinearLayoutManager(this));
                    rv.setAdapter(new PdfPageAdapter(pages));
                    rv.setVisibility(View.VISIBLE);
                    placeholder.setVisibility(View.GONE);

                    pageIndicator.setVisibility(totalPages > 1 ? View.VISIBLE : View.GONE);
                    pageIndicator.setText("Page 1 / " + totalPages);
                    rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
                        @Override
                        public void onScrolled(@NonNull RecyclerView r, int dx, int dy) {
                            LinearLayoutManager lm = (LinearLayoutManager) r.getLayoutManager();
                            if (lm == null) return;
                            int pos = lm.findFirstVisibleItemPosition();
                            if (pos >= 0)
                                pageIndicator.setText("Page " + (pos + 1) + " / " + totalPages);
                        }
                    });
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Couldn't render PDF preview", Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        }).start();
    }

    private static class PdfPageAdapter extends RecyclerView.Adapter<PdfPageAdapter.VH> {
        private final List<Bitmap> pages;

        PdfPageAdapter(List<Bitmap> pages) {
            this.pages = pages;
        }

        static class VH extends RecyclerView.ViewHolder {
            ImageView iv;

            VH(ImageView v) {
                super(v);
                iv = v;
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ImageView iv = new ImageView(parent.getContext());
            iv.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int pad = (int) (4 * parent.getResources().getDisplayMetrics().density);
            iv.setPadding(0, pad, 0, pad);
            return new VH(iv);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            h.iv.setImageBitmap(pages.get(pos));
        }

        @Override
        public int getItemCount() {
            return pages.size();
        }
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
}