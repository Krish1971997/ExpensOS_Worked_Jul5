package com.expenseos.ui;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.dao.CashBookDao;
import com.expenseos.util.CategoryComparisonReport;
import com.expenseos.util.DownloadsSaver;
import com.expenseos.util.GmailSender;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MonthlyCategoryReportActivity extends AppCompatActivity {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("MMM yyyy");

    private int bookId;
    private String cashbookName;
    private CategoryComparisonReport.Result currentResult;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private CheckBox cbIncludeCurrentMonth;
    private Spinner spMonthsCount;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_monthly_category_report);

        bookId = getIntent().getIntExtra("bookId", 0);
        if (bookId <= 0) {
            Toast.makeText(this, "No active cashbook found.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        com.expenseos.model.CashBook activeBook = new CashBookDao(this).findById(bookId);
        cashbookName = activeBook != null ? activeBook.getName() : "";

        findViewById(R.id.btnMcrBack).setOnClickListener(v -> finish());

        cbIncludeCurrentMonth = findViewById(R.id.cbIncludeCurrentMonth);
        spMonthsCount = findViewById(R.id.spMonthsCount);

        List<String> monthOptions = new ArrayList<>();
        for (int i = 2; i <= 12; i++) monthOptions.add(i + " months");
        ArrayAdapter<String> adp = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, monthOptions);
        adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spMonthsCount.setAdapter(adp);
        spMonthsCount.setSelection(1); // default "3 months"

        findViewById(R.id.btnGenerateReport).setOnClickListener(v -> generateReport());
        findViewById(R.id.btnExportEmail).setOnClickListener(v -> emailReport());
        findViewById(R.id.btnExportPdf).setOnClickListener(v -> exportPdf());
        findViewById(R.id.btnExportExcel).setOnClickListener(v -> exportExcel());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdown();
    }

    private void generateReport() {
        int monthsCount = spMonthsCount.getSelectedItemPosition() + 2; // position 0 = "2 months"
        boolean includeCurrent = cbIncludeCurrentMonth.isChecked();

        currentResult = CategoryComparisonReport.build(this, bookId, monthsCount, includeCurrent);

        TextView tvRange = findViewById(R.id.tvMcrRange);
        LinearLayout header = findViewById(R.id.rowMcrHeader);
        View exportBar = findViewById(R.id.exportBar);

        TextView emptyView = findViewById(R.id.tvMcrEmpty);
        RecyclerView rv = findViewById(R.id.rvComparison);

        List<YearMonth> months = currentResult.months;
        tvRange.setText(MONTH_FMT.format(months.get(0)) + " – " + MONTH_FMT.format(months.get(months.size() - 1)));
        tvRange.setVisibility(View.VISIBLE);

        if (currentResult.rows.isEmpty()) {
            header.setVisibility(View.GONE);
            exportBar.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
            emptyView.setText("No expense data in this range.");
            return;
        }

        buildHeaderRow(header, months);
        header.setVisibility(View.VISIBLE);
        exportBar.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);

        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new ComparisonAdapter(currentResult.rows, months));
    }

    // ── Dynamic header — Category + one cell per selected month + % Chg ──
    // Fixed dp widths (not weights) — header + rvComparison ippo
    // HorizontalScrollView-ku ulla irukku (see activity_monthly_category_report.xml),
    // adhukku weight measure aagadhu, fixed width thaan correct-ah render aagum.
    static final int COL_CATEGORY_DP = 130;
    static final int COL_MONTH_DP = 100;
    static final int COL_PCT_DP = 74;

    private void buildHeaderRow(LinearLayout header, List<YearMonth> months) {
        header.removeAllViews();
        header.addView(headerCell("Category", COL_CATEGORY_DP, Gravity.START));
        for (YearMonth ym : months)
            header.addView(headerCell(MONTH_FMT.format(ym), COL_MONTH_DP, Gravity.END));
        header.addView(headerCell("% Chg", COL_PCT_DP, Gravity.END));
    }

    private TextView headerCell(String text, int widthDp, int gravity) {
        TextView tv = new TextView(this);
        tv.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT));
        tv.setText(text);
        tv.setGravity(gravity);
        tv.setPadding(dp(4), 0, dp(4), 0);
        tv.setTextColor(getResources().getColor(R.color.text_muted, null));
        tv.setTextSize(11);
        tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        return tv;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    class ComparisonAdapter extends RecyclerView.Adapter<ComparisonAdapter.VH> {
        private final List<CategoryComparisonReport.RowData> rows;
        private final List<YearMonth> months;

        ComparisonAdapter(List<CategoryComparisonReport.RowData> rows, List<YearMonth> months) {
            this.rows = rows;
            this.months = months;
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvCategory, tvPct;
            TextView[] tvMonths;

            VH(LinearLayout root) {
                super(root);
                tvCategory = (TextView) root.getChildAt(0);
                tvMonths = new TextView[months.size()];
                for (int i = 0; i < months.size(); i++)
                    tvMonths[i] = (TextView) root.getChildAt(i + 1);
                tvPct = (TextView) root.getChildAt(root.getChildCount() - 1);
            }
        }

        @Override
        public VH onCreateViewHolder(ViewGroup p, int t) {
            LinearLayout root = (LinearLayout) android.view.LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_category_comparison_row, p, false);

            root.addView(rowCell(p.getContext(), COL_CATEGORY_DP, Gravity.START, false));
            for (int i = 0; i < months.size(); i++)
                root.addView(rowCell(p.getContext(), COL_MONTH_DP, Gravity.END, false));
            root.addView(rowCell(p.getContext(), COL_PCT_DP, Gravity.END, true));

            return new VH(root);
        }

        private TextView rowCell(android.content.Context ctx, int widthDp, int gravity, boolean isPct) {
            TextView tv = new TextView(ctx);
            float density = ctx.getResources().getDisplayMetrics().density;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (widthDp * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            tv.setGravity(gravity);
            if (isPct) {
                lp.setMarginStart((int) (4 * density));
                tv.setLayoutParams(lp);
                tv.setTextColor(getResources().getColor(android.R.color.white, null));
                tv.setTextSize(11);
                tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
                int padH = (int) (6 * density), padV = (int) (3 * density);
                tv.setPadding(padH, padV, padH, padV);
            } else {
                tv.setLayoutParams(lp);
                tv.setTextColor(getResources().getColor(R.color.text, null));
                tv.setTextSize(12);
                tv.setPadding((int) (4 * density), 0, (int) (4 * density), 0);
            }
            return tv;
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            CategoryComparisonReport.RowData row = rows.get(pos);
            h.tvCategory.setText(row.category);
            for (int i = 0; i < row.monthlyAmounts.size(); i++)
                h.tvMonths[i].setText("₹" + row.monthlyAmounts.get(i).toPlainString());
            h.tvPct.setText(String.format(Locale.US, "%+.1f%%", row.pctChange));
            h.tvPct.setBackgroundColor(getColor(colorFor(row.trend)));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private int colorFor(CategoryComparisonReport.Trend trend) {
        return switch (trend) {
            case RED -> R.color.red;
            case YELLOW -> R.color.amber;
            case GREEN -> R.color.green;
            case NEUTRAL -> R.color.text_muted;
        };
    }

    // ── Exports ──────────────────────────────────────────────
    private void exportPdf() {
        if (currentResult == null) return;

        EditText etTitle = new EditText(this);
        etTitle.setHint("Report title (optional)");
        etTitle.setText("Monthly Category Report");
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        etTitle.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(this)
                .setTitle("PDF title")
                .setView(etTitle)
                .setPositiveButton("Generate", (d, w) -> generatePdf(etTitle.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void generatePdf(String customTitle) {
        exec.execute(() -> {
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                CategoryComparisonReport.writePdf(currentResult, cashbookName, customTitle, bos);

                // Preview screen-ku kudukardhukku cache-la ezhudhurom — Downloads
                // save inimel preview screen-oda "Save" button-la thaan nadakkum.
                File dir = new File(getCacheDir(), "report_previews");
                if (!dir.exists()) dir.mkdirs();
                File tempFile = new File(dir, "preview_" + System.currentTimeMillis() + ".pdf");
                try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                    fos.write(bos.toByteArray());
                }

                String downloadFileName = "category_report_" + System.currentTimeMillis() + ".pdf";
                mainHandler.post(() -> {
                    Intent i = new Intent(this, ReportPdfPreviewActivity.class);
                    i.putExtra("pdfPath", tempFile.getAbsolutePath());
                    i.putExtra("suggestedFileName", downloadFileName);
                    i.putExtra("title", "Monthly Category Report");
                    startActivity(i);
                });
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(this, "PDF export failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void exportExcel() {
        if (currentResult == null) return;
        exec.execute(() -> {
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                CategoryComparisonReport.writeXlsx(currentResult, cashbookName, bos);
                String fileName = "category_report_" + System.currentTimeMillis() + ".xlsx";
                DownloadsSaver.Result r = DownloadsSaver.save(this, fileName,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        out -> out.write(bos.toByteArray()));
                mainHandler.post(() -> Toast.makeText(this, "Saved to " + r.displayLocation, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(this, "Excel export failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    // AppConfig.KEY_SCHEDULER_ALERT_EMAIL (scheduler.alert.email) நேரடியா
    // eduthu use pannudhu — inimel manual button-um scheduler-um same
    // configured email-kku thaan send pannum, dialog kekkadhu.
    private void emailReport() {
        if (currentResult == null) return;
        String toAddress = com.expenseos.util.AppConfig.get(this).getSchedulerAlertEmail();
        if (toAddress == null || toAddress.isBlank()) {
            Toast.makeText(this, "No alert email configured — set it in Config first.", Toast.LENGTH_LONG).show();
            return;
        }
        sendEmail(toAddress);
    }

    private void sendEmail(String toAddress) {
        Toast.makeText(this, "Sending…", Toast.LENGTH_SHORT).show();
        exec.execute(() -> {
            try {
                ByteArrayOutputStream pdfBytes = new ByteArrayOutputStream();
                CategoryComparisonReport.writePdf(currentResult, cashbookName, null, pdfBytes);

                String subject = "Monthly Category Report — " +
                        currentResult.months.get(currentResult.months.size() - 1).getMonth()
                                .getDisplayName(TextStyle.FULL, Locale.ENGLISH);
                String html = CategoryComparisonReport.buildHtmlEmail(currentResult, cashbookName);

                GmailSender.Attachment attachment = new GmailSender.Attachment(
                        "monthly_category_report.pdf", pdfBytes.toByteArray(), "application/pdf");

                GmailSender.send(this, toAddress, subject, html, attachment);
                mainHandler.post(() -> Toast.makeText(this, "✔ Email sent!", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                mainHandler.post(() -> Toast.makeText(this, "✘ Send failed: " + msg, Toast.LENGTH_LONG).show());
            }
        });
    }
}
