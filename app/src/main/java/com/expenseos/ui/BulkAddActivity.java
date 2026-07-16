package com.expenseos.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;

import com.expenseos.R;
import com.expenseos.dao.CategoryDao;
import com.expenseos.dao.TransactionDao;
import com.expenseos.model.Category;
import com.expenseos.model.Transaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bulk Add Transactions — add multiple rows at once.
 * Each row: Type (INCOME/EXPENSE), DateTime, Amount, Category, Note.
 * "Save All" submits them sequentially and shows a progress count.
 */
public class BulkAddActivity extends AppCompatActivity {

    private int bookId;
    private TransactionDao txnDao;
    private CategoryDao catDao;

    private LinearLayout rowContainer;
    private TextView tvSummary, tvResult;
    private Button btnSaveAll;

    // Row data holder
    private final List<BulkRow> rows = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_bulk_add);

//        SharedPreferences prefs = getSharedPreferences("expenseos_prefs", MODE_PRIVATE);
//        int bookId = prefs.getInt("active_book_id", 0);
        int bookId = com.expenseos.util.AppConfig.get(this).getActiveBookId();
        txnDao = new TransactionDao(this);
        catDao = new CategoryDao(this);

        rowContainer = findViewById(R.id.llBulkRows);
        tvSummary = findViewById(R.id.tvBulkSummary);
        tvResult = findViewById(R.id.tvBulkResult);
        btnSaveAll = findViewById(R.id.btnBulkSaveAll);

        findViewById(R.id.btnBulkBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnBulkAddRow).setOnClickListener(v -> addRow());
        findViewById(R.id.btnBulkAdd5).setOnClickListener(v -> {
            for (int i = 0; i < 5; i++) addRow();
        });
        findViewById(R.id.btnBulkClear).setOnClickListener(v -> clearAll());
        btnSaveAll.setOnClickListener(v -> saveAll());

        // Start with 3 default rows
        addRow();
        addRow();
        addRow();
    }

    // ── Add one input row ─────────────────────────────────
    private void addRow() {
        View v = LayoutInflater.from(this)
                .inflate(R.layout.item_bulk_row, rowContainer, false);

        BulkRow row = new BulkRow();
        row.spType = v.findViewById(R.id.spBulkType);
        row.etDate = v.findViewById(R.id.etBulkDate);
        row.etAmount = v.findViewById(R.id.etBulkAmount);
        row.spCat = v.findViewById(R.id.spBulkCategory);
        row.etNote = v.findViewById(R.id.etBulkNote);
        row.btnDel = v.findViewById(R.id.btnBulkRowDel);

        // Type spinner: EXPENSE | INCOME
        ArrayAdapter<String> typeAdp = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"EXPENSE", "INCOME"});
        typeAdp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        row.spType.setAdapter(typeAdp);

        // Date pre-fill
        row.etDate.setText(LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        // Category — reload when type changes
        row.spType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View vw, int pos, long id) {
                String type = pos == 0 ? "EXPENSE" : "INCOME";
                loadCategories(row, type);
            }

            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        loadCategories(row, "EXPENSE"); // default

        // Delete row
        row.btnDel.setOnClickListener(x -> {
            rows.remove(row);
            rowContainer.removeView(v);
            updateSummary();
        });

        // Live summary on amount change
        row.etAmount.addTextChangedListener(new android.text.TextWatcher() {
            public void afterTextChanged(android.text.Editable e) {
                updateSummary();
            }

            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }
        });

        rows.add(row);
        rowContainer.addView(v);
        updateSummary();
    }

    private void loadCategories(BulkRow row, String type) {
        List<Category> cats = catDao.findByType(type, bookId);
        ArrayAdapter<Category> adp = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, cats);
        adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        row.spCat.setAdapter(adp);
        row.cachedCats = cats;
    }

    // ── Live summary ──────────────────────────────────────
    private void updateSummary() {
        double income = 0, expense = 0;
        for (BulkRow r : rows) {
            double amt = parseAmt(r.etAmount.getText().toString());
            String type = r.spType.getSelectedItemPosition() == 1 ? "INCOME" : "EXPENSE";
            if ("INCOME".equals(type)) income += amt;
            else expense += amt;
        }
        tvSummary.setText(
                rows.size() + " rows  |  ↑₹" + String.format("%.2f", income) +
                        "  ↓₹" + String.format("%.2f", expense) +
                        "  Net₹" + String.format("%.2f", income - expense));
    }

    // ── Clear all rows ────────────────────────────────────
    private void clearAll() {
        rows.clear();
        rowContainer.removeAllViews();
        addRow();
        addRow();
        addRow();
        updateSummary();
    }

    // ── Save All ──────────────────────────────────────────
    private void saveAll() {
        // Validate
        for (BulkRow r : rows) {
            if (r.etAmount.getText().toString().trim().isEmpty()) {
                Toast.makeText(this, "Fill all Amount fields", Toast.LENGTH_SHORT).show();
                return;
            }
            if (r.cachedCats == null || r.cachedCats.isEmpty() ||
                    r.spCat.getSelectedItemPosition() < 0) {
                Toast.makeText(this, "Select category for each row", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        btnSaveAll.setEnabled(false);
        btnSaveAll.setText("Saving…");
        tvResult.setVisibility(View.GONE);

        // Snapshot rows before async
        List<Transaction> toSave = new ArrayList<>();
        for (BulkRow r : rows) {
            String type = r.spType.getSelectedItemPosition() == 1 ? "INCOME" : "EXPENSE";
            Category cat = r.cachedCats.get(
                    Math.max(0, r.spCat.getSelectedItemPosition()));
            Transaction t = new Transaction();
            t.setType(Transaction.Type.valueOf(type));
            t.setAmount(new BigDecimal(r.etAmount.getText().toString().trim()));
            t.setCategoryId(cat.getId());
            t.setNote(r.etNote.getText().toString().trim());
            String dt=r.etDate.getText().toString().trim();
            if (dt != null) {
                t.setDateTime(LocalDateTime.parse(dt));
            }
            t.setBookId(bookId);
            toSave.add(t);
        }

        exec.execute(() -> {
            int saved = 0, failed = 0;
            for (Transaction t : toSave) {
                try {
                    txnDao.insert(t);
                    saved++;
                } catch (Exception e) {
                    failed++;
                }
            }
            final int s = saved, f = failed;
            mainHandler.post(() -> {
                btnSaveAll.setEnabled(true);
                btnSaveAll.setText("✓ Save All");
                tvResult.setVisibility(View.VISIBLE);
                tvResult.setText(s + " saved" + (f > 0 ? ", " + f + " failed" : ""));
                tvResult.setTextColor(getColor(f == 0 ? R.color.green : R.color.red));
                if (f == 0) {
                    // Reset to 3 fresh rows
                    rows.clear();
                    rowContainer.removeAllViews();
                    addRow();
                    addRow();
                    addRow();
                    updateSummary();
                    Toast.makeText(this, "All saved!", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private double parseAmt(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdown();
    }

    // ── Row data holder ───────────────────────────────────
    static class BulkRow {
        Spinner spType, spCat;
        EditText etDate, etAmount, etNote;
        Button btnDel;
        List<Category> cachedCats;
    }
}