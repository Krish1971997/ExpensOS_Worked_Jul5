package com.expenseos.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
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

import com.expenseos.R;
import com.expenseos.dao.CashBookDao;
import com.expenseos.dao.CategoryDao;
import com.expenseos.dao.SubCategoryDao;
import com.expenseos.dao.TransactionDao;
import com.expenseos.model.CashBook;
import com.expenseos.model.Category;
import com.expenseos.model.SubCategory;
import com.expenseos.model.Transaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Transaction Detail — Edit / Delete / Duplicate / Move to another book
 * Prev ← → Next navigation (same order as list: date DESC)
 */
public class TransactionDetailActivity extends AppCompatActivity {

    private int bookId;
    private int txnId;
    private TransactionDao txnDao;
    private CategoryDao catDao;
    private SubCategoryDao scDao;
    private CashBookDao bookDao;
    private Transaction current;

    // Edit fields
    private EditText etAmount, etNote, etDateTime;
    private Spinner spCategory, spSubCategory, spMoveBook;
    private Button btnPrev, btnNext;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_transaction_detail);

        SharedPreferences prefs = getSharedPreferences("expenseos_prefs", MODE_PRIVATE);
        bookId = prefs.getInt("active_book_id", 0);
        txnId = getIntent().getIntExtra("txnId", -1);

        if (txnId < 0) {
            finish();
            return;
        }

        txnDao = new TransactionDao(this);
        catDao = new CategoryDao(this);
        scDao = new SubCategoryDao(this);
        bookDao = new CashBookDao(this);

        bindViews();
        setupButtons();
        loadTransaction();
    }

    private void bindViews() {
        etAmount = findViewById(R.id.etDetailAmount);
        etNote = findViewById(R.id.etDetailNote);
        etDateTime = findViewById(R.id.etDetailDateTime);
        spCategory = findViewById(R.id.spDetailCategory);
        spSubCategory = findViewById(R.id.spDetailSubCategory);
        spMoveBook = findViewById(R.id.spMoveBook);
        btnPrev = findViewById(R.id.btnDetailPrev);
        btnNext = findViewById(R.id.btnDetailNext);
    }

    private void setupButtons() {
        // Back
        findViewById(R.id.btnDetailBack).setOnClickListener(v -> finish());

        // Save (update)
        findViewById(R.id.btnDetailSave).setOnClickListener(v -> saveTransaction());

        // Duplicate
        findViewById(R.id.btnDetailDuplicate).setOnClickListener(v -> showDuplicateDialog());

        // Move to another book
        findViewById(R.id.btnDetailMove).setOnClickListener(v -> moveTransaction());

        // Delete
        findViewById(R.id.btnDetailDelete).setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle("Delete Transaction")
                        .setMessage("Permanently delete this transaction?")
                        .setPositiveButton("Delete", (d, w) -> {
                            txnDao.delete(txnId);
                            Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
                            finish();
                        })
                        .setNegativeButton("Cancel", null)
                        .show());

        // Prev / Next
        btnPrev.setOnClickListener(v -> navigateTo(txnDao.findPrevId(txnId, bookId)));
        btnNext.setOnClickListener(v -> navigateTo(txnDao.findNextId(txnId, bookId)));
    }

    // ── Load transaction into form ─────────────────────────
    private void loadTransaction() {
        current = txnDao.findById(txnId);
        if (current == null) {
            finish();
            return;
        }

        // Header
        ((TextView) findViewById(R.id.tvDetailTitle))
                .setText("Transaction #" + txnId);
        ((TextView) findViewById(R.id.tvDetailType))
                .setText(current.getType().name());
        int typeColor = current.getType() == Transaction.Type.INCOME
                ? getColor(R.color.green) : getColor(R.color.red);
        ((TextView) findViewById(R.id.tvDetailType)).setTextColor(typeColor);

        // Fields
        etAmount.setText(current.getAmount().toPlainString());
        etNote.setText(current.getNote() != null ? current.getNote() : "");
//        String dt = current.getDateTime();
//        etDateTime.setText(dt != null && dt.length() > 16 ? dt.substring(0, 16) : dt);

        LocalDateTime dt = current.getDateTime();
        etDateTime.setText(dt == null ? "" : dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
        );

        // Category spinner
        String catType = current.getType().name();
        List<Category> cats = catDao.findByType(catType, bookId);
        ArrayAdapter<Category> catAdp = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, cats);
        catAdp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCategory.setAdapter(catAdp);
        for (int i = 0; i < cats.size(); i++) {
            if (cats.get(i).getId() == current.getCategoryId()) {
                spCategory.setSelection(i);
                break;
            }
        }

        // Sub-category cascade
        spCategory.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View vw, int pos, long id) {
                refreshSubCat(cats.get(pos).getId());
            }

            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        refreshSubCat(current.getCategoryId());

        // Move-to-book spinner
        List<CashBook> books = bookDao.findAll();
        ArrayAdapter<CashBook> bookAdp = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, books) {
            @Override
            public String toString() {
                return "";
            }
        };
        // Use custom toString via CashBook.getName()
        List<String> bookNames = new ArrayList<>();
        int selBook = 0;
        for (int i = 0; i < books.size(); i++) {
            bookNames.add(books.get(i).getName());
            if (books.get(i).getId() == current.getBookId()) selBook = i;
        }
        ArrayAdapter<String> bAdp = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, bookNames);
        bAdp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spMoveBook.setAdapter(bAdp);
        spMoveBook.setSelection(selBook);
        // Store book objects for later
        spMoveBook.setTag(books);

        // Prev / Next availability
        int prevId = txnDao.findPrevId(txnId, bookId);
        int nextId = txnDao.findNextId(txnId, bookId);
        btnPrev.setEnabled(prevId != 0);
        btnNext.setEnabled(nextId != 0);
        btnPrev.setAlpha(prevId != 0 ? 1f : 0.4f);
        btnNext.setAlpha(nextId != 0 ? 1f : 0.4f);
    }

    private void refreshSubCat(int catId) {
        List<SubCategory> subs = scDao.findByCategoryId(catId);
        List<SubCategory> withNone = new ArrayList<>();
        withNone.add(new SubCategory(0, "None", catId));
        withNone.addAll(subs);
        ArrayAdapter<SubCategory> adp = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, withNone);
        adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSubCategory.setAdapter(adp);
        // Restore current selection
        for (int i = 0; i < withNone.size(); i++) {
            if (withNone.get(i).getId() == current.getSubCategoryId()) {
                spSubCategory.setSelection(i);
                break;
            }
        }
    }

    // ── Save (update) ─────────────────────────────────────
    private void saveTransaction() {
        String amtStr = etAmount.getText().toString().trim();
        if (amtStr.isEmpty()) {
            Toast.makeText(this, "Amount is required", Toast.LENGTH_SHORT).show();
            return;
        }
        Category selCat = (Category) spCategory.getSelectedItem();
        SubCategory selSub = (SubCategory) spSubCategory.getSelectedItem();

        // Resolve selected book (move)
        @SuppressWarnings("unchecked")
        List<CashBook> books = (List<CashBook>) spMoveBook.getTag();
        int newBookId = books != null && spMoveBook.getSelectedItemPosition() >= 0
                ? books.get(spMoveBook.getSelectedItemPosition()).getId()
                : current.getBookId();

        Transaction updated = new Transaction();
        updated.setId(txnId);
        updated.setType(current.getType());
        updated.setAmount(new BigDecimal(amtStr));
        updated.setCategoryId(selCat != null ? selCat.getId() : current.getCategoryId());
        updated.setSubCategoryId(selSub != null && selSub.getId() > 0 ? selSub.getId() : 0);
        updated.setNote(etNote.getText().toString().trim());
//        updated.setDateTime(etDateTime.getText().toString().trim() + ":00");
        String dt = etDateTime.getText().toString().trim();

        if (!dt.isEmpty()) {
            updated.setDateTime(LocalDateTime.parse(dt + ":00"));
        } else {
            updated.setDateTime(null);
        }
        updated.setBookId(newBookId);

        txnDao.update(current, updated);
        Toast.makeText(this, "✓ Saved!", Toast.LENGTH_SHORT).show();
        loadTransaction(); // refresh display
    }

    // ── Duplicate ─────────────────────────────────────────
    private void showDuplicateDialog() {

        String[] opts = {"Copy with today's date",  "Copy with original date"};
        new AlertDialog.Builder(this)
                .setTitle("📋 Duplicate Transaction")
                .setItems(opts, (d, which) -> {
                    LocalDateTime newDt = (which == 0)
                            ? LocalDateTime.now()
                            : current.getDateTime();

                    Transaction dup = new Transaction();
                    dup.setType(current.getType());
                    dup.setDateTime(newDt);
                    dup.setAmount(current.getAmount());
                    dup.setCategoryId(current.getCategoryId());
                    dup.setSubCategoryId(current.getSubCategoryId());
                    dup.setNote(current.getNote());
                    dup.setBookId(current.getBookId());
                    int newId = (int) txnDao.insert(dup);

                    Toast.makeText(this,
                            "Duplicated! New #" + newId,Toast.LENGTH_SHORT).show();
                    txnId = newId;
                    current = txnDao.findById(newId);
                    loadTransaction();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Move to another book ──────────────────────────────
    private void moveTransaction() {
        @SuppressWarnings("unchecked")
        List<CashBook> books = (List<CashBook>) spMoveBook.getTag();
        if (books == null || spMoveBook.getSelectedItemPosition() < 0) return;

        int targetBookId = books.get(spMoveBook.getSelectedItemPosition()).getId();
        if (targetBookId == current.getBookId()) {
            Toast.makeText(this, "Already in this book", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Move Transaction")
                .setMessage("Move to \"" +
                        books.get(spMoveBook.getSelectedItemPosition()).getName() + "\"?")
                .setPositiveButton("Move", (d, w) -> {
                    Transaction moved = new Transaction();
                    moved.setId(txnId);
                    moved.setType(current.getType());
                    moved.setAmount(current.getAmount());
                    moved.setCategoryId(current.getCategoryId());
                    moved.setSubCategoryId(current.getSubCategoryId());
                    moved.setNote(current.getNote());
                    moved.setDateTime(current.getDateTime());
                    moved.setBookId(targetBookId);
                    txnDao.update(current, moved);
                    Toast.makeText(this, "Moved!", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Prev / Next navigation ────────────────────────────
    private void navigateTo(int id) {
        if (id == 0) return;
        txnId = id;
        current = txnDao.findById(id);
        loadTransaction();
    }
}