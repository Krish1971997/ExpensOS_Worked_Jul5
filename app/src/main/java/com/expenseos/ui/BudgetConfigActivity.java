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

/**
 * Edits the ONE shared budget template (category -> amount) used by every
 * cash book. Not tied to a bookId/month — this screen doesn't belong to
 * any single book's Budget tab, and creates nothing by itself; the
 * scheduler (or a manual apply) is what copies these amounts into an
 * actual month's budget for each book.
 */
public class BudgetConfigActivity extends AppCompatActivity {

    private CategoryDao catDao;
    private BudgetTemplateDao templateDao;

    private EditText etTotalBudget;
    private TextView tvAllocated, tvRemaining;
    private LinearLayout rowsContainer;

    private final List<Category> categories = new ArrayList<>();
    private final Map<Integer, EditText> pctFields = new HashMap<>();
    private final Map<Integer, EditText> amtFields = new HashMap<>();
    private boolean suppressSync = false;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_budget_config);

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
        // Common (book-independent) categories only — this template is
        // shared across every book, so book-specific categories don't apply.
        categories.addAll(catDao.findByType("EXPENSE"));

        Map<Integer, BigDecimal> savedAmounts = templateDao.loadGlobalAmounts();
        BigDecimal savedTotal = BigDecimal.ZERO;
        for (BigDecimal amt : savedAmounts.values()) savedTotal = savedTotal.add(amt);

        // Guard the initial setText — it fires the TextWatcher immediately,
        // but the per-row EditTexts don't exist yet at this point.
        suppressSync = true;
        etTotalBudget.setText(savedTotal.compareTo(BigDecimal.ZERO) > 0
                ? savedTotal.stripTrailingZeros().toPlainString() : "");
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

            BigDecimal savedAmt = savedAmounts.get(c.getId());
            if (savedAmt != null && savedTotal.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal pct = savedAmt.multiply(BigDecimal.valueOf(100))
                        .divide(savedTotal, 2, RoundingMode.HALF_UP);
                etPct.setText(pct.stripTrailingZeros().toPlainString());
            }
            row.addView(etPct);

            EditText etAmt = new EditText(this);
            etAmt.setHint("Amount");
            etAmt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            LinearLayout.LayoutParams amtLp = new LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT);
            amtLp.setMarginStart(dp(8));
            etAmt.setLayoutParams(amtLp);
            if (savedAmt != null) etAmt.setText(savedAmt.stripTrailingZeros().toPlainString());
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

    // Amount typed by hand -> recompute that row's % against the total, then refresh totals.
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

    // % typed by hand -> recompute that row's amount against the total, then refresh totals.
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

    // Total typed by hand -> keep each row's % fixed, recompute amounts from it.
    private void recalcAllRowsFromTotal() {
        if (suppressSync) return;
        BigDecimal total = totalBudget();
        suppressSync = true;
        for (Category c : categories) {
            EditText etPct = pctFields.get(c.getId());
            EditText etAmt = amtFields.get(c.getId());
            if (etPct == null || etAmt == null) continue;
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

        Map<Integer, BigDecimal> amounts = new HashMap<>();
        for (Category c : categories) {
            BigDecimal amt = parse(amtFields.get(c.getId()).getText().toString());
            if (amt.compareTo(BigDecimal.ZERO) > 0) amounts.put(c.getId(), amt);
        }
        if (amounts.isEmpty()) {
            Toast.makeText(this, "Enter an amount for at least one category", Toast.LENGTH_SHORT).show();
            return;
        }

        // This template is common to every book. It never touches an
        // actual month's budgets/budget_categories rows itself — those get
        // created only when the BUDGET scheduler runs (auto or "Run now"),
        // one per book, copying these exact amounts.
        templateDao.saveGlobalTemplate(amounts);

        Toast.makeText(this,
                "Budget template saved — applies to every cash book. Run the Budget scheduler to apply it.",
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