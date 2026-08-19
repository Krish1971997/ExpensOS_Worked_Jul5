package com.expenseos.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.expenseos.R;
import com.expenseos.dao.BudgetTemplateDao;
import com.expenseos.dao.CategoryDao;
import com.expenseos.model.Category;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BudgetConfigActivity extends AppCompatActivity {

    private int bookId, year, month;
    private CategoryDao catDao;
    private BudgetTemplateDao templateDao;

    private EditText etTotalBudget;
    private TextView tvAllocated, tvRemaining;
    private LinearLayout rowsContainer;

    private final List<Category> categories = new ArrayList<>();
    private final Map<Integer, EditText> pctFields = new HashMap<>();
    private final Map<Integer, EditText> amtFields = new HashMap<>();
    private boolean suppressSync = false; // guards against %<->amount TextWatcher feedback loops

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_budget_config);

        bookId = getIntent().getIntExtra("bookId", 0);
        year = getIntent().getIntExtra("year", java.time.LocalDate.now().getYear());
        month = getIntent().getIntExtra("month", java.time.LocalDate.now().getMonthValue());
        if (bookId <= 0) {
            finish();
            return;
        }

        catDao = new CategoryDao(this);
        templateDao = new BudgetTemplateDao(this);

        findViewById(R.id.btnConfigBack).setOnClickListener(v -> finish());
        etTotalBudget = findViewById(R.id.etConfigTotalBudget);
        tvAllocated = findViewById(R.id.tvConfigAllocated);
        tvRemaining = findViewById(R.id.tvConfigRemaining);
        rowsContainer = findViewById(R.id.configRowsContainer);
        findViewById(R.id.btnConfigSave).setOnClickListener(v -> saveConfig());

        etTotalBudget.addTextChangedListener(simpleWatcher(this::recalcAllRowsFromTotal));

        loadCategoriesAndBuildTable();
    }

    private void loadCategoriesAndBuildTable() {
        categories.clear();
        categories.addAll(catDao.findByType("EXPENSE", bookId));

        BigDecimal defaultTotal = templateDao.loadDefaultOverallLimit(bookId);
        Map<Integer, BigDecimal> savedPercents = templateDao.loadPercents(bookId);

        // Guard the initial setText — it fires the TextWatcher immediately,
        // but the per-row EditTexts (pctFields/amtFields) don't exist yet at
        // this point, so recalcAllRowsFromTotal() would NPE on a null field.
        suppressSync = true;
        etTotalBudget.setText(defaultTotal != null ? defaultTotal.stripTrailingZeros().toPlainString() : "");
        suppressSync = false;

        rowsContainer.removeAllViews();
        pctFields.clear();
        amtFields.clear();

        for (Category c : categories) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int padV = dp(10), padH = dp(4);
            row.setPadding(padH, padV, padH, padV);

            TextView tvName = new TextView(this);
            tvName.setText(c.getName());
            tvName.setTextSize(14);
            tvName.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tvName);

            EditText etPct = new EditText(this);
            etPct.setHint("%");
            etPct.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            etPct.setLayoutParams(new LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT));
            BigDecimal savedPct = savedPercents.get(c.getId());
            if (savedPct != null) etPct.setText(savedPct.stripTrailingZeros().toPlainString());
            row.addView(etPct);

            EditText etAmt = new EditText(this);
            etAmt.setHint("Amount");
            etAmt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            LinearLayout.LayoutParams amtLp = new LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT);
            amtLp.setMarginStart(dp(8));
            etAmt.setLayoutParams(amtLp);
            row.addView(etAmt);

            pctFields.put(c.getId(), etPct);
            amtFields.put(c.getId(), etAmt);

            etPct.addTextChangedListener(simpleWatcher(() -> onPctChanged(c.getId())));
            etAmt.addTextChangedListener(simpleWatcher(() -> onAmtChanged(c.getId())));

            rowsContainer.addView(row);
        }

        recalcAllRowsFromTotal();
    }

    private BigDecimal totalBudget() {
        String s = etTotalBudget.getText().toString().trim();
        try {
            return s.isEmpty() ? BigDecimal.ZERO : new BigDecimal(s);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    // Amount changed by hand -> recompute that row's % against the total, then totals.
    private void onAmtChanged(int categoryId) {
        if (suppressSync) return;
        BigDecimal total = totalBudget();
        EditText etAmt = amtFields.get(categoryId);
        EditText etPct = pctFields.get(categoryId);
        BigDecimal amt = parse(etAmt.getText().toString());
        suppressSync = true;
        if (total.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal pct = amt.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP);
            etPct.setText(pct.stripTrailingZeros().toPlainString());
        }
        suppressSync = false;
        refreshTotals();
    }

    // % changed by hand -> recompute that row's amount against the total, then totals.
    private void onPctChanged(int categoryId) {
        if (suppressSync) return;
        BigDecimal total = totalBudget();
        EditText etAmt = amtFields.get(categoryId);
        EditText etPct = pctFields.get(categoryId);
        BigDecimal pct = parse(etPct.getText().toString());
        suppressSync = true;
        BigDecimal amt = total.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        etAmt.setText(amt.stripTrailingZeros().toPlainString());
        suppressSync = false;
        refreshTotals();
    }

    // Total budget changed by hand -> keep each row's % fixed, recompute amounts.
    private void recalcAllRowsFromTotal() {
        if (suppressSync) return;
        BigDecimal total = totalBudget();
        suppressSync = true;
        for (Category c : categories) {
            EditText etPct = pctFields.get(c.getId());
            EditText etAmt = amtFields.get(c.getId());
            if (etPct == null || etAmt == null) continue; // rows not built yet — skip safely
            BigDecimal pct = parse(etPct.getText().toString());
            if (pct.compareTo(BigDecimal.ZERO) > 0 && total.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal amt = total.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                etAmt.setText(amt.stripTrailingZeros().toPlainString());
            }
        }
        suppressSync = false;
        refreshTotals();
    }

    private void refreshTotals() {
        BigDecimal total = totalBudget();
        BigDecimal allocated = BigDecimal.ZERO;
        for (EditText et : amtFields.values())
            allocated = allocated.add(parse(et.getText().toString()));
        BigDecimal remaining = total.subtract(allocated);

        tvAllocated.setText("Allocated: ₹" + allocated.setScale(2, RoundingMode.HALF_UP).toPlainString());
        tvRemaining.setText("Remaining: ₹" + remaining.setScale(2, RoundingMode.HALF_UP).toPlainString());
        tvRemaining.setTextColor(getColor(remaining.compareTo(BigDecimal.ZERO) < 0 ? R.color.red : R.color.green));
    }

    private void saveConfig() {
        BigDecimal total = totalBudget();
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            Toast.makeText(this, "Enter a total monthly budget first", Toast.LENGTH_SHORT).show();
            return;
        }

        Map<Integer, BigDecimal> percents = new HashMap<>();
        for (Category c : categories) {
            BigDecimal pct = parse(pctFields.get(c.getId()).getText().toString());
            if (pct.compareTo(BigDecimal.ZERO) > 0) percents.put(c.getId(), pct);
        }
        if (percents.isEmpty()) {
            Toast.makeText(this, "Allocate at least one category", Toast.LENGTH_SHORT).show();
            return;
        }

        // Config screen only ever writes the reusable % template — it never
        // touches an actual month's budgets/budget_categories rows directly.
        // Those get materialized only when the BUDGET scheduler runs (auto
        // on its schedule, or manually via "Run now" in Scheduler screen).
        // This keeps "edit the template" and "apply it to a real month"
        // as two separate, predictable actions.
        templateDao.saveTemplate(bookId, percents, total);

        Toast.makeText(this,
                "Template saved. Run the Budget scheduler to apply it to a month.",
                Toast.LENGTH_LONG).show();
        finish();
    }

    private BigDecimal parse(String s) {
        s = s.trim();
        try {
            return s.isEmpty() ? BigDecimal.ZERO : new BigDecimal(s);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private TextWatcher simpleWatcher(Runnable onChange) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                onChange.run();
            }
        };
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}