package com.expenseos.ui;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.expenseos.R;
import com.expenseos.dao.CategoryDao;
import com.expenseos.dao.SubCategoryDao;
import com.expenseos.dao.TransactionDao;
import com.expenseos.model.Category;
import com.expenseos.model.SubCategory;
import com.expenseos.model.Transaction;
import com.expenseos.model.TransactionFilter;
import com.expenseos.util.DownloadsSaver;
import com.expenseos.util.ReportGenerator;

import java.io.File;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Note: "Payment Mode" from the old app's filter row is intentionally not
 * present here — there's no payment_mode column anywhere in this app's
 * schema, so a filter for it would just be a non-functional stub. Flagging
 * this rather than building a fake control.
 */
public class GenerateReportActivity extends AppCompatActivity {

    private static final int REQ_STORAGE_PERM = 2001;
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    private int bookId;
    private TransactionDao txnDao;
    private CategoryDao catDao;
    private SubCategoryDao subCatDao;

    private TextView tvDuration, tvEntryType, tvCategory, tvSearchTerm, tvSubCategory, tvAmount;
    private RadioGroup rgReportType;

    // Filter state
    private String durationLabel = "All Time";
    private LocalDate dateFrom, dateTo;
    private String entryType = null; // null=All, "INCOME", "EXPENSE"
    private List<Category> allCategories = new ArrayList<>();
    private final List<Integer> selectedCategoryIds = new ArrayList<>();
    private List<SubCategory> allSubCategories = new ArrayList<>();
    private final List<Integer> selectedSubCategoryIds = new ArrayList<>();
    private String amountOp1 = null;
    private BigDecimal amount1 = null;
    private String amountOp2 = null;
    private BigDecimal amount2 = null;
    private String searchTerm = null;

    // Resumed after a storage-permission grant on API < 29
    private String pendingAction; // "csv" or "pdf-save"

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_generate_report);

        SharedPreferences prefs = getSharedPreferences("expenseos_prefs", MODE_PRIVATE);
        bookId = prefs.getInt("active_book_id", 0);
        txnDao = new TransactionDao(this);
        catDao = new CategoryDao(this);
        subCatDao = new SubCategoryDao(this);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        tvDuration = findViewById(R.id.tvDuration);
        tvEntryType = findViewById(R.id.tvEntryType);
        tvCategory = findViewById(R.id.tvCategory);
        tvSubCategory = findViewById(R.id.tvSubCategory);
        tvAmount = findViewById(R.id.tvAmount);
        tvSearchTerm = findViewById(R.id.tvSearchTerm);
        rgReportType = findViewById(R.id.rgReportType);

        findViewById(R.id.filterDuration).setOnClickListener(v -> showDurationDialog());
        findViewById(R.id.filterType).setOnClickListener(v -> showEntryTypeDialog());
        findViewById(R.id.filterCategory).setOnClickListener(v -> showCategoryDialog());
        findViewById(R.id.filterSubCategory).setOnClickListener(v -> showSubCategoryDialog());
        findViewById(R.id.filterAmount).setOnClickListener(v -> showAmountDialog());
        findViewById(R.id.filterSearch).setOnClickListener(v -> showSearchDialog());

        findViewById(R.id.btnGenerateExcel).setOnClickListener(v -> {
            if (ensureStoragePermission("csv")) generateExcel();
        });
        findViewById(R.id.btnGeneratePdf).setOnClickListener(v -> showPdfOptionsSheet());

        // Both types merged — a report can span income and expense, so the
        // category picker shouldn't be locked to whatever Entry Type filter
        // happens to be set separately.
        allCategories = new ArrayList<>();
        allCategories.addAll(catDao.findByType("INCOME", bookId));
        allCategories.addAll(catDao.findByType("EXPENSE", bookId));

        // Not scoped to selected category — matches the old app's own
        // sub-category picker, which lists sub-categories across the board
        // rather than only those under whatever category happens to be
        // checked in the Category filter.
        allSubCategories = subCatDao.findAll();
    }

    // ── Duration filter ─────────────────────────────────────
    private void showDurationDialog() {
        String[] opts = {"All Time", "Today", "Yesterday", "This Month", "Last Month", "Single Day", "Date Range"};
        new AlertDialog.Builder(this)
                .setTitle("Select Date Filter")
                .setItems(opts, (d, which) -> {
                    switch (which) {
                        case 0 -> {
                            durationLabel = "All Time";
                            dateFrom = null;
                            dateTo = null;
                            tvDuration.setText(durationLabel);
                        }
                        case 1 -> {
                            durationLabel = "Today";
                            dateFrom = dateTo = LocalDate.now();
                            tvDuration.setText(durationLabel);
                        }
                        case 2 -> {
                            durationLabel = "Yesterday";
                            dateFrom = dateTo = LocalDate.now().minusDays(1);
                            tvDuration.setText(durationLabel);
                        }
                        case 3 -> {
                            YearMonth ym = YearMonth.now();
                            dateFrom = ym.atDay(1);
                            dateTo = ym.atEndOfMonth();
                            durationLabel = "This Month";
                            tvDuration.setText(durationLabel);
                        }
                        case 4 -> {
                            YearMonth ym = YearMonth.now().minusMonths(1);
                            dateFrom = ym.atDay(1);
                            dateTo = ym.atEndOfMonth();
                            durationLabel = "Last Month";
                            tvDuration.setText(durationLabel);
                        }
                        case 5 -> pickSingleDay();
                        case 6 -> pickDateRange();
                    }
                })
                .show();
    }

    private void pickSingleDay() {
        LocalDate base = LocalDate.now();
        new android.app.DatePickerDialog(this, (view, y, m, day) -> {
            dateFrom = dateTo = LocalDate.of(y, m + 1, day);
            durationLabel = dateFrom.format(DAY_FMT);
            tvDuration.setText(durationLabel);
        }, base.getYear(), base.getMonthValue() - 1, base.getDayOfMonth()).show();
    }

    private void pickDateRange() {
        LocalDate base = LocalDate.now();
        new android.app.DatePickerDialog(this, (view, y1, m1, d1) -> {
            LocalDate from = LocalDate.of(y1, m1 + 1, d1);
            new android.app.DatePickerDialog(this, (view2, y2, m2, d2) -> {
                LocalDate to = LocalDate.of(y2, m2 + 1, d2);
                dateFrom = from;
                dateTo = to;
                durationLabel = from.format(DAY_FMT) + " - " + to.format(DAY_FMT);
                tvDuration.setText(durationLabel);
            }, base.getYear(), base.getMonthValue() - 1, base.getDayOfMonth()).show();
        }, base.getYear(), base.getMonthValue() - 1, base.getDayOfMonth()).show();
    }

    // ── Entry Type filter ───────────────────────────────────
    private void showEntryTypeDialog() {
        String[] opts = {"All", "Income", "Expense"};
        int sel = entryType == null ? 0 : ("INCOME".equals(entryType) ? 1 : 2);
        new AlertDialog.Builder(this)
                .setTitle("Entry Type")
                .setSingleChoiceItems(opts, sel, (d, which) -> {
                    entryType = which == 0 ? null : (which == 1 ? "INCOME" : "EXPENSE");
                    tvEntryType.setText(opts[which]);
                    d.dismiss();
                })
                .show();
    }

    // ── Category filter (multi-select) ──────────────────────
    private void showCategoryDialog() {
        if (allCategories.isEmpty()) {
            Toast.makeText(this, "No categories found", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[allCategories.size()];
        boolean[] checked = new boolean[allCategories.size()];
        for (int i = 0; i < allCategories.size(); i++) {
            names[i] = allCategories.get(i).getName();
            checked[i] = selectedCategoryIds.contains(allCategories.get(i).getId());
        }

        new AlertDialog.Builder(this)
                .setTitle("Select Category Filter")
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Apply", (d, w) -> {
                    selectedCategoryIds.clear();
                    int count = 0;
                    for (int i = 0; i < checked.length; i++)
                        if (checked[i]) {
                            selectedCategoryIds.add(allCategories.get(i).getId());
                            count++;
                        }
                    tvCategory.setText(count == 0 ? "All" : count + " selected");
                })
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Clear", (d, w) -> {
                    selectedCategoryIds.clear();
                    tvCategory.setText("All");
                })
                .show();
    }

    // ── Sub Category filter (multi-select) ──────────────────
    private void showSubCategoryDialog() {
        if (allSubCategories.isEmpty()) {
            Toast.makeText(this, "No sub categories found", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[allSubCategories.size()];
        boolean[] checked = new boolean[allSubCategories.size()];
        for (int i = 0; i < allSubCategories.size(); i++) {
            names[i] = allSubCategories.get(i).getName();
            checked[i] = selectedSubCategoryIds.contains(allSubCategories.get(i).getId());
        }

        new AlertDialog.Builder(this)
                .setTitle("Select Sub Category Filter")
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Apply", (d, w) -> {
                    selectedSubCategoryIds.clear();
                    int count = 0;
                    for (int i = 0; i < checked.length; i++)
                        if (checked[i]) {
                            selectedSubCategoryIds.add(allSubCategories.get(i).getId());
                            count++;
                        }
                    tvSubCategory.setText(count == 0 ? "All" : count + " selected");
                })
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Clear", (d, w) -> {
                    selectedSubCategoryIds.clear();
                    tvSubCategory.setText("All");
                })
                .show();
    }

    // ── Amount filter — operator + value, optional second AND clause,
    // e.g. ">=10 AND <=50" for a range ─────────────────────
    private void showAmountDialog() {
        String[] ops = {"=", ">", ">=", "<", "<="};
        String[] ops2 = {"(none)", "AND >", "AND >=", "AND <", "AND <="};

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(12), dp(20), dp(4));

        android.widget.Spinner spOp1 = new android.widget.Spinner(this);
        spOp1.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, ops));
        int op1Pos = amountOp1 == null ? 0 : java.util.Arrays.asList(ops).indexOf(amountOp1);
        spOp1.setSelection(Math.max(0, op1Pos));
        container.addView(spOp1);

        android.widget.EditText etAmount1 = new android.widget.EditText(this);
        etAmount1.setHint("Amount");
        etAmount1.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (amount1 != null) etAmount1.setText(amount1.toPlainString());
        container.addView(etAmount1);

        TextView andLabel = new TextView(this);
        andLabel.setText("Range end (optional)");
        andLabel.setPadding(0, dp(16), 0, 0);
        andLabel.setTextColor(getColor(R.color.text_secondary));
        container.addView(andLabel);

        android.widget.Spinner spOp2 = new android.widget.Spinner(this);
        spOp2.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, ops2));
        int op2Pos = amountOp2 == null ? 0 : java.util.Arrays.asList(ops2).indexOf("AND " + amountOp2);
        spOp2.setSelection(Math.max(0, op2Pos));
        container.addView(spOp2);

        android.widget.EditText etAmount2 = new android.widget.EditText(this);
        etAmount2.setHint("Range end");
        etAmount2.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (amount2 != null) etAmount2.setText(amount2.toPlainString());
        container.addView(etAmount2);

        new AlertDialog.Builder(this)
                .setTitle("Amount")
                .setView(container)
                .setPositiveButton("Apply", (d, w) -> {
                    String v1 = etAmount1.getText().toString().trim();
                    if (v1.isEmpty()) {
                        amountOp1 = null;
                        amount1 = null;
                        amountOp2 = null;
                        amount2 = null;
                        tvAmount.setText("All");
                        return;
                    }
                    amountOp1 = ops[spOp1.getSelectedItemPosition()];
                    amount1 = new BigDecimal(v1);

                    String v2 = etAmount2.getText().toString().trim();
                    int sel2 = spOp2.getSelectedItemPosition();
                    if (sel2 > 0 && !v2.isEmpty()) {
                        amountOp2 = ops2[sel2].replace("AND ", "");
                        amount2 = new BigDecimal(v2);
                        tvAmount.setText(amountOp1 + amount1.toPlainString() + " " + amountOp2 + amount2.toPlainString());
                    } else {
                        amountOp2 = null;
                        amount2 = null;
                        tvAmount.setText(amountOp1 + amount1.toPlainString());
                    }
                })
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Clear", (d, w) -> {
                    amountOp1 = null;
                    amount1 = null;
                    amountOp2 = null;
                    amount2 = null;
                    tvAmount.setText("All");
                })
                .show();
    }
    private void showSearchDialog() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("Search by remark or amount");
        if (searchTerm != null) input.setText(searchTerm);

        new AlertDialog.Builder(this)
                .setTitle("Search Term")
                .setView(input)
                .setPositiveButton("Apply", (d, w) -> {
                    String q = input.getText().toString().trim();
                    searchTerm = q.isEmpty() ? null : q;
                    tvSearchTerm.setText(searchTerm == null ? "None" : searchTerm);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Build the filtered transaction list for the report ──
    private List<Transaction> loadFilteredTransactions() {
        TransactionFilter f = new TransactionFilter();
        f.setBookId(bookId);
        f.setType(entryType);
        f.setDateFrom(dateFrom);
        f.setDateTo(dateTo);
        f.setNoteSearch(searchTerm);
        f.setCategoryIds(selectedCategoryIds.isEmpty() ? null : new ArrayList<>(selectedCategoryIds));
        f.setSubCategoryIds(selectedSubCategoryIds.isEmpty() ? null : new ArrayList<>(selectedSubCategoryIds));
        f.setAmountOp1(amountOp1);
        f.setAmount1(amount1);
        f.setAmountOp2(amountOp2);
        f.setAmount2(amount2);
        f.setSortBy("date");
        f.setSortDir("asc");
        f.setPageSize(Integer.MAX_VALUE);
        return txnDao.findByFilter(f);
    }

    private String currentReportType() {
        int id = rgReportType.getCheckedRadioButtonId();
        if (id == R.id.rbDaywise) return ReportGenerator.TYPE_DAYWISE;
        if (id == R.id.rbCategorywise) return ReportGenerator.TYPE_CATEGORYWISE;
        if (id == R.id.rbSubcategorywise) return ReportGenerator.TYPE_SUBCATEGORYWISE;
        return ReportGenerator.TYPE_ALL;
    }

    // ── Excel (CSV) ──────────────────────────────────────────
    private void generateExcel() {
        List<Transaction> txns = loadFilteredTransactions();
        String reportType = currentReportType();
        String fileName = "Report_" + reportType + "_" + System.currentTimeMillis() + ".csv";
        try {
            DownloadsSaver.Result result = DownloadsSaver.save(this, fileName, "text/csv",
                    out -> ReportGenerator.writeCsv(txns, reportType, out));
            Toast.makeText(this, "Saved to " + result.displayLocation, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ── PDF — Show Preview / Save to Downloads Folder ───────
    private void showPdfOptionsSheet() {
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, dp(8), 0, dp(16));

        TextView title = new TextView(this);
        title.setText("PDF report");
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(dp(20), dp(12), dp(20), dp(12));
        container.addView(title);

        container.addView(sheetOption("👁", "Show Preview", () -> {
            sheet.dismiss();
            previewPdf();
        }));
        container.addView(sheetOption("⬇", "Save to Downloads Folder", () -> {
            sheet.dismiss();
            if (ensureStoragePermission("pdf-save")) savePdfToDownloads();
        }));

        sheet.setContentView(container);
        sheet.show();
    }

    private View sheetOption(String emoji, String label, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(14), dp(20), dp(14));
        row.setClickable(true);
        row.setFocusable(true);
        android.util.TypedValue outValue = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        row.setBackgroundResource(outValue.resourceId);

        TextView tvEmoji = new TextView(this);
        tvEmoji.setText(emoji);
        tvEmoji.setTextSize(18);
        tvEmoji.setLayoutParams(new LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(tvEmoji);

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextSize(16);
        tvLabel.setTextColor(getColor(R.color.text_primary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(16);
        tvLabel.setLayoutParams(lp);
        row.addView(tvLabel);

        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void previewPdf() {
        try {
            List<Transaction> txns = loadFilteredTransactions();
            String reportType = currentReportType();

            File dir = new File(getCacheDir(), "reports");
            if (!dir.exists()) dir.mkdirs();
            File pdfFile = new File(dir, "preview_" + System.currentTimeMillis() + ".pdf");
            try (FileOutputStream out = new FileOutputStream(pdfFile)) {
                ReportGenerator.writePdf(txns, reportType, out);
            }

            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pdfFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/pdf");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't generate preview: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void savePdfToDownloads() {
        List<Transaction> txns = loadFilteredTransactions();
        String reportType = currentReportType();
        String fileName = "Report_" + reportType + "_" + System.currentTimeMillis() + ".pdf";
        try {
            DownloadsSaver.Result result = DownloadsSaver.save(this, fileName, "application/pdf",
                    out -> ReportGenerator.writePdf(txns, reportType, out));
            Toast.makeText(this, "Saved to " + result.displayLocation, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ── Storage permission (API 26-28 only — API 29+ uses MediaStore,
    // which needs no permission at all) ─────────────────────
    private boolean ensureStoragePermission(String action) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) return true;

        pendingAction = action;
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE_PERM);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_STORAGE_PERM) return;

        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if ("csv".equals(pendingAction)) generateExcel();
            else if ("pdf-save".equals(pendingAction)) savePdfToDownloads();
        } else {
            Toast.makeText(this, "Storage permission needed to save to Downloads", Toast.LENGTH_SHORT).show();
        }
        pendingAction = null;
    }
}