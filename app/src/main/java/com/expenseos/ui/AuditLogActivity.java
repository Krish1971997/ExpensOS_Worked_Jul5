// ═══ AuditLogActivity.java ═══
package com.expenseos.ui;

import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.db.LocalDB;

import java.util.ArrayList;
import java.util.List;

public class AuditLogActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_audit_log);

//        SharedPreferences prefs = getSharedPreferences("expenseos_prefs", MODE_PRIVATE);
//        int bookId = prefs.getInt("active_book_id", 0);
        int bookId = com.expenseos.util.AppConfig.get(this).getActiveBookId();

        findViewById(R.id.btnBackAudit).setOnClickListener(v -> finish());

        loadAuditLog(bookId);
    }

    private void loadAuditLog(int bookId) {
        // SQLite doesn't have audit_log table by default
        // Show transactions with datetime as a simple "history"
        Cursor c = LocalDB.getInstance(this).getReadableDatabase().rawQuery(
                "SELECT t.id, t.type, t.txn_datetime, t.amount, " +
                        "c.name AS cat_name, t.note, t.synced " +
                        "FROM transactions t " +
                        "LEFT JOIN categories c ON t.category_id = c.id " +
                        "WHERE t.book_id = ? " +
                        "ORDER BY t.txn_datetime DESC LIMIT 100",
                new String[]{String.valueOf(bookId)});

        List<String[]> rows = new ArrayList<>();
        while (c.moveToNext()) {
            rows.add(new String[]{
                    c.getString(0),  // id
                    c.getString(1),  // type
                    c.getString(2),  // datetime
                    c.getString(3),  // amount
                    c.getString(4),  // category
                    c.getString(5),  // note
                    c.getString(6)   // synced
            });
        }
        c.close();

        ((TextView) findViewById(R.id.tvAuditCount))
                .setText(rows.size() + " records");

        RecyclerView rv = findViewById(R.id.rvAuditLog);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new AuditAdapter(rows));
    }

    // ── Simple adapter ────────────────────────────────────
    static class AuditAdapter extends RecyclerView.Adapter<AuditAdapter.VH> {
        private final List<String[]> data;

        AuditAdapter(List<String[]> data) {
            this.data = data;
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvId, tvType, tvDate, tvAmount, tvCat, tvNote, tvSynced;

            VH(View v) {
                super(v);
                tvId = v.findViewById(R.id.auditTvId);
                tvType = v.findViewById(R.id.auditTvType);
                tvDate = v.findViewById(R.id.auditTvDate);
                tvAmount = v.findViewById(R.id.auditTvAmount);
                tvCat = v.findViewById(R.id.auditTvCat);
                tvNote = v.findViewById(R.id.auditTvNote);
                tvSynced = v.findViewById(R.id.auditTvSynced);
            }
        }

        @Override
        public VH onCreateViewHolder(ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_audit_log, p, false));
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            String[] r = data.get(pos);
            boolean isIncome = "INCOME".equals(r[1]);

            h.tvId.setText("#" + r[0]);
            h.tvType.setText(r[1]);
            h.tvType.setTextColor(h.itemView.getContext().getResources()
                    .getColor(isIncome ? R.color.green : R.color.red, null));
            h.tvDate.setText(r[2] != null ? r[2].substring(0, 16) : "");
            h.tvAmount.setText((isIncome ? "+₹" : "-₹") + r[3]);
            h.tvAmount.setTextColor(h.itemView.getContext().getResources()
                    .getColor(isIncome ? R.color.green : R.color.red, null));
            h.tvCat.setText(r[4] != null ? r[4] : "");
            h.tvNote.setText(r[5] != null ? r[5] : "");
            h.tvSynced.setText("1".equals(r[6]) ? "✓ Synced" : "⏳ Pending");
            h.tvSynced.setTextColor(h.itemView.getContext().getResources()
                    .getColor("1".equals(r[6]) ? R.color.green : R.color.amber, null));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }
    }
}