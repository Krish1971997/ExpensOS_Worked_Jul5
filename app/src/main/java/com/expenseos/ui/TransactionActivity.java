package com.expenseos.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.adapter.TransactionAdapter;
import com.expenseos.dao.CategoryDao;
import com.expenseos.dao.SubCategoryDao;
import com.expenseos.dao.TransactionDao;
import com.expenseos.model.Category;
import com.expenseos.model.SubCategory;
import com.expenseos.model.Transaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class TransactionActivity extends AppCompatActivity {

    private int bookId;
    private String filterType = null; // null=All, INCOME, EXPENSE
    private int page = 1;
    private static final int PAGE_SIZE = 15;
    private TransactionDao txnDao;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_transactions);

        SharedPreferences prefs = getSharedPreferences("expenseos_prefs", MODE_PRIVATE);
        bookId = prefs.getInt("active_book_id", 0);
        txnDao = new TransactionDao(this);

        // If launched with type (from Home + Income/Expense buttons)
        String initType = getIntent().getStringExtra("type");
        if (initType != null) showAddDialog(initType);

        // Filter tabs
        ((Button) findViewById(R.id.btnAll)).setOnClickListener(v -> {
            filterType = null;
            page = 1;
            loadData();
        });
        ((Button) findViewById(R.id.btnIncome)).setOnClickListener(v -> {
            filterType = "INCOME";
            page = 1;
            loadData();
        });
        ((Button) findViewById(R.id.btnExpense)).setOnClickListener(v -> {
            filterType = "EXPENSE";
            page = 1;
            loadData();
        });

        // Add buttons
        findViewById(R.id.btnAddIncome2).setOnClickListener(v -> showAddDialog("INCOME"));
        findViewById(R.id.btnAddExpense2).setOnClickListener(v -> showAddDialog("EXPENSE"));

        // Pagination
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

        // Back
        findViewById(R.id.btnBackTxn).setOnClickListener(v -> finish());

        loadData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadData();
    }

    private void loadData() {
        List<Transaction> txns = txnDao.findAll(filterType, page, PAGE_SIZE, bookId);
        int total = txnDao.count(filterType, bookId);

        RecyclerView rv = findViewById(R.id.rvTransactions);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new TransactionAdapter(txns, txn -> {
        }));

        ((TextView) findViewById(R.id.tvTxnCount)).setText(total + " transactions");

        int totalPages = (int) Math.ceil((double) total / PAGE_SIZE);
        ((TextView) findViewById(R.id.tvPage)).setText("Page " + page + " / " + totalPages);
        findViewById(R.id.btnPrev).setEnabled(page > 1);
        findViewById(R.id.btnNext).setEnabled(page < totalPages);
    }

    private void showAddDialog(String type) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_add_transaction, null);
        boolean isIncome = "INCOME".equals(type);

        EditText etAmount = v.findViewById(R.id.et_amount);
        EditText etNote = v.findViewById(R.id.et_note);
        EditText etDateTime = v.findViewById(R.id.et_date);
        Spinner spCat = v.findViewById(R.id.sp_category);
        Spinner spSubCat = v.findViewById(R.id.sp_subcategory);

        // Pre-fill datetime
        etDateTime.setText(LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        // Load categories
        List<Category> cats = new CategoryDao(this).findByType(type);
        ArrayAdapter<Category> catAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, cats);
        catAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCat.setAdapter(catAdapter);

        // Sub category cascade
        SubCategoryDao scDao = new SubCategoryDao(this);
        spCat.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View vw, int pos, long id) {
                int catId = cats.get(pos).getId();
                List<SubCategory> subs = scDao.findByCategoryId(catId);
                List<SubCategory> withNone = new ArrayList<>();
                withNone.add(new SubCategory(0, "None", catId));
                withNone.addAll(subs);
                spSubCat.setAdapter(new ArrayAdapter<>(
                        TransactionActivity.this,
                        android.R.layout.simple_spinner_item, withNone));
            }
            public void onNothingSelected(AdapterView<?> p) {}
        });

        new AlertDialog.Builder(this)
                .setTitle(isIncome ? "+ Add Income" : "+ Add Expense")
                .setView(v)
                .setPositiveButton(isIncome ? "Save Income" : "Save Expense", (d, w) -> {
                    String amtStr = etAmount.getText().toString().trim();
                    if (amtStr.isEmpty() || spCat.getSelectedItem() == null) {
                        Toast.makeText(this, "Amount and category required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Transaction t = new Transaction();
                    t.setType(Transaction.Type.valueOf(type));
                    t.setAmount(new BigDecimal(amtStr));
                    t.setCategoryId(((Category) spCat.getSelectedItem()).getId());
                    SubCategory sc = (SubCategory) spSubCat.getSelectedItem();
                    if (sc != null && sc.getId() > 0) t.setSubCategoryId(sc.getId());
                    t.setNote(etNote.getText().toString().trim());
                    t.setTxnDatetime(etDateTime.getText().toString().trim());
                    t.setBookId(bookId);
                    txnDao.insert(t);
                    loadData();
                    Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
