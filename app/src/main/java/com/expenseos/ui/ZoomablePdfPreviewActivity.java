package com.expenseos.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.util.DownloadsSaver;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reusable, in-app zoomable PDF preview.
 * <p>
 * One activity for every report path (MonthlyCategoryReportActivity,
 * GenerateReportActivity, FoodTrackerActivity, StatsActivity, AttachmentPreview).
 * Pinch-to-zoom (ScaleGestureDetector), double-tap zoom, plus a floating +/−
 * pill (the same visual as the rest of ExpenseOS).
 * <p>
 * Inputs (Intent extras):
 * pdfPath              (String, required)  — absolute path to PDF on disk
 * suggestedFileName    (String, optional)  — used for the Save action
 * title                (String, optional)  — toolbar title (default "PDF Preview")
 * sourceTag            (String, optional)  — e.g. "monthly-category"
 */
public class ZoomablePdfPreviewActivity extends AppCompatActivity {

    private File pdfFile;
    private RecyclerView rvPages;
    private TextView tvPageIndicator;
    private ProgressBar progressBar;
    private TextView tvLoadingText;
    private PageAdapter adapter;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_zoomable_pdf_preview);

        String path = getIntent().getStringExtra("pdfPath");
        String title = getIntent().getStringExtra("title");
        if (title == null || title.isBlank()) title = "PDF Preview";

        if (path == null) {
            Toast.makeText(this, "No PDF to preview", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        pdfFile = new File(path);
        if (!pdfFile.exists()) {
            Toast.makeText(this, "PDF not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        findViewById(R.id.btnZpBack).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.tvZpTitle)).setText(title);
        tvPageIndicator = findViewById(R.id.tvZpPageIndicator);

        rvPages = findViewById(R.id.rvZpPages);
        rvPages.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PageAdapter();
        rvPages.setAdapter(adapter);

        progressBar = findViewById(R.id.pbZpLoading);
        tvLoadingText = findViewById(R.id.tvZpLoading);

        // Floating zoom controls
        ImageButton btnIn = findViewById(R.id.btnZoomIn);
        ImageButton btnOut = findViewById(R.id.btnZoomOut);
        btnIn.setOnClickListener(v -> applyZoomToVisible(+0.5f));
        btnOut.setOnClickListener(v -> applyZoomToVisible(-0.5f));

        // Save / share actions
        findViewById(R.id.btnZpSave).setOnClickListener(v -> savePdfToDownloads());
        findViewById(R.id.btnZpShare).setOnClickListener(v -> sharePdf());

        String tag = getIntent().getStringExtra("sourceTag");
        if (tag != null)
            ConsoleLogger.get().info("📄 Zoomable PDF preview opened: source=" + tag + " file=" + path);

        progressBar.setVisibility(View.VISIBLE);
        tvLoadingText.setVisibility(View.VISIBLE);
        rvPages.setVisibility(View.GONE);
        tvPageIndicator.setVisibility(View.GONE);

        exec.execute(this::renderAllPages);
    }

    private void renderAllPages() {
        try {
            Uri uri = Uri.fromFile(pdfFile);
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) {
                main.post(() -> {
                    Toast.makeText(this, "Could not open PDF", Toast.LENGTH_SHORT).show();
                    finish();
                });
                return;
            }
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int targetWidth = (int) (dm.widthPixels * (dm.density / 2f));

            try (PdfRenderer renderer = new PdfRenderer(pfd)) {
                int count = renderer.getPageCount();
                final Bitmap[] bits = new Bitmap[count];
                for (int i = 0; i < count; i++) {
                    try (PdfRenderer.Page page = renderer.openPage(i)) {
                        int w = targetWidth > 0 ? targetWidth : page.getWidth() * 2;
                        int h = (int) (page.getHeight() * ((double) w / page.getWidth()));
                        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        bmp.eraseColor(Color.WHITE);
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        bits[i] = bmp;
                    }
                    final int done = i + 1;
                    main.post(() -> tvLoadingText.setText("Rendering PDF… " + done + " / " + count));
                }
                main.post(() -> {
                    adapter.setBitmaps(bits);
                    progressBar.setVisibility(View.GONE);
                    tvLoadingText.setVisibility(View.GONE);
                    rvPages.setVisibility(View.VISIBLE);
                    if (count > 1) {
                        tvPageIndicator.setVisibility(View.VISIBLE);
                        updateIndicator(0);
                    }
                });
            }
        } catch (Exception e) {
            main.post(() -> {
                Toast.makeText(this, "PDF render failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                finish();
            });
        }
    }

    private void updateIndicator(int pos) {
        if (adapter == null || adapter.getItemCount() <= 1) return;
        tvPageIndicator.setText("Page " + (pos + 1) + " / " + adapter.getItemCount());
    }

    /**
     * Apply a +/- to the currently visible page's zoom level.
     */
    private void applyZoomToVisible(float delta) {
        LinearLayoutManager lm = (LinearLayoutManager) rvPages.getLayoutManager();
        if (lm == null) return;
        int first = lm.findFirstVisibleItemPosition();
        if (first < 0 || first >= adapter.getItemCount()) return;
        RecyclerView.ViewHolder vh = rvPages.findViewHolderForLayoutPosition(first);
        if (vh != null && vh.itemView instanceof ZoomablePdfView) {
            ((ZoomablePdfView) vh.itemView).bump(delta);
        }
    }

    private void savePdfToDownloads() {
        String nameInput = getIntent().getStringExtra("suggestedFileName");
        if (nameInput == null || nameInput.isBlank()) nameInput = "report.pdf";

        // ✅ Lambda-க்குள்ள use பண்ண variable-ஐ 'final' ஆக்கியாச்சு
        final String fileName = nameInput;

        exec.execute(() -> {
            try {
                DownloadsSaver.Result result = DownloadsSaver.save(this, fileName, "application/pdf", out -> {
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(pdfFile)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = fis.read(buf)) > 0) out.write(buf, 0, n);
                    }
                });
                main.post(() -> Toast.makeText(this,
                        "Saved to " + result.displayLocation, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, "Save failed: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void sharePdf() {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pdfFile);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/pdf");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Share PDF"));
        } catch (Exception e) {
            Toast.makeText(this, "Share failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // Tiny logger shim so we don't pull in util-only deps.
    private static final class ConsoleLogger {
        private static ConsoleLogger I;

        static ConsoleLogger get() {
            if (I == null) I = new ConsoleLogger();
            return I;
        }

        void info(String s) {
            android.util.Log.i("ZoomPdf", s);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdownNow();
    }

    // ── Adapter + zoomable page view ──
    class PageAdapter extends RecyclerView.Adapter<PageAdapter.VH> {
        private Bitmap[] pages = new Bitmap[0];

        void setBitmaps(Bitmap[] b) {
            this.pages = b == null ? new Bitmap[0] : b;
            notifyDataSetChanged();
        }

        @Override
        public VH onCreateViewHolder(ViewGroup p, int v) {
            ZoomablePdfView z = new ZoomablePdfView(ZoomablePdfPreviewActivity.this);
            z.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return new VH(z);
        }

        @Override
        public void onBindViewHolder(VH h, int i) {
            h.z.setBitmap(pages[i]);
        }

        @Override
        public int getItemCount() {
            return pages.length;
        }

        class VH extends RecyclerView.ViewHolder {
            final ZoomablePdfView z;

            VH(View v) {
                super(v);
                z = (ZoomablePdfView) v;
            }
        }
    }

    /**
     * One page = image + pinch + double-tap + pan. Same matrix-based zoom
     * logic as ZoomableImageView but with a smoother InteractiveViewer-style
     * inertia at the centre.
     */
    public static class ZoomablePdfView extends FrameLayout {
        private final ImageView iv;
        private Bitmap bmp;
        private float scale = 1f, baseScale = 1f;
        private static final float MIN = 1f, MAX = 6f, STEP = 0.5f;
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector tapDetector;
        private float lastX, lastY;
        private boolean dragging;

        public ZoomablePdfView(android.content.Context ctx) {
            super(ctx);
            iv = new ImageView(ctx);
            iv.setScaleType(ImageView.ScaleType.MATRIX);
            addView(iv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            scaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    float n = scale * d.getScaleFactor();
                    n = Math.max(MIN, Math.min(MAX, n));
                    applyMatrix(n, d.getFocusX(), d.getFocusY());
                    return true;
                }
            });
            tapDetector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (scale > 1.05f) fit();
                    else applyMatrix(2.5f, e.getX(), e.getY());
                    return true;
                }

                @Override
                public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                    if (scale > 1.01f) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                        iv.getImageMatrix().postTranslate(-dx, -dy);
                        iv.setImageMatrix(iv.getImageMatrix());
                        clamp();
                    }
                    return true;
                }
            });

            setOnTouchListener((v, ev) -> {
                scaleDetector.onTouchEvent(ev);
                tapDetector.onTouchEvent(ev);
                if (ev.getPointerCount() == 1) {
                    switch (ev.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            lastX = ev.getX();
                            lastY = ev.getY();
                            dragging = false;
                            break;
                        case MotionEvent.ACTION_MOVE:
                            float dx = ev.getX() - lastX, dy = ev.getY() - lastY;
                            if (Math.abs(dx) > 6 || Math.abs(dy) > 6) dragging = true;
                            if (dragging && scale > 1.01f) {
                                getParent().requestDisallowInterceptTouchEvent(true);
                                iv.getImageMatrix().postTranslate(dx, dy);
                                iv.setImageMatrix(iv.getImageMatrix());
                                clamp();
                            }
                            lastX = ev.getX();
                            lastY = ev.getY();
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            if (scale <= 1.01f && getParent() != null)
                                getParent().requestDisallowInterceptTouchEvent(false);
                            break;
                    }
                }
                return true;
            });
        }

        public void setBitmap(Bitmap b) {
            this.bmp = b;
            iv.setImageBitmap(b);
            post(this::fit);
        }

        public void bump(float delta) {
            float n = Math.max(MIN, Math.min(MAX, scale + delta));
            float cx = iv.getWidth() / 2f;
            float cy = iv.getHeight() / 2f;
            applyMatrix(n, cx, cy);
        }

        private void fit() {
            if (bmp == null || iv.getWidth() == 0 || bmp.getWidth() == 0) return;
            baseScale = (float) iv.getWidth() / bmp.getWidth();
            iv.setImageBitmap(null);
            iv.setImageBitmap(bmp);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(baseScale, baseScale);
            iv.setImageMatrix(m);
            scale = 1f;
            requestLayout();
        }

        private void applyMatrix(float s, float focusX, float focusY) {
            if (bmp == null) return;
            float factor = s / scale;
            android.graphics.Matrix m = new android.graphics.Matrix(iv.getImageMatrix());
            m.postScale(factor, factor, focusX, focusY);
            iv.setImageMatrix(m);
            scale = s;
            clamp();
            requestLayout();
        }

        private void clamp() {
            android.graphics.Matrix m = iv.getImageMatrix();
            float[] v = new float[9];
            m.getValues(v);
            float tx = v[2], ty = v[5];
            float viewW = iv.getWidth() * scale;
            float viewH = iv.getHeight() * scale;
            if (viewW <= iv.getWidth()) tx = (iv.getWidth() - viewW) / 2f;
            else if (tx > 0) tx = 0;
            else if (tx < iv.getWidth() - viewW) tx = iv.getWidth() - viewW;
            if (viewH <= iv.getHeight()) ty = (iv.getHeight() - viewH) / 2f;
            else if (ty > 0) ty = 0;
            else if (ty < iv.getHeight() - viewH) ty = iv.getHeight() - viewH;
            v[2] = tx;
            v[5] = ty;
            m.setValues(v);
            iv.setImageMatrix(m);
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, h);
            if (bmp != null && iv.getWidth() > 0) {
                int imgH = (int) (bmp.getHeight() * ((float) iv.getWidth() / bmp.getWidth()));
                setMeasuredDimension(iv.getWidth(), imgH);
            }
        }
    }
}