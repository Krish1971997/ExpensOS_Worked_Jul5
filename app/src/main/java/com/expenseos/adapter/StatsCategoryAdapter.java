package com.expenseos.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

public class StatsCategoryAdapter extends RecyclerView.Adapter<StatsCategoryAdapter.VH> {

    public interface OnCategoryClick {
        void onClick(int categoryId, String categoryName);
    }

    private final List<Map<String, Object>> rows;
    private final BigDecimal total;
    private final OnCategoryClick listener;

    private static final int[] COLORS = {
            0xFFF59E0B, 0xFF16A34A, 0xFFDC2626, 0xFF2563EB, 0xFF7C3AED, 0xFF0891B2
    };

    public StatsCategoryAdapter(List<Map<String, Object>> rows, BigDecimal total, OnCategoryClick listener) {
        this.rows = rows;
        this.total = total.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ONE : total; // avoid /0
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_stats_category, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Map<String, Object> row = rows.get(pos);
        String name = (String) row.get("name");
        BigDecimal amount = (BigDecimal) row.get("total");
        int categoryId = (int) row.get("id");

        int pct = amount.multiply(BigDecimal.valueOf(100))
                .divide(total, 0, RoundingMode.HALF_UP).intValue();

        h.dot.setBackgroundColor(COLORS[pos % COLORS.length]);
        h.tvName.setText(name);
        h.tvPct.setText(pct + "%");
        h.tvAmount.setText("₹" + amount.toPlainString());
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(categoryId, name);
        });
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        View dot;
        TextView tvName, tvPct, tvAmount;

        VH(@NonNull View v) {
            super(v);
            dot = v.findViewById(R.id.viewCatDot);
            tvName = v.findViewById(R.id.tvCatName);
            tvPct = v.findViewById(R.id.tvCatPct);
            tvAmount = v.findViewById(R.id.tvCatAmount);
        }
    }
}