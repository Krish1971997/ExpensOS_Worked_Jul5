package com.expenseos.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.model.Transaction;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Groups transactions by date (like the old app): a date header row,
 * followed by each transaction for that date showing
 * category/subcategory ....... amount
 * note ....................... balance
 * ............................ time
 * <p>
 * IMPORTANT: assumes the incoming `list` is already sorted so that
 * transactions on the same date are adjacent (e.g. newest-first, which
 * is how "Showing N entries" screens are usually sorted). If your DAO
 * doesn't guarantee that ordering, sort by dateTime desc before passing
 * the list in.
 */
public class TransactionAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_TXN = 1;

    private static final DateTimeFormatter HEADER_FMT = DateTimeFormatter.ofPattern("d MMMM yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("h:mm a");

    public interface OnTxnClick {
        void onClick(Transaction t);
    }

    public interface OnTxnLongClick {
        void onLongClick(Transaction t);
    }

    // Sealed-ish row model: either a date header (String) or a Transaction
    private static class Row {
        final int type;
        final String headerText;
        final Transaction txn;

        Row(String headerText) {
            this.type = TYPE_HEADER;
            this.headerText = headerText;
            this.txn = null;
        }

        Row(Transaction txn) {
            this.type = TYPE_TXN;
            this.headerText = null;
            this.txn = txn;
        }
    }

    private final Context ctx;
    private final List<Row> rows = new ArrayList<>();
    private final OnTxnLongClick onLongClick; // long-press → options (edit/dup/del)
    private final OnTxnClick onClick;     // tap → detail

    // ── Constructor with both callbacks ──────────────────
    public TransactionAdapter(Context ctx, List<Transaction> list,
                              OnTxnLongClick onLongClick,
                              OnTxnClick onClick) {
        this.ctx = ctx;
        this.onLongClick = onLongClick;
        this.onClick = onClick;
        setData(list);
    }

    // ── Backward-compat constructor (tap only) ────────────
    public TransactionAdapter(Context ctx, List<Transaction> list) {
        this(ctx, list, null, null);
    }

    /**
     * Rebuilds the grouped row list (header + transactions) from a flat list.
     */
    public void setData(List<Transaction> list) {
        rows.clear();
        LocalDate lastDate = null;
        if (list != null) {
            for (Transaction t : list) {
                LocalDate d = t.getDateTime() != null ? t.getDateTime().toLocalDate() : null;
                if (d != null && !d.equals(lastDate)) {
                    rows.add(new Row(d.format(HEADER_FMT)));
                    lastDate = d;
                }
                rows.add(new Row(t));
            }
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).type;
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        TextView tvHeader;

        HeaderVH(View v) {
            super(v);
            tvHeader = (TextView) v;
        }
    }

    static class TxnVH extends RecyclerView.ViewHolder {
        TextView tvDate, tvCat, tvSubCat, tvAmount, tvNote, tvBalance, tvSyncDot;
        View typeBadge;

        TxnVH(View v) {
            super(v);
            tvDate = v.findViewById(R.id.tvTxnDate); // repurposed to show TIME only
            tvCat = v.findViewById(R.id.tvTxnCat);
            tvSubCat = v.findViewById(R.id.tvTxnSubCat);
            tvAmount = v.findViewById(R.id.tvTxnAmount);
            tvNote = v.findViewById(R.id.tvTxnNote);
            tvBalance = v.findViewById(R.id.tvTxnBalance);
            tvSyncDot = v.findViewById(R.id.tvTxnSyncDot);
            typeBadge = v.findViewById(R.id.viewTypeBadge);
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View v = LayoutInflater.from(ctx).inflate(R.layout.item_date_header, parent, false);
            return new HeaderVH(v);
        }
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_transaction, parent, false);
        return new TxnVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
        Row row = rows.get(pos);

        if (row.type == TYPE_HEADER) {
            ((HeaderVH) holder).tvHeader.setText(row.headerText);
            return;
        }

        TxnVH h = (TxnVH) holder;
        Transaction t = row.txn;
        boolean isIncome = t.getType() == Transaction.Type.INCOME;

        // Time only (date is already shown in the header above)
        LocalDateTime dt = t.getDateTime();
        h.tvDate.setText(dt != null ? dt.format(TIME_FMT) : "");

        // Color strip
        int badgeBg = isIncome ? R.color.income_badge_bg : R.color.expense_badge_bg;
        h.typeBadge.setBackgroundColor(ContextCompat.getColor(ctx, badgeBg));

        // Category chip
        h.tvCat.setText(t.getCategoryName() != null ? t.getCategoryName() : "");

        // Sub-category chip (hide if empty)
        String sub = t.getSubCategoryName();
        h.tvSubCat.setText(sub != null ? sub : "");
        h.tvSubCat.setVisibility(sub != null && !sub.isEmpty() ? View.VISIBLE : View.GONE);

        // Amount
        h.tvAmount.setText(t.getFormattedAmount());
        h.tvAmount.setTextColor(ContextCompat.getColor(ctx,
                isIncome ? R.color.green : R.color.red));

        // Note
        h.tvNote.setText(t.getNote() != null ? t.getNote() : "");

//         Running balance — populated by the DAO/service layer via
//         Transaction.setRunningBalance(...) before this list is passed in.
        if (t.getRunningBalance() != null) {
            h.tvBalance.setText("Balance: " + t.getRunningBalance().toPlainString());
        } else {
            h.tvBalance.setText("");
        }

        // Sync pending dot — small amber indicator if not synced
        h.tvSyncDot.setVisibility(t.isSynced() ? View.GONE : View.VISIBLE);

        // Click → detail
        if (onClick != null)
            h.itemView.setOnClickListener(v -> onClick.onClick(t));

        // Long-click → options menu (edit/duplicate/delete)
        if (onLongClick != null)
            h.itemView.setOnLongClickListener(v -> {
                onLongClick.onLongClick(t);
                return true;
            });
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }
}
