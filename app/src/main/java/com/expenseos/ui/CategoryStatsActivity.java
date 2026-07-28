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
import com.expenseos.dao.TransactionDao;
import com.expenseos.model.Transaction;
import com.expenseos.model.TransactionFilter;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.math.BigDecimal;
import java.text.DateFormatSymbols;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CategoryStatsActivity extends AppCompatActivity {

    private int bookId, categoryId;
    private String categoryName;
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
                loadAllDataForSelectedMonth();
            });
        }

        View btnNextMonth = findViewById(R.id.btnNextMonth);
        if (btnNextMonth != null) {
            btnNextMonth.setOnClickListener(v -> {
                currentCal.add(Calendar.MONTH, 1);
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

    private void loadAllDataForSelectedMonth() {
        int year = currentCal.get(Calendar.YEAR);
        int month = currentCal.get(Calendar.MONTH) + 1;

        TextView tvMonthHeader = findViewById(R.id.tvMonthYearHeader);
        if (tvMonthHeader != null) {
            String monthName = new DateFormatSymbols().getMonths()[month - 1];
            tvMonthHeader.setText(monthName.substring(0, 3) + " " + year);
        }

        String type = isExpense ? "EXPENSE" : "INCOME";

        loadSubcategorySplit(type, year, month);
        loadTrendChartWithZeroPadding(year, month);
        loadTransactionList(type, year, month);
    }

    private void loadSubcategorySplit(String type, int year, int month) {
        // Pass 0 to query globally across books for this month
        List<Map<String, Object>> allSub = dao.subCategoryBreakdownByMonth(type, year, month, 0);

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
        List<Map<String, Object>> dbTrend = dao.categoryMonthlyTrend(categoryId, 8);

        Map<String, BigDecimal> dbMap = new HashMap<>();
        if (dbTrend != null) {
            for (Map<String, Object> row : dbTrend) {
                String m = (String) row.get("month");
                BigDecimal t = (BigDecimal) row.get("total");
                if (m != null && t != null) dbMap.put(m, t);
            }
        }

        Map<String, BigDecimal> paddedTrend = new LinkedHashMap<>();
        Calendar cal = (Calendar) currentCal.clone();
        String[] monthShortNames = new DateFormatSymbols().getShortMonths();

        for (int i = 7; i >= 0; i--) {
            Calendar tempCal = (Calendar) cal.clone();
            tempCal.add(Calendar.MONTH, -i);
            String label = monthShortNames[tempCal.get(Calendar.MONTH)];
            BigDecimal value = dbMap.getOrDefault(label, BigDecimal.ZERO);
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

    private void loadTransactionList(String type, int activeYear, int activeMonth) {
        TransactionFilter f = new TransactionFilter();
        f.setBookId(0);
        f.setType(type);
        if (categoryId > 0) {
            f.setCategoryIds(List.of(categoryId));
        }

        f.setPageSize(Integer.MAX_VALUE);
        f.setSortBy("date");
        f.setSortDir("desc");

        List<Transaction> txns = dao.findByFilter(f);
        List<Transaction> monthlyTxns = new ArrayList<>();

        if (txns != null) {
            for (Transaction t : txns) {
                LocalDateTime dt = t.getDateTime();
                if (dt == null) dt = t.getCreatedAt();
                if (dt != null) {
                    if (dt.getYear() == activeYear && dt.getMonthValue() == activeMonth) {
                        monthlyTxns.add(t);
                    }
                }
            }
        }

        RecyclerView rv = findViewById(R.id.rvCategoryTxns);
        if (rv != null) {
            rv.setLayoutManager(new LinearLayoutManager(this));
            rv.setAdapter(new TransactionAdapter(this, monthlyTxns, null, t -> {
                Intent i = new Intent(this, EntryDetailActivity.class);
                i.putExtra("txnId", t.getId());
                startActivity(i);
            }));
        }
    }
}