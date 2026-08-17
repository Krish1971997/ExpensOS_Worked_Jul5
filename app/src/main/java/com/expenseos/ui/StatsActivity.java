package com.expenseos.ui;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.adapter.StatsCategoryAdapter;
import com.expenseos.dao.CashBookDao;
import com.expenseos.dao.TransactionDao;
import com.expenseos.model.CashBook;
import com.expenseos.util.MonthBookResolver;
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private PieChart pieChart;
    private RecyclerView rvCategories;
    private TextView tabIncome, tabExpense;
    private View btnPrevMonth, btnNextMonth;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_stats);

        findViewById(R.id.btnStatsBack).setOnClickListener(v -> finish());

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
        rvCategories.setLayoutManager(new LinearLayoutManager(this));

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

        refresh();
    }

    private void refresh() {
        tabExpense.setTextColor(showExpense ? Color.parseColor("#DC2626") : Color.GRAY);
        tabIncome.setTextColor(!showExpense ? Color.parseColor("#16A34A") : Color.GRAY);

        CashBook book;
        if (directBook != null) {
            book = directBook;
            tvMonth.setText(book.getName());
        } else {
            tvMonth.setText(currentMonth.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
                    + " " + currentMonth.getYear()
                    + (seriesSuffix.isEmpty() ? "" : " · " + seriesSuffix));
            book = MonthBookResolver.findBookForMonth(this, currentMonth, seriesSuffix);
        }

        if (book == null) {
            pieChart.setVisibility(View.GONE);
            rvCategories.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("No cash book found for " + MonthBookResolver.expectedName(currentMonth, seriesSuffix));
            tvTotalBalance.setText("₹0.00");
            return;
        }

        TransactionDao dao = new TransactionDao(this);
        List<Map<String, Object>> rows = showExpense
                ? dao.categoryBreakdownWithId("EXPENSE", book.getId())
                : dao.categoryBreakdownWithId("INCOME", book.getId());

        // Sort descending by amount — screenshot shows highest first
        rows.sort((a, b) -> ((BigDecimal) b.get("total")).compareTo((BigDecimal) a.get("total")));

        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) total = total.add((BigDecimal) r.get("total"));
        tvTotalBalance.setText("₹" + total.toPlainString());

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

        // Pie
        List<PieEntry> entries = new ArrayList<>();
        int[] colors = {0xFFF59E0B, 0xFF16A34A, 0xFFDC2626, 0xFF2563EB, 0xFF7C3AED, 0xFF0891B2};
        for (Map<String, Object> r : rows) {
            entries.add(new PieEntry(((BigDecimal) r.get("total")).floatValue(), (String) r.get("name")));
        }
        PieDataSet ds = new PieDataSet(entries, "");
        ds.setColors(colors, 255);
        ds.setValueTextSize(11f);
        ds.setValueTextColor(Color.WHITE);
        pieChart.setData(new PieData(ds));
        pieChart.setUsePercentValues(true);
        pieChart.getDescription().setEnabled(false);
        pieChart.setDrawHoleEnabled(true);
        pieChart.invalidate();

        // Category list — tap → CategoryStatsActivity drill-down
        final CashBook resolvedBook = book;
        rvCategories.setAdapter(new StatsCategoryAdapter(rows, total, (categoryId, categoryName) -> {
            Intent i = new Intent(this, CategoryStatsActivity.class);
            i.putExtra("bookId", resolvedBook.getId());
            i.putExtra("categoryId", categoryId);
            i.putExtra("categoryName", categoryName);
            i.putExtra("isExpense", showExpense);
            i.putExtra("year", currentMonth.getYear());
            i.putExtra("month", currentMonth.getMonthValue());
            startActivity(i);
        }));
    }
}