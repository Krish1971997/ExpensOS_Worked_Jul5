package com.expenseos.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.dao.AuditLogDao;
import com.expenseos.model.AuditLog;
import com.expenseos.util.AppConfig;

import java.util.List;

public class AuditLogActivity extends AppCompatActivity {

    private AuditLogDao auditLogDao;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_audit_log);

        int bookId = AppConfig.get(this).getActiveBookId();
        auditLogDao = new AuditLogDao(this);

        findViewById(R.id.btnBackAudit).setOnClickListener(v -> finish());

        loadAuditLog(bookId);
    }

    private void loadAuditLog(int bookId) {
        // AuditLogDao மூலம் transaction_audit_log அட்டவணையில் இருந்து தரவுகள் பெறப்படுகிறது
        List<AuditLog> auditLogs = auditLogDao.findRecentByBook(bookId, 1, 100);
        int totalCount = auditLogDao.countByBook(bookId);

        ((TextView) findViewById(R.id.tvAuditCount))
                .setText(totalCount + " records");

        RecyclerView rv = findViewById(R.id.rvAuditLog);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new AuditAdapter(auditLogs));
    }

    // ── Audit Log Adapter ────────────────────────────────────
    static class AuditAdapter extends RecyclerView.Adapter<AuditAdapter.VH> {
        private final List<AuditLog> data;

        AuditAdapter(List<AuditLog> data) {
            this.data = data;
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvId, tvAction, tvDate, tvAmount, tvCat, tvDetails, tvChangedBy;

            VH(View v) {
                super(v);
                tvId = v.findViewById(R.id.auditTvId);
                tvAction = v.findViewById(R.id.auditTvType); // Reuse type TextView for Action
                tvDate = v.findViewById(R.id.auditTvDate);
                tvAmount = v.findViewById(R.id.auditTvAmount);
                tvCat = v.findViewById(R.id.auditTvCat);
                tvDetails = v.findViewById(R.id.auditTvNote); // Field changes or Note
                tvChangedBy = v.findViewById(R.id.auditTvSynced); // Changed by user
            }
        }

        @Override
        public VH onCreateViewHolder(ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_audit_log, p, false));
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            AuditLog item = data.get(pos);

            h.tvId.setText("Txn #" + item.getTransactionId());
            h.tvId.setOnLongClickListener(v -> {
                Toast.makeText(v.getContext(),
                        "Audit Log ID: " + item.getId() + "  |  Transaction ID: " + item.getTransactionId(),
                        Toast.LENGTH_SHORT).show();
                return true;
            });

            // CREATE / UPDATE / DELETE Action
            String action = item.getAction() != null ? item.getAction() : "";
            h.tvAction.setText(action);

            // Action Type Color setup
            int actionColor = R.color.text_muted;
            if ("CREATE".equals(action)) {
                actionColor = R.color.green;
            } else if ("DELETE".equals(action)) {
                actionColor = R.color.red;
            } else if ("UPDATE".equals(action)) {
                actionColor = R.color.amber;
            }
            h.tvAction.setTextColor(h.itemView.getContext().getResources().getColor(actionColor, null));

            // Audit Date / Time
            h.tvDate.setText(item.getChangedAt() != null ? item.getChangedAt().toString().replace("T", " ") : "");

            // Transaction Amount & Category
            if (item.getTxnAmount() != null) {
                h.tvAmount.setText("₹" + item.getTxnAmount().toPlainString());
            } else {
                h.tvAmount.setText("");
            }
            h.tvCat.setText(item.getTxnCategoryName() != null ? item.getTxnCategoryName() : "");

            // Detailed Change info (e.g. Field Name: Old -> New or Note)
            StringBuilder details = new StringBuilder();
            if (item.getFieldName() != null) {
                details.append(item.getFieldName()).append(": ")
                        .append(item.getOldValue() != null ? item.getOldValue() : "empty")
                        .append(" ➔ ")
                        .append(item.getNewValue() != null ? item.getNewValue() : "empty");
            } else if (item.getNote() != null) {
                details.append(item.getNote());
            }
            h.tvDetails.setText(details.toString());

            // Changed By
            h.tvChangedBy.setText(item.getChangedBy() != null ? item.getChangedBy() : "user");
        }

        @Override
        public int getItemCount() {
            return data.size();
        }
    }
}