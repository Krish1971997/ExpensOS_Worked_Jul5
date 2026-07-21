package com.expenseos.ui;

import android.content.Intent;
import android.graphics.Color;
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

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_main);
        dao = new CashBookDao(this);

        // Settings
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

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
        if (b != null && !b.isActive()) {
            Toast.makeText(this, "This book is inactive. Edit it to activate.", Toast.LENGTH_SHORT).show();
            return;
        }
        AppConfig.get(this).setActiveBook(id, name);   // <-- replace SharedPreferences block with this
        startActivity(new Intent(this, HomeActivity.class));
    }

    // ── Inner RecyclerView adapter ────────────────────────
    // NOTE: item_book.xml was redesigned to a single net-amount row with a
    // per-item menu button (btn_book_menu) instead of separate income/expense
    // text views and separate Open/Edit buttons. This adapter was updated to
    // match: tap the row to open, tap the menu (⋮) for Open/Edit options.
    class BookAdapter extends RecyclerView.Adapter<BookAdapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            View row;
            TextView tvName, tvCreated, tvActiveBadge, tvNet;
            ImageButton btnMenu;

            VH(View v) {
                super(v);
                row = v.findViewById(R.id.row_book_item);
                tvName = v.findViewById(R.id.tv_book_name);
                tvCreated = v.findViewById(R.id.tv_book_created);
                tvActiveBadge = v.findViewById(R.id.tv_book_active);
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
//            h.tvCreated.setText(b.getFormattedDate() != null ? b.getFormattedDate() : "");
            h.tvCreated.setText(b.getStatusLabel());

            boolean negative = net.signum() < 0;
            h.tvNet.setText((negative ? "-₹" : "₹") + String.format("%,.2f", net.abs()));
            h.tvNet.setTextColor(Color.parseColor(negative ? "#B91C1C" : "#2E7D32"));

            // Active badge — item_book.xml just shows/hides it (green pill), no INACTIVE state built in
            h.tvActiveBadge.setVisibility(b.isActive() ? View.VISIBLE : View.GONE);

            // Row tap = open (only if active)
            h.row.setOnClickListener(v -> {
                if (b.isActive()) {
                    openBook(b.getId(), b.getName());
                } else {
                    Toast.makeText(MainActivity.this,
                            "This book is inactive. Edit it to activate.",
                            Toast.LENGTH_SHORT).show();
                }
            });

            // Menu button = Open / Edit
            h.btnMenu.setOnClickListener(v -> {
                PopupMenu popup = new PopupMenu(MainActivity.this, v);
                popup.getMenu().add(0, 1, 0, "Open");
                popup.getMenu().add(0, 2, 1, "Edit");
                popup.getMenu().add(0, 3, 2, "Delete");   // <-- new
                popup.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 1) {
                        if (b.isActive()) openBook(b.getId(), b.getName());
                        else Toast.makeText(MainActivity.this, "This book is inactive. Edit it to activate.", Toast.LENGTH_SHORT).show();
                        return true;
                    } else if (item.getItemId() == 2) {
                        showEditDialog(b);
                        return true;
                    } else if (item.getItemId() == 3) {          // <-- new
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
                public void beforeTextChanged(CharSequence s, int a, int c, int cn) {}
                public void onTextChanged(CharSequence s, int a, int b2, int c) {}
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
}