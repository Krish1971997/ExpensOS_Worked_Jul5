package com.expenseos.ui;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.expenseos.R;
import com.expenseos.dao.TransactionDao;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ReportsActivity extends AppCompatActivity {

    private int bookId;
    private TransactionDao dao;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_reports);

        SharedPreferences prefs = getSharedPreferences("expenseos_prefs", MODE_PRIVATE);
        bookId = prefs.getInt("active_book_id", 0);
        dao = new TransactionDao(this);

        findViewById(R.id.btnBackReports).setOnClickListener(v -> finish());
        loadStats();
        loadBarChart();
        loadPieChart();
    }

    private void loadStats() {
        BigDecimal income = dao.sumByType("INCOME", bookId);
        BigDecimal expense = dao.sumByType("EXPENSE", bookId);
        if (income == null) income = BigDecimal.ZERO;
        if (expense == null) expense = BigDecimal.ZERO;

        BigDecimal balance = income.subtract(expense);

        BigDecimal savings = BigDecimal.ZERO;

        if (income.compareTo(BigDecimal.ZERO) > 0) {
            savings = balance
                    .divide(income, 2, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }

        ((TextView) findViewById(R.id.tvRptIncome)).setText("₹" + String.format("%,.2f", income));
        ((TextView) findViewById(R.id.tvRptExpense)).setText("₹" + String.format("%,.2f", expense));
        ((TextView) findViewById(R.id.tvRptBalance)).setText("₹" + String.format("%,.2f", balance));
        ((TextView) findViewById(R.id.tvRptSavings)).setText(String.format("%.1f%%", savings));
    }

    private void loadBarChart() {
        List<Map<String, Object>> monthly = dao.monthlyTrend(6, bookId);
        List<BarEntry> incEntries = new ArrayList<>();
        List<BarEntry> expEntries = new ArrayList<>();
        List<String> labels = new ArrayList<>();

        for (int i = 0; i < monthly.size(); i++) {
            Map<String, Object> row = monthly.get(i);
            labels.add((String) row.get("month"));
            BigDecimal income = (BigDecimal) row.get("income");
            BigDecimal expense = (BigDecimal) row.get("expense");
            incEntries.add(new BarEntry(i, income != null ? income.floatValue() : 0f));
            expEntries.add(new BarEntry(i, expense != null ? expense.floatValue() : 0f));
        }

        BarDataSet incSet = new BarDataSet(incEntries, "Income");
        incSet.setColor(Color.parseColor("#16A34A"));
        BarDataSet expSet = new BarDataSet(expEntries, "Expense");
        expSet.setColor(Color.parseColor("#DC2626"));

        BarData barData = new BarData(incSet, expSet);
        barData.setBarWidth(0.35f);
        barData.groupBars(0f, 0.1f, 0.05f);

        BarChart chart = findViewById(R.id.barChart);
        chart.setData(barData);
        chart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float v) {
                int idx = (int) v;
                return idx < labels.size() ? labels.get(idx) : "";
            }
        });
        chart.getDescription().setEnabled(false);
        chart.setFitBars(true);
        chart.invalidate();
    }

    private void loadPieChart() {
        List<Map<String, Object>> cats = dao.expenseByCategory(bookId);
        List<PieEntry> entries = new ArrayList<>();
        int[] colors = {
                Color.parseColor("#2563EB"), Color.parseColor("#DC2626"),
                Color.parseColor("#16A34A"), Color.parseColor("#D97706"),
                Color.parseColor("#7C3AED"), Color.parseColor("#0891B2")
        };

        for (Map<String, Object> row : cats) {
            BigDecimal total = (BigDecimal) row.get("total");
            String name = (String) row.get("name");
            entries.add(new PieEntry(total != null ? total.floatValue() : 0f, name));
        }

        PieDataSet ds = new PieDataSet(entries, "Expenses");
        ds.setColors(colors);
        ds.setValueTextSize(11f);
        ds.setValueTextColor(Color.WHITE);

        PieChart pie = findViewById(R.id.pieChart);
        pie.setData(new PieData(ds));
        pie.setUsePercentValues(true);
        pie.getDescription().setEnabled(false);
        pie.setDrawHoleEnabled(true);
        pie.setHoleRadius(42f);
        pie.setTransparentCircleRadius(48f);
        pie.setCenterText("Expenses");
        pie.invalidate();
    }
}