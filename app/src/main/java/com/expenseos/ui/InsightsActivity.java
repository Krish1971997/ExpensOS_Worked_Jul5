package com.expenseos.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.expenseos.R;
import com.expenseos.dao.TransactionDao;
import com.expenseos.model.CashBook;
import com.expenseos.util.MonthBookResolver;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Insights & Reports — "where is my money going, and where can I cut it".
 * Month-over-month comparison + top-spender highlights, driven by the same
 * proven aggregation path the Stats screen uses.
 */
public class InsightsActivity extends AppCompatActivity {

    private YearMonth currentMonth = YearMonth.now();
    private boolean showExpense = true;

    private TextView tvMonth, tvHero, tvCompare, tvInsight, tvEmpty;
    private LinearLayout llTopSpenders, llCats, llBars;
    private ImageButton btnPrev, btnNext;
    private View tabExpense, tabIncome;

    private int[] PALETTE = {0xFFF59E0B, 0xFF16A34A, 0xFFDC2626, 0xFF2563EB, 0xFF7C3AED, 0xFF0891B2};

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_insights);

        tvMonth = findViewById(R.id.tvInsightsMonth);
        tvHero = findViewById(R.id.tvInsightsHero);
        tvCompare = findViewById(R.id.tvInsightsCompare);
        tvInsight = findViewById(R.id.tvInsightsInsight);
        tvEmpty = findViewById(R.id.tvInsightsEmpty);
        llTopSpenders = findViewById(R.id.llTopSpenders);
        llCats = findViewById(R.id.llInsightsCats);
        llBars = findViewById(R.id.llInsightsBars);
        btnPrev = findViewById(R.id.btnInsightsPrev);
        btnNext = findViewById(R.id.btnInsightsNext);
        tabExpense = findViewById(R.id.tabInsightsExpense);
        tabIncome = findViewById(R.id.tabInsightsIncome);

        findViewById(R.id.btnInsightsBack).setOnClickListener(v -> finish());
        btnPrev.setOnClickListener(v -> { currentMonth = currentMonth.minusMonths(1); refresh(); });
        btnNext.setOnClickListener(v -> { currentMonth = currentMonth.plusMonths(1); refresh(); });
        tabExpense.setOnClickListener(v -> { showExpense = true; refresh(); });
        tabIncome.setOnClickListener(v -> { showExpense = false; refresh(); });

        refresh();
    }

    private List<Map<String, Object>> breakdownFor(YearMonth ym) {
        CashBook book = MonthBookResolver.findBookForMonth(this, ym, "");
        if (book == null) return new ArrayList<>();
        TransactionDao dao = new TransactionDao(this);
        List<Map<String, Object>> rows = dao.categoryBreakdownWithId(showExpense ? "EXPENSE" : "INCOME", book.getId());
        if (rows == null) return new ArrayList<>();
        rows.sort((a, b) -> ((BigDecimal) b.get("total")).compareTo((BigDecimal) a.get("total")));
        return rows;
    }

    private static BigDecimal sum(List<Map<String, Object>> rows) {
        BigDecimal t = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) t = t.add((BigDecimal) r.get("total"));
        return t;
    }

    private void refresh() {
        tvMonth.setText(currentMonth.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH) + " " + currentMonth.getYear());
        tabExpense.setAlpha(showExpense ? 1f : 0.45f);
        tabIncome.setAlpha(showExpense ? 0.45f : 1f);

        List<Map<String, Object>> cur = breakdownFor(currentMonth);
        List<Map<String, Object>> prev = breakdownFor(currentMonth.minusMonths(1));
        BigDecimal curTotal = sum(cur);
        BigDecimal prevTotal = sum(prev);

        tvHero.setText("\u20B9" + curTotal.setScale(0, RoundingMode.HALF_UP).toPlainString());
        tvCompare.setText("Last month \u20B9" + prevTotal.setScale(0, RoundingMode.HALF_UP).toPlainString());

        llTopSpenders.removeAllViews();
        llCats.removeAllViews();
        llBars.removeAllViews();

        if (cur.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvInsight.setText("No " + (showExpense ? "expenses" : "income") + " recorded for this month yet.");
            return;
        }
        tvEmpty.setVisibility(View.GONE);

        // ── Insight sentence: biggest increase vs last month ────────────
        Map<String, BigDecimal> prevMap = new LinkedHashMap<>();
        for (Map<String, Object> r : prev) prevMap.put((String) r.get("name"), (BigDecimal) r.get("total"));

        String worstName = null;
        BigDecimal worstDelta = BigDecimal.ZERO;
        for (Map<String, Object> r : cur) {
            String n = (String) r.get("name");
            BigDecimal d = ((BigDecimal) r.get("total")).subtract(prevMap.getOrDefault(n, BigDecimal.ZERO));
            if (d.compareTo(worstDelta) > 0) { worstDelta = d; worstName = n; }
        }
        int pctChange = prevTotal.compareTo(BigDecimal.ZERO) == 0 ? 0
                : curTotal.subtract(prevTotal).multiply(BigDecimal.valueOf(100))
                  .divide(prevTotal, 0, RoundingMode.HALF_UP).intValue();

        StringBuilder ins = new StringBuilder();
        if (showExpense) {
            if (pctChange > 0) ins.append("Spending is up ").append(pctChange).append("% vs last month. ");
            else if (pctChange < 0) ins.append("Good job \u2014 spending is down ").append(Math.abs(pctChange)).append("% vs last month. ");
            else ins.append("Spending is flat vs last month. ");
            if (worstName != null && worstDelta.compareTo(BigDecimal.ZERO) > 0) {
                ins.append("Biggest jump: ").append(worstName).append(" (+").append(worstDelta.setScale(0, RoundingMode.HALF_UP).toPlainString()).append(").");
            } else if (!cur.isEmpty()) {
                ins.append("Top category: ").append((String) cur.get(0).get("name")).append(".");
            }
        } else {
            ins.append("Income for the month is \u20B9").append(curTotal.setScale(0, RoundingMode.HALF_UP).toPlainString()).append(".");
        }
        tvInsight.setText(ins.toString());

        float dens = getResources().getDisplayMetrics().density;
        int pad = (int) (dens * 14);

        // ── Top spenders (top 3 highlight cards) ────────────────────────
        int top = Math.min(3, cur.size());
        for (int i = 0; i < top; i++) {
            Map<String, Object> r = cur.get(i);
            BigDecimal amt = (BigDecimal) r.get("total");
            int pct = curTotal.compareTo(BigDecimal.ZERO) == 0 ? 0
                    : amt.multiply(BigDecimal.valueOf(100)).divide(curTotal, 0, RoundingMode.HALF_UP).intValue();

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.bg_card);
            card.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            clp.setMargins(i == 0 ? 0 : (int) (dens * 8), 0, 0, 0);
            card.setLayoutParams(clp);

            TextView t1 = new TextView(this);
            t1.setText((i + 1) + ". " + (String) r.get("name"));
            t1.setTextSize(12f);
            t1.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
            card.addView(t1);

            TextView t2 = new TextView(this);
            t2.setText("\u20B9" + amt.setScale(0, RoundingMode.HALF_UP).toPlainString());
            t2.setTextSize(16f);
            t2.setTypeface(t2.getTypeface(), android.graphics.Typeface.BOLD);
            t2.setTextColor(ContextCompat.getColor(this, i == 0 ? R.color.red : R.color.text));
            card.addView(t2);

            TextView t3 = new TextView(this);
            t3.setText(pct + "% of total");
            t3.setTextSize(11f);
            t3.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
            card.addView(t3);

            final String cname = (String) r.get("name");
            final int cid = (int) r.get("id");
            card.setOnClickListener(v -> openCategory(cid, cname));
            llTopSpenders.addView(card);
        }

        // ── Full category list with proportional bars ───────────────────
        int ci = 0;
        for (Map<String, Object> r : cur) {
            String name = (String) r.get("name");
            BigDecimal amt = (BigDecimal) r.get("total");
            int pct = curTotal.compareTo(BigDecimal.ZERO) == 0 ? 0
                    : amt.multiply(BigDecimal.valueOf(100)).divide(curTotal, 0, RoundingMode.HALF_UP).intValue();

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, (int) (dens * 8), 0, (int) (dens * 8));

            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);

            TextView nm = new TextView(this);
            nm.setText(name);
            nm.setTextSize(14f);
            nm.setTextColor(ContextCompat.getColor(this, R.color.text));
            head.addView(nm, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView pc = new TextView(this);
            pc.setText(pct + "%");
            pc.setTextSize(12f);
            pc.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
            head.addView(pc);

            TextView av = new TextView(this);
            av.setText("   \u20B9" + amt.setScale(0, RoundingMode.HALF_UP).toPlainString());
            av.setTextSize(14f);
            av.setTypeface(av.getTypeface(), android.graphics.Typeface.BOLD);
            av.setTextColor(ContextCompat.getColor(this, R.color.text));
            head.addView(av);
            row.addView(head);

            android.widget.ProgressBar bar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgress(pct);
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(PALETTE[ci % PALETTE.length]));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (dens * 6));
            blp.topMargin = (int) (dens * 6);
            bar.setLayoutParams(blp);
            row.addView(bar);

            final String cname = name;
            final int cid = (int) r.get("id");
            row.setOnClickListener(v -> openCategory(cid, cname));
            llCats.addView(row);
            ci++;
        }
    }

    private void openCategory(int categoryId, String categoryName) {
        Intent i = new Intent(this, CategoryStatsActivity.class);
        i.putExtra("categoryName", categoryName);
        i.putExtra("categoryId", categoryId);
        i.putExtra("isExpense", showExpense);
        i.putExtra("year", currentMonth.getYear());
        i.putExtra("month", currentMonth.getMonthValue());
        startActivity(i);
    }
}
