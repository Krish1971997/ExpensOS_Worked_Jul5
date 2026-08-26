package com.expenseos.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.dao.CashBookDao;
import com.expenseos.model.CashBook;
import com.expenseos.util.AppConfig;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private CashBookDao dao;
    private List<CashBook> books;
    private String search = null;
    private String sort = null; // null=updated, name_asc, balance_desc, balance_asc, created
    private static final int REQ_SMS_PERMISSION = 101;


    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_main);
        dao = new CashBookDao(this);

        com.expenseos.util.ReminderScheduler.scheduleDaily9PM(this);
        requestNotificationPermissionIfNeeded();

        // ADD — bottom nav:
        findViewById(R.id.navCashbooks).setOnClickListener(v -> {
            loadBooks();
        });

        com.expenseos.scheduler.SchedulerWorker.schedulePeriodic(this);

        findViewById(R.id.navStats).setOnClickListener(v ->
                startActivity(new Intent(this, StatsActivity.class)));

        findViewById(R.id.navPassbook).setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_SMS}, REQ_SMS_PERMISSION);
            } else {
                startActivity(new Intent(this, PassbookActivity.class));
            }
        });

        findViewById(R.id.navSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        findViewById(R.id.navIntegrations).setOnClickListener(v ->
                startActivity(new Intent(this, IntegrationsActivity.class)));

        // ── ADDED: AI Assistant Navigation Click Listener ──
        View navAi = findViewById(R.id.navAiAssistant);
        if (navAi != null) {
            navAi.setOnClickListener(v ->
                    startActivity(new Intent(this, ChatActivity.class)));
        }

        // Restore from Cloud
        findViewById(R.id.btnRestoreCloud).setOnClickListener(v -> showRestoreCloudDialog());

        findViewById(R.id.btnAllTxn).setOnClickListener(v ->
                startActivity(new Intent(this, AllTransactionsActivity.class)));

        // New Book
        findViewById(R.id.btnNewBook).setOnClickListener(v -> showNewBookDialog());

        // Search bar
        EditText etSearch = findViewById(R.id.etBookSearch);
        etSearch.addTextChangedListener(new TextWatcher() {
            public void afterTextChanged(Editable e) {
                search = e.toString().trim().isEmpty() ? null : e.toString().trim();
                loadBooks();
            }

            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }
        });

        // Sort button
        findViewById(R.id.btnBookSort).setOnClickListener(v -> showSortDialog());

        loadBooks();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadBooks();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }

    private void loadBooks() {
        books = dao.findAll(search, sort);
        RecyclerView rv = findViewById(R.id.rvBooks);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new BookAdapter());
    }

    // ── Sort dialog ───────────────────────────────────────
    private void showSortDialog() {
        String[] opts = {"Last Updated", "Name (A-Z)", "Balance (High→Low)",
                "Balance (Low→High)", "Last Created"};
        String[] keys = {null, "name_asc", "balance_desc", "balance_asc", "created"};
        int cur = 0;
        for (int i = 0; i < keys.length; i++)
            if ((keys[i] == null && sort == null) || (keys[i] != null && keys[i].equals(sort))) {
                cur = i;
                break;
            }

        new AlertDialog.Builder(this)
                .setTitle("Sort Books")
                .setSingleChoiceItems(opts, cur, null)
                .setPositiveButton("Apply", (d, w) -> {
                    int sel = ((AlertDialog) d).getListView().getCheckedItemPosition();
                    sort = keys[Math.max(0, sel)];
                    loadBooks();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── New book dialog ───────────────────────────────────
    private void showNewBookDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_new_book, null);
        EditText etName = v.findViewById(R.id.et_book_name);
        EditText etDesc = v.findViewById(R.id.et_book_desc);
        new AlertDialog.Builder(this)
                .setTitle("New Cash Book")
                .setView(v)
                .setPositiveButton("Create & Open", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) return;
                    int id = (int) dao.insert(name, etDesc.getText().toString().trim());
                    openBook(id, name);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openBook(int id, String name) {
        CashBook b = dao.findById(id);
        AppConfig.get(this).setActiveBook(id, name);
        startActivity(new Intent(this, HomeActivity.class));
    }

    // ── Inner RecyclerView adapter ────────────────────────
    class BookAdapter extends RecyclerView.Adapter<BookAdapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            View row;
            TextView tvName, tvCreated, tvNet;
            View vStatusBar;
            ImageButton btnMenu;

            VH(View v) {
                super(v);
                row = v.findViewById(R.id.row_book_item);
                tvName = v.findViewById(R.id.tv_book_name);
                tvCreated = v.findViewById(R.id.tv_book_created);
                vStatusBar = v.findViewById(R.id.v_book_status_bar);
                tvNet = v.findViewById(R.id.tv_book_net);
                btnMenu = v.findViewById(R.id.btn_book_menu);
            }
        }

        @Override
        public VH onCreateViewHolder(android.view.ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_book, p, false));
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            CashBook b = books.get(pos);
            Map<String, BigDecimal> sum = dao.getSummary(b.getId());
            BigDecimal income = sum.getOrDefault("income", BigDecimal.ZERO);
            BigDecimal expense = sum.getOrDefault("expense", BigDecimal.ZERO);
            BigDecimal net = income.subtract(expense);

            h.tvName.setText(b.getName());
            h.tvCreated.setText(b.getStatusLabel());

            boolean negative = net.signum() < 0;
            h.tvNet.setText((negative ? "-₹" : "₹") + String.format("%,.2f", net.abs()));
            h.tvNet.setTextColor(Color.parseColor(negative ? "#B91C1C" : "#2E7D32"));

            if (h.vStatusBar != null) {
                int statusColor = Color.parseColor(b.isActive() ? "#4CAF50" : "#DC2626");
                h.vStatusBar.setBackgroundColor(statusColor);
            }

            h.row.setOnClickListener(v -> openBook(b.getId(), b.getName()));

            h.btnMenu.setOnClickListener(v -> {
                PopupMenu popup = new PopupMenu(MainActivity.this, v);
                popup.getMenu().add(0, 1, 0, "Open");
                popup.getMenu().add(0, 2, 1, "Edit");
                popup.getMenu().add(0, 3, 2, "Delete");
                popup.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 1) {
                        openBook(b.getId(), b.getName());
                        return true;
                    } else if (item.getItemId() == 2) {
                        showEditDialog(b);
                        return true;
                    } else if (item.getItemId() == 3) {
                        showDeleteBookDialog(b);
                        return true;
                    }
                    return false;
                });
                popup.show();
            });
        }

        @Override
        public int getItemCount() {
            return books.size();
        }
    }

    // ── Edit book dialog ──────────────────────────────────
    private void showEditDialog(CashBook b) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_edit_book, null);
        EditText etName = v.findViewById(R.id.etEditBookName);
        EditText etDesc = v.findViewById(R.id.etEditBookDesc);
        Switch swActive = v.findViewById(R.id.swEditBookActive);

        etName.setText(b.getName());
        etDesc.setText(b.getDescription() != null ? b.getDescription() : "");
        swActive.setChecked(b.isActive());

        new AlertDialog.Builder(this)
                .setTitle("Edit Cash Book")
                .setView(v)
                .setPositiveButton("Save", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) return;
                    dao.update(b.getId(), name,
                            etDesc.getText().toString().trim(),
                            swActive.isChecked());
                    loadBooks();
                    Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showDeleteBookDialog(CashBook book) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_delete_book, null);
        TextView tvMsg = v.findViewById(R.id.tvDeleteConfirmMsg);
        EditText etConfirm = v.findViewById(R.id.etDeleteBookName);
        tvMsg.setText("Please type " + book.getName() + " to confirm");

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Delete " + book.getName() + " ?")
                .setView(v)
                .setPositiveButton("Delete", null)
                .setNegativeButton("Cancel", null)
                .create();

        dialog.setOnShowListener(d -> {
            Button deleteBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            deleteBtn.setEnabled(false);

            etConfirm.addTextChangedListener(new TextWatcher() {
                public void afterTextChanged(Editable s) {
                    deleteBtn.setEnabled(s.toString().equals(book.getName()));
                }

                public void beforeTextChanged(CharSequence s, int a, int c, int cn) {
                }

                public void onTextChanged(CharSequence s, int a, int b2, int c) {
                }
            });

            deleteBtn.setOnClickListener(view -> {
                dao.deleteCascade(book.getId());

                AppConfig cfg = AppConfig.get(this);
                if (cfg.getActiveBookId() == book.getId()) {
                    cfg.setActiveBook(0, null);
                }

                Toast.makeText(this, "Book deleted", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                loadBooks();
            });
        });

        dialog.show();
    }

    public void updateBookLabel() {
        String bookName = AppConfig.get(this).getActiveBookName();
        if (getSupportActionBar() != null) {
            getSupportActionBar().setSubtitle("● " + bookName);
        }
    }

    private void showRestoreCloudDialog() {
        AppConfig cfg = AppConfig.get(this);
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_cloud_restore, null);
        EditText etUrl = v.findViewById(R.id.etRestoreDbUrl);
        EditText etUser = v.findViewById(R.id.etRestoreDbUser);
        EditText etPass = v.findViewById(R.id.etRestoreDbPass);

        etUrl.setText(cfg.getDbUrl());
        etUser.setText(cfg.getDbUser());
        etPass.setText(cfg.getDbPassword());

        new AlertDialog.Builder(this)
                .setTitle("☁ Restore from Cloud")
                .setMessage("Enter your Neon DB credentials to pull down all cash books, categories, and transactions.")
                .setView(v)
                .setPositiveButton("Restore", (d, w) -> {
                    String url = etUrl.getText().toString().trim();
                    String user = etUser.getText().toString().trim();
                    String pass = etPass.getText().toString().trim();
                    if (url.isEmpty()) {
                        Toast.makeText(this, "DB URL is required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    cfg.setDb(url, user, pass);
                    doCloudRestore();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doCloudRestore() {
        Toast.makeText(this, "Restoring from cloud…", Toast.LENGTH_SHORT).show();
        com.expenseos.sync.SyncManager.get().restoreAllFromCloud(this, (ok, summary) -> {
            Toast.makeText(this, ok ? "✔ " + summary : "✘ " + summary, Toast.LENGTH_LONG).show();
            if (ok) loadBooks();
        });
    }

    private void requestSmsPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_SMS}, REQ_SMS_PERMISSION);
        }
    }
}