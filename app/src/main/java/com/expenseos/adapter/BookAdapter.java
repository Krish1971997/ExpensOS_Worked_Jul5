package com.expenseos.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.model.CashBook;

import java.util.List;

public class BookAdapter extends RecyclerView.Adapter<BookAdapter.VH> {

    public interface OnBookSelected {
        void onOpen(CashBook book);
    }

    private final List<CashBook> data;
    private final int activeId;
    private final OnBookSelected listener;

    public BookAdapter(List<CashBook> data, int activeId, OnBookSelected listener) {
        this.data = data;
        this.activeId = activeId;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_book, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        CashBook b = data.get(pos);
        h.tvName.setText(b.getName());
        h.tvIncome.setText("₹" + (b.getTotalIncome() != null ? b.getTotalIncome().toPlainString() : "0"));
        h.tvExpense.setText("₹" + (b.getTotalExpense() != null ? b.getTotalExpense().toPlainString() : "0"));
        h.tvCreated.setText("Created: " + (b.getCreatedAt() != null
                ? b.getCreatedAt().substring(0, Math.min(10, b.getCreatedAt().length())) : ""));
        boolean active = b.getId() == activeId;
        h.tvActive.setVisibility(active ? View.VISIBLE : View.GONE);
        h.itemView.setBackgroundColor(active ? Color.parseColor("#E8F5E9") : Color.WHITE);
        h.btnOpen.setOnClickListener(v -> listener.onOpen(b));
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvName, tvIncome, tvExpense, tvCreated, tvActive;
        Button btnOpen;

        VH(View v) {
            super(v);
            tvName = v.findViewById(R.id.tv_book_name);
            tvIncome = v.findViewById(R.id.tv_book_income);
            tvExpense = v.findViewById(R.id.tv_book_expense);
            tvCreated = v.findViewById(R.id.tv_book_created);
            tvActive = v.findViewById(R.id.tv_book_active);
            btnOpen = v.findViewById(R.id.btn_open_book);
        }
    }
}
