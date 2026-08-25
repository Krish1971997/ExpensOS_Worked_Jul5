package com.expenseos.ui;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.adapter.SubCategorySplitAdapter;
import com.expenseos.adapter.TransactionAdapter;
import com.expenseos.dao.CategoryDao;
import com.expenseos.dao.TransactionDao;
import com.expenseos.model.CashBook;
import com.expenseos.model.Category;
import com.expenseos.model.Transaction;
import com.expenseos.model.TransactionFilter;
import com.expenseos.util.MonthBookResolver;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.math.BigDecimal;
import java.text.DateFormatSymbols;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CategoryStatsActivity extends AppCompatActivity {

    private int bookId, categoryId;
    private String categoryName;
    private String seriesSuffix; // "" = plain month books; "Credit Card" etc — which series Prev/Next stays within
    private boolean isExpense;
    private Calendar currentCal;
    private TransactionDao dao;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_category_stats);

        bookId = getIntent().getIntExtra("bookId", 0);
        categoryId = getIntent().getIntExtra("categoryId", 0);
        categoryName = getIntent().getStringExtra("categoryName");
        seriesSuffix = getIntent().getStringExtra("seriesSuffix");
        if (seriesSuffix == null) seriesSuffix = "";
        isExpense = getIntent().getBooleanExtra("isExpense", true);

        int year = getIntent().getIntExtra("year", 2026);
        int month = getIntent().getIntExtra("month", 7); // Default July

        currentCal = Calendar.getInstance();
        currentCal.set(Calendar.YEAR, year);
        currentCal.set(Calendar.MONTH, month - 1);

        dao = new TransactionDao(this);

        TextView tvTitle = findViewById(R.id.tvCategoryStatsTitle);
        if (tvTitle != null) {
            tvTitle.setText(categoryName != null ? categoryName : "");
        }

        View btnBack = findViewById(R.id.btnCategoryStatsBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        // Set Month Navigation Click Listeners (< & >)
        View btnPrevMonth = findViewById(R.id.btnPrevMonth);
        if (btnPrevMonth != null) {
            btnPrevMonth.setOnClickListener(v -> {
                currentCal.add(Calendar.MONTH, -1);
                resolveBookAndCategoryForCurrentMonth();
                loadAllDataForSelectedMonth();
            });
        }

        View btnNextMonth = findViewById(R.id.btnNextMonth);
        if (btnNextMonth != null) {
            btnNextMonth.setOnClickListener(v -> {
                currentCal.add(Calendar.MONTH, 1);
                resolveBookAndCategoryForCurrentMonth();
                loadAllDataForSelectedMonth();
            });
        }

        // Direct Month Jump Dialog Click Listener
        TextView tvMonthHeader = findViewById(R.id.tvMonthYearHeader);
        if (tvMonthHeader != null) {
            tvMonthHeader.setOnClickListener(v -> showMonthYearPickerDialog());
        }

        loadAllDataForSelectedMonth();
    }

    // ── Show Direct Month Jump Dialog ──
    private void showMonthYearPickerDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_month_year_picker);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        final int[] selectedYear = {currentCal.get(Calendar.YEAR)};
        TextView tvPickerYear = dialog.findViewById(R.id.tvPickerYear);
        tvPickerYear.setText(String.valueOf(selectedYear[0]));

        ImageView btnPrevYear = dialog.findViewById(R.id.btnPrevYear);
        ImageView btnNextYear = dialog.findViewById(R.id.btnNextYear);

        btnPrevYear.setOnClickListener(v -> {
            selectedYear[0]--;
            tvPickerYear.setText(String.valueOf(selectedYear[0]));
        });

        btnNextYear.setOnClickListener(v -> {
            selectedYear[0]++;
            tvPickerYear.setText(String.valueOf(selectedYear[0]));
        });

        // "THIS MONTH" Jump Button
        TextView tvThisMonth = dialog.findViewById(R.id.tvThisMonth);
        tvThisMonth.setOnClickListener(v -> {
            Calendar now = Calendar.getInstance();
            currentCal.set(Calendar.YEAR, now.get(Calendar.YEAR));
            currentCal.set(Calendar.MONTH, now.get(Calendar.MONTH));
            resolveBookAndCategoryForCurrentMonth();
            loadAllDataForSelectedMonth();
            dialog.dismiss();
        });

        // Close Button
        ImageView btnClose = dialog.findViewById(R.id.btnClosePicker);
        btnClose.setOnClickListener(v -> dialog.dismiss());

        // Fill Month Grid (3 columns, 12 months)
        GridLayout gridMonths = dialog.findViewById(R.id.gridMonths);
        String[] monthsShort = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};

        int currentSelectedMonth = currentCal.get(Calendar.MONTH);

        for (int i = 0; i < 12; i++) {
            TextView tvMonth = new TextView(this);
            tvMonth.setText(monthsShort[i]);
            tvMonth.setTextSize(15f);
            tvMonth.setGravity(Gravity.CENTER);
            tvMonth.setPadding(16, 24, 16, 24);

            // Highlight current selected month
            if (i == currentSelectedMonth && selectedYear[0] == currentCal.get(Calendar.YEAR)) {
                tvMonth.setTextColor(Color.parseColor("#EF4444")); // Red/Orange active color
                tvMonth.setTypeface(null, android.graphics.Typeface.BOLD);
            } else {
                tvMonth.setTextColor(Color.WHITE);
            }

            final int monthIndex = i;
            tvMonth.setOnClickListener(v -> {
                currentCal.set(Calendar.YEAR, selectedYear[0]);
                currentCal.set(Calendar.MONTH, monthIndex);
                resolveBookAndCategoryForCurrentMonth();
                loadAllDataForSelectedMonth();
                dialog.dismiss();
            });


            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            tvMonth.setLayoutParams(params);

            gridMonths.addView(tvMonth);
        }

        dialog.show();
    }

    // Prev/Next changes the calendar month, but bookId points at one
    // specific cashbook (e.g. "September 2026 Credit Card") — re-resolve
    // which book that new month/series actually maps to, and re-find this
    // category's row within THAT book (categories are per-book, so the
    // numeric id can differ book to book even for the same name).
    private void resolveBookAndCategoryForCurrentMonth() {
        java.time.YearMonth ym = java.time.YearMonth.of(currentCal.get(Calendar.YEAR), currentCal.get(Calendar.MONTH) + 1);
        CashBook book = MonthBookResolver.findBookForMonth(this, ym, seriesSuffix);
        if (book == null) {
            bookId = 0;
            categoryId = 0;
            return;
        }
        bookId = book.getId();

        String type = isExpense ? "EXPENSE" : "INCOME";
        categoryId = 0;
        for (Category cat : new CategoryDao(this).findByType(type, bookId)) {
            if (categoryName != null && categoryName.equalsIgnoreCase(cat.getName())) {
                categoryId = cat.getId();
                break;
            }
        }
    }

    private void loadAllDataForSelectedMonth() {
        int year = currentCal.get(Calendar.YEAR);
        int month = currentCal.get(Calendar.MONTH) + 1;

        TextView tvMonthHeader = findViewById(R.id.tvMonthYearHeader);
        if (tvMonthHeader != null) {
            String monthName = new DateFormatSymbols().getMonths()[month - 1];
            tvMonthHeader.setText(monthName.substring(0, 3) + " " + year
                    + (seriesSuffix.isEmpty() ? "" : " · " + seriesSuffix));
        }

        String type = isExpense ? "EXPENSE" : "INCOME";

        if (bookId <= 0 || categoryId <= 0) {
            TextView tvTotalBalance = findViewById(R.id.tvTotalBalance);
            if (tvTotalBalance != null) tvTotalBalance.setText("₹ 0.00");
            TextView tvAllAmount = findViewById(R.id.tvAllAmount);
            if (tvAllAmount != null) tvAllAmount.setText("₹ 0.00");
            View subSection = findViewById(R.id.llSubcategorySection);
            if (subSection != null) subSection.setVisibility(View.GONE);
            RecyclerView rv = findViewById(R.id.rvCategoryTxns);
            if (rv != null)
                rv.setAdapter(new TransactionAdapter(this, new ArrayList<>(), null, null));
            return;
        }

        loadSubcategorySplit(type);
        loadTrendChartWithZeroPadding(year, month);
        loadTransactionList(type);
    }

    private void loadSubcategorySplit(String type) {
        // Scoped to THIS cashbook only (bookId) — NOT additionally filtered
        // by calendar date. Book = period here (e.g. a credit-card
        // statement book can hold both Aug- and Sep-dated entries); the old
        // date filter was silently dropping those and undercounting.
        List<Map<String, Object>> allSub = dao.subCategoryBreakdownByBook(type, bookId);
        List<Map<String, Object>> mine = new ArrayList<>();
        BigDecimal overallCategoryTotal = BigDecimal.ZERO;

        if (allSub != null) {
            for (Map<String, Object> r : allSub) {
                if (categoryName != null && categoryName.equalsIgnoreCase((String) r.get("category"))) {
                    mine.add(r);
                    BigDecimal total = (BigDecimal) r.get("total");
                    if (total != null) {
                        overallCategoryTotal = overallCategoryTotal.add(total);
                    }
                }
            }
        }

        String formattedAmount = String.format("₹ %,.2f", overallCategoryTotal.doubleValue());

        TextView tvTotalBalance = findViewById(R.id.tvTotalBalance);
        if (tvTotalBalance != null) tvTotalBalance.setText(formattedAmount);

        TextView tvAllAmount = findViewById(R.id.tvAllAmount);
        if (tvAllAmount != null) tvAllAmount.setText(formattedAmount);

        View subSection = findViewById(R.id.llSubcategorySection);
        RecyclerView rvSubSplit = findViewById(R.id.rvSubCategorySplit);

        if (mine.isEmpty()) {
            if (subSection != null) subSection.setVisibility(View.GONE);
        } else {
            if (subSection != null) subSection.setVisibility(View.VISIBLE);
            if (rvSubSplit != null) {
                rvSubSplit.setLayoutManager(new LinearLayoutManager(this));
                rvSubSplit.setAdapter(new SubCategorySplitAdapter(this, mine, overallCategoryTotal));
            }
        }
    }

    private void loadTrendChartWithZeroPadding(int activeYear, int activeMonth) {
        // Book-by-book, same "book = period" rule as everywhere else in
        // Stats — NOT a real calendar-date range. For each of the last 8
        // months in this SAME series, resolve which cashbook that was, find
        // this category's row within THAT book, and sum its total — a book
        // with no matching category shows 0.
        Map<String, BigDecimal> paddedTrend = new LinkedHashMap<>();
        Calendar cal = (Calendar) currentCal.clone();
        String[] monthShortNames = new DateFormatSymbols().getShortMonths();
        String type = isExpense ? "EXPENSE" : "INCOME";
        CategoryDao catDao = new CategoryDao(this);

        for (int i = 7; i >= 0; i--) {
            Calendar tempCal = (Calendar) cal.clone();
            tempCal.add(Calendar.MONTH, -i);
            String label = monthShortNames[tempCal.get(Calendar.MONTH)];

            java.time.YearMonth ym = java.time.YearMonth.of(tempCal.get(Calendar.YEAR), tempCal.get(Calendar.MONTH) + 1);
            CashBook book = MonthBookResolver.findBookForMonth(this, ym, seriesSuffix);
            BigDecimal value = BigDecimal.ZERO;
            if (book != null) {
                int catIdForBook = 0;
                for (Category cat : catDao.findByType(type, book.getId())) {
                    if (categoryName != null && categoryName.equalsIgnoreCase(cat.getName())) {
                        catIdForBook = cat.getId();
                        break;
                    }
                }
                if (catIdForBook > 0)
                    value = dao.categoryTotalForBook(type, book.getId(), catIdForBook);
            }
            paddedTrend.put(label, value);
        }

        LineChart chart = findViewById(R.id.lineCategoryTrend);
        if (chart == null) return;

        List<Entry> entries = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        int index = 0;

        for (Map.Entry<String, BigDecimal> entry : paddedTrend.entrySet()) {
            labels.add(entry.getKey());
            entries.add(new Entry(index++, entry.getValue().floatValue()));
        }

//        LineDataSet ds = new LineDataSet(entries, categoryName);
//        ds.setColor(Color.parseColor("#EF4444"));
//        ds.setCircleColor(Color.parseColor("#EF4444"));
//        ds.setLineWidth(2.5f);
//        ds.setCircleRadius(4.5f);
//        ds.setDrawCircleHole(false);
//        ds.setDrawValues(false);
//
//        chart.setData(new LineData(ds));
//        chart.getAxisRight().setEnabled(false);
//        chart.getDescription().setEnabled(false);
//        chart.getLegend().setEnabled(false);
//
//        XAxis xAxis = chart.getXAxis();
//        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
//        xAxis.setGranularity(1f);
//        xAxis.setDrawGridLines(false);
//        xAxis.setValueFormatter(new ValueFormatter() {
//            @Override
//            public String getFormattedValue(float value) {
//                int idx = (int) value;
//                return (idx >= 0 && idx < labels.size()) ? labels.get(idx) : "";
//            }
//        });
//
//        chart.invalidate();

        LineDataSet ds = new LineDataSet(entries, categoryName);
        ds.setColor(Color.parseColor("#EF4444"));
        ds.setCircleColor(Color.parseColor("#EF4444"));
        ds.setLineWidth(2.5f);
        ds.setCircleRadius(4.5f);
        ds.setDrawCircleHole(false);
        ds.setDrawValues(false);

        // --- புதிய மாற்ங்கள் (Marker Enable செய்ய) ---
        ds.setHighlightEnabled(true);             // Click highlight-ஐ ஆன் செய்கிறது
        ds.setDrawHighlightIndicators(true);     // Highlight கோடுகளைக் காட்டுகிறது
        ds.setHighLightColor(Color.parseColor("#FCA5A5")); // கோட்டின் நிறம்

        chart.setData(new LineData(ds));
        chart.getAxisRight().setEnabled(false);
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);

        // MarkerView-ஐ Chart உடன் இணைத்தல்
        CustomMarkerView mv = new CustomMarkerView(this, R.layout.marker_view);
        mv.setChartView(chart);
        chart.setMarker(mv);

        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(1f);
        xAxis.setDrawGridLines(false);
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                int idx = (int) value;
                return (idx >= 0 && idx < labels.size()) ? labels.get(idx) : "";
            }
        });

        chart.invalidate();
    }

    private void loadTransactionList(String type) {
        // bookId + categoryId already uniquely scope this — no extra
        // calendar-date filter (see loadSubcategorySplit()'s note above).
        TransactionFilter f = new TransactionFilter();
        f.setBookId(bookId);
        f.setType(type);
        if (categoryId > 0) {
            f.setCategoryIds(List.of(categoryId));
        }

        f.setPageSize(Integer.MAX_VALUE);
        f.setSortBy("date");
        f.setSortDir("desc");

        List<Transaction> txns = dao.findByFilter(f);

        RecyclerView rv = findViewById(R.id.rvCategoryTxns);
        if (rv != null) {
            rv.setLayoutManager(new LinearLayoutManager(this));
            rv.setAdapter(new TransactionAdapter(this, txns != null ? txns : new ArrayList<>(), null, t -> {
                Intent i = new Intent(this, EntryDetailActivity.class);
                i.putExtra("txnId", t.getId());
                startActivity(i);
            }));
        }
    }

}