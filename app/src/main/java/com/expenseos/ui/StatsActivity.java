package com.expenseos.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.LinearLayout;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.adapter.StatsCategoryAdapter;
import com.expenseos.dao.CashBookDao;
import com.expenseos.dao.TransactionDao;
import com.expenseos.model.CashBook;
import com.expenseos.util.GmailSender;
import com.expenseos.util.MonthBookResolver;
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Document;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.Image;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StatsActivity extends AppCompatActivity {

    private YearMonth currentMonth = YearMonth.now();
    private boolean showExpense = true; // default tab = Expense, per screenshot
    private String seriesSuffix = ""; // "" = plain month books; "Credit Card" etc = scoped to that series

    // Set only when we were launched from a cashbook whose NAME does NOT match
    // the "<Month> <Year>[suffix]" pattern (e.g. "Temple trip August 2026",
    // "trip expense aug 2026"). Such books have no month/series to cycle
    // through, so we show that exact book's stats directly and skip
    // MonthBookResolver entirely. When this is non-null, currentMonth /
    // seriesSuffix based lookups are NOT used.
    private CashBook directBook = null;

    private TextView tvMonth, tvTotalBalance, tvEmpty;
    private android.widget.CheckBox cbNetSettlements;
    private PieChart pieChart;
    private LinearLayout rvCategories;
    private TextView tabIncome, tabExpense;
    private View btnPrevMonth, btnNextMonth;

    // Home-page ("all cashbooks") entry only — lets the user jump straight
    // to any book's stats via Series -> Book, instead of only cycling
    // month-by-month within whatever series they happened to land on.
    private static final String[] SERIES_OPTIONS = {"All", "Default", "Credit Card", "Expense", "Others"};
    private Spinner spSeriesFilter, spBookFilter;
    private boolean isHomeEntry;
    private boolean suppressBookFilterCallback; // guards Android's async auto-fire on setAdapter
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private static final String XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    // Last-rendered category breakdown, kept for export — set inside refresh()
    private final List<StatsCategoryRow> lastCategoryRows = new ArrayList<>();

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_stats);
        try { findViewById(R.id.btnStatsInsights).setOnClickListener(v -> startActivity(new Intent(this, InsightsActivity.class))); } catch (Throwable ignored) {}

        findViewById(R.id.btnStatsBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnStatsMenu).setOnClickListener(this::showExportMenu);

        btnPrevMonth = findViewById(R.id.btnPrevMonth);
        btnNextMonth = findViewById(R.id.btnNextMonth);
        btnPrevMonth.setOnClickListener(v -> {
            if (directBook != null) return; // no cycling for standalone/irregular books
            currentMonth = currentMonth.minusMonths(1);
            refresh();
        });
        btnNextMonth.setOnClickListener(v -> {
            if (directBook != null) return;
            currentMonth = currentMonth.plusMonths(1);
            refresh();
        });

        tvMonth = findViewById(R.id.tvStatsMonth);
        tvTotalBalance = findViewById(R.id.tvStatsTotal);
        tvEmpty = findViewById(R.id.tvStatsEmpty);
        pieChart = findViewById(R.id.pieStats);
        rvCategories = findViewById(R.id.rvStatsCategories);

        cbNetSettlements = findViewById(R.id.cbNetSettlements);
        cbNetSettlements.setOnCheckedChangeListener((btn, checked) -> refresh());

        spSeriesFilter = findViewById(R.id.spSeriesFilter);
        spBookFilter = findViewById(R.id.spBookFilter);

        tabIncome = findViewById(R.id.tabIncome);
        tabExpense = findViewById(R.id.tabExpense);
        tabIncome.setOnClickListener(v -> {
            showExpense = false;
            refresh();
        });
        tabExpense.setOnClickListener(v -> {
            showExpense = true;
            refresh();
        });

        // Launched from inside a specific cashbook — decide whether it's a
        // month-pattern book ("August 2026", "August 2026 Expense",
        // "August 2026 Credit Card") or an irregular one-off book
        // ("Temple trip August 2026", "trip expense aug 2026").
        int scopeBookId = getIntent().getIntExtra("scopeBookId", -1);
        isHomeEntry = scopeBookId <= 0;
        if (scopeBookId > 0) {
            CashBook scopeBook = new CashBookDao(this).findById(scopeBookId);
            if (scopeBook != null) {
                YearMonth parsed = MonthBookResolver.parseYearMonth(scopeBook.getName());
                if (parsed != null) {
                    // Conforms to "<Month> <Year>[suffix]" — scope prev/next
                    // cycling to books sharing this same suffix/series.
                    seriesSuffix = MonthBookResolver.extractSuffix(scopeBook.getName());
                    currentMonth = parsed;
                } else {
                    // Irregular name — no month/series to cycle through.
                    // Show this exact book's stats, nothing else.
                    directBook = scopeBook;
                    btnPrevMonth.setEnabled(false);
                    btnNextMonth.setEnabled(false);
                    btnPrevMonth.setAlpha(0.3f);
                    btnNextMonth.setAlpha(0.3f);
                }
            }
        }

        if (isHomeEntry) {
            findViewById(R.id.llStatsFilters).setVisibility(View.VISIBLE);
            setupSeriesFilter(); // populates the dropdowns and triggers the first refresh() itself
        } else {
            refresh();
        }
    }

    private void setupSeriesFilter() {
        ArrayAdapter<String> adp = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, SERIES_OPTIONS);
        adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSeriesFilter.setAdapter(adp);
        spSeriesFilter.setSelection(1, false); // "Default" — matches the old hardcoded behavior

        spBookFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (suppressBookFilterCallback) return;
                Object tag = spBookFilter.getTag();
                if (tag instanceof List) {
                    @SuppressWarnings("unchecked") List<CashBook> shown = (List<CashBook>) tag;
                    if (pos >= 0 && pos < shown.size()) selectBookFromDropdown(shown.get(pos));
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        // Attached AFTER the setSelection(1) above, so picking "Default"
        // programmatically here doesn't also fire this listener.
        spSeriesFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                populateBookDropdown(SERIES_OPTIONS[pos]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        populateBookDropdown(SERIES_OPTIONS[1]);
    }

    // "Default" = plain "<Month> <Year>" books ("August 2026")
    // "Credit Card" / "Expense" = that exact suffix
    // "Others" = everything else — irregular names AND any other suffix
    //            (e.g. "August 2026 Business") that isn't one of the three above
    private String classifySeries(CashBook book) {
        YearMonth ym = MonthBookResolver.parseYearMonth(book.getName());
        if (ym == null) return "Others";
        String suffix = MonthBookResolver.extractSuffix(book.getName());
        if (suffix.isEmpty()) return "Default";
        if (suffix.equalsIgnoreCase("Credit Card")) return "Credit Card";
        if (suffix.equalsIgnoreCase("Expense")) return "Expense";
        return "Others";
    }

    private void populateBookDropdown(String seriesFilter) {
        List<CashBook> all = new CashBookDao(this).findAll();
        List<CashBook> shown = new ArrayList<>();
        for (CashBook b : all) {
            if (seriesFilter.equals("All") || classifySeries(b).equals(seriesFilter)) {
                shown.add(b);
            }
        }
        // Newest month first where the name is parseable; unparseable
        // ("Others") names fall back to alphabetical, listed after those.
        shown.sort((a, b) -> {
            YearMonth ya = MonthBookResolver.parseYearMonth(a.getName());
            YearMonth yb = MonthBookResolver.parseYearMonth(b.getName());
            if (ya != null && yb != null) return yb.compareTo(ya);
            if (ya != null) return -1;
            if (yb != null) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        List<String> names = new ArrayList<>();
        for (CashBook b : shown) names.add(b.getName());
        if (names.isEmpty()) names.add("No cash books");

        ArrayAdapter<String> adp = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        suppressBookFilterCallback = true;
        spBookFilter.setAdapter(adp);
        spBookFilter.setTag(shown);
        // Clear the guard one message-loop pass later, after Android's own
        // queued selection-changed callback for this setAdapter has run —
        // otherwise that auto-fire double-triggers selectBookFromDropdown()
        // on top of the explicit call below.
        spBookFilter.post(() -> suppressBookFilterCallback = false);

        if (!shown.isEmpty()) {
            selectBookFromDropdown(shown.get(0));
        } else {
            directBook = null;
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("No cash books in this series");
            pieChart.setVisibility(View.GONE);
            rvCategories.setVisibility(View.GONE);
        }
    }

    // Same month-pattern-vs-irregular split used for the scoped (Type 2)
    // entry — reused here so the header's Prev/Next month arrows keep
    // working for any Default/Credit Card/Expense book picked from the
    // dropdown, and get disabled for an irregular ("Others") one.
    private void selectBookFromDropdown(CashBook book) {
        YearMonth parsed = MonthBookResolver.parseYearMonth(book.getName());
        if (parsed != null) {
            directBook = null;
            seriesSuffix = MonthBookResolver.extractSuffix(book.getName());
            currentMonth = parsed;
            btnPrevMonth.setEnabled(true);
            btnNextMonth.setEnabled(true);
            btnPrevMonth.setAlpha(1f);
            btnNextMonth.setAlpha(1f);
        } else {
            directBook = book;
            btnPrevMonth.setEnabled(false);
            btnNextMonth.setEnabled(false);
            btnPrevMonth.setAlpha(0.3f);
            btnNextMonth.setAlpha(0.3f);
        }
        refresh();
    }

    // Keeps spBookFilter's visible selection in step with whatever book is
    // actually being shown — needed because Prev/Next month changes the
    // book without going through selectBookFromDropdown().
    private void syncBookDropdownSelection(CashBook book) {
        Object tag = spBookFilter.getTag();
        if (!(tag instanceof List)) return;
        @SuppressWarnings("unchecked") List<CashBook> shown = (List<CashBook>) tag;
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).getId() == book.getId()) {
                if (spBookFilter.getSelectedItemPosition() != i) {
                    suppressBookFilterCallback = true;
                    spBookFilter.setSelection(i, false);
                    spBookFilter.post(() -> suppressBookFilterCallback = false);
                }
                return;
            }
        }
    }
    // Book isn't in the currently-shown series list at all (e.g. Prev
    // month arrow moved to a book outside whatever series filter is
    // selected) — leave the dropdown as-is rather than force a mismatch.


    private void refresh() {
        tabExpense.setTextColor(showExpense ? Color.parseColor("#DC2626") : Color.GRAY);
        tabIncome.setTextColor(!showExpense ? Color.parseColor("#16A34A") : Color.GRAY);

        CashBook book;
        if (directBook != null) {
            book = directBook;
            tvMonth.setText(book.getName());
        } else {
            tvMonth.setText(currentMonth.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) + " " + currentMonth.getYear() + (seriesSuffix.isEmpty() ? "" : " · " + seriesSuffix));
            book = MonthBookResolver.findBookForMonth(this, currentMonth, seriesSuffix);
        }

        if (book == null) {
            lastCategoryRows.clear();
            pieChart.setVisibility(View.GONE);
            rvCategories.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("No cash book found for " + MonthBookResolver.expectedName(currentMonth, seriesSuffix));
            tvTotalBalance.setText("₹0.00");
            return;
        }

        if (isHomeEntry) syncBookDropdownSelection(book);

        TransactionDao dao = new TransactionDao(this);
        String currentType = showExpense ? "EXPENSE" : "INCOME";
        List<Map<String, Object>> rows = dao.categoryBreakdownWithId(currentType, book.getId());

        // "Net Settlements" ON pannirundha, ovvoru category-oda total-la
        // irundhu, andha category-oda transactions-la SettlementLinkActivity
        // vazhi link pannirukura amount-ah kழி pannunga (e.g. Snacks/Fuel/
        // Food expense-ah oru single "Others" income settle pannirundha,
        // andha 3 categories-um correspondingly reduce aagum — category
        // matching venaam, transaction-level link mattum podhum).
        if (cbNetSettlements.isChecked()) {
            com.expenseos.dao.SettlementLinkDao settleDao = new com.expenseos.dao.SettlementLinkDao(this);
            Map<Integer, BigDecimal> linkedByCategory = settleDao.sumLinkedByCategory(book.getId(), currentType);
            for (Map<String, Object> r : rows) {
                int catId = (int) r.get("id");
                BigDecimal linked = linkedByCategory.getOrDefault(catId, BigDecimal.ZERO);
                BigDecimal netTotal = ((BigDecimal) r.get("total")).subtract(linked).max(BigDecimal.ZERO);
                r.put("total", netTotal);
            }
        }

        // Sort descending by amount — screenshot shows highest first
        rows.sort((a, b) -> ((BigDecimal) b.get("total")).compareTo((BigDecimal) a.get("total")));

        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) total = total.add((BigDecimal) r.get("total"));
        tvTotalBalance.setText("₹" + total.toPlainString());

        lastCategoryRows.clear();

        if (rows.isEmpty()) {
            pieChart.setVisibility(View.GONE);
            rvCategories.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("No " + (showExpense ? "expenses" : "income") + " this month");
            return;
        }
        tvEmpty.setVisibility(View.GONE);
        pieChart.setVisibility(View.VISIBLE);
        rvCategories.setVisibility(View.VISIBLE);

        // Pie + export snapshot (lastCategoryRows) — same loop, same %
        // formula StatsCategoryAdapter uses, so on-screen list and
        // PDF/Excel export ellame ஒரே data-ah kaatum.
        List<PieEntry> entries = new ArrayList<>();
        int[] colors = {0xFFF59E0B, 0xFF16A34A, 0xFFDC2626, 0xFF2563EB, 0xFF7C3AED, 0xFF0891B2};
        for (Map<String, Object> r : rows) {
            BigDecimal amount = (BigDecimal) r.get("total");
            entries.add(new PieEntry(amount.floatValue(), (String) r.get("name")));

            StatsCategoryRow row = new StatsCategoryRow();
            row.name = (String) r.get("name");
            row.amount = amount;
            row.percent = amount.multiply(BigDecimal.valueOf(100))
                    .divide(total.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ONE : total, 0, java.math.RoundingMode.HALF_UP)
                    .doubleValue();
            lastCategoryRows.add(row);
        }
        PieDataSet ds = new PieDataSet(entries, "");
        ds.setColors(colors, 255);
        ds.setValueTextSize(11f);
        ds.setValueTextColor(Color.WHITE);
        ds.setSliceSpace(2f);
        pieChart.setData(new PieData(ds));
        pieChart.setUsePercentValues(true);
        pieChart.setDrawEntryLabels(false);
        pieChart.getDescription().setEnabled(false);
        pieChart.setDrawHoleEnabled(true);
        pieChart.invalidate();

        // Category list — tap → CategoryStatsActivity drill-down
        final CashBook resolvedBook = book;
                // Category list — tap → CategoryStatsActivity drill-down.
        // Built as plain views so every category renders regardless of how
        // the parent ScrollView measures the container.
        rvCategories.removeAllViews();
        int ci = 0;
        float dens = getResources().getDisplayMetrics().density;
        for (Map<String, Object> r : rows) {
            final int cid = (int) r.get("id");
            final String cname = (String) r.get("name");
            BigDecimal amt = (BigDecimal) r.get("total");
            int pct = amt.multiply(BigDecimal.valueOf(100))
                    .divide(total.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ONE : total, 0, java.math.RoundingMode.HALF_UP)
                    .intValue();

            LinearLayout rowV = new LinearLayout(this);
            rowV.setOrientation(LinearLayout.HORIZONTAL);
            rowV.setGravity(android.view.Gravity.CENTER_VERTICAL);
            int pad = (int) (dens * 14);
            rowV.setPadding(pad, pad, pad, pad);

            View dot = new View(this);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams((int) (dens * 12), (int) (dens * 12));
            dlp.rightMargin = (int) (dens * 12);
            dot.setLayoutParams(dlp);
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            gd.setColor(colors[ci % colors.length]);
            dot.setBackground(gd);
            rowV.addView(dot);

            TextView nm = new TextView(this);
            nm.setText(cname);
            nm.setTextSize(15f);
            nm.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text));
            rowV.addView(nm, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView pc = new TextView(this);
            pc.setText(pct + "%");
            pc.setTextSize(14f);
            pc.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_muted));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            plp.rightMargin = (int) (dens * 16);
            rowV.addView(pc, plp);

            TextView av = new TextView(this);
            av.setText("\u20B9" + amt.toPlainString());
            av.setTextSize(15f);
            av.setTypeface(av.getTypeface(), android.graphics.Typeface.BOLD);
            av.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text));
            rowV.addView(av);

            rowV.setOnClickListener(v -> {
                Intent ii = new Intent(this, CategoryStatsActivity.class);
                ii.putExtra("bookId", resolvedBook.getId());
                ii.putExtra("categoryId", cid);
                ii.putExtra("categoryName", cname);
                ii.putExtra("isExpense", showExpense);
                ii.putExtra("year", currentMonth.getYear());
                ii.putExtra("month", currentMonth.getMonthValue());
                ii.putExtra("seriesSuffix", directBook != null ? "" : seriesSuffix);
                startActivity(ii);
            });
            rvCategories.addView(rowV);
            ci++;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdown();
    }

    // Plain data holder for export — filled from whatever populates
    // rvCategories/StatsCategoryAdapter today. Wire the 3 setters below
    // into refresh()'s existing category-loop so exports stay in sync
    // with what's on screen.
    private static class StatsCategoryRow {
        String name;
        double percent;
        BigDecimal amount;
    }

    // ══════════════════════════════════════════════════════
    // Export — Email / PDF (with chart image) / Excel
    // ══════════════════════════════════════════════════════
// NEW — same three actions, icon-capable bottom sheet instead of PopupMenu
    private void showExportMenu(View anchor) {
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setPadding(0, dp(8), 0, dp(16));

        container.addView(iconRow(R.drawable.ic_email, "Email", () -> {
            sheet.dismiss();
            showEmailDialog();
        }));
        container.addView(iconRow(R.drawable.ic_pdf, "PDF", () -> {
            sheet.dismiss();
            exportPdf();
        }));
        container.addView(iconRow(R.drawable.ic_excel, "Excel", () -> {
            sheet.dismiss();
            exportExcel();
        }));

        sheet.setContentView(container);
        sheet.show();
    }

    private View iconRow(int iconRes, String label, Runnable onClick) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(14), dp(20), dp(14));
        row.setClickable(true);
        row.setFocusable(true);
        android.util.TypedValue outValue = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        row.setBackgroundResource(outValue.resourceId);

        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(iconRes);
        icon.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(28), dp(28)));
        row.addView(icon);

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextSize(16);
        tvLabel.setTextColor(getColor(R.color.text_primary));
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(16);
        tvLabel.setLayoutParams(lp);
        row.addView(tvLabel);

        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private String reportTitle() {
        return (showExpense ? "Expense" : "Income") + " — " + tvMonth.getText();
    }

    // Renders the live PieChart view into a Bitmap for the PDF.
    private Bitmap capturePieChart() {
        Bitmap bmp = Bitmap.createBitmap(pieChart.getWidth(), pieChart.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.WHITE);
        pieChart.draw(canvas);
        return bmp;
    }

    private void exportPdf() {
        Bitmap chartBmp = capturePieChart();
        exec.execute(() -> {
            try {
                File dir = new File(getCacheDir(), "reports");
                if (!dir.exists()) dir.mkdirs();
                File pdfFile = new File(dir, "stats_" + System.currentTimeMillis() + ".pdf");
                try (FileOutputStream out = new FileOutputStream(pdfFile)) {
                    writeStatsPdf(out, chartBmp);
                }
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pdfFile);
                runOnUiThread(() -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(uri, "application/pdf");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(intent);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "PDF failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void exportExcel() {
        // Preview, not a silent save — writes to the app's cache and opens
        // it via ACTION_VIEW so the user's spreadsheet app (Sheets, Excel,
        // WPS, etc.) shows it first; they choose to save/share from there.
        // Mirrors exportPdf()'s pattern rather than DownloadsSaver's
        // straight-to-Downloads write.
        exec.execute(() -> {
            try {
                File dir = new File(getCacheDir(), "reports");
                if (!dir.exists()) dir.mkdirs();
                File xlsxFile = new File(dir, "stats_" + System.currentTimeMillis() + ".xlsx");
                try (FileOutputStream out = new FileOutputStream(xlsxFile)) {
                    writeStatsXlsx(out);
                }
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", xlsxFile);
                runOnUiThread(() -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(uri, XLSX_MIME);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        startActivity(intent);
                    } catch (android.content.ActivityNotFoundException e) {
                        Toast.makeText(this, "No app found to preview Excel files — install Google Sheets, Excel, or WPS Office.", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Excel failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showEmailDialog() {
        // Sends straight to the address configured in Config
        // (scheduler.alert.email) — no confirm dialog, no manual typing.
        String configuredEmail = com.expenseos.util.AppConfig.get(this).getSchedulerAlertEmail();
        if (configuredEmail == null || configuredEmail.isBlank()) {
            Toast.makeText(this,
                    "No email configured — set it under Config first.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        sendEmail(configuredEmail);
    }

    private void sendEmail(String toAddress) {
        Toast.makeText(this, "Sending…", Toast.LENGTH_SHORT).show();
        Bitmap chartBmp = capturePieChart();
        exec.execute(() -> {
            try {
                ByteArrayOutputStream pdfBytes = new ByteArrayOutputStream();
                writeStatsPdf(pdfBytes, chartBmp);

                String subject = reportTitle();
                String html = buildStatsEmailHtml(subject);
                GmailSender.Attachment attachment = new GmailSender.Attachment(
                        "stats.pdf", pdfBytes.toByteArray(), "application/pdf");
                GmailSender.send(this, toAddress, subject, html, attachment);
                runOnUiThread(() -> Toast.makeText(this, "✔ Email sent!", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                runOnUiThread(() -> Toast.makeText(this, "✘ Send failed: " + msg, Toast.LENGTH_LONG).show());
            }
        });
    }

    // Full stats data rendered directly in the email body (not just a
    // "see attached" note) — the PDF is still attached separately for
    // anyone who wants the formatted/printable version.
    private String buildStatsEmailHtml(String subject) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body style='font-family:Arial,sans-serif;'>");
        sb.append("<h2>").append(subject).append("</h2>");
        sb.append("<p style='color:#555;'>Total Balance: <b>").append(tvTotalBalance.getText()).append("</b></p>");

        sb.append("<table style='border-collapse:collapse;width:100%;max-width:480px;'>");
        sb.append("<tr style='background:#2563EB;color:#fff;'>")
                .append("<th style='padding:6px;text-align:left;'>Category</th>")
                .append("<th style='padding:6px;text-align:right;'>%</th>")
                .append("<th style='padding:6px;text-align:right;'>Amount</th>")
                .append("</tr>");

        for (StatsCategoryRow row : lastCategoryRows) {
            sb.append("<tr style='border-bottom:1px solid #eee;'>")
                    .append("<td style='padding:6px;'>").append(row.name).append("</td>")
                    .append("<td style='padding:6px;text-align:right;'>")
                    .append(String.format(java.util.Locale.US, "%.0f%%", row.percent)).append("</td>")
                    .append("<td style='padding:6px;text-align:right;'>₹")
                    .append(row.amount.toPlainString()).append("</td>")
                    .append("</tr>");
        }
        sb.append("</table>");
        sb.append("<p style='color:#888;font-size:12px;margin-top:16px;'>A PDF copy of this report is attached.</p>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private void writeStatsPdf(OutputStream out, Bitmap chartBmp) throws Exception {
        Document doc = new Document(PageSize.A4, 24, 24, 32, 32);
        PdfWriter.getInstance(doc, out);
        doc.open();

        Font titleFont = new Font(Font.FontFamily.HELVETICA, 16, Font.BOLD);
        Font headFont = new Font(Font.FontFamily.HELVETICA, 10, Font.BOLD, BaseColor.WHITE);
        Font cellFont = new Font(Font.FontFamily.HELVETICA, 9, Font.NORMAL);

        Paragraph title = new Paragraph(reportTitle(), titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(4);
        doc.add(title);

        Paragraph balance = new Paragraph("Total Balance: " + tvTotalBalance.getText(),
                new Font(Font.FontFamily.HELVETICA, 11, Font.NORMAL, BaseColor.GRAY));
        balance.setAlignment(Element.ALIGN_CENTER);
        balance.setSpacingAfter(16);
        doc.add(balance);

        // Chart image
        ByteArrayOutputStream chartBytes = new ByteArrayOutputStream();
        chartBmp.compress(Bitmap.CompressFormat.PNG, 100, chartBytes);
        Image chartImg = Image.getInstance(chartBytes.toByteArray());
        chartImg.scaleToFit(300, 300);
        chartImg.setAlignment(Element.ALIGN_CENTER);
        doc.add(chartImg);
        doc.add(new Paragraph(" "));

        // Category table
        PdfPTable table = new PdfPTable(new float[]{2f, 1f, 1.5f});
        table.setWidthPercentage(100);
        for (String h : new String[]{"Category", "%", "Amount"}) {
            PdfPCell cell = new PdfPCell(new Paragraph(h, headFont));
            cell.setBackgroundColor(new BaseColor(37, 99, 235));
            cell.setPadding(6);
            table.addCell(cell);
        }
        for (StatsCategoryRow row : lastCategoryRows) {
            addPdfCell(table, row.name, cellFont);
            addPdfCell(table, String.format(java.util.Locale.US, "%.0f%%", row.percent), cellFont);
            addPdfCell(table, "₹" + row.amount.toPlainString(), cellFont);
        }
        doc.add(table);
        doc.close();
    }

    private void addPdfCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Paragraph(text, font));
        cell.setPadding(5);
        table.addCell(cell);
    }

    private void writeStatsXlsx(OutputStream out) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Stats");
            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            org.apache.poi.ss.usermodel.Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);

            sheet.createRow(0).createCell(0).setCellValue(reportTitle());
            sheet.createRow(1).createCell(0).setCellValue("Total Balance: " + tvTotalBalance.getText());

            Row header = sheet.createRow(3);
            String[] cols = {"Category", "%", "Amount"};
            for (int i = 0; i < cols.length; i++) {
                Cell c = header.createCell(i);
                c.setCellValue(cols[i]);
                c.setCellStyle(headerStyle);
            }
            int r = 4;
            for (StatsCategoryRow row : lastCategoryRows) {
                Row xr = sheet.createRow(r++);
                xr.createCell(0).setCellValue(row.name);
                xr.createCell(1).setCellValue(row.percent);
                xr.createCell(2).setCellValue(row.amount.doubleValue());
            }
            // autoSizeColumn() needs java.awt.font.FontRenderContext for text
            // measurement — AWT isn't available on Android, so it crashes with
            // NoClassDefFoundError. Set reasonable fixed widths instead
            // (POI widths are in 1/256 of a character width).
            sheet.setColumnWidth(0, 28 * 256); // Category
            sheet.setColumnWidth(1, 10 * 256); // %
            sheet.setColumnWidth(2, 16 * 256); // Amount
            wb.write(out);
        }
    }
}