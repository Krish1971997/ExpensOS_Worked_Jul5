package com.expenseos.ui.reports;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.github.mikephil.charting.utils.ColorTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reports tab — mobile port of ReportServlet.java / reports.jsp.
 * Shows: all-time summary, a month picker (mirrors the web app's month-bar),
 * selected-month summary, last-6-months trend, and expense/income category
 * pies for the selected month.
 */
public class ReportsFragment extends Fragment {

    private TextView tvTotalIncome, tvTotalExpense, tvNetBalance, tvSavingsRate;
    private TextView tvMonthIncome, tvMonthExpense, tvMonthNet, tvMonthCount;
    private Spinner spinnerMonth, spinnerYear;
    private ImageButton btnPrevMonth, btnNextMonth;
    private PieChart pieExpense, pieIncome;
    private BarChart barMonthly;

    private int bookId;
    private SQLiteDatabase db;

    private int selYear, selMonth; // 1-based month, mirrors ReportServlet's selYear/selMonth
    private static final String[] MONTH_NAMES = {
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    };

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_reports, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View root, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(root, savedInstanceState);

        tvTotalIncome = root.findViewById(R.id.tv_report_income);
        tvTotalExpense = root.findViewById(R.id.tv_report_expense);
        tvNetBalance = root.findViewById(R.id.tv_report_balance);
        tvSavingsRate = root.findViewById(R.id.tv_savings_rate);

        tvMonthIncome = root.findViewById(R.id.tv_month_income);
        tvMonthExpense = root.findViewById(R.id.tv_month_expense);
        tvMonthNet = root.findViewById(R.id.tv_month_net);
        tvMonthCount = root.findViewById(R.id.tv_month_count);

        spinnerMonth = root.findViewById(R.id.spinner_month);
        spinnerYear = root.findViewById(R.id.spinner_year);
        btnPrevMonth = root.findViewById(R.id.btn_prev_month);
        btnNextMonth = root.findViewById(R.id.btn_next_month);

        pieExpense = root.findViewById(R.id.pie_expense);
        pieIncome = root.findViewById(R.id.pie_income);
        barMonthly = root.findViewById(R.id.bar_monthly);

        bookId = AppConfig.get(requireContext()).getActiveBookId();
        db = LocalDatabase.get(requireContext()).getReadableDatabase();

        // Default to the most recent month that actually has transactions —
        // using the device's real "today" here would leave the Selected
        // Month card blank whenever the demo/test data doesn't reach the
        // current real-world month.
        int[] latest = getLatestMonthWithData();
        selYear = latest[0];
        selMonth = latest[1];

        setupMonthSelector();
        loadAllTimeSummary();
        loadMonthlyTrendChart();
        refreshSelectedMonth();
    }

    // ── Finds latest year/month with a transaction, falls back to the
    //    real calendar month if the active book has no data yet ──────────
    private int[] getLatestMonthWithData() {
        Cursor c = db.rawQuery(
                "SELECT strftime('%Y', txn_datetime), strftime('%m', txn_datetime) " +
                        "FROM transactions WHERE book_id=? AND is_deleted=0 " +
                        "ORDER BY txn_datetime DESC LIMIT 1",
                new String[]{String.valueOf(bookId)});
        int y, m;
        if (c.moveToFirst()) {
            y = Integer.parseInt(c.getString(0));
            m = Integer.parseInt(c.getString(1));
        } else {
            Calendar now = Calendar.getInstance();
            y = now.get(Calendar.YEAR);
            m = now.get(Calendar.MONTH) + 1; // Calendar month is 0-based
        }
        c.close();
        return new int[]{y, m};
    }

    // ── Month selector (mirrors reports.jsp's month-bar / prev-next links) ──
    private void setupMonthSelector() {
        // Custom item layouts with explicit dark text — the default
        // android.R.layout.simple_spinner_item inherits the app theme's
        // textColorPrimary, which renders white-on-white on a light card
        // and looks like the picker has no options / doesn't respond.
        ArrayAdapter<String> monthAdapter = new ArrayAdapter<>(requireContext(),
                R.layout.spinner_item_dark, MONTH_NAMES);
        monthAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_dark);
        spinnerMonth.setAdapter(monthAdapter);
        spinnerMonth.setSelection(selMonth - 1);

        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        List<String> years = new ArrayList<>();
        for (int y = Math.min(currentYear, selYear) - 3; y <= Math.max(currentYear, selYear) + 1; y++)
            years.add(String.valueOf(y));
        ArrayAdapter<String> yearAdapter = new ArrayAdapter<>(requireContext(),
                R.layout.spinner_item_dark, years);
        yearAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_dark);
        spinnerYear.setAdapter(yearAdapter);
        spinnerYear.setSelection(years.indexOf(String.valueOf(selYear)));

        spinnerMonth.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                selMonth = pos + 1;
                refreshSelectedMonth();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        spinnerYear.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                selYear = Integer.parseInt(years.get(pos));
                refreshSelectedMonth();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        btnPrevMonth.setOnClickListener(v -> {
            selMonth--;
            if (selMonth < 1) {
                selMonth = 12;
                selYear--;
            }
            syncSpinnersToSelection();
            refreshSelectedMonth();
        });

        btnNextMonth.setOnClickListener(v -> {
            selMonth++;
            if (selMonth > 12) {
                selMonth = 1;
                selYear++;
            }
            syncSpinnersToSelection();
            refreshSelectedMonth();
        });
    }

    private void syncSpinnersToSelection() {
        spinnerMonth.setSelection(selMonth - 1);
        ArrayAdapter adapter = (ArrayAdapter) spinnerYear.getAdapter();
        int pos = adapter.getPosition(String.valueOf(selYear));
        if (pos >= 0) spinnerYear.setSelection(pos);
    }

    private void refreshSelectedMonth() {
        loadMonthSummary();
        loadCategoryPies();
    }

    // ── All-time summary (ReportServlet: dao.sumByType) ──────────────────
    private void loadAllTimeSummary() {
        BigDecimal income = sumByType("INCOME", null, null);
        BigDecimal expense = sumByType("EXPENSE", null, null);
        BigDecimal balance = income.subtract(expense);
        double savingsRate = income.compareTo(BigDecimal.ZERO) > 0
                ? balance.divide(income, 4, java.math.RoundingMode.HALF_UP).doubleValue() * 100 : 0;

        tvTotalIncome.setText("₹" + income.toPlainString());
        tvTotalExpense.setText("₹" + expense.toPlainString());
        tvNetBalance.setText("₹" + balance.toPlainString());
        tvSavingsRate.setText(String.format(Locale.getDefault(), "%.1f%%", savingsRate));
    }

    // ── Selected month summary (ReportServlet: dao.monthSummary) ─────────
    private void loadMonthSummary() {
        String ys = String.valueOf(selYear);
        String ms = String.format(Locale.US, "%02d", selMonth);

        BigDecimal income = BigDecimal.ZERO, expense = BigDecimal.ZERO;
        int count = 0;

        Cursor c = db.rawQuery(
                "SELECT type, SUM(amount), COUNT(*) FROM transactions " +
                        "WHERE book_id=? AND is_deleted=0 " +
                        "AND strftime('%Y', txn_datetime)=? AND strftime('%m', txn_datetime)=? " +
                        "GROUP BY type",
                new String[]{String.valueOf(bookId), ys, ms});
        while (c.moveToNext()) {
            String type = c.getString(0);
            BigDecimal amt = BigDecimal.valueOf(c.getDouble(1));
            count += c.getInt(2);
            if ("INCOME".equals(type)) income = amt;
            else expense = amt;
        }
        c.close();

        BigDecimal net = income.subtract(expense);

        tvMonthIncome.setText("₹" + income.toPlainString());
        tvMonthExpense.setText("₹" + expense.toPlainString());
        tvMonthNet.setText("₹" + net.toPlainString());
        tvMonthCount.setText(String.valueOf(count));
    }

    // ── Last-6-months trend bar chart (ReportServlet: dao.monthlyTrend) ──
    private void loadMonthlyTrendChart() {
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
        dsInc.setColor(Color.parseColor("#16A34A"));
        BarDataSet dsExp = new BarDataSet(expEntries, "Expense");
        dsExp.setColor(Color.parseColor("#DC2626"));

        BarData barData = new BarData(dsInc, dsExp);
        barData.setBarWidth(0.35f);
        barData.groupBars(0f, 0.1f, 0.05f);

        barMonthly.setData(barData);
        barMonthly.getDescription().setEnabled(false);
        barMonthly.getXAxis().setValueFormatter(new IndexAxisValueFormatter(labels));
        barMonthly.getXAxis().setGranularity(1f);
        barMonthly.animateY(600);
        barMonthly.invalidate();
    }

    // ── Category pies, scoped to the selected month
    //    (ReportServlet: dao.categoryBreakdownByMonth) ─────────────────────
    private void loadCategoryPies() {
        String ys = String.valueOf(selYear);
        String ms = String.format(Locale.US, "%02d", selMonth);

        setupPie(pieExpense, categoryBreakdown("EXPENSE", ys, ms), "Expense");
        setupPie(pieIncome, categoryBreakdown("INCOME", ys, ms), "Income");
    }

    private Map<String, Float> categoryBreakdown(String type, String year, String month) {
        Map<String, Float> data = new LinkedHashMap<>();
        Cursor c = db.rawQuery(
                "SELECT c.name, SUM(t.amount) FROM transactions t " +
                        "JOIN categories c ON t.category_id = c.id " +
                        "WHERE t.book_id=? AND t.type=? AND t.is_deleted=0 " +
                        "AND strftime('%Y', t.txn_datetime)=? AND strftime('%m', t.txn_datetime)=? " +
                        "GROUP BY c.name",
                new String[]{String.valueOf(bookId), type, year, month});
        while (c.moveToNext()) data.put(c.getString(0), c.getFloat(1));
        c.close();
        return data;
    }

    private void setupPie(PieChart chart, Map<String, Float> data, String label) {
        List<PieEntry> entries = new ArrayList<>();
        for (Map.Entry<String, Float> e : data.entrySet()) {
            entries.add(new PieEntry(e.getValue(), e.getKey()));
        }
        if (entries.isEmpty()) entries.add(new PieEntry(1f, "No data"));

        PieDataSet ds = new PieDataSet(entries, label);
        ds.setColors(ColorTemplate.MATERIAL_COLORS);
        ds.setValueTextSize(11f);
        ds.setValueTextColor(Color.WHITE);

        chart.setData(new PieData(ds));
        chart.setHoleRadius(40f);
        chart.setTransparentCircleRadius(45f);
        chart.getDescription().setEnabled(false);
        chart.getLegend().setOrientation(Legend.LegendOrientation.VERTICAL);
        chart.getLegend().setHorizontalAlignment(Legend.LegendHorizontalAlignment.RIGHT);
        chart.getLegend().setTextSize(9f);
        chart.animateY(600);
        chart.invalidate();
    }

    // ── Small helper mirroring TransactionDao.sumByType, restricted to
    //    an optional year/month so it can double as an all-time query too ──
    private BigDecimal sumByType(String type, String year, String month) {
        StringBuilder sql = new StringBuilder(
                "SELECT SUM(amount) FROM transactions WHERE book_id=? AND type=? AND is_deleted=0");
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(bookId));
        args.add(type);
        if (year != null) {
            sql.append(" AND strftime('%Y', txn_datetime)=?");
            args.add(year);
        }
        if (month != null) {
            sql.append(" AND strftime('%m', txn_datetime)=?");
            args.add(month);
        }
        Cursor c = db.rawQuery(sql.toString(), args.toArray(new String[0]));
        BigDecimal result = BigDecimal.ZERO;
        if (c.moveToFirst() && !c.isNull(0)) result = BigDecimal.valueOf(c.getDouble(0));
        c.close();
        return result;
    }
}
