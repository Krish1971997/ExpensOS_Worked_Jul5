package com.expenseos.adapter;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.db.LocalDB;
import com.expenseos.model.Transaction;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class TransactionAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_TXN = 1;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("h:mm a");

    public interface OnTxnClick {
        void onClick(Transaction t);
    }

    public interface OnTxnLongClick {
        void onLongClick(Transaction t);
    }

    // Sealed-ish row model: either a date header (CharSequence) or a Transaction
    private static class Row {
        final int type;
        final CharSequence headerText;
        final Transaction txn;

        Row(CharSequence headerText) {
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
    private final SQLiteDatabase readDb;
    private final List<Row> rows = new ArrayList<>();
    private final OnTxnLongClick onLongClick;
    private final OnTxnClick onClick;

    public TransactionAdapter(Context ctx, List<Transaction> list,
                              OnTxnLongClick onLongClick,
                              OnTxnClick onClick) {
        this.ctx = ctx;
        this.readDb = LocalDB.getInstance(ctx).getReadableDatabase();
        this.onLongClick = onLongClick;
        this.onClick = onClick;
        setData(list);
    }

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
                LocalDateTime dt = t.getDateTime();
                LocalDate d = dt != null ? dt.toLocalDate() : null;
                if (d != null && !d.equals(lastDate)) {
                    // 🔥 NEW: formatHeaderDate method மூலம் "19 Tue 05.2026" Style-ல் Header உருவாக்கப்படுகிறது
                    rows.add(new Row(formatHeaderDate(dt)));
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
        TextView tvDate, tvCat, tvSubCat, tvPaymentType, tvAmount, tvNote, tvBalance, tvSyncDot, tvAttachments;
        View typeBadge;

        TxnVH(View v) {
            super(v);
            tvDate = v.findViewById(R.id.tvTxnDate);
            tvCat = v.findViewById(R.id.tvTxnCat);
            tvSubCat = v.findViewById(R.id.tvTxnSubCat);
            tvPaymentType = v.findViewById(R.id.tvTxnPaymentType);
            tvAmount = v.findViewById(R.id.tvTxnAmount);
            tvNote = v.findViewById(R.id.tvTxnNote);
            tvBalance = v.findViewById(R.id.tvTxnBalance);
            tvSyncDot = v.findViewById(R.id.tvTxnSyncDot);
            tvAttachments = v.findViewById(R.id.tvTxnAttachments);
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

        LocalDateTime dt = t.getDateTime();
        h.tvDate.setText(dt != null ? dt.format(TIME_FMT) : "");

        int badgeBg = isIncome ? R.color.income_badge_bg : R.color.expense_badge_bg;
        h.typeBadge.setBackgroundColor(ContextCompat.getColor(ctx, badgeBg));

        h.tvCat.setText(t.getCategoryName() != null ? t.getCategoryName() : "");

        String sub = t.getSubCategoryName();
        h.tvSubCat.setText(sub != null ? sub : "");
        h.tvSubCat.setVisibility(sub != null && !sub.isEmpty() ? View.VISIBLE : View.GONE);

        h.tvPaymentType.setText(t.getPaymentType() != null ? t.getPaymentType() : "");
        h.tvPaymentType.setVisibility(t.getPaymentType() != null && !t.getPaymentType().isEmpty() ? View.VISIBLE : View.GONE);
        
        h.tvAmount.setText(t.getFormattedAmount());
        h.tvAmount.setTextColor(ContextCompat.getColor(ctx,
                isIncome ? R.color.green : R.color.red));

        h.tvNote.setText(t.getNote() != null ? t.getNote() : "");

        if (t.getRunningBalance() != null) {
            h.tvBalance.setText("Balance: " + t.getRunningBalance().toPlainString());
        } else {
            h.tvBalance.setText("");
        }

        h.tvSyncDot.setVisibility(View.VISIBLE);
        if (t.isSynced()) {
            h.tvSyncDot.setText("● synced");
            h.tvSyncDot.setTextColor(ContextCompat.getColor(ctx, R.color.green));
        } else {
            h.tvSyncDot.setText("● sync");
            h.tvSyncDot.setTextColor(ContextCompat.getColor(ctx, R.color.red));
        }

        int attCount = getAttachmentCount(t.getId());
        if (attCount > 0) {
            h.tvAttachments.setText("📎 " + attCount + (attCount == 1 ? " Attachment" : " Attachments"));
            h.tvAttachments.setVisibility(View.VISIBLE);
        } else {
            h.tvAttachments.setVisibility(View.GONE);
        }

        if (onClick != null)
            h.itemView.setOnClickListener(v -> onClick.onClick(t));

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

    private int getAttachmentCount(int transactionId) {
        try (Cursor c = readDb.rawQuery(
                "SELECT COUNT(*) FROM transaction_receipts WHERE transaction_id = ?",
                new String[]{String.valueOf(transactionId)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    // ── Header Date Formatting: "19 Tue 05.2026" ─────────────────────
    public CharSequence formatHeaderDate(LocalDateTime dateTime) {
        if (dateTime == null) return "";

        String dayNumber = dateTime.format(DateTimeFormatter.ofPattern("dd"));       // e.g., "19"
        String dayName = " " + dateTime.format(DateTimeFormatter.ofPattern("EEE")) + " "; // e.g., " Tue "
        String monthYear = " " + dateTime.format(DateTimeFormatter.ofPattern("MM.yyyy")); // e.g., " 05.2026"

        SpannableStringBuilder builder = new SpannableStringBuilder();

        // 1. Date Number (19) - Bold & Big
        int start = builder.length();
        builder.append(dayNumber);
        builder.setSpan(new AbsoluteSizeSpan(18, true), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new StyleSpan(Typeface.BOLD), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new ForegroundColorSpan(Color.BLACK), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // 2. Day Badge ( Tue ) - Highlight Background
        builder.append(" ");
        start = builder.length();
        builder.append(dayName);
        builder.setSpan(new BackgroundColorSpan(Color.parseColor("#808080")), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new ForegroundColorSpan(Color.WHITE), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new AbsoluteSizeSpan(12, true), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new StyleSpan(Typeface.BOLD), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // 3. Month.Year (05.2026) - Small Grey Text
        start = builder.length();
        builder.append(monthYear);
        builder.setSpan(new AbsoluteSizeSpan(13, true), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new ForegroundColorSpan(Color.parseColor("#6B7280")), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        return builder;
    }
}