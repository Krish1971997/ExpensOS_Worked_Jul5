package com.expenseos.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.expenseos.R;
import com.expenseos.dao.ReceiptDao;
import com.expenseos.model.Receipt;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows one receipt (transaction_receipts row). Images render inline.
 * PDFs render every page (via Android's built-in PdfRenderer) into a
 * scrollable list with a "Page X / Y" indicator, matching the old app's
 * preview behaviour. "Open / Download" still hands the file off to
 * whatever PDF app the user has for anything the built-in renderer can't
 * do (annotations, forms, etc).
 */
public class AttachmentPreviewActivity extends AppCompatActivity {

    private Receipt receipt;
    private boolean isImage;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_attachment_preview);

        int receiptId = getIntent().getIntExtra("receiptId", -1);
        findViewById(R.id.btnPreviewBack).setOnClickListener(v -> finish());

        if (receiptId <= 0) {
            finish();
            return;
        }

        receipt = new ReceiptDao(this).findById(receiptId);
        if (receipt == null || receipt.getFileData() == null) {
            Toast.makeText(this, "Attachment not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        isImage = receipt.getFileType() != null && receipt.getFileType().startsWith("image/");

        ImageView iv = findViewById(R.id.ivPreview);
        View pdfPlaceholder = findViewById(R.id.pdfPlaceholder);
        RecyclerView rvPdfPages = findViewById(R.id.rvPdfPages);
        TextView tvPageIndicator = findViewById(R.id.tvPageIndicator);

        if (isImage) {
            Bitmap bmp = BitmapFactory.decodeByteArray(receipt.getFileData(), 0, receipt.getFileData().length);
            iv.setImageBitmap(bmp);
            iv.setVisibility(View.VISIBLE);
            pdfPlaceholder.setVisibility(View.GONE);
        } else {
            // Show the placeholder immediately, then swap in the real
            // rendered pages (all of them, scrollable) once PdfRenderer
            // finishes (can take a moment for larger PDFs, so it runs off
            // the main thread).
            ((TextView) findViewById(R.id.tvPdfName)).setText(receipt.getFileName());
            pdfPlaceholder.setVisibility(View.VISIBLE);
            iv.setVisibility(View.GONE);
            renderAllPdfPages(pdfPlaceholder, rvPdfPages, tvPageIndicator);
        }

        findViewById(R.id.btnDownloadAttachment).setOnClickListener(v -> openExternally());
        findViewById(R.id.btnShareAttachment).setOnClickListener(v -> shareAttachment());
    }

    // Renders EVERY page of the PDF to a bitmap using Android's built-in
    // PdfRenderer (API 21+, no external library needed) and shows them all
    // in a scrollable RecyclerView (matching the old app's page-by-page
    // scroll view), with a "Page X / Y" indicator that tracks scroll
    // position. Falls back to the icon placeholder if rendering fails for
    // any reason (corrupt file, password-protected PDF, etc.).
    //
    // NOTE: all pages are rendered up front and held in memory as bitmaps.
    // Fine for typical receipts/documents; a very large page-count PDF
    // (50+ pages) could use noticeable memory — not a concern for this
    // screen's normal use case, but worth knowing if that ever changes.
    private void renderAllPdfPages(View pdfPlaceholder, RecyclerView rvPdfPages, TextView tvPageIndicator) {
        new Thread(() -> {
            File tempFile = null;
            List<Bitmap> pages = new ArrayList<>();
            try {
                tempFile = File.createTempFile("preview_", ".pdf", getCacheDir());
                try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                    fos.write(receipt.getFileData());
                }

                try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY);
                     PdfRenderer renderer = new PdfRenderer(pfd)) {

                    int count = renderer.getPageCount();
                    if (count <= 0) throw new IllegalStateException("Empty PDF");

                    for (int i = 0; i < count; i++) {
                        try (PdfRenderer.Page page = renderer.openPage(i)) {
                            // 2x scale for a sharper render on high-density screens
                            int width = page.getWidth() * 2;
                            int height = page.getHeight() * 2;
                            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                            bitmap.eraseColor(Color.WHITE); // PDF pages render transparent otherwise
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            pages.add(bitmap);
                        }
                    }
                }

                int totalPages = pages.size();
                runOnUiThread(() -> {
                    rvPdfPages.setLayoutManager(new LinearLayoutManager(this));
                    rvPdfPages.setAdapter(new PdfPageAdapter(pages));
                    rvPdfPages.setVisibility(View.VISIBLE);
                    pdfPlaceholder.setVisibility(View.GONE);

                    tvPageIndicator.setVisibility(totalPages > 1 ? View.VISIBLE : View.GONE);
                    tvPageIndicator.setText("Page 1 / " + totalPages);
                    rvPdfPages.addOnScrollListener(new RecyclerView.OnScrollListener() {
                        @Override
                        public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                            LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                            if (lm == null) return;
                            int pos = lm.findFirstVisibleItemPosition();
                            if (pos >= 0)
                                tvPageIndicator.setText("Page " + (pos + 1) + " / " + totalPages);
                        }
                    });
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Couldn't render PDF preview — use Open/Download instead", Toast.LENGTH_SHORT).show());
            } finally {
                if (tempFile != null) tempFile.delete();
            }
        }).start();
    }

    // One full-width page per row; RecyclerView recycles the ImageViews as
    // you scroll, so this stays lightweight even with many pages.
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

    // Writes the blob to a cache file and hands it off via FileProvider —
    // required because we only have raw bytes in the DB, not a real file.
    private Uri writeToCacheAndGetUri() {
        try {
            File dir = new File(getCacheDir(), "receipts");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, receipt.getFileName());
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(receipt.getFileData());
            }
            return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", out);
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't prepare file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return null;
        }
    }

    private void openExternally() {
        Uri uri = writeToCacheAndGetUri();
        if (uri == null) return;
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, receipt.getFileType() != null ? receipt.getFileType() : "*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "No app found to open this file", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareAttachment() {
        Uri uri = writeToCacheAndGetUri();
        if (uri == null) return;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(receipt.getFileType() != null ? receipt.getFileType() : "*/*");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "Share attachment"));
    }
}