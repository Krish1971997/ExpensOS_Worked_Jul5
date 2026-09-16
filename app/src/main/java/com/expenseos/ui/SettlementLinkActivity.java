package com.expenseos.ui;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.expenseos.R;
import com.expenseos.dao.SettlementLinkDao;
import com.expenseos.dao.TransactionDao;
import com.expenseos.model.Transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Link the transaction opened from (txnId) to one or more OPPOSITE-type
 * transactions in the same book that it settles/reimburses (or that
 * settle it) — partial amounts supported. Used for the "money came back"
 * case: pay for things across several expense categories, then one
 * income entry reimburses the total — link them here so StatsActivity's
 * "Net Settlements" checkbox can show the true net expense (₹0 if fully
 * reimbursed) without needing a shared category between the sides.
 */
public class SettlementLinkActivity extends AppCompatActivity {

    private int sourceTxnId;
    private Transaction source;
    private TransactionDao txnDao;
    private SettlementLinkDao linkDao;

    private LinearLayout containerLinked, containerAvailable;
    private TextView tvNoLinked, tvNoAvailable, tvSourceSummary, tvRemaining;

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

        findViewById(R.id.btnSettleBack).setOnClickListener(v -> finish());

        loadAll();
    }

    private void loadAll() {
        source = txnDao.findById(sourceTxnId);
        if (source == null) {
            finish();
            return;
        }

        BigDecimal sourceLinked = linkDao.sumLinkedFor(sourceTxnId);
        BigDecimal sourceRemaining = source.getAmount().subtract(sourceLinked).max(BigDecimal.ZERO);

        tvSourceSummary.setText((source.getType() == Transaction.Type.INCOME ? "↑ " : "↓ ") +
                source.getFormattedDate() + " — " + source.getCategoryName() +
                " — " + source.getFormattedAmount() +
                (source.getNote() != null && !source.getNote().isEmpty() ? " (" + source.getNote() + ")" : ""));
        tvRemaining.setText("Remaining to settle: ₹" + sourceRemaining.toPlainString());

        renderLinked();
        renderAvailable(sourceRemaining);
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
            TextView tv = new TextView(this);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            tv.setTextColor(getColor(R.color.text));
            tv.setTextSize(13);
            tv.setText((other.getType() == Transaction.Type.INCOME ? "↑ " : "↓ ") +
                    other.getFormattedDate() + " — " + other.getCategoryName() +
                    " — ₹" + link.amount.toPlainString() + " linked");
            row.addView(tv);

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

    private void renderAvailable(BigDecimal sourceRemaining) {
        containerAvailable.removeAllViews();

        String oppositeType = source.getType() == Transaction.Type.INCOME ? "EXPENSE" : "INCOME";
        List<Transaction> candidates = txnDao.findAll(oppositeType, 0, 1000, source.getBookId());

        boolean anyShown = false;
        for (Transaction t : candidates) {
            if (t.getId() == sourceTxnId) continue;
            BigDecimal remaining = t.getAmount().subtract(linkDao.sumLinkedFor(t.getId())).max(BigDecimal.ZERO);
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) continue; // fully settled already

            anyShown = true;
            LinearLayout row = row();
            row.setClickable(true);
            row.setFocusable(true);

            TextView tv = new TextView(this);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            tv.setTextColor(getColor(R.color.text));
            tv.setTextSize(13);
            tv.setText(t.getFormattedDate() + " — " + t.getCategoryName() +
                    " — ₹" + remaining.toPlainString() + " remaining" +
                    (t.getNote() != null && !t.getNote().isEmpty() ? " (" + t.getNote() + ")" : ""));
            row.addView(tv);

            TextView add = new TextView(this);
            add.setText("+ Link");
            add.setTextColor(getColor(R.color.primary));
            add.setTextSize(12);
            add.setPadding(dp(8), dp(4), dp(8), dp(4));
            row.addView(add);

            View.OnClickListener open = v -> showAmountDialog(t, remaining, sourceRemaining);
            row.setOnClickListener(open);
            add.setOnClickListener(open);

            containerAvailable.addView(row);
        }

        tvNoAvailable.setVisibility(anyShown ? View.GONE : View.VISIBLE);
        containerAvailable.setVisibility(anyShown ? View.VISIBLE : View.GONE);
    }

    private void showAmountDialog(Transaction target, BigDecimal targetRemaining, BigDecimal sourceRemaining) {
        BigDecimal suggested = targetRemaining.min(sourceRemaining);

        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setText(suggested.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
        int pad = dp(16);
        et.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(this)
                .setTitle("Link amount")
                .setMessage("How much of ₹" + target.getAmount().toPlainString() + " (" + target.getCategoryName() + ") does this settle? Max ₹" + suggested.toPlainString())
                .setView(et)
                .setPositiveButton("Link", (d, w) -> {
                    BigDecimal amt;
                    try {
                        amt = new BigDecimal(et.getText().toString().trim());
                    } catch (Exception e) {
                        Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (amt.compareTo(BigDecimal.ZERO) <= 0 || amt.compareTo(suggested) > 0) {
                        Toast.makeText(this, "Amount must be between ₹0 and ₹" + suggested.toPlainString(), Toast.LENGTH_LONG).show();
                        return;
                    }
                    linkDao.insert(sourceTxnId, target.getId(), amt);
                    loadAll();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row.setLayoutParams(lp);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        return row;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}