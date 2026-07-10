package com.expenseos.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.model.Transaction;

import java.util.List;

public class TransactionAdapter extends RecyclerView.Adapter<TransactionAdapter.VH> {

    public interface OnItemClickListener {
        void onClick(Transaction txn);
    }

    private final List<Transaction> data;
    private final OnItemClickListener listener;

    public TransactionAdapter(List<Transaction> data, OnItemClickListener listener) {
        this.data = data;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_transaction, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Transaction t = data.get(pos);
        h.tvDate.setText(t.getTxnDatetime() != null
                ? t.getTxnDatetime().substring(0, Math.min(16, t.getTxnDatetime().length()))
                : "");
        h.tvCategory.setText(t.getCategoryName() != null ? t.getCategoryName() : "—");
        h.tvSubCat.setText(t.getSubCategoryName() != null ? t.getSubCategoryName() : "");
        h.tvNote.setText(t.getNote() != null ? t.getNote() : "");
        h.tvAmount.setText(t.getAmountFormatted());

        boolean isIncome = t.getType() == Transaction.Type.INCOME;
        h.tvAmount.setTextColor(isIncome ? Color.parseColor("#1B8A1B") : Color.parseColor("#C0392B"));

        String typeLabel = isIncome ? "INCOME" : "EXPENSE";
        h.tvType.setText(typeLabel);
        h.tvType.setBackgroundColor(isIncome ? Color.parseColor("#E8F5E9") : Color.parseColor("#FDECEA"));
        h.tvType.setTextColor(isIncome ? Color.parseColor("#1B8A1B") : Color.parseColor("#C0392B"));

        // Show pending sync indicator
        h.tvSync.setVisibility(t.isSynced() ? View.GONE : View.VISIBLE);

        h.itemView.setOnClickListener(v -> listener.onClick(t));
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvDate, tvCategory, tvSubCat, tvNote, tvAmount, tvType, tvSync;

        VH(View v) {
            super(v);
            tvDate = v.findViewById(R.id.tv_date);
            tvCategory = v.findViewById(R.id.tv_category);
            tvSubCat = v.findViewById(R.id.tv_subcategory);
            tvNote = v.findViewById(R.id.tv_note);
            tvAmount = v.findViewById(R.id.tv_amount);
            tvType = v.findViewById(R.id.tv_type);
            tvSync = v.findViewById(R.id.tv_sync_pending);
        }
    }
}
