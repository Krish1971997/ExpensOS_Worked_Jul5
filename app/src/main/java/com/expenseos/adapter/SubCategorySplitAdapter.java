package com.expenseos.adapter;

import android.content.Context;
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

public class SubCategorySplitAdapter extends RecyclerView.Adapter<SubCategorySplitAdapter.ViewHolder> {

    private final Context context;
    private final List<Map<String, Object>> subCategoryList;
    private final BigDecimal totalCategoryAmount;

    public SubCategorySplitAdapter(Context context, List<Map<String, Object>> subCategoryList, BigDecimal totalCategoryAmount) {
        this.context = context;
        this.subCategoryList = subCategoryList;
        this.totalCategoryAmount = totalCategoryAmount != null && totalCategoryAmount.compareTo(BigDecimal.ZERO) > 0
                ? totalCategoryAmount
                : BigDecimal.ONE;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_subcategory_split, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Map<String, Object> item = subCategoryList.get(position);

        String subName = (String) item.get("subcategory");
        BigDecimal total = (BigDecimal) item.get("total");
        if (total == null) total = BigDecimal.ZERO;

        // Percentage calculation
        int percentage = total.multiply(new BigDecimal(100))
                .divide(totalCategoryAmount, 0, RoundingMode.HALF_UP)
                .intValue();

        holder.tvSubName.setText(subName != null ? subName : "Other");
        holder.tvPercent.setText(percentage + "%");
        holder.tvSubAmount.setText(String.format("₹ %.2f", total.doubleValue()));
    }

    @Override
    public int getItemCount() {
        return subCategoryList != null ? subCategoryList.size() : 0;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvSubName, tvPercent, tvSubAmount;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvSubName = itemView.findViewById(R.id.tvSubCategoryName);
            tvPercent = itemView.findViewById(R.id.tvSubCategoryPercent);
            tvSubAmount = itemView.findViewById(R.id.tvSubCategoryAmount);
        }
    }
}