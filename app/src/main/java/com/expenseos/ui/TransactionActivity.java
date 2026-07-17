package com.expenseos.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.adapter.TransactionAdapter;
import com.expenseos.dao.TransactionDao;
import com.expenseos.model.Transaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class TransactionActivity extends AppCompatActivity {

    private int bookId;
    private TransactionDao txnDao;

    // Filter state
    private String filterType = null;
    private String sortBy = "date";
    private String sortDir = "desc";
    private String searchQuery = null;
    private String dateFrom = null;
    private String dateTo = null;
    private int page = 1;
    private static final int PAGE_SIZE = 15;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_transactions);

//        SharedPreferences prefs = getSharedPreferences("expenseos_prefs", MODE_PRIVATE);
//        bookId = prefs.getInt("active_book_id", 0);
        bookId = com.expenseos.util.AppConfig.get(this).getActiveBookId();
        txnDao = new TransactionDao(this);

        // If launched with type (from Home + Income/Expense buttons)
        String initType = getIntent().getStringExtra("type");
        if (initType != null) showAddDialog(initType);

        setupToolbar();
        setupFilterTabs();
        setupSortButtons();
        setupSearchBar();
        setupPagination();

        loadData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadData();
    }

    // ── Toolbar ───────────────────────────────────────────
    private void setupToolbar() {
        findViewById(R.id.btnBackTxn).setOnClickListener(v -> finish());
        findViewById(R.id.btnAddIncome2).setOnClickListener(v -> showAddDialog("INCOME"));
        findViewById(R.id.btnAddExpense2).setOnClickListener(v -> showAddDialog("EXPENSE"));
        // Bulk Add
        findViewById(R.id.btnBulkAdd).setOnClickListener(v ->
                startActivity(new Intent(this, BulkAddActivity.class)));
    }

    // ── Filter tabs: All / Income / Expense ───────────────
    private void setupFilterTabs() {
        findViewById(R.id.btnAll).setOnClickListener(v -> {
            filterType = null;
            page = 1;
            loadData();
        });
        findViewById(R.id.btnIncome).setOnClickListener(v -> {
            filterType = "INCOME";
            page = 1;
            loadData();
        });
        findViewById(R.id.btnExpense).setOnClickListener(v -> {
            filterType = "EXPENSE";
            page = 1;
            loadData();
        });
    }

    // ── Sort buttons ──────────────────────────────────────
    private void setupSortButtons() {
        // Show sort dialog on long-press of table header or sort icon
        findViewById(R.id.btnSort).setOnClickListener(v -> showSortDialog());
    }

    private void showSortDialog() {
        String[] cols = {"Date", "Amount", "Category", "Type", "Note"};
        String[] keys = {"date", "amount", "category", "type", "note"};
        String[] dirs = {"Descending (newest first)", "Ascending (oldest first)"};
        String[] dirKeys = {"desc", "asc"};

        // Current selections
        int selCol = 0;
        for (int i = 0; i < keys.length; i++)
            if (keys[i].equals(sortBy)) {
                selCol = i;
                break;
            }
        int selDir = "asc".equals(sortDir) ? 1 : 0;

        // Two-step dialog: first pick column
        new AlertDialog.Builder(this)
                .setTitle("Sort by")
                .setSingleChoiceItems(cols, selCol, null)
                .setPositiveButton("Next", (d1, w1) -> {
                    int pickedCol = ((AlertDialog) d1).getListView().getCheckedItemPosition();
                    String newSortBy = keys[Math.max(0, pickedCol)];
                    new AlertDialog.Builder(this)
                            .setTitle("Direction")
                            .setSingleChoiceItems(dirs, selDir, null)
                            .setPositiveButton("Apply", (d2, w2) -> {
                                int pickedDir = ((AlertDialog) d2).getListView().getCheckedItemPosition();
                                sortBy = newSortBy;
                                sortDir = dirKeys[Math.max(0, pickedDir)];
                                page = 1;
                                loadData();
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Search bar ────────────────────────────────────────
    private void setupSearchBar() {
        EditText etSearch = findViewById(R.id.etTxnSearch);
        findViewById(R.id.btnTxnSearch).setOnClickListener(v -> {
            searchQuery = etSearch.getText().toString().trim();
            if (searchQuery.isEmpty()) searchQuery = null;
            page = 1;
            loadData();
        });
        // Date filter
        findViewById(R.id.btnDateFilter).setOnClickListener(v -> showDateFilterDialog());
    }

    private void showDateFilterDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_date_filter, null);
        EditText etFrom = v.findViewById(R.id.etDateFrom);
        EditText etTo = v.findViewById(R.id.etDateTo);
        if (dateFrom != null) etFrom.setText(dateFrom);
        if (dateTo != null) etTo.setText(dateTo);

        new AlertDialog.Builder(this)
                .setTitle("Filter by Date")
                .setView(v)
                .setPositiveButton("Apply", (d, w) -> {
                    dateFrom = etFrom.getText().toString().trim();
                    dateTo = etTo.getText().toString().trim();
                    if (dateFrom.isEmpty()) dateFrom = null;
                    if (dateTo.isEmpty()) dateTo = null;
                    page = 1;
                    loadData();
                })
                .setNeutralButton("Clear", (d, w) -> {
                    dateFrom = null;
                    dateTo = null;
                    page = 1;
                    loadData();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Pagination ────────────────────────────────────────
    private void setupPagination() {
        findViewById(R.id.btnPrev).setOnClickListener(v -> {
            if (page > 1) {
                page--;
                loadData();
            }
        });
        findViewById(R.id.btnNext).setOnClickListener(v -> {
            page++;
            loadData();
        });
    }

    // ── Load data ─────────────────────────────────────────
    private void loadData() {
        com.expenseos.model.TransactionFilter f = new com.expenseos.model.TransactionFilter();
        f.setBookId(bookId);
        f.setType(filterType);
        f.setPage(page);
        f.setPageSize(PAGE_SIZE);
        f.setSortBy(sortBy);
        f.setSortDir(sortDir);
        f.setNoteSearch(searchQuery);
        if (dateFrom != null && !dateFrom.isEmpty())
            f.setDateFrom(java.time.LocalDate.parse(dateFrom));
        if (dateTo != null && !dateTo.isEmpty())
            f.setDateTo(java.time.LocalDate.parse(dateTo));

        List<Transaction> txns = txnDao.findByFilter(f);
        int total = txnDao.count(filterType, bookId);
        int totalPages = (int) Math.ceil((double) total / PAGE_SIZE);

        RecyclerView rv = findViewById(R.id.rvTransactions);
        rv.setLayoutManager(new LinearLayoutManager(this));

        // Adapter with click → detail, long-click → options menu
        rv.setAdapter(new TransactionAdapter(this, txns,
                txn -> showTxnOptions(txn),
                txn -> {
                    Intent i = new Intent(this, TransactionDetailActivity.class);
                    i.putExtra("txnId", txn.getId());
                    startActivity(i);
                }));

        ((TextView) findViewById(R.id.tvTxnCount))
                .setText(total + " transactions");
        ((TextView) findViewById(R.id.tvPage))
                .setText("Page " + page + " / " + totalPages);
        findViewById(R.id.btnPrev).setEnabled(page > 1);
        findViewById(R.id.btnNext).setEnabled(page < totalPages);

        // Sort indicator
        ((TextView) findViewById(R.id.tvSortLabel))
                .setText("↕ " + sortBy + " " + (sortDir.equals("asc") ? "↑" : "↓"));
    }

    // ── Transaction options: Edit / Duplicate / Delete ────
    private void showTxnOptions(Transaction txn) {
        String[] opts = {"✏ Edit", "📋 Duplicate", "🗑 Delete"};
        new AlertDialog.Builder(this)
                .setTitle("Transaction #" + txn.getId())
                .setItems(opts, (d, which) -> {
                    switch (which) {
                        case 0 -> {
                            Intent i = new Intent(this, TransactionDetailActivity.class);
                            i.putExtra("txnId", txn.getId());
                            startActivity(i);
                        }
                        case 1 -> showDuplicateDialog(txn);
                        case 2 -> confirmDelete(txn);
                    }
                })
                .show();
    }

    private void showDuplicateDialog(Transaction txn) {
        String[] opts = {"Copy with today's date", "Copy with original date"};
        new AlertDialog.Builder(this)
                .setTitle("📋 Duplicate")
                .setItems(opts, (d, which) -> {
                    LocalDateTime newDt = which == 0
                            ? LocalDateTime.now()
                            : txn.getDateTime();

                    Transaction dup = new Transaction();
                    dup.setType(txn.getType());
                    dup.setDateTime(newDt);
                    dup.setAmount(txn.getAmount());
                    dup.setCategoryId(txn.getCategoryId());
                    dup.setSubCategoryId(txn.getSubCategoryId());
                    dup.setNote(txn.getNote());
                    dup.setBookId(txn.getBookId());
                    long newId = txnDao.insert(dup);

                    Toast.makeText(this, "Duplicated! #" + newId, Toast.LENGTH_SHORT).show();
                    loadData();
                })
                .show();
    }

    private void confirmDelete(Transaction txn) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Transaction")
                .setMessage("Delete ₹" + txn.getAmount() + " " + txn.getType().name() + "?")
                .setPositiveButton("Delete", (d, w) -> {
                    txnDao.delete(txn.getId());
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
                    loadData();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Add/Edit now open the dedicated full-screen entry activity
    // instead of a popup dialog ────────────────────────────
    private void showAddDialog(String type) {
        Intent i = new Intent(this, com.expenseos.ui.TransactionEntryActivity.class);
        i.putExtra("type", type);
        startActivity(i);
    }
}
