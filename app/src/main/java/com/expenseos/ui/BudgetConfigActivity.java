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

        // Total is derived from the category amounts below — read-only.
        etTotalBudget.setFocusable(false);
        etTotalBudget.setClickable(false);
        etTotalBudget.setCursorVisible(false);
        etTotalBudget.setHint("Sum of category amounts below");

        loadCategoriesAndBuildTable();
    }

    private void loadCategoriesAndBuildTable() {
        categories.clear();
        // Common (book-independent) categories only — this template is
        // shared across every book, so book-specific categories don't apply.
        categories.addAll(catDao.findByType("EXPENSE"));

        Map<Integer, BigDecimal> savedAmounts = templateDao.loadGlobalAmounts();

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
            etPct.setFocusable(false);
            etPct.setClickable(false);
            etPct.setCursorVisible(false);
            etPct.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            etPct.setLayoutParams(new LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT));
            row.addView(etPct);

            EditText etAmt = new EditText(this);
            etAmt.setHint("Amount");
            etAmt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            LinearLayout.LayoutParams amtLp = new LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT);
            amtLp.setMarginStart(dp(8));
            etAmt.setLayoutParams(amtLp);

            BigDecimal savedAmt = savedAmounts.get(c.getId());
            if (savedAmt != null) etAmt.setText(savedAmt.stripTrailingZeros().toPlainString());
            row.addView(etAmt);

            pctFields.put(c.getId(), etPct);
            amtFields.put(c.getId(), etAmt);

            etAmt.addTextChangedListener(simpleWatcher(this::recalcFromAmounts));

            rowsContainer.addView(row);
        }

        recalcFromAmounts();
    }

    private BigDecimal sumOfAmounts() {
        BigDecimal sum = BigDecimal.ZERO;
        for (EditText et : amtFields.values()) sum = sum.add(parse(et.getText().toString()));
        return sum;
    }

    private void recalcFromAmounts() {
        if (suppressSync) return;
        suppressSync = true;

        BigDecimal total = sumOfAmounts();
        etTotalBudget.setText(total.stripTrailingZeros().toPlainString());

        for (Category c : categories) {
            EditText etAmt = amtFields.get(c.getId());
            EditText etPct = pctFields.get(c.getId());
            if (etAmt == null || etPct == null) continue;
            BigDecimal amt = parse(etAmt.getText().toString());
            if (total.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal pct = amt.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP);
                etPct.setText(pct.stripTrailingZeros().toPlainString());
            } else {
                etPct.setText("");
            }
        }

        suppressSync = false;
        refreshTotals(total);
    }

    private void refreshTotals(BigDecimal total) {
        tvAllocated.setText("Allocated: ₹" + total.setScale(2, RoundingMode.HALF_UP).toPlainString());
        tvRemaining.setText("Remaining: ₹0.00");
        tvRemaining.setTextColor(getColor(R.color.green));
    }

    private void saveConfig() {
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