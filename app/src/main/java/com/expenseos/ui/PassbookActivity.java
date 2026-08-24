package com.expenseos.ui;

import android.app.AlertDialog;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.adapter.PassbookAdapter;
import com.expenseos.dao.CashBookDao;
import com.expenseos.dao.CategoryDao;
import com.expenseos.dao.TransactionDao;
import com.expenseos.db.LocalDB;
import com.expenseos.model.CashBook;
import com.expenseos.model.Category;
import com.expenseos.model.PassbookEntry;
import com.expenseos.model.Transaction;
import com.expenseos.sync.SmsReaderService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads bank/UPI SMS from the device inbox, parses them into candidate
 * transactions (SmsParser), and lets the user select a subset to copy into
 * a chosen cash book — mirrors the reference app's Passbook → Copy to
 * Cashbook flow (screenshots: entry list w/ checkboxes → select book →
 * confirm → done, with an "Incomplete Entries" fallback when a matching
 * category can't be found automatically).
 */
public class PassbookActivity extends AppCompatActivity {

    private RecyclerView rv;
    private PassbookAdapter adapter;
    private TextView tvCount, tvEmpty;
    private final List<PassbookEntry> entries = new ArrayList<>();

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_passbook);

        findViewById(R.id.btnPassbookBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnScanSms).setOnClickListener(v -> scanSms());
        findViewById(R.id.btnCopySelected).setOnClickListener(v -> onCopyClicked());

        rv = findViewById(R.id.rvPassbookEntries);
        rv.setLayoutManager(new LinearLayoutManager(this));
        tvCount = findViewById(R.id.tvPassbookCount);
        tvEmpty = findViewById(R.id.tvPassbookEmpty);

        scanSms(); // initial load — also picks up any new SMS since last open
    }

    // ── Scan inbox, then reload the uncopied-entries list ─────────────
    private void scanSms() {
        Toast.makeText(this, "Reading SMS…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int found = SmsReaderService.scanInboxAndStore(this);
            runOnUiThread(() -> {
                Toast.makeText(this, found + " transaction SMS found", Toast.LENGTH_SHORT).show();
                loadEntries();
            });
        }).start();
    }

    private void loadEntries() {
        entries.clear();
        SQLiteDatabase db = LocalDB.getInstance(this).getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT sms_id, type, amount, sender, raw_body, remark, timestamp_millis, copied, payment_type " +
                        "FROM passbook_entries WHERE copied=0 ORDER BY timestamp_millis DESC", null)) {
            while (c.moveToNext()) {
                PassbookEntry e = new PassbookEntry();
                e.setSmsId(c.getLong(0));
                e.setType(c.getString(1));
                e.setAmount(new BigDecimal(c.getString(2)));
                e.setSender(c.getString(3));
                e.setRawBody(c.getString(4));
                e.setRemark(c.isNull(5) ? null : c.getString(5));
                e.setTimestampMillis(c.getLong(6));
                e.setCopied(c.getInt(7) == 1);
                e.setPaymentType(c.isNull(8) ? null : c.getString(8));
                entries.add(e);
            }
        }

        tvEmpty.setVisibility(entries.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
        adapter = new PassbookAdapter(entries, this::updateSelectedCount);
        rv.setAdapter(adapter);
        updateSelectedCount();
    }

    private void updateSelectedCount() {
        int n = adapter != null ? adapter.getSelectedCount() : 0;
        tvCount.setText(n + " selected  •  " + entries.size() + " total");
        findViewById(R.id.btnCopySelected).setEnabled(n > 0);
    }

    // ── Copy flow: pick destination book → confirm → insert transactions ──
    private void onCopyClicked() {
        List<PassbookEntry> selected = adapter.getSelectedEntries();
        if (selected.isEmpty()) return;
        showBookPickerDialog(selected);
    }

    private void showBookPickerDialog(List<PassbookEntry> selected) {
        List<CashBook> books = new CashBookDao(this).findAll();
        if (books.isEmpty()) {
            Toast.makeText(this, "Create a cash book first", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[books.size()];
        for (int i = 0; i < books.size(); i++) names[i] = books.get(i).getName();

        final int[] pickedIdx = {0};
        new AlertDialog.Builder(this)
                .setTitle("Select Book")
                .setSingleChoiceItems(names, 0, (d, which) -> pickedIdx[0] = which)
                .setPositiveButton("Next", (d, w) -> {
                    CashBook target = books.get(pickedIdx[0]);
                    confirmAndCopy(selected, target);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmAndCopy(List<PassbookEntry> selected, CashBook target) {
        new AlertDialog.Builder(this)
                .setTitle("Copy Entries?")
                .setMessage("This will copy " + selected.size() + " entr" +
                        (selected.size() == 1 ? "y" : "ies") + " into \"" + target.getName() +
                        "\" and change its net balance.")
                .setPositiveButton("Yes", (d, w) -> doCopy(selected, target))
                .setNegativeButton("No", null)
                .show();
    }

    private void doCopy(List<PassbookEntry> selected, CashBook target) {
        TransactionDao txnDao = new TransactionDao(this);
        CategoryDao catDao = new CategoryDao(this);
        SQLiteDatabase local = LocalDB.getInstance(this).getWritableDatabase();

        List<PassbookEntry> incomplete = new ArrayList<>();
        int copied = 0;

        for (PassbookEntry e : selected) {
            Transaction.Type type = "CREDIT".equals(e.getType())
                    ? Transaction.Type.INCOME : Transaction.Type.EXPENSE;

            // Try to find a default category for this book+type ("Other" is
            // seeded for every fresh install — see LocalDB.onCreate()). If
            // none exists yet, this entry needs the user to pick one manually.
            List<Category> cats = catDao.findByType(type.name(), target.getId());
            if (cats.isEmpty()) {
                incomplete.add(e);
                continue;
            }
            Category defaultCat = cats.get(0);
            for (Category c : cats) {
                if ("Other".equalsIgnoreCase(c.getName())) {
                    defaultCat = c;
                    break;
                }
            }

//payment type was never being carried over before; every copied
// transaction silently landed with no payment type at all.
            Transaction t = new Transaction();
            t.setType(type);
            t.setDateTime(LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(e.getTimestampMillis()), ZoneId.systemDefault()));
            t.setAmount(e.getAmount());
            t.setCategoryId(defaultCat.getId());
            t.setSubCategoryId(0);
            t.setNote(e.getRemark() != null ? e.getRemark() : e.getRawBody());
            t.setBookId(target.getId());
            t.setPaymentType(e.getPaymentType() != null ? e.getPaymentType() : "Other");
            txnDao.insert(t);

            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put("copied", 1);
            local.update("passbook_entries", cv, "sms_id=?", new String[]{String.valueOf(e.getSmsId())});
            copied++;
        }

        String msg = "✔ Copied " + copied + " entr" + (copied == 1 ? "y" : "ies") + " to " + target.getName();
        if (!incomplete.isEmpty())
            msg += " — " + incomplete.size() + " need a category (see Incomplete Entries)";
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();

        loadEntries();

        if (!incomplete.isEmpty()) showIncompleteEntriesDialog(incomplete);
    }

    /**
     * Entries whose book has no category of the right type yet — mirrors
     * the reference app's "Incomplete Entries" screen. Simplest fix: tell
     * the user to create at least one Income/Expense category in that book
     * (via the normal + Cash In/Out flow), then retry the copy.
     */
    private void showIncompleteEntriesDialog(List<PassbookEntry> incomplete) {
        StringBuilder sb = new StringBuilder(
                "These entries are missing a matching category in the destination book. " +
                        "Add at least one category there, then select and copy them again:\n\n");
        for (PassbookEntry e : incomplete) {
            sb.append("₹").append(e.getAmount().toPlainString());
            if (e.getRemark() != null) sb.append(" — ").append(e.getRemark());
            sb.append("\n");
        }
        new AlertDialog.Builder(this)
                .setTitle("Incomplete Entries")
                .setMessage(sb.toString())
                .setPositiveButton("OK", null)
                .show();
    }
}