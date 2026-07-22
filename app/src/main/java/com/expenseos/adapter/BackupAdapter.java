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

    public interface OnShare {
        void onShare(BackupManager.BackupEntry entry);   // export/share the local zip file
    }

    public interface OnRestore {
        void onRestore(BackupManager.BackupEntry entry);  // restore this backup into the app
    }

    public interface OnDelete {
        void onDelete(BackupManager.BackupEntry entry);
    }

    private final List<BackupManager.BackupEntry> data;
    private final OnShare shareListener;
    private final OnRestore restoreListener;

    private final OnDelete deleteListener;


    public BackupAdapter(List<BackupManager.BackupEntry> data, OnShare shareListener,
                         OnRestore restoreListener, OnDelete deleteListener) {
        this.data = data;
        this.shareListener = shareListener;
        this.restoreListener = restoreListener;
        this.deleteListener = deleteListener;
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

        boolean cloud = "CLOUD".equals(e.backupMode);
        h.tvMode.setText(cloud ? "☁ CLOUD" : "📱 LOCAL");
        h.tvMode.setTextColor(cloud ? Color.parseColor("#1565C0") : Color.parseColor("#546E7A"));

        // Share only makes sense for LOCAL files (a real File exists)
        h.btnShare.setOnClickListener(v -> shareListener.onShare(e));  // now fires for both LOCAL & CLOUD        h.btnShare.setOnClickListener(v -> shareListener.onShare(e));
        h.btnRestore.setOnClickListener(v -> restoreListener.onRestore(e));
        h.btnDelete.setOnClickListener(v -> deleteListener.onDelete(e));
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvFileName, tvType, tvSize, tvDate, tvDesc, tvRecords, tvStatus, tvMode;
        Button btnShare, btnRestore, btnDelete;

        VH(View v) {
            super(v);
            tvFileName = v.findViewById(R.id.tv_backup_filename);
            tvType = v.findViewById(R.id.tv_backup_type);
            tvSize = v.findViewById(R.id.tv_backup_size);
            tvDate = v.findViewById(R.id.tv_backup_date);
            tvDesc = v.findViewById(R.id.tv_backup_desc);
            tvRecords = v.findViewById(R.id.tv_backup_records);
            tvStatus = v.findViewById(R.id.tv_backup_status);
            tvMode = v.findViewById(R.id.tv_backup_mode);
            btnShare = v.findViewById(R.id.btn_backup_download);
            btnRestore = v.findViewById(R.id.btn_backup_restore);
            btnDelete = v.findViewById(R.id.btn_backup_delete);
        }
    }
}