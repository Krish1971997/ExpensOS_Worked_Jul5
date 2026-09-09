package com.expenseos.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.dao.BudgetDao;
import com.expenseos.dao.CategoryDao;
import com.expenseos.model.Budget;
import com.expenseos.model.BudgetCategory;
import com.expenseos.model.Category;
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
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Android port of budget.jsp + BudgetServlet — Budget tab (set/track a
 * monthly overall + per-category spending limit) and Trend tab (monthly
 * income/expense + category-spend charts, reusing the same MPAndroidChart
 * library ReportsActivity already uses).
 */
public class BudgetActivity extends AppCompatActivity {

    private int bookId;
    private BudgetDao budgetDao;
    private CategoryDao catDao;

    private int selYear, selMonth;
    private Budget currentBudget;

    private TextView tabBudget, tabTrend;
    private View panelBudget, panelTrend;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_budget);

        bookId = getIntent().getIntExtra("bookId", 0);
        if (bookId <= 0) {
            Toast.makeText(this, "No active cashbook found.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        budgetDao = new BudgetDao(this);
        catDao = new CategoryDao(this);

        java.time.LocalDate now = java.time.LocalDate.now();
        selYear = now.getYear();
        selMonth = now.getMonthValue();

        findViewById(R.id.btnBudgetBack).setOnClickListener(v -> finish());

        // Configure Budget button navigation — this now opens the ONE shared
        // template used by every cash book, so no bookId/year/month to pass.
        findViewById(R.id.btnConfigureBudget).setOnClickListener(v ->
                startActivity(new Intent(this, BudgetConfigActivity.class)));

        bindTabs();
        setupMonthNav();
        setupOverallLimitSave();
        setupAddCategoryBudget();
        setupTrendMonthsSpinner();

        switchTab(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Automatically refreshes the current tab data when coming back from BudgetConfigActivity
        if (panelBudget != null && panelBudget.getVisibility() == View.VISIBLE) {
            loadBudget();
        } else if (panelTrend != null && panelTrend.getVisibility() == View.VISIBLE) {
            loadTrend(currentTrendMonths());
        }
    }

    // ── Tabs ────────────────────────────────────────────────
    private void bindTabs() {
        tabBudget = findViewById(R.id.tabBudget);
        tabTrend = findViewById(R.id.tabTrend);
        panelBudget = findViewById(R.id.panelBudget);
        panelTrend = findViewById(R.id.panelTrend);
        tabBudget.setOnClickListener(v -> switchTab(0));
        tabTrend.setOnClickListener(v -> switchTab(1));
    }

    private void switchTab(int tab) {
        panelBudget.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        panelTrend.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);

        int active = getColor(R.color.primary);
        int inactive = getColor(R.color.text_muted);
        tabBudget.setTextColor(tab == 0 ? active : inactive);
        tabBudget.setTypeface(null, tab == 0 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        tabTrend.setTextColor(tab == 1 ? active : inactive);
        tabTrend.setTypeface(null, tab == 1 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);

        if (tab == 0) loadBudget();
        if (tab == 1) loadTrend(currentTrendMonths());
    }

    // ══════════════════════════════════════════════════════
    // BUDGET TAB
    // ══════════════════════════════════════════════════════

    private void setupMonthNav() {
        findViewById(R.id.btnPrevMonth).setOnClickListener(v -> {
            shiftMonth(-1);
            loadBudget();
        });
        findViewById(R.id.btnNextMonth).setOnClickListener(v -> {
            shiftMonth(1);
            loadBudget();
        });
    }

    private void shiftMonth(int delta) {
        YearMonth ym = YearMonth.of(selYear, selMonth).plusMonths(delta);
        selYear = ym.getYear();
        selMonth = ym.getMonthValue();
    }

    private void loadBudget() {
        ((TextView) findViewById(R.id.tvBudgetMonth)).setText(
                java.time.Month.of(selMonth).getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + selYear);

        currentBudget = budgetDao.findByMonth(bookId, selYear, selMonth);

        // Self-heal: if categories were added to the shared template AFTER
        // this month's budget was first created, the scheduler's
        // "already exists — skip" rule means it never went back to add
        // them. Fill in anything missing every time this screen loads.
        if (currentBudget != null) {
            com.expenseos.dao.BudgetTemplateDao templateDao = new com.expenseos.dao.BudgetTemplateDao(this);
            if (templateDao.hasGlobalTemplate()) {
                budgetDao.syncMissingCategoriesFromTemplate(currentBudget.getId(), templateDao.loadGlobalAmounts());
                currentBudget = budgetDao.findByMonth(bookId, selYear, selMonth); // reload with synced rows
            }
        }

        EditText etOverallLimit = findViewById(R.id.etOverallLimit);
        View summaryCard = findViewById(R.id.summaryCard);
        View noBudgetMsg = findViewById(R.id.noBudgetMsg);
        View categorySection = findViewById(R.id.categorySection);

        if (currentBudget == null) {
            etOverallLimit.setText("");
            summaryCard.setVisibility(View.GONE);
            categorySection.setVisibility(View.GONE);
            noBudgetMsg.setVisibility(View.VISIBLE);
        } else {
            etOverallLimit.setText(currentBudget.getOverallLimit().stripTrailingZeros().toPlainString());
            summaryCard.setVisibility(View.VISIBLE);
            categorySection.setVisibility(View.VISIBLE);
            noBudgetMsg.setVisibility(View.GONE);
            bindSummary(currentBudget);
            bindCategoryList(currentBudget);
        }

        loadPastBudgets();
    }

    private void bindSummary(Budget b) {
        ((TextView) findViewById(R.id.tvOverallLimitDisplay)).setText("₹" + b.getOverallLimit().toPlainString());
        BigDecimal spent = b.getTotalSpent() != null ? b.getTotalSpent() : BigDecimal.ZERO;
        ((TextView) findViewById(R.id.tvSpent)).setText("₹" + spent.toPlainString());

        BigDecimal remaining = b.getRemainingAmount() != null ? b.getRemainingAmount() : b.getOverallLimit();
        TextView tvRemaining = findViewById(R.id.tvRemaining);
        tvRemaining.setText("₹" + remaining.toPlainString());
        tvRemaining.setTextColor(getColor(b.isRemainingPositive() ? R.color.green : R.color.red));

        int pct = b.getUsedPct();
        ((TextView) findViewById(R.id.tvUsedPct)).setText(pct + "% used");

        ProgressBar pb = findViewById(R.id.pbOverall);
        pb.setProgress(pct);
        int barColor = pct >= 100 ? R.color.red : pct >= 80 ? R.color.amber : R.color.green;
        pb.setProgressTintList(ColorStateList.valueOf(getColor(barColor)));
    }

    private void bindCategoryList(Budget b) {
        RecyclerView rv = findViewById(R.id.rvBudgetCategories);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new BudgetCategoryAdapter(b.getCategories()));
    }

    private void setupOverallLimitSave() {
        findViewById(R.id.btnSaveOverallLimit).setOnClickListener(v -> {
            String limitStr = ((EditText) findViewById(R.id.etOverallLimit)).getText().toString().trim();
            if (limitStr.isEmpty()) {
                Toast.makeText(this, "Enter a limit amount", Toast.LENGTH_SHORT).show();
                return;
            }
            Budget b = new Budget();
            b.setBookId(bookId);
            b.setYear(selYear);
            b.setMonth(selMonth);
            b.setOverallLimit(new BigDecimal(limitStr));
            budgetDao.upsert(b);
            Toast.makeText(this, "Budget saved!", Toast.LENGTH_SHORT).show();
            loadBudget();
        });
    }

    // ── Add category budget ──────────────────────────────────
    private void setupAddCategoryBudget() {
        findViewById(R.id.btnAddCategoryBudget).setOnClickListener(v -> {
            if (currentBudget == null) {
                Toast.makeText(this, "Set the overall budget first", Toast.LENGTH_SHORT).show();
                return;
            }
            showAddCategoryBudgetDialog();
        });
    }

    private void showAddCategoryBudgetDialog() {
        List<Category> allExpenseCats = catDao.findByType("EXPENSE", bookId);
        List<Integer> alreadyBudgeted = new ArrayList<>();
        for (BudgetCategory bc : currentBudget.getCategories())
            alreadyBudgeted.add(bc.getCategoryId());

        List<Category> available = new ArrayList<>();
        for (Category c : allExpenseCats)
            if (!alreadyBudgeted.contains(c.getId())) available.add(c);

        if (available.isEmpty()) {
            Toast.makeText(this, "All expense categories already have a budget this month.", Toast.LENGTH_SHORT).show();
            return;
        }

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout form = new android.widget.LinearLayout(this);
        form.setOrientation(android.widget.LinearLayout.VERTICAL);
        form.setPadding(pad, pad, pad, pad);

        TextView lbl1 = new TextView(this);
        lbl1.setText("Category");
        lbl1.setTextSize(11);
        form.addView(lbl1);

        Spinner spCat = new Spinner(this);
        ArrayAdapter<Category> catAdp = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, available);
        catAdp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCat.setAdapter(catAdp);
        form.addView(spCat);

        TextView lbl2 = new TextView(this);
        lbl2.setText("Limit");
        lbl2.setTextSize(11);
        lbl2.setPadding(0, pad, 0, 0);
        form.addView(lbl2);

        EditText etLimit = new EditText(this);
        etLimit.setHint("e.g. 3000");
        etLimit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        form.addView(etLimit);

        TextView lbl3 = new TextView(this);
        lbl3.setText("Alert at %");
        lbl3.setTextSize(11);
        lbl3.setPadding(0, pad, 0, 0);
        form.addView(lbl3);

        EditText etAlert = new EditText(this);
        etAlert.setText("80");
        etAlert.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        form.addView(etAlert);

        new AlertDialog.Builder(this)
                .setTitle("Add Category Budget")
                .setView(form)
                .setPositiveButton("Save", (d, w) -> {
                    String limitStr = etLimit.getText().toString().trim();
                    if (limitStr.isEmpty() || spCat.getSelectedItem() == null) return;

                    BudgetCategory bc = new BudgetCategory();
                    bc.setBudgetId(currentBudget.getId());
                    bc.setCategoryId(((Category) spCat.getSelectedItem()).getId());
                    bc.setCatLimit(new BigDecimal(limitStr));
                    int alertPct = 80;
                    try {
                        alertPct = Integer.parseInt(etAlert.getText().toString().trim());
                    } catch (Exception ignored) {
                    }
                    bc.setAlertPct(alertPct);

                    budgetDao.upsertCategory(bc);
                    loadBudget();
                    Toast.makeText(this, "Category budget added!", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    class BudgetCategoryAdapter extends RecyclerView.Adapter<BudgetCategoryAdapter.VH> {
        private final List<BudgetCategory> list;

        BudgetCategoryAdapter(List<BudgetCategory> list) {
            this.list = list;
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvAlertBadge, tvSpentLimit;
            ProgressBar pb;
            Button btnDelete;

            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tvBcCatName);
                tvAlertBadge = v.findViewById(R.id.tvBcAlertBadge);
                tvSpentLimit = v.findViewById(R.id.tvBcSpentLimit);
                pb = v.findViewById(R.id.pbBcProgress);
                btnDelete = v.findViewById(R.id.btnBcDelete);
            }
        }

        @Override
        public VH onCreateViewHolder(ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_budget_category, p, false));
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            BudgetCategory bc = list.get(pos);
            h.tvName.setText(bc.getCategoryName());
            h.tvSpentLimit.setText("₹" + bc.getSpentSafe().toPlainString() + " of ₹" + bc.getCatLimit().toPlainString());

            int pct = bc.getUsedPct();
            h.pb.setProgress(pct);
            int barColor = bc.isExceeded() ? R.color.red : bc.isAlertTriggered() ? R.color.amber : R.color.green;
            h.pb.setProgressTintList(ColorStateList.valueOf(getColor(barColor)));

            h.tvAlertBadge.setVisibility(bc.isAlertTriggered() ? View.VISIBLE : View.GONE);
            h.tvAlertBadge.setText(bc.isExceeded() ? "⚠ Exceeded" : "⚠ Alert");

            h.btnDelete.setOnClickListener(v ->
                    new AlertDialog.Builder(BudgetActivity.this)
                            .setTitle("Remove Category Budget")
                            .setMessage("Remove the limit for \"" + bc.getCategoryName() + "\"?")
                            .setPositiveButton("Remove", (d, w) -> {
                                budgetDao.deleteCategory(bc.getBudgetId(), bc.getCategoryId());
                                loadBudget();
                            })
                            .setNegativeButton("Cancel", null)
                            .show());
        }

        @Override
        public int getItemCount() {
            return list.size();
        }
    }

    // ── Past budgets ──────────────────────────────────────────
    private void loadPastBudgets() {
        List<Budget> all = budgetDao.listByBook(bookId);
        RecyclerView rv = findViewById(R.id.rvPastBudgets);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new PastBudgetAdapter(all));
    }

    class PastBudgetAdapter extends RecyclerView.Adapter<PastBudgetAdapter.VH> {
        private final List<Budget> list;

        PastBudgetAdapter(List<Budget> list) {
            this.list = list;
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvMonth, tvLimit, tvSpentRemaining;

            VH(View v) {
                super(v);
                tvMonth = v.findViewById(R.id.tvPbMonth);
                tvLimit = v.findViewById(R.id.tvPbLimit);
                tvSpentRemaining = v.findViewById(R.id.tvPbSpentRemaining);
            }
        }

        @Override
        public VH onCreateViewHolder(ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_past_budget, p, false));
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            Budget b = list.get(pos);
            h.tvMonth.setText(b.getMonthName() + " " + b.getYear());
            h.tvLimit.setText("Limit: ₹" + b.getOverallLimit().toPlainString());
            BigDecimal spent = b.getTotalSpent() != null ? b.getTotalSpent() : BigDecimal.ZERO;
            BigDecimal remaining = b.getRemainingAmount() != null ? b.getRemainingAmount() : b.getOverallLimit();
            h.tvSpentRemaining.setText("Spent ₹" + spent.toPlainString() + "  •  Remaining ₹" + remaining.toPlainString());
            h.tvSpentRemaining.setTextColor(getColor(b.isRemainingPositive() ? R.color.text_muted : R.color.red));

            h.itemView.setOnClickListener(v -> {
                selYear = b.getYear();
                selMonth = b.getMonth();
                loadBudget();
            });
        }

        @Override
        public int getItemCount() {
            return list.size();
        }
    }

    // ══════════════════════════════════════════════════════
    // TREND TAB
    // ══════════════════════════════════════════════════════

    private void setupTrendMonthsSpinner() {
        Spinner sp = findViewById(R.id.spTrendMonths);
        ArrayAdapter<String> adp = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"Last 3 months", "Last 6 months", "Last 12 months"});
        adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(adp);
        sp.setSelection(2); // default 12 months, matches web default
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (panelTrend.getVisibility() == View.VISIBLE) loadTrend(monthsForSpinnerPos(pos));
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
    }

    private int currentTrendMonths() {
        Spinner sp = findViewById(R.id.spTrendMonths);
        return monthsForSpinnerPos(sp.getSelectedItemPosition());
    }

    private int monthsForSpinnerPos(int pos) {
        return pos == 0 ? 3 : pos == 1 ? 6 : 12;
    }

    private void loadTrend(int months) {
        loadMonthlyTrendChart(months);
        loadCategoryTrendChart(months);
        loadBudgetVsSpendChart(months);
        loadCategoryStatusList();
        loadAdherenceSummary(months);
    }

    // ── Overall Budget vs Spend, month-by-month ─────────────────────
    private void loadBudgetVsSpendChart(int months) {
        List<Map<String, Object>> expenseData = budgetDao.monthlyTrend(bookId, months);
        List<Map<String, Object>> limitData = budgetDao.budgetLimitTrend(bookId, months);

        // Key both lists by "yr-mo" so months with a budget but no spend
        // (or vice versa) still line up correctly instead of silently misaligning.
        Map<String, BigDecimal> limitByKey = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : limitData) {
            limitByKey.put(row.get("yr") + "-" + row.get("mo"), (BigDecimal) row.get("limit"));
        }

        List<String> labels = new ArrayList<>();
        List<com.github.mikephil.charting.data.BarEntry> budgetEntries = new ArrayList<>();
        List<com.github.mikephil.charting.data.BarEntry> spendEntries = new ArrayList<>();

        for (int i = 0; i < expenseData.size(); i++) {
            Map<String, Object> row = expenseData.get(i);
            String key = row.get("yr") + "-" + row.get("mo");
            BigDecimal limit = limitByKey.getOrDefault(key, BigDecimal.ZERO);
            BigDecimal spend = (BigDecimal) row.get("expense");

            labels.add((String) row.get("label"));
            budgetEntries.add(new com.github.mikephil.charting.data.BarEntry(i, limit.floatValue()));
            spendEntries.add(new com.github.mikephil.charting.data.BarEntry(i, spend.floatValue()));
        }

        com.github.mikephil.charting.data.BarDataSet budgetSet =
                new com.github.mikephil.charting.data.BarDataSet(budgetEntries, "Budget");
        budgetSet.setColor(getColor(R.color.primary));
        com.github.mikephil.charting.data.BarDataSet spendSet =
                new com.github.mikephil.charting.data.BarDataSet(spendEntries, "Spend");
        spendSet.setColor(getColor(R.color.red));

        com.github.mikephil.charting.data.BarData barData =
                new com.github.mikephil.charting.data.BarData(budgetSet, spendSet);
        barData.setBarWidth(0.35f);
        barData.groupBars(0f, 0.15f, 0.02f);

        BarChart chart = findViewById(R.id.barBudgetVsSpend);
        chart.setData(barData);
        chart.getXAxis().setValueFormatter(new com.github.mikephil.charting.formatter.ValueFormatter() {
            @Override
            public String getFormattedValue(float v) {
                int idx = Math.round(v);
                return idx >= 0 && idx < labels.size() ? labels.get(idx) : "";
            }
        });
        chart.getXAxis().setGranularity(1f);
        chart.getXAxis().setGranularityEnabled(true);
        chart.getXAxis().setLabelCount(Math.max(labels.size(), 1), false);
        chart.getXAxis().setAxisMinimum(-0.5f);
        chart.getXAxis().setAxisMaximum(Math.max(labels.size() - 0.5f, 0.5f));
        chart.getDescription().setEnabled(false);
        chart.setFitBars(true);
        chart.invalidate();
    }

    // ── Current month: over/under per category, worst first ─────────
    private void loadCategoryStatusList() {
        LinearLayout container = findViewById(R.id.categoryStatusContainer);
        container.removeAllViews();

        Budget current = budgetDao.currentMonthBudget(bookId);
        if (current == null || current.getCategories().isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No budget set for this month yet.");
            empty.setTextColor(getColor(R.color.text_muted));
            empty.setTextSize(13);
            container.addView(empty);
            return;
        }

        List<BudgetCategory> cats = new ArrayList<>(current.getCategories());
        // Worst first: most over-budget (or closest to it) at the top.
        cats.sort((a, b) -> {
            BigDecimal aOver = a.getSpentSafe().subtract(a.getCatLimit());
            BigDecimal bOver = b.getSpentSafe().subtract(b.getCatLimit());
            return bOver.compareTo(aOver);
        });

        for (BudgetCategory bc : cats) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            int padV = dp(8);
            row.setPadding(0, padV, 0, padV);

            TextView tvName = new TextView(this);
            tvName.setText(bc.getCategoryName());
            tvName.setTextSize(14);
            tvName.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tvName);

            TextView tvStatus = new TextView(this);
            tvStatus.setTextSize(13);
            tvStatus.setTypeface(null, android.graphics.Typeface.BOLD);
            if (bc.isExceeded()) {
                BigDecimal over = bc.getSpentSafe().subtract(bc.getCatLimit());
                tvStatus.setText("🔴 Over by ₹" + over.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
                tvStatus.setTextColor(getColor(R.color.red));
            } else {
                BigDecimal left = bc.getCatLimit().subtract(bc.getSpentSafe());
                tvStatus.setText("🟢 ₹" + left.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " left");
                tvStatus.setTextColor(getColor(R.color.green));
            }
            row.addView(tvStatus);

            container.addView(row);
        }
    }

    // ── "X of Y months within budget" + average spend vs current budget ──
    private void loadAdherenceSummary(int months) {
        List<Map<String, Object>> expenseData = budgetDao.monthlyTrend(bookId, months);
        List<Map<String, Object>> limitData = budgetDao.budgetLimitTrend(bookId, months);

        Map<String, BigDecimal> limitByKey = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : limitData) {
            limitByKey.put(row.get("yr") + "-" + row.get("mo"), (BigDecimal) row.get("limit"));
        }

        int monthsWithBudget = 0, monthsWithinBudget = 0;
        BigDecimal totalSpend = BigDecimal.ZERO;
        int monthsWithSpend = 0;

        for (Map<String, Object> row : expenseData) {
            String key = row.get("yr") + "-" + row.get("mo");
            BigDecimal spend = (BigDecimal) row.get("expense");
            if (spend.compareTo(BigDecimal.ZERO) > 0) {
                totalSpend = totalSpend.add(spend);
                monthsWithSpend++;
            }
            BigDecimal limit = limitByKey.get(key);
            if (limit != null && limit.compareTo(BigDecimal.ZERO) > 0) {
                monthsWithBudget++;
                if (spend.compareTo(limit) <= 0) monthsWithinBudget++;
            }
        }

        TextView tvAdherence = findViewById(R.id.tvAdherenceSummary);
        if (monthsWithBudget == 0) {
            tvAdherence.setText("No budget history yet for this period.");
        } else {
            tvAdherence.setText(monthsWithinBudget + " of " + monthsWithBudget + " months stayed within budget");
        }

        TextView tvAvgVsCurrent = findViewById(R.id.tvAvgSpendVsCurrent);
        Budget current = budgetDao.currentMonthBudget(bookId);
        if (monthsWithSpend > 0 && current != null) {
            BigDecimal avg = totalSpend.divide(BigDecimal.valueOf(monthsWithSpend), 2, java.math.RoundingMode.HALF_UP);
            BigDecimal diff = avg.subtract(current.getOverallLimit());
            String cmp = diff.compareTo(BigDecimal.ZERO) > 0
                    ? "₹" + diff.abs().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " above"
                    : "₹" + diff.abs().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " below";
            tvAvgVsCurrent.setText("Avg monthly spend: ₹" + avg.toPlainString() + " (" + cmp + " current budget of ₹" + current.getOverallLimit().toPlainString() + ")");
        } else {
            tvAvgVsCurrent.setText("");
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void loadMonthlyTrendChart(int months) {
        List<Map<String, Object>> data = budgetDao.monthlyTrend(bookId, months);
        List<BarEntry> incEntries = new ArrayList<>();
        List<BarEntry> expEntries = new ArrayList<>();
        List<String> labels = new ArrayList<>();

        for (int i = 0; i < data.size(); i++) {
            Map<String, Object> row = data.get(i);
            labels.add((String) row.get("label"));
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

        BarChart chart = findViewById(R.id.barMonthlyTrend);
        chart.setData(barData);
        chart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float v) {
                int idx = Math.round(v);
                return idx >= 0 && idx < labels.size() ? labels.get(idx) : "";
            }
        });

        chart.getXAxis().setGranularity(1f);
        chart.getXAxis().setGranularityEnabled(true);
        chart.getXAxis().setLabelCount(Math.max(labels.size(), 1), false);
        chart.getXAxis().setAxisMinimum(-0.5f);
        chart.getXAxis().setAxisMaximum(Math.max(labels.size() - 0.5f, 0.5f));
        chart.getXAxis().setCenterAxisLabels(false);
        chart.getDescription().setEnabled(false);
        chart.setFitBars(true);
        chart.invalidate();
    }

    private void loadCategoryTrendChart(int months) {
        List<Map<String, Object>> data = budgetDao.categoryTrend(bookId, months);

        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (Map<String, Object> row : data) {
            String cat = (String) row.get("category");
            BigDecimal total = (BigDecimal) row.get("total");
            totals.merge(cat, total != null ? total : BigDecimal.ZERO, BigDecimal::add);
        }

        List<PieEntry> entries = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : totals.entrySet())
            entries.add(new PieEntry(e.getValue().floatValue(), e.getKey()));

        int[] colors = {
                Color.parseColor("#2563EB"), Color.parseColor("#DC2626"),
                Color.parseColor("#16A34A"), Color.parseColor("#D97706"),
                Color.parseColor("#7C3AED"), Color.parseColor("#0891B2")
        };

        PieDataSet ds = new PieDataSet(entries, "Spend by category");
        ds.setColors(colors, 255);
        ds.setValueTextSize(11f);
        ds.setValueTextColor(Color.WHITE);

        ds.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float v) {
                return v < 4f ? "" : String.format(Locale.ENGLISH, "%.0f%%", v);
            }
        });

        PieChart pie = findViewById(R.id.pieCategoryTrend);
        pie.setData(new PieData(ds));
        pie.setUsePercentValues(true);
        pie.getDescription().setEnabled(false);
        pie.setDrawHoleEnabled(true);
        pie.setHoleRadius(42f);
        pie.setCenterText("By Category");
        pie.setDrawEntryLabels(false);
        pie.getLegend().setEnabled(true);
        pie.getLegend().setWordWrapEnabled(true);
        pie.invalidate();
    }
}