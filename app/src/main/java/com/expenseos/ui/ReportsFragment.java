package com.expenseos.ui;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

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

public class ReportsFragment extends Fragment {

    private int bookId;
    private TransactionDao dao;

    @Override
    public View onCreateView(@NonNull LayoutInflater inf, ViewGroup pg, Bundle s) {
        return inf.inflate(R.layout.fragment_reports, pg, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        super.onViewCreated(v, s);
        SharedPreferences prefs = requireContext()
                .getSharedPreferences("expenseos_prefs", android.content.Context.MODE_PRIVATE);
        bookId = prefs.getInt("active_book_id", 0);
        dao = new TransactionDao(requireContext());
        loadStats(v);
        loadBarChart(v);
        loadPieCharts(v);
    }

    private void loadStats(View v) {
        double income = dao.sumByType("INCOME", bookId);
        double expense = dao.sumByType("EXPENSE", bookId);
        double balance = income - expense;
        double savings = income > 0 ? (balance / income) * 100 : 0;

        ((TextView) v.findViewById(R.id.tv_report_income)).setText("₹" + String.format("%,.2f", income));
        ((TextView) v.findViewById(R.id.tv_report_expense)).setText("₹" + String.format("%,.2f", expense));
        ((TextView) v.findViewById(R.id.tv_report_balance)).setText("₹" + String.format("%,.2f", balance));
        ((TextView) v.findViewById(R.id.tv_savings_rate)).setText(String.format("%.1f%%", savings));
    }

    private void loadBarChart(View v) {
        List<String[]> monthly = dao.monthlyTrend(6, bookId);
        List<BarEntry> incE = new ArrayList<>(), expE = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < monthly.size(); i++) {
            String[] row = monthly.get(i);
            labels.add(row[0]);
            incE.add(new BarEntry(i, Float.parseFloat(row[1])));
            expE.add(new BarEntry(i, Float.parseFloat(row[2])));
        }
        BarDataSet incSet = new BarDataSet(incE, "Income");
        incSet.setColor(Color.parseColor("#16A34A"));
        BarDataSet expSet = new BarDataSet(expE, "Expense");
        expSet.setColor(Color.parseColor("#DC2626"));
        BarData barData = new BarData(incSet, expSet);
        barData.setBarWidth(0.35f);
        barData.groupBars(0f, 0.1f, 0.05f);

        BarChart chart = v.findViewById(R.id.bar_monthly);
        chart.setData(barData);
        chart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float val) {
                int idx = (int) val;
                return idx < labels.size() ? labels.get(idx) : "";
            }
        });
        chart.getDescription().setEnabled(false);
        chart.setFitBars(true);
        chart.invalidate();
    }

    private void loadPieCharts(View v) {
        int[] colors = {0xFF2563EB, 0xFFDC2626, 0xFF16A34A, 0xFFD97706, 0xFF7C3AED, 0xFF0891B2};

        // Expense-by-category pie
        List<String[]> expenseCats = dao.expenseByCategory(bookId);
        PieChart expensePie = v.findViewById(R.id.pie_expense);
        bindPieChart(expensePie, expenseCats, colors, "Expenses");

        // Income-by-category pie
        // NOTE: assumes TransactionDao has an incomeByCategory(bookId) method
        // returning rows in the same [categoryName, amount] shape as expenseByCategory.
        // Add it to TransactionDao if it doesn't exist yet.
        List<String[]> incomeCats = dao.incomeByCategory(bookId);
        PieChart incomePie = v.findViewById(R.id.pie_income);
        bindPieChart(incomePie, incomeCats, colors, "Income");
    }

    private void bindPieChart(PieChart pie, List<String[]> rows, int[] colors, String centerLabel) {
        List<PieEntry> entries = new ArrayList<>();
        for (String[] row : rows)
            entries.add(new PieEntry(Float.parseFloat(row[1]), row[0]));

        PieDataSet ds = new PieDataSet(entries, centerLabel);
        ds.setColors(colors, 255);
        ds.setValueTextSize(11f);
        ds.setValueTextColor(Color.WHITE);

        pie.setData(new PieData(ds));
        pie.setUsePercentValues(true);
        pie.getDescription().setEnabled(false);
        pie.setDrawHoleEnabled(true);
        pie.setHoleRadius(42f);
        pie.setCenterText(centerLabel);
        pie.invalidate();
    }
}