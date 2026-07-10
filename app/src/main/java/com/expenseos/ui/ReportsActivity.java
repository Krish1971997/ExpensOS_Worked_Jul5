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

import java.util.ArrayList;
import java.util.List;

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
        double income = dao.sumByType("INCOME", bookId);
        double expense = dao.sumByType("EXPENSE", bookId);
        double balance = income - expense;
        double savings = income > 0 ? (balance / income) * 100 : 0;

        ((TextView) findViewById(R.id.tvRptIncome)).setText("₹" + String.format("%,.2f", income));
        ((TextView) findViewById(R.id.tvRptExpense)).setText("₹" + String.format("%,.2f", expense));
        ((TextView) findViewById(R.id.tvRptBalance)).setText("₹" + String.format("%,.2f", balance));
        ((TextView) findViewById(R.id.tvRptSavings)).setText(String.format("%.1f%%", savings));
    }

    private void loadBarChart() {
        List<String[]> monthly = dao.monthlyTrend(6, bookId);
        List<BarEntry> incEntries = new ArrayList<>();
        List<BarEntry> expEntries = new ArrayList<>();
        List<String> labels = new ArrayList<>();

        for (int i = 0; i < monthly.size(); i++) {
            String[] row = monthly.get(i);
            labels.add(row[0]);
            incEntries.add(new BarEntry(i, Float.parseFloat(row[1])));
            expEntries.add(new BarEntry(i, Float.parseFloat(row[2])));
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
        List<String[]> cats = dao.expenseByCategory(bookId);
        List<PieEntry> entries = new ArrayList<>();
        int[] colors = {
                Color.parseColor("#2563EB"), Color.parseColor("#DC2626"),
                Color.parseColor("#16A34A"), Color.parseColor("#D97706"),
                Color.parseColor("#7C3AED"), Color.parseColor("#0891B2")
        };

        for (String[] row : cats)
            entries.add(new PieEntry(Float.parseFloat(row[1]), row[0]));

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