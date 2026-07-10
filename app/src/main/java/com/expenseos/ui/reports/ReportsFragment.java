package com.expenseos.ui.reports;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.expenseos.R;
import com.expenseos.dao.LocalDatabase;
import com.expenseos.util.AppConfig;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.utils.ColorTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReportsFragment extends Fragment {

    private TextView tvTotalIncome, tvTotalExpense, tvNetBalance, tvSavingsRate;
    private PieChart pieExpense, pieIncome;
    private BarChart barMonthly;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_reports, container, false);

        tvTotalIncome = root.findViewById(R.id.tv_report_income);
        tvTotalExpense = root.findViewById(R.id.tv_report_expense);
        tvNetBalance = root.findViewById(R.id.tv_report_balance);
        tvSavingsRate = root.findViewById(R.id.tv_savings_rate);
        pieExpense = root.findViewById(R.id.pie_expense);
        pieIncome = root.findViewById(R.id.pie_income);
        barMonthly = root.findViewById(R.id.bar_monthly);

        loadReports();
        return root;
    }

    private void loadReports() {
        int bookId = AppConfig.get(requireContext()).getActiveBookId();
        SQLiteDatabase db = LocalDatabase.get(requireContext()).getReadableDatabase();

        // Summary
        BigDecimal income = BigDecimal.ZERO, expense = BigDecimal.ZERO;
        Cursor cInc = db.rawQuery(
                "SELECT SUM(amount) FROM transactions WHERE book_id=? AND type='INCOME' AND is_deleted=0",
                new String[]{String.valueOf(bookId)});
        if (cInc.moveToFirst() && !cInc.isNull(0)) income = BigDecimal.valueOf(cInc.getDouble(0));
        cInc.close();

        Cursor cExp = db.rawQuery(
                "SELECT SUM(amount) FROM transactions WHERE book_id=? AND type='EXPENSE' AND is_deleted=0",
                new String[]{String.valueOf(bookId)});
        if (cExp.moveToFirst() && !cExp.isNull(0)) expense = BigDecimal.valueOf(cExp.getDouble(0));
        cExp.close();

        BigDecimal balance = income.subtract(expense);
        double savingsRate = income.compareTo(BigDecimal.ZERO) > 0
                ? balance.divide(income, 4, BigDecimal.ROUND_HALF_UP).doubleValue() * 100 : 0;

        tvTotalIncome.setText("₹" + income.toPlainString());
        tvTotalExpense.setText("₹" + expense.toPlainString());
        tvNetBalance.setText("₹" + balance.toPlainString());
        tvSavingsRate.setText(String.format("%.1f%%", savingsRate));

        // Expense by category pie
        Map<String, Float> expCats = new LinkedHashMap<>();
        Cursor cExpCat = db.rawQuery(
                "SELECT c.name, SUM(t.amount) FROM transactions t " +
                        "JOIN categories c ON t.category_id = c.id " +
                        "WHERE t.book_id=? AND t.type='EXPENSE' AND t.is_deleted=0 " +
                        "GROUP BY c.name",
                new String[]{String.valueOf(bookId)});
        while (cExpCat.moveToNext()) expCats.put(cExpCat.getString(0), cExpCat.getFloat(1));
        cExpCat.close();
        setupPie(pieExpense, expCats, "Expense by Category");

        // Income by category pie
        Map<String, Float> incCats = new LinkedHashMap<>();
        Cursor cIncCat = db.rawQuery(
                "SELECT c.name, SUM(t.amount) FROM transactions t " +
                        "JOIN categories c ON t.category_id = c.id " +
                        "WHERE t.book_id=? AND t.type='INCOME' AND t.is_deleted=0 " +
                        "GROUP BY c.name",
                new String[]{String.valueOf(bookId)});
        while (cIncCat.moveToNext()) incCats.put(cIncCat.getString(0), cIncCat.getFloat(1));
        cIncCat.close();
        setupPie(pieIncome, incCats, "Income by Category");

        // Monthly bar chart
        setupBarChart(db, bookId);
    }

    private void setupPie(PieChart chart, Map<String, Float> data, String label) {
        List<PieEntry> entries = new ArrayList<>();
        for (Map.Entry<String, Float> e : data.entrySet()) {
            entries.add(new PieEntry(e.getValue(), e.getKey()));
        }
        if (entries.isEmpty()) entries.add(new PieEntry(1f, "No data"));

        PieDataSet ds = new PieDataSet(entries, label);
        ds.setColors(ColorTemplate.MATERIAL_COLORS);
        ds.setValueTextSize(12f);
        ds.setValueTextColor(Color.WHITE);

        chart.setData(new PieData(ds));
        chart.setHoleRadius(40f);
        chart.setTransparentCircleRadius(45f);
        chart.getDescription().setEnabled(false);
        chart.getLegend().setOrientation(Legend.LegendOrientation.VERTICAL);
        chart.getLegend().setHorizontalAlignment(Legend.LegendHorizontalAlignment.RIGHT);
        chart.animateY(800);
        chart.invalidate();
    }

    private void setupBarChart(SQLiteDatabase db, int bookId) {
        // Group by month (last 6 months)
        Cursor c = db.rawQuery(
                "SELECT strftime('%Y-%m', txn_datetime) as month, type, SUM(amount) " +
                        "FROM transactions WHERE book_id=? AND is_deleted=0 " +
                        "GROUP BY month, type ORDER BY month DESC LIMIT 12",
                new String[]{String.valueOf(bookId)});

        Map<String, float[]> monthly = new LinkedHashMap<>(); // [income, expense]
        while (c.moveToNext()) {
            String month = c.getString(0);
            String type = c.getString(1);
            float amt = c.getFloat(2);
            monthly.putIfAbsent(month, new float[]{0f, 0f});
            if ("INCOME".equals(type)) monthly.get(month)[0] = amt;
            else monthly.get(month)[1] = amt;
        }
        c.close();

        List<BarEntry> incEntries = new ArrayList<>(), expEntries = new ArrayList<>();
        List<String> labels = new ArrayList<>(monthly.keySet());
        for (int i = 0; i < labels.size(); i++) {
            float[] vals = monthly.get(labels.get(i));
            incEntries.add(new BarEntry(i, vals[0]));
            expEntries.add(new BarEntry(i, vals[1]));
        }

        BarDataSet dsInc = new BarDataSet(incEntries, "Income");
        dsInc.setColor(Color.parseColor("#4CAF50"));
        BarDataSet dsExp = new BarDataSet(expEntries, "Expense");
        dsExp.setColor(Color.parseColor("#F44336"));

        BarData barData = new BarData(dsInc, dsExp);
        barData.setBarWidth(0.35f);
        barData.groupBars(0f, 0.1f, 0.05f);

        barMonthly.setData(barData);
        barMonthly.getDescription().setEnabled(false);
        barMonthly.getXAxis().setValueFormatter(new com.github.mikephil.charting.formatter.IndexAxisValueFormatter(labels));
        barMonthly.getXAxis().setGranularity(1f);
        barMonthly.animateY(800);
        barMonthly.invalidate();
    }
}
