package com.expenseos.ui;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.model.SchedulerConfig;

import java.util.List;

public class SchedulerAdapter extends RecyclerView.Adapter<SchedulerAdapter.VH> {

    public interface Listener {
        void onToggleEnabled(SchedulerConfig s, boolean enabled);
        void onRunNow(SchedulerConfig s);
        void onEdit(SchedulerConfig s);
    }

    private final List<SchedulerConfig> items;
    private final Listener listener;

    public SchedulerAdapter(List<SchedulerConfig> items, Listener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_scheduler, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        SchedulerConfig s = items.get(position);

        h.tvName.setText(s.getDisplayName() != null ? s.getDisplayName() : s.getName());
        h.tvRepeat.setText(s.getRepeatDescription());
        h.tvNextRun.setText("Next run: " + s.getNextRunDisplay());
        h.tvLastRun.setText("Last: " + s.getLastRunDisplay());

        String status = s.getLastRunStatus() != null ? s.getLastRunStatus() : "—";
        h.tvStatus.setText(status);
        int bg, fg;
        switch (status) {
            case "SUCCESS":
                bg = h.tvStatus.getContext().getResources().getColor(R.color.green_bg, null);
                fg = h.tvStatus.getContext().getResources().getColor(R.color.green, null);
                break;
            case "FAILED":
                bg = h.tvStatus.getContext().getResources().getColor(R.color.red_bg, null);
                fg = h.tvStatus.getContext().getResources().getColor(R.color.red, null);
                break;
            case "RUNNING":
                bg = h.tvStatus.getContext().getResources().getColor(R.color.amber_bg, null);
                fg = h.tvStatus.getContext().getResources().getColor(R.color.amber, null);
                break;
            default:
                bg = Color.LTGRAY;
                fg = h.tvStatus.getContext().getResources().getColor(R.color.text_muted, null);
        }
        h.tvStatus.getBackground().setTint(bg);
        h.tvStatus.setTextColor(fg);

        // Avoid firing the listener while we programmatically set state
        h.swEnabled.setOnCheckedChangeListener(null);
        h.swEnabled.setChecked(s.isEnabled());
        h.swEnabled.setOnCheckedChangeListener((btn, checked) -> {
            if (listener != null) listener.onToggleEnabled(s, checked);
        });

        h.btnRunNow.setOnClickListener(v -> {
            if (listener != null) listener.onRunNow(s);
        });
        h.btnEdit.setOnClickListener(v -> {
            if (listener != null) listener.onEdit(s);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvName, tvRepeat, tvNextRun, tvStatus, tvLastRun;
        Switch swEnabled;
        Button btnRunNow;
        ImageButton btnEdit;

        VH(@NonNull View v) {
            super(v);
            tvName = v.findViewById(R.id.tvSchedName);
            tvRepeat = v.findViewById(R.id.tvSchedRepeat);
            tvNextRun = v.findViewById(R.id.tvSchedNextRun);
            tvStatus = v.findViewById(R.id.tvSchedStatus);
            tvLastRun = v.findViewById(R.id.tvSchedLastRun);
            swEnabled = v.findViewById(R.id.swSchedEnabled);
            btnRunNow = v.findViewById(R.id.btnSchedRunNow);
            btnEdit = v.findViewById(R.id.btnSchedEdit);
        }
    }
}
