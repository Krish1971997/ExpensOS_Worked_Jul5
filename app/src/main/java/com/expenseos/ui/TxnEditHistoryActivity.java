package com.expenseos.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.dao.AuditLogDao;
import com.expenseos.model.AuditLog;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class TxnEditHistoryActivity extends AppCompatActivity {

    private static final DateTimeFormatter HEADER_FMT = DateTimeFormatter.ofPattern("d MMMM yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("hh:mm a");

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_txn_edit_history);

        int txnId = getIntent().getIntExtra("txnId", -1);
        findViewById(R.id.btnHistoryBack).setOnClickListener(v -> finish());

        List<AuditLog> history = new AuditLogDao(this).findByTransactionId(txnId); // ASC
        // Show newest first, like the old-app reference
        List<AuditLog> newestFirst = new ArrayList<>(history);
        java.util.Collections.reverse(newestFirst);

        ((TextView) findViewById(R.id.tvHistoryCount)).setText("Showing " + newestFirst.size() + " activities");

        RecyclerView rv = findViewById(R.id.rvHistory);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new HistoryAdapter(newestFirst));
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ENTRY = 1;

    private static class Row {
        int type;
        String headerText;
        AuditLog log;
    }

    class HistoryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<Row> rows = new ArrayList<>();

        HistoryAdapter(List<AuditLog> logs) {
            LocalDate lastDate = null;
            for (AuditLog a : logs) {
                LocalDate d = a.getChangedAt() != null ? a.getChangedAt().toLocalDate() : null;
                if (d != null && !d.equals(lastDate)) {
                    Row header = new Row();
                    header.type = TYPE_HEADER;
                    header.headerText = d.format(HEADER_FMT);
                    rows.add(header);
                    lastDate = d;
                }
                Row entry = new Row();
                entry.type = TYPE_ENTRY;
                entry.log = a;
                rows.add(entry);
            }
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_HEADER) {
                View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_txn_history_header, parent, false);
                return new RecyclerView.ViewHolder(v) {
                };
            }
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_txn_history, parent, false);
            return new EntryVH(v);
        }

        class EntryVH extends RecyclerView.ViewHolder {
            TextView tvTitle, tvTime, tvDetail;

            EntryVH(View v) {
                super(v);
                tvTitle = v.findViewById(R.id.tvHistTitle);
                tvTime = v.findViewById(R.id.tvHistTime);
                tvDetail = v.findViewById(R.id.tvHistDetail);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            if (row.type == TYPE_HEADER) {
                ((TextView) holder.itemView).setText(row.headerText);
                return;
            }

            EntryVH h = (EntryVH) holder;
            AuditLog a = row.log;
            h.tvTitle.setText(titleFor(a));
            h.tvTime.setText(a.getChangedAt() != null ? a.getChangedAt().format(TIME_FMT) : "");

            if ("UPDATE".equals(a.getAction()) && a.getFieldName() != null) {
                h.tvDetail.setVisibility(View.VISIBLE);
                h.tvDetail.setText("To: " + safe(a.getNewValue()) + "\nFrom: " + safe(a.getOldValue()));
            } else if (a.getNote() != null && !a.getNote().isEmpty()) {
                h.tvDetail.setVisibility(View.VISIBLE);
                h.tvDetail.setText(a.getNote());
            } else {
                h.tvDetail.setVisibility(View.GONE);
            }
        }

        private String titleFor(AuditLog a) {
            switch (a.getAction()) {
                case "CREATE": return "Created Entry";
                case "DELETE": return "Deleted Entry";
                case "RECEIPT_ADD": return "Added 1 attachment";
                case "RECEIPT_DEL": return "Removed 1 attachment";
                case "UPDATE": return "Edited " + (a.getFieldName() != null ? a.getFieldName() : "entry");
                default: return a.getAction();
            }
        }

        private String safe(String s) {
            return s != null ? s : "—";
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }
}
