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
import com.expenseos.sync.BackupManager;

import java.util.List;

public class BackupAdapter extends RecyclerView.Adapter<BackupAdapter.VH> {

    public interface OnDownload {
        void onDownload(BackupManager.BackupEntry entry);
    }

    private final List<BackupManager.BackupEntry> data;
    private final OnDownload listener;

    public BackupAdapter(List<BackupManager.BackupEntry> data, OnDownload listener) {
        this.data = data;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_backup, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        BackupManager.BackupEntry e = data.get(pos);
        h.tvFileName.setText(e.fileName != null ? e.fileName : "—");
        h.tvType.setText(e.backupType != null ? e.backupType : "MANUAL");
        h.tvSize.setText(e.getSizeFormatted());
        h.tvDate.setText(e.createdAt != null
                ? e.createdAt.substring(0, Math.min(16, e.createdAt.length())) : "");
        h.tvDesc.setText(e.description != null && !e.description.isEmpty() ? e.description : "—");
        h.tvRecords.setText("▲" + e.incomeCount + "  ▼" + e.expenseCount);
        boolean ok = "SUCCESS".equals(e.status);
        h.tvStatus.setText(ok ? "✔ SUCCESS" : e.status);
        h.tvStatus.setTextColor(ok ? Color.parseColor("#1B8A1B") : Color.parseColor("#C0392B"));
        h.btnDownload.setOnClickListener(v -> listener.onDownload(e));
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvFileName, tvType, tvSize, tvDate, tvDesc, tvRecords, tvStatus;
        Button btnDownload;

        VH(View v) {
            super(v);
            tvFileName = v.findViewById(R.id.tv_backup_filename);
            tvType = v.findViewById(R.id.tv_backup_type);
            tvSize = v.findViewById(R.id.tv_backup_size);
            tvDate = v.findViewById(R.id.tv_backup_date);
            tvDesc = v.findViewById(R.id.tv_backup_desc);
            tvRecords = v.findViewById(R.id.tv_backup_records);
            tvStatus = v.findViewById(R.id.tv_backup_status);
            btnDownload = v.findViewById(R.id.btn_backup_download);
        }
    }
}
