package com.expenseos.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.dao.RecycleBinDao;
import com.expenseos.dao.RecycleBinDao.RecycledItem;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class RecycleBinActivity extends AppCompatActivity {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("d MMM yyyy, hh:mm a");

    private RecycleBinDao dao;
    private RecyclerView rv;
    private View emptyState;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_recycle_bin);

        dao = new RecycleBinDao(this);
        rv = findViewById(R.id.rvRecycleBin);
        emptyState = findViewById(R.id.emptyRecycleBinState);
        rv.setLayoutManager(new LinearLayoutManager(this));

        findViewById(R.id.btnRecycleBinBack).setOnClickListener(v -> finish());

        load();
    }

    private void load() {
        List<RecycledItem> items = dao.findAll();
        if (items.isEmpty()) {
            rv.setVisibility(View.GONE);
            emptyState.setVisibility(View.VISIBLE);
        } else {
            rv.setVisibility(View.VISIBLE);
            emptyState.setVisibility(View.GONE);
            rv.setAdapter(new Adapter(items));
        }
    }

    private String labelFor(String table) {
        switch (table) {
            case "categories":
                return "Category";
            case "sub_categories":
                return "Sub-category";
            case "transactions":
                return "Transaction";
            case "cash_books":
                return "Cash Book";
            case "column_definitions":
                return "Custom Field";
            case "events":
                return "Event";
            case "keyword_mappings":
                return "Keyword Mapping";
            case "payment_types":
                return "Payment Type";
            case "transaction_receipts":
                return "Attachment";
            case "reminders":
                return "Reminder";
            case "tasks":
                return "Task";
            case "budget_categories":
                return "Budget Limit";
            default:
                return table;
        }
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        private final List<RecycledItem> items;

        Adapter(List<RecycledItem> items) {
            this.items = items;
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvType, tvName, tvDeletedAt;
            View btnRestore, btnPurge;

            VH(View v) {
                super(v);
                tvType = v.findViewById(R.id.tvBinType);
                tvName = v.findViewById(R.id.tvBinName);
                tvDeletedAt = v.findViewById(R.id.tvBinDeletedAt);
                btnRestore = v.findViewById(R.id.btnBinRestore);
                btnPurge = v.findViewById(R.id.btnBinPurge);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recycle_bin, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            RecycledItem item = items.get(pos);
            h.tvType.setText(labelFor(item.tableName()));
            h.tvName.setText(item.displayName());
            h.tvDeletedAt.setText(item.deletedAt() != null
                    ? LocalDateTime.parse(item.deletedAt().replace(" ", "T")).format(FMT)
                    : "");

            h.btnRestore.setOnClickListener(v -> {
                boolean ok = dao.restore(item.id());
                Toast.makeText(RecycleBinActivity.this,
                        ok ? "Restored" : "Couldn't restore — that id is already in use", Toast.LENGTH_SHORT).show();
                load();
            });

            h.btnPurge.setOnClickListener(v -> new AlertDialog.Builder(RecycleBinActivity.this)
                    .setTitle("Delete forever?")
                    .setMessage(item.displayName() + " will be permanently removed and can't be restored.")
                    .setPositiveButton("Delete Forever", (d, w) -> {
                        dao.purge(item.id());
                        load();
                    })
                    .setNegativeButton("Cancel", null)
                    .show());
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}