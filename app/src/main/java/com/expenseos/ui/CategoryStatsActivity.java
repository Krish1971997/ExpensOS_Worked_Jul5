package com.expenseos.ui;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Category drill-down (Image 2): subcategory split (only shown when the
 * category actually has more than one subcategory in use this month —
 * otherwise just the running total already shown above is enough), a
 * monthly trend line across all months for this category, and the list of
 * transactions in this category for the selected month, tap → edit.
 */
public class CategoryStatsActivity extends AppCompatActivity {

    private int bookId, categoryId, year, month;
    private String categoryName;
    private boolean isExpense;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_category_stats);

        bookId = getIntent().getIntExtra("bookId", 0);
        categoryId = getIntent().getIntExtra("categoryId", 0);
        categoryName = getIntent().getStringExtra("categoryName");
        isExpense = getIntent().getBooleanExtra("isExpense", true);
        year = getIntent().getIntExtra("year", 0);
        month = getIntent().getIntExtra("month", 0);

        ((TextView) findViewById(R.id.tvCategoryStatsTitle)).setText(categoryName);
        findViewById(R.id.btnCategoryStatsBack).setOnClickListener(v -> finish());

        TransactionDao dao = new TransactionDao(this);
        String type = isExpense ? "EXPENSE" : "INCOME";

        loadSubcategorySplit(dao, type);
        loadTrendChart(dao);
        loadTransactionList(dao, type);
    }

    // ── Subcategory split — only if this category has 2+ distinct subcategories this month ──
    private void loadSubcategorySplit(TransactionDao dao, String type) {
        List<Map<String, Object>> allSub = dao.subCategoryBreakdownByMonth(type, year, month, bookId);

        List<Map<String, Object>> mine = new ArrayList<>();
        Set<String> distinctSub = new LinkedHashSet<>();
        for (Map<String, Object> r : allSub) {
            if (categoryName.equals(r.get("category"))) {
                mine.add(r);
                distinctSub.add((String) r.get("subcategory"));
            }
        }

        LinearLayout container = findViewById(R.id.llSubcategorySplit);
        container.removeAllViews();

        if (distinctSub.size() <= 1) {
            // Only one (or zero) subcategory in use — showing a "split" would
            // just repeat the category total, so skip it entirely.
            container.setVisibility(View.GONE);
            return;
        }

        container.setVisibility(View.VISIBLE);
        for (Map<String, Object> r : mine) {
            TextView row = new TextView(this);
            row.setPadding(16, 12, 16, 12);
            row.setTextSize(14f);
            String sub = (String) r.get("subcategory");
            BigDecimal total = (BigDecimal) r.get("total");
            row.setText(sub + "        ₹" + total.toPlainString());
            container.addView(row);
        }
    }

    // ── Monthly trend line — this category across all months ──
    private void loadTrendChart(TransactionDao dao) {
        List<Map<String, Object>> trend = dao.categoryMonthlyTrend(categoryId, 8);
        LineChart chart = findViewById(R.id.lineCategoryTrend);

        if (trend.size() < 2) {
            chart.setVisibility(View.GONE);
            return;
        }
        chart.setVisibility(View.VISIBLE);

        List<Entry> entries = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < trend.size(); i++) {
            Map<String, Object> r = trend.get(i);
            labels.add((String) r.get("month"));
            entries.add(new Entry(i, ((BigDecimal) r.get("total")).floatValue()));
        }

        LineDataSet ds = new LineDataSet(entries, categoryName);
        ds.setColor(Color.parseColor("#EF4444"));
        ds.setCircleColor(Color.parseColor("#EF4444"));
        ds.setLineWidth(2f);
        ds.setCircleRadius(4f);
        ds.setDrawValues(false);

        chart.setData(new LineData(ds));
        chart.getAxisRight().setEnabled(false);
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);
        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(1f);
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                int idx = (int) value;
                return idx >= 0 && idx < labels.size() ? labels.get(idx) : "";
            }
        });
        chart.invalidate();
    }

    // ── Transaction list for this category this month — tap → edit ──
    private void loadTransactionList(TransactionDao dao, String type) {
        TransactionFilter f = new TransactionFilter();
        f.setBookId(bookId);
        f.setType(type);
        f.setCategoryIds(List.of(categoryId));
        f.setPageSize(Integer.MAX_VALUE);
        f.setSortBy("date");
        f.setSortDir("desc");

        List<Transaction> txns = dao.findByFilter(f);

        RecyclerView rv = findViewById(R.id.rvCategoryTxns);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new TransactionAdapter(this, txns, null, t -> {
            Intent i = new Intent(this, EntryDetailActivity.class);
            i.putExtra("txnId", t.getId());
            startActivity(i);
        }));
    }
}
