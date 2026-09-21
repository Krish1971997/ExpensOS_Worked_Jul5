package com.expenseos.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.expenseos.R;
import com.expenseos.dao.SettlementLinkDao;
import com.expenseos.dao.TransactionDao;
import com.expenseos.model.Transaction;
import com.expenseos.model.TransactionFilter;
import com.expenseos.ui.home.TransactionFilterDialog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Link the transaction opened from (txnId) to one or more OPPOSITE-type
 * transactions in the same book that it settles/reimburses (or that
 * settle it) — partial amounts supported.
 * <p>
 * Multi-select flow: check candidates, edit the auto-suggested amount if
 * needed, "Remaining to settle" updates live as you check/uncheck — then
 * "💾 Save Links" commits everything in one batch. Nothing is written to
 * the DB until Save is tapped.
 */
public class SettlementLinkActivity extends AppCompatActivity {

    private int sourceTxnId;
    private Transaction source;
    private TransactionDao txnDao;
    private SettlementLinkDao linkDao;

    private LinearLayout containerLinked, containerAvailable;
    private TextView tvNoLinked, tvNoAvailable, tvSourceSummary, tvRemaining;
    private TextView tvFilterSummary, btnFilter, btnFilterClear;
    private View btnSaveLinks;

    private BigDecimal sourceRemaining = BigDecimal.ZERO;
    // candidate txnId -> amount the user has checked/entered (not yet saved)
    private final Map<Integer, BigDecimal> pendingSelections = new LinkedHashMap<>();
    // Reuses the app's existing filter dialog (Category/Sub Category/Amount/
    // Date/Payment Type/Attachment) instead of a plain search box — type
    // and bookId are forced to this screen's constraints at query time,
    // not exposed as dialog tabs.
    private TransactionFilter currentFilter = new TransactionFilter();

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_settlement_link);

        sourceTxnId = getIntent().getIntExtra("txnId", -1);
        if (sourceTxnId <= 0) {
            finish();
            return;
        }

        txnDao = new TransactionDao(this);
        linkDao = new SettlementLinkDao(this);

        tvSourceSummary = findViewById(R.id.tvSettleSourceSummary);
        tvRemaining = findViewById(R.id.tvSettleRemaining);
        containerLinked = findViewById(R.id.containerLinked);
        containerAvailable = findViewById(R.id.containerAvailable);
        tvNoLinked = findViewById(R.id.tvNoLinked);
        tvNoAvailable = findViewById(R.id.tvNoAvailable);
        tvFilterSummary = findViewById(R.id.tvSettleFilterSummary);
        btnFilter = findViewById(R.id.btnSettleFilter);
        btnFilterClear = findViewById(R.id.btnSettleFilterClear);
        btnSaveLinks = findViewById(R.id.btnSaveLinks);

        findViewById(R.id.btnSettleBack).setOnClickListener(v -> finish());
        btnSaveLinks.setOnClickListener(v -> saveLinks());

        btnFilter.setOnClickListener(v -> new TransactionFilterDialog(this, source.getBookId(), currentFilter, filter -> {
            currentFilter = filter;
            updateFilterSummary();
            renderAvailable();
        }).show());

        btnFilterClear.setOnClickListener(v -> {
            currentFilter = new TransactionFilter();
            updateFilterSummary();
            renderAvailable();
        });

        loadAll();
    }

    // "Filters applied ✕" pill kāтtudhu, edhavadhu field set aagirundha —
    // Category/Sub Category/Date/Amount/Payment Type/Attachment ellame check pannuрОm.
    private void updateFilterSummary() {
        boolean active = (currentFilter.getCategoryIds() != null && !currentFilter.getCategoryIds().isEmpty()) ||
                (currentFilter.getSubCategoryIds() != null && !currentFilter.getSubCategoryIds().isEmpty()) ||
                currentFilter.getDateFrom() != null || currentFilter.getDateTo() != null ||
                currentFilter.getAmount1() != null ||
                (currentFilter.getPaymentTypes() != null && !currentFilter.getPaymentTypes().isEmpty()) ||
                currentFilter.getHasAttachment() != null;

        tvFilterSummary.setText(active ? "Filters applied" : "Filter by category, amount, date…");
        tvFilterSummary.setTextColor(active ? getColor(R.color.primary) : getColor(R.color.text_muted));
        btnFilterClear.setVisibility(active ? View.VISIBLE : View.GONE);
    }

    private void loadAll() {
        pendingSelections.clear();
        source = txnDao.findById(sourceTxnId);
        if (source == null) {
            finish();
            return;
        }

        BigDecimal alreadyLinked = linkDao.sumLinkedFor(sourceTxnId);
        sourceRemaining = source.getAmount().subtract(alreadyLinked).max(BigDecimal.ZERO);

        tvSourceSummary.setText((source.getType() == Transaction.Type.INCOME ? "↑ " : "↓ ") +
                source.getFormattedDate() + " — " + source.getCategoryName() +
                " — " + source.getFormattedAmount() +
                (source.getNote() != null && !source.getNote().isEmpty() ? " (" + source.getNote() + ")" : ""));

        updateRemainingLabel();
        updateFilterSummary();
        renderLinked();
        renderAvailable();
    }

    // "Remaining to settle" — green pill at ₹0, orange otherwise, so it
    // actually stands out instead of being plain body text.
    private void updateRemainingLabel() {
        BigDecimal displayRemaining = sourceRemaining;
        for (BigDecimal v : pendingSelections.values())
            displayRemaining = displayRemaining.subtract(v);
        displayRemaining = displayRemaining.max(BigDecimal.ZERO);

        boolean isZero = displayRemaining.compareTo(BigDecimal.ZERO) == 0;
        tvRemaining.setText("Remaining to settle: ₹" + displayRemaining.toPlainString());
        tvRemaining.setTextColor(0xFFFFFFFF);
        tvRemaining.setTypeface(null, android.graphics.Typeface.BOLD);
        tvRemaining.setBackgroundColor(isZero ? 0xFF16A34A : 0xFFF59E0B); // green : amber
        int padH = dp(10), padV = dp(4);
        tvRemaining.setPadding(padH, padV, padH, padV);

        btnSaveLinks.setEnabled(!pendingSelections.isEmpty());
        btnSaveLinks.setAlpha(pendingSelections.isEmpty() ? 0.5f : 1f);
    }

    private void renderLinked() {
        containerLinked.removeAllViews();
        List<SettlementLinkDao.Link> links = linkDao.findForTransaction(sourceTxnId);

        tvNoLinked.setVisibility(links.isEmpty() ? View.VISIBLE : View.GONE);
        containerLinked.setVisibility(links.isEmpty() ? View.GONE : View.VISIBLE);

        for (SettlementLinkDao.Link link : links) {
            int otherId = link.otherSide(sourceTxnId);
            Transaction other = txnDao.findById(otherId);
            if (other == null) continue;

            LinearLayout row = row();
            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView tv = new TextView(this);
            tv.setTextColor(getColor(R.color.text));
            tv.setTextSize(13);
            tv.setText((other.getType() == Transaction.Type.INCOME ? "↑ " : "↓ ") +
                    other.getFormattedDate() + " — " + other.getCategoryName() +
                    " — ₹" + link.amount.toPlainString() + " linked");
            textCol.addView(tv);

            String otherStatus = statusLabelFor(other);
            if (otherStatus != null) {
                TextView tvStatus = new TextView(this);
                tvStatus.setTextSize(11);
                tvStatus.setText(otherStatus);
                tvStatus.setTextColor(otherStatus.startsWith("✓") ? getColor(R.color.green) : getColor(R.color.amber));
                textCol.addView(tvStatus);
            }
            row.addView(textCol);

            TextView remove = new TextView(this);
            remove.setText("✕ Unlink");
            remove.setTextColor(getColor(R.color.red));
            remove.setTextSize(12);
            remove.setPadding(dp(8), dp(4), dp(8), dp(4));
            remove.setOnClickListener(v -> {
                linkDao.delete(link.id);
                loadAll();
            });
            row.addView(remove);

            containerLinked.addView(row);
        }
    }

    private void renderAvailable() {
        containerAvailable.removeAllViews();

        String oppositeType = source.getType() == Transaction.Type.INCOME ? "EXPENSE" : "INCOME";
        // Screen-oda fixed constraints (type + this book) force pannunga —
        // dialog-la idhu tabs-ah expose aagalai, user-oda filter choices
        // (Category/Amount/Date/etc.) mattum andha mேlே apply aagum.
        currentFilter.setType(oppositeType);
        currentFilter.setBookIds(java.util.Collections.singletonList(source.getBookId()));
        List<Transaction> candidates = txnDao.findByFilter(currentFilter);

        boolean anyShown = false;
        for (Transaction t : candidates) {
            if (t.getId() == sourceTxnId) continue;

            BigDecimal remaining = t.getAmount().subtract(linkDao.sumLinkedFor(t.getId())).max(BigDecimal.ZERO);
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) continue; // fully settled already

            anyShown = true;
            containerAvailable.addView(buildAvailableRow(t, remaining));
        }

        tvNoAvailable.setVisibility(anyShown ? View.GONE : View.VISIBLE);
        containerAvailable.setVisibility(anyShown ? View.VISIBLE : View.GONE);
    }

    private LinearLayout buildAvailableRow(Transaction t, BigDecimal remaining) {
        LinearLayout row = row();

        CheckBox cb = new CheckBox(this);
        cb.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        boolean alreadyPending = pendingSelections.containsKey(t.getId());
        cb.setChecked(alreadyPending);
        row.addView(cb);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        textCol.setPadding(dp(8), 0, dp(8), 0);

        TextView tv = new TextView(this);
        tv.setTextColor(getColor(R.color.text));
        tv.setTextSize(13);
        tv.setText(t.getFormattedDate() + " — " + t.getCategoryName() + " — ₹" + remaining.toPlainString() + " remaining" +
                (t.getNote() != null && !t.getNote().isEmpty() ? " (" + t.getNote() + ")" : ""));
        textCol.addView(tv);

        String status = statusLabelFor(t);
        if (status != null) {
            TextView tvStatus = new TextView(this);
            tvStatus.setTextSize(11);
            tvStatus.setText(status);
            tvStatus.setTextColor(status.startsWith("✓") ? getColor(R.color.green) : getColor(R.color.amber));
            textCol.addView(tvStatus);
        }
        row.addView(textCol);

        EditText etAmount = new EditText(this);
        etAmount.setLayoutParams(new LinearLayout.LayoutParams(dp(70), LinearLayout.LayoutParams.WRAP_CONTENT));
        etAmount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etAmount.setTextSize(12);
        etAmount.setEnabled(alreadyPending);
        BigDecimal defaultAmt = alreadyPending ? pendingSelections.get(t.getId()) : remaining.min(currentRemainingHeadroom());
        etAmount.setText(defaultAmt.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
        row.addView(etAmount);

        etAmount.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                if (!cb.isChecked()) return;
                BigDecimal val;
                try {
                    val = e.length() == 0 ? BigDecimal.ZERO : new BigDecimal(e.toString());
                } catch (NumberFormatException ex) {
                    val = BigDecimal.ZERO;
                }
                pendingSelections.put(t.getId(), val);
                updateRemainingLabel();
            }
        });

        cb.setOnCheckedChangeListener((CompoundButton btn, boolean checked) -> {
            etAmount.setEnabled(checked);
            if (checked) {
                BigDecimal amt = remaining.min(currentRemainingHeadroom());
                etAmount.setText(amt.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
                pendingSelections.put(t.getId(), amt);
            } else {
                pendingSelections.remove(t.getId());
            }
            updateRemainingLabel();
        });

        return row;
    }

    // How much of sourceRemaining is still un-allocated by OTHER pending
    // selections — used to suggest a sensible default when a new box is checked.
    private BigDecimal currentRemainingHeadroom() {
        BigDecimal used = BigDecimal.ZERO;
        for (BigDecimal v : pendingSelections.values()) used = used.add(v);
        return sourceRemaining.subtract(used).max(BigDecimal.ZERO);
    }

    private String statusLabelFor(Transaction t) {
        BigDecimal linked = linkDao.sumLinkedFor(t.getId());
        if (linked.compareTo(BigDecimal.ZERO) <= 0) return null;
        BigDecimal remaining = t.getAmount().subtract(linked).max(BigDecimal.ZERO);
        return remaining.compareTo(BigDecimal.ZERO) <= 0 ? "✓ Fully settled" : "◐ Partially settled elsewhere too";
    }

    private void saveLinks() {
        if (pendingSelections.isEmpty()) return;

        for (Map.Entry<Integer, BigDecimal> e : pendingSelections.entrySet()) {
            if (e.getValue().compareTo(BigDecimal.ZERO) <= 0) continue;
            linkDao.insert(sourceTxnId, e.getKey(), e.getValue());
        }
        Toast.makeText(this, "✓ Links saved", Toast.LENGTH_SHORT).show();
        loadAll();
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        return row;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}