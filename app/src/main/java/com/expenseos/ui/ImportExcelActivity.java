package com.expenseos.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.expenseos.R;
import com.expenseos.dao.ImportLogDao;
import com.expenseos.excel.ExcelImportService;
import com.expenseos.excel.ImportRow;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ImportExcelActivity extends AppCompatActivity {

    private static final int MAX_PREVIEW_ROWS = 300;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExcelImportService service;
    private ImportLogDao logDao;
    private Button btnUpdate, btnFetch, btnImport;
    private ProgressBar progress;
    private TextView tvStatus, tvSummary;
    private LinearLayout previewList, logList;
    private ExcelImportService.Preview preview;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_import_excel);
        com.expenseos.util.UiUtils.styleStatusBar(getWindow(), this);

        service = new ExcelImportService(this);
        logDao = new ImportLogDao(this);

        btnUpdate = findViewById(R.id.btnUpdateExcel);
        btnFetch = findViewById(R.id.btnFetchExcel);
        btnImport = findViewById(R.id.btnImportValid);
        progress = findViewById(R.id.importProgress);
        tvStatus = findViewById(R.id.tvImportStatus);
        tvSummary = findViewById(R.id.tvImportSummary);
        previewList = findViewById(R.id.previewList);
        logList = findViewById(R.id.logList);

        findViewById(R.id.btnImportBack).setOnClickListener(v -> finish());
        btnUpdate.setOnClickListener(v -> doUpdateExcel());
        btnFetch.setOnClickListener(v -> doFetch());
        btnImport.setOnClickListener(v -> confirmImport());
        com.expenseos.util.ScreenAnim.staggerIn(
                (android.view.ViewGroup) ((android.view.ViewGroup) btnUpdate.getParent()).getParent());

        renderLog();
    }

    // ── Update Excel ──
    private void doUpdateExcel() {
        setBusy(true, "Updating Excel in WorkDrive…");
        exec.execute(() -> {
            try {
                String msg = service.updateExcel();
                mainHandler.post(() -> {
                    setBusy(false, null);
                    showStatus(msg, false);
                    renderLog();
                });
            } catch (Exception e) {
                postError(e);
            }
        });
    }

    // ── Fetch & Preview ──
    private void doFetch() {
        setBusy(true, "Downloading and checking Excel…");
        exec.execute(() -> {
            try {
                ExcelImportService.Preview p = service.fetch();
                mainHandler.post(() -> {
                    setBusy(false, null);
                    preview = p;
                    renderPreview();
                });
            } catch (Exception e) {
                postError(e);
            }
        });
    }

    private void renderPreview() {
        previewList.removeAllViews();
        btnImport.setVisibility(View.GONE);
        if (preview == null || preview.rows.isEmpty()) {
            tvSummary.setText("No new data in the Excel.");
            return;
        }
        tvSummary.setText(preview.rows.size() + " rows · ✓ " + preview.valid + " valid · ✗ " + preview.invalid + " invalid");
        int shown = 0;
        for (ImportRow r : preview.rows) {
            if (shown++ >= MAX_PREVIEW_ROWS) {
                addText(previewList, "… +" + (preview.rows.size() - MAX_PREVIEW_ROWS) + " more rows", R.color.text_muted, 12);
                break;
            }
            String when = ((r.date != null ? r.date.toString() : r.dateRaw) + " "
                    + (r.time != null ? r.time.toString() : r.timeRaw)).trim();
            String amt = r.amount != null ? "₹" + r.amount.toPlainString() : r.amountRaw;
            StringBuilder sb = new StringBuilder();
            sb.append(r.valid ? "✓ " : "✗ ").append("Row ").append(r.sheetRow).append(" · ").append(when)
                    .append(" · ").append(amt).append("\n").append(r.cashbook).append(" · ").append(r.type)
                    .append(" · ").append(r.category);
            if (!r.subCategory.isEmpty()) sb.append(" › ").append(r.subCategory);
            sb.append(" · ").append(r.paymentType);
            if (!r.description.isEmpty()) sb.append("\n").append(r.description);
            if (!r.valid) sb.append("\n⚠ ").append(r.error);
            addText(previewList, sb.toString(), r.valid ? R.color.green : R.color.red, 13);
        }
        if (preview.valid > 0) {
            btnImport.setText("Import " + preview.valid + " valid rows");
            btnImport.setVisibility(View.VISIBLE);
        }
    }

    // ── Import ──
    private void confirmImport() {
        if (preview == null || preview.valid == 0) return;
        new AlertDialog.Builder(this)
                .setTitle("Import " + preview.valid + " rows?")
                .setMessage(preview.invalid > 0
                        ? preview.invalid + " invalid rows will stay in the Excel with Status/Error."
                        : "All rows will be saved and removed from the Excel.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Import", (d, w) -> doImport())
                .show();
    }

    private void doImport() {
        final ExcelImportService.Preview p = preview;
        setBusy(true, "Saving transactions…");
        exec.execute(() -> {
            try {
                ExcelImportService.Result res = service.importValid(p);
                mainHandler.post(() -> {
                    setBusy(false, null);
                    preview = null;
                    previewList.removeAllViews();
                    btnImport.setVisibility(View.GONE);
                    tvSummary.setText("✓ " + res.success + " imported · ✗ " + res.failed + " failed"
                            + (res.failed > 0 ? "\nFailed rows are kept in the Excel with Status/Error." : ""));
                    if (!res.cleanupOk)
                        showStatus("⚠ Rows saved, but Excel cleanup failed (" + res.cleanupError
                                + "). Don't import again — remove the imported rows from the Excel manually.", true);
                    else showStatus("Import finished", false);
                    renderLog();
                });
            } catch (Exception e) {
                postError(e);
            }
        });
    }

    // ── Import log ──
    private void renderLog() {
        logList.removeAllViews();
        java.util.List<ImportLogDao.Entry> list = logDao.findRecent(30);
        if (list.isEmpty()) {
            addText(logList, "No imports yet", R.color.text_muted, 12);
            return;
        }
        for (ImportLogDao.Entry e : list) {
            String line = e.at + " · " + e.action;
            if ("IMPORT".equals(e.action))
                line += " · ✓ " + e.success + "  ✗ " + e.failed + "  (total " + e.total + ") · " + e.status;
            if (e.message != null && !e.message.isEmpty()) line += "\n" + e.message;
            addText(logList, line, e.failed > 0 ? R.color.red : R.color.text_muted, 12);
        }
    }

    // ── UI helpers ──
    private void setBusy(boolean busy, String msg) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        btnUpdate.setEnabled(!busy);
        btnFetch.setEnabled(!busy);
        btnImport.setEnabled(!busy);
        if (busy) showStatus(msg, false);
    }

    private void showStatus(String msg, boolean error) {
        tvStatus.setText(msg == null ? "" : msg);
        tvStatus.setTextColor(getColor(error ? R.color.red : R.color.text_muted));
    }

    private void postError(Exception e) {
        mainHandler.post(() -> {
            setBusy(false, null);
            showStatus("✗ " + (e.getMessage() != null ? e.getMessage() : e.toString()), true);
            renderLog();
        });
    }

    private void addText(LinearLayout parent, String text, int colorRes, int sp) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(getColor(colorRes));
        int pad = (int) (6 * getResources().getDisplayMetrics().density);
        tv.setPadding(0, pad, 0, pad);
        parent.addView(tv);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdown();
    }
}