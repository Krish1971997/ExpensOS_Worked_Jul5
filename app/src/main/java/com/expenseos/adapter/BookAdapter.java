package com.expenseos.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.model.CashBook;

import java.math.BigDecimal;
import java.util.List;

public class BookAdapter extends RecyclerView.Adapter<BookAdapter.VH> {

    public interface OnBookSelected {
        void onOpen(CashBook book);
    }

    public interface OnBookEdit {
        void onEdit(CashBook book);
    }

    private final List<CashBook> data;
    private final int activeId;
    private final OnBookSelected openListener;
    private final OnBookEdit editListener;

    public BookAdapter(List<CashBook> data, int activeId, OnBookSelected openListener, OnBookEdit editListener) {
        this.data = data;
        this.activeId = activeId;
        this.openListener = openListener;
        this.editListener = editListener;
    }

    // Backward-compatible constructor (no edit callback)
    public BookAdapter(List<CashBook> data, int activeId, OnBookSelected openListener) {
        this(data, activeId, openListener, null);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_book, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        CashBook b = data.get(pos);
        h.tvName.setText(b.getName());
        h.tvCreated.setText("Created: " + b.getFormattedDate());

        BigDecimal income = b.getTotalIncome() != null ? b.getTotalIncome() : BigDecimal.ZERO;
        BigDecimal expense = b.getTotalExpense() != null ? b.getTotalExpense() : BigDecimal.ZERO;
        BigDecimal net = income.subtract(expense);
        boolean negative = net.signum() < 0;
        h.tvNet.setText((negative ? "-₹" : "₹") + net.abs().toPlainString());
        h.tvNet.setTextColor(Color.parseColor(negative ? "#B91C1C" : "#2E7D32"));

        boolean isSelected = b.getId() == activeId;
        h.tvActive.setVisibility((isSelected || b.isActive()) ? View.VISIBLE : View.GONE);

        h.itemView.setBackgroundColor(isSelected ? Color.parseColor("#E8F5E9") : Color.WHITE);

        boolean canOpen = b.isActive();
        h.row.setOnClickListener(v -> {
            if (canOpen && openListener != null) openListener.onOpen(b);
        });

        h.btnMenu.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(v.getContext(), v);
            popup.getMenu().add(0, 1, 0, "Open");
            popup.getMenu().add(0, 2, 1, "Edit");
            popup.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) {
                    if (canOpen && openListener != null) openListener.onOpen(b);
                    return true;
                } else if (item.getItemId() == 2) {
                    if (editListener != null) editListener.onEdit(b);
                    return true;
                }
                return false;
            });
            popup.show();
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        View row;
        TextView tvName, tvCreated, tvActive, tvNet;
        ImageButton btnMenu;

        VH(View v) {
            super(v);
            row = v.findViewById(R.id.row_book_item);
            tvName = v.findViewById(R.id.tv_book_name);
            tvCreated = v.findViewById(R.id.tv_book_created);
            tvActive = v.findViewById(R.id.tv_book_active);
            tvNet = v.findViewById(R.id.tv_book_net);
            btnMenu = v.findViewById(R.id.btn_book_menu);
        }
    }
}