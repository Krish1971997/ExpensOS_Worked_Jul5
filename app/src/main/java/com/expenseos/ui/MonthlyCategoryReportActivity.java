package com.expenseos.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.util.CategoryComparisonReport;
import com.expenseos.util.DownloadsSaver;
import com.expenseos.util.GmailSender;

import java.io.ByteArrayOutputStream;
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
        findViewById(R.id.btnExportEmail).setOnClickListener(v -> showEmailDialog());
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
        View header = findViewById(R.id.rowMcrHeader);
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

        header.setVisibility(View.VISIBLE);
        exportBar.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);

        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new ComparisonAdapter(currentResult.rows));
    }

    class ComparisonAdapter extends RecyclerView.Adapter<ComparisonAdapter.VH> {
        private final List<CategoryComparisonReport.RowData> rows;

        ComparisonAdapter(List<CategoryComparisonReport.RowData> rows) {
            this.rows = rows;
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvCategory, tvLast, tvPrev, tvPct;

            VH(View v) {
                super(v);
                tvCategory = v.findViewById(R.id.tvMcrCategory);
                tvLast = v.findViewById(R.id.tvMcrLast);
                tvPrev = v.findViewById(R.id.tvMcrPrev);
                tvPct = v.findViewById(R.id.tvMcrPct);
            }
        }

        @Override
        public VH onCreateViewHolder(ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_category_comparison_row, p, false));
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            CategoryComparisonReport.RowData row = rows.get(pos);
            h.tvCategory.setText(row.category);
            h.tvLast.setText("₹" + row.lastMonthAmt.toPlainString());
            h.tvPrev.setText("₹" + row.prevMonthAmt.toPlainString());
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
        exec.execute(() -> {
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                CategoryComparisonReport.writePdf(currentResult, bos);
                String fileName = "category_report_" + System.currentTimeMillis() + ".pdf";
                DownloadsSaver.Result r = DownloadsSaver.save(this, fileName, "application/pdf", out -> out.write(bos.toByteArray()));
                mainHandler.post(() -> Toast.makeText(this, "Saved to " + r.displayLocation, Toast.LENGTH_LONG).show());
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
                CategoryComparisonReport.writeXlsx(currentResult, bos);
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

    private void showEmailDialog() {
        if (currentResult == null) return;
        String defaultTo = com.expenseos.util.AppConfig.get(this).getGmailFrom();

        EditText etTo = new EditText(this);
        etTo.setHint("Recipient email");
        etTo.setText(defaultTo);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        etTo.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(this)
                .setTitle("Email Report")
                .setView(etTo)
                .setPositiveButton("Send", (d, w) -> sendEmail(etTo.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendEmail(String toAddress) {
        Toast.makeText(this, "Sending…", Toast.LENGTH_SHORT).show();
        exec.execute(() -> {
            try {
                ByteArrayOutputStream pdfBytes = new ByteArrayOutputStream();
                CategoryComparisonReport.writePdf(currentResult, pdfBytes);

                String subject = "Monthly Category Report — " +
                        currentResult.months.get(currentResult.months.size() - 1).getMonth()
                                .getDisplayName(TextStyle.FULL, Locale.ENGLISH);
                String html = CategoryComparisonReport.buildHtmlEmail(currentResult);
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
