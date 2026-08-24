package com.expenseos.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.model.PassbookEntry;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class PassbookAdapter extends RecyclerView.Adapter<PassbookAdapter.VH> {

    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault());

    private final List<PassbookEntry> entries;
    private final Runnable onSelectionChanged;
    // Keyed by smsId rather than list position — positions shift as the
    // adapter re-binds/filters, smsId doesn't.
    private final Set<Long> selected = new HashSet<>();

    public PassbookAdapter(List<PassbookEntry> entries, Runnable onSelectionChanged) {
        this.entries = entries;
        this.onSelectionChanged = onSelectionChanged;
    }

    public int getSelectedCount() {
        return selected.size();
    }

    public List<PassbookEntry> getSelectedEntries() {
        List<PassbookEntry> out = new ArrayList<>();
        for (PassbookEntry e : entries)
            if (selected.contains(e.getSmsId())) out.add(e);
        return out;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_passbook_entry, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        PassbookEntry e = entries.get(position);
        Context ctx = h.itemView.getContext();
        boolean isCredit = "CREDIT".equals(e.getType());

        h.tvType.setText(isCredit ? "CREDIT" : "DEBIT");
        h.tvType.setBackgroundColor(ContextCompat.getColor(ctx,
                isCredit ? R.color.income_badge_bg : R.color.expense_badge_bg));
        h.tvType.setTextColor(ContextCompat.getColor(ctx,
                isCredit ? R.color.income_badge_text : R.color.expense_badge_text));

        h.tvAmount.setText((isCredit ? "+ ₹" : "- ₹") + e.getAmount().toPlainString());
        h.tvAmount.setTextColor(ContextCompat.getColor(ctx, isCredit ? R.color.green : R.color.red));

        h.tvRemark.setText(e.getRemark() != null ? e.getRemark() : e.getRawBody());
        h.tvRemark.setOnLongClickListener(v -> {
            android.widget.Toast.makeText(ctx, "SMS ID: " + e.getSmsId(), android.widget.Toast.LENGTH_SHORT).show();
            return true;
        });

        h.tvSender.setText(e.getSender());

        if (e.getPaymentType() != null && !e.getPaymentType().isEmpty()) {
            h.tvPaymentType.setText(e.getPaymentType());
            h.tvPaymentType.setVisibility(View.VISIBLE);
        } else {
            h.tvPaymentType.setVisibility(View.GONE);
        }

        h.tvDate.setText(DATE_FMT.format(new java.util.Date(e.getTimestampMillis())));

        // Avoid firing the listener while we programmatically set state
        h.cb.setOnCheckedChangeListener(null);
        h.cb.setChecked(selected.contains(e.getSmsId()));
        h.cb.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) selected.add(e.getSmsId());
            else selected.remove(e.getSmsId());
            if (onSelectionChanged != null) onSelectionChanged.run();
        });

        // Tapping anywhere on the row toggles the checkbox too
        h.itemView.setOnClickListener(v -> h.cb.setChecked(!h.cb.isChecked()));
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        CheckBox cb;
        TextView tvType, tvAmount, tvRemark, tvSender, tvDate, tvPaymentType;

        VH(@NonNull View v) {
            super(v);
            cb = v.findViewById(R.id.cbPassbookEntry);
            tvType = v.findViewById(R.id.tvPassbookType);
            tvAmount = v.findViewById(R.id.tvPassbookAmount);
            tvRemark = v.findViewById(R.id.tvPassbookRemark);
            tvSender = v.findViewById(R.id.tvPassbookSender);
            tvDate = v.findViewById(R.id.tvPassbookDate);
            tvPaymentType = v.findViewById(R.id.tvPassbookPaymentType);
        }
    }
}
