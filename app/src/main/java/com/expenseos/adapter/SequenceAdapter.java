package com.expenseos.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;

import java.util.List;

public class SequenceAdapter extends RecyclerView.Adapter<SequenceAdapter.VH> {

    public interface Listener {
        void onEdit(String tableName, long currentNextId);

        void onResync(String tableName);
    }

    private final List<SequenceRow> rows;
    private final Listener listener;

    public SequenceAdapter(List<SequenceRow> rows, Listener listener) {
        this.rows = rows;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_sequence, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        SequenceRow row = rows.get(position);
        h.tvTableName.setText(row.tableName);
        h.tvNextId.setText("next_id: " + row.nextId);
        h.btnEdit.setOnClickListener(v -> listener.onEdit(row.tableName, row.nextId));
        h.btnResync.setOnClickListener(v -> listener.onResync(row.tableName));
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    public void updateRow(String tableName, long newNextId) {
        for (SequenceRow r : rows) {
            if (r.tableName.equals(tableName)) {
                r.nextId = newNextId;
                break;
            }
        }
        notifyDataSetChanged();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvTableName, tvNextId;
        ImageButton btnEdit, btnResync;

        VH(View v) {
            super(v);
            tvTableName = v.findViewById(R.id.tvTableName);
            tvNextId = v.findViewById(R.id.tvNextId);
            btnEdit = v.findViewById(R.id.btnEdit);
            btnResync = v.findViewById(R.id.btnResync);
        }
    }

    public static class SequenceRow {
        public final String tableName;
        public long nextId;

        public SequenceRow(String tableName, long nextId) {
            this.tableName = tableName;
            this.nextId = nextId;
        }
    }
}