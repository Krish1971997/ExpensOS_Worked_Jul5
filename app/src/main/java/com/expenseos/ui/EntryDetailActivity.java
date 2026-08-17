package com.expenseos.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.expenseos.R;
import com.expenseos.dao.AuditLogDao;
import com.expenseos.dao.CashBookDao;
import com.expenseos.dao.CategoryDao;
import com.expenseos.dao.ColumnDefinitionDao;
import com.expenseos.dao.ReceiptDao;
import com.expenseos.dao.TransactionDao;
import com.expenseos.model.AuditLog;
import com.expenseos.model.CashBook;
import com.expenseos.model.Category;
import com.expenseos.model.ColumnDefinition;
import com.expenseos.model.Receipt;
import com.expenseos.model.Transaction;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only "Entry Details" screen: shows a transaction's full info (type,
 * date, amount, note, attachments, category/sub-category/custom fields),
 * who created/last-edited it, and a link to the full change history.
 * Actual editing happens in TransactionEntryActivity — this screen just
 * launches it via "EDIT ENTRY". The 3-dot menu handles Move / Copy / Delete.
 */
public class EntryDetailActivity extends AppCompatActivity {

    private static final DateTimeFormatter DISPLAY_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");

    private int txnId;
    private int bookId;
    private Transaction txn;

    private TransactionDao txnDao;
    private ReceiptDao receiptDao;
    private AuditLogDao auditDao;
    private CashBookDao bookDao;
    private ColumnDefinitionDao colDefDao;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_entry_detail);

        bookId = com.expenseos.util.AppConfig.get(this).getActiveBookId();
        txnId = getIntent().getIntExtra("txnId", -1);
        if (txnId <= 0) {
            finish();
            return;
        }

        txnDao = new TransactionDao(this);
        receiptDao = new ReceiptDao(this);
        auditDao = new AuditLogDao(this);
        bookDao = new CashBookDao(this);
        colDefDao = new ColumnDefinitionDao(this);

        findViewById(R.id.btnEntryBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnEntryMenu).setOnClickListener(this::showEntryMenu);
        findViewById(R.id.btnEditEntry).setOnClickListener(v -> {
            // Edit goes through the dedicated TransactionDetailActivity edit
            // screen (not TransactionEntryActivity — that stays reserved for
            // creating brand-new transactions).
            Intent i = new Intent(this, TransactionDetailActivity.class);
            i.putExtra("txnId", txnId);
            startActivity(i);
        });
        findViewById(R.id.btnViewHistory).setOnClickListener(v -> {
            Intent i = new Intent(this, TxnEditHistoryActivity.class);
            i.putExtra("txnId", txnId);
            startActivity(i);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadEntry(); // refresh after returning from Edit
    }

    private void loadEntry() {
        txn = txnDao.findById(txnId);
        if (txn == null) {
            finish();
            return;
        }

        boolean isIncome = txn.getType() == Transaction.Type.INCOME;

        ((TextView) findViewById(R.id.tvEntryType)).setText(isIncome ? "Cash In" : "Cash Out");
        ((TextView) findViewById(R.id.tvEntryDate)).setText(
                txn.getDateTime() != null ? "On " + txn.getDateTime().format(DISPLAY_FMT) : "");

        TextView tvAmount = findViewById(R.id.tvEntryAmount);
        tvAmount.setText(txn.getAmount().toPlainString());
        tvAmount.setTextColor(getColor(isIncome ? R.color.green : R.color.red));

        findViewById(R.id.viewTypeStrip).setBackgroundColor(getColor(isIncome ? R.color.green : R.color.red));

        TextView tvNote = findViewById(R.id.tvEntryNote);
        if (txn.getNote() != null && !txn.getNote().isEmpty()) {
            tvNote.setText(txn.getNote());
            tvNote.setVisibility(View.VISIBLE);
        } else {
            tvNote.setVisibility(View.GONE);
        }

        loadAttachments();
        loadChips();
        loadSyncStatus();
        loadCreatedEditedInfo();
    }

    // ── Attachments ─────────────────────────────────────────
    private void loadAttachments() {
        LinearLayout container = findViewById(R.id.attachmentThumbContainer);
        container.removeAllViews();
        List<Receipt> receipts = receiptDao.findMetaByTransactionId(txnId);

        findViewById(R.id.scrollAttachments).setVisibility(receipts.isEmpty() ? View.GONE : View.VISIBLE);

        int size = (int) (72 * getResources().getDisplayMetrics().density);
        for (Receipt r : receipts) {
            View thumb;
            boolean isImage = r.getFileType() != null && r.getFileType().startsWith("image/");

            if (isImage) {
                ImageView iv = new ImageView(this);
                iv.setLayoutParams(new LinearLayout.LayoutParams(size, size));
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundColor(0xFFF1F5F9);
                thumb = iv;
                // Thumbnail loaded lazily from full receipt row (findMetaByTransactionId
                // doesn't include file_data) — fetch bytes only when we actually render it.
                new Thread(() -> {
                    Receipt full = receiptDao.findById(r.getId());
                    if (full != null && full.getFileData() != null) {
                        Bitmap bmp = BitmapFactory.decodeByteArray(full.getFileData(), 0, full.getFileData().length);
                        if (bmp != null) runOnUiThread(() -> iv.setImageBitmap(bmp));
                    }
                }).start();
            } else {
                TextView tv = new TextView(this);
                tv.setLayoutParams(new LinearLayout.LayoutParams(size, size));
                tv.setGravity(android.view.Gravity.CENTER);
                tv.setText("📄");
                tv.setTextSize(28);
                tv.setBackgroundColor(0xFFF1F5F9);
                thumb = tv;
            }

            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) thumb.getLayoutParams();
            lp.rightMargin = (int) (8 * getResources().getDisplayMetrics().density);
            thumb.setLayoutParams(lp);
            thumb.setOnClickListener(v -> openAttachmentPreview(r));
            container.addView(thumb);
        }
    }

    private void openAttachmentPreview(Receipt r) {
        Intent i = new Intent(this, AttachmentPreviewActivity.class);
        i.putExtra("receiptId", r.getId());
        startActivity(i);
    }

    // ── Category / Sub-category / custom field chips ────────
    private void loadChips() {
        LinearLayout container = findViewById(R.id.chipContainer);
        container.removeAllViews();

        if (txn.getCategoryName() != null && !txn.getCategoryName().isEmpty())
            container.addView(makeChip(txn.getCategoryName()));

        if (txn.getSubCategoryName() != null && !txn.getSubCategoryName().isEmpty())
            container.addView(makeChip(txn.getSubCategoryName()));

        if (txn.getCustomValues() != null && !txn.getCustomValues().isEmpty()) {
            Map<String, String> colNameByKey = new HashMap<>();
            for (ColumnDefinition cd : colDefDao.findByType(txn.getType().name()))
                colNameByKey.put(cd.getColKey(), cd.getColName());

            for (Map.Entry<String, String> e : txn.getCustomValues().entrySet()) {
                if (e.getValue() == null || e.getValue().isEmpty()) continue;
                String label = colNameByKey.getOrDefault(e.getKey(), e.getKey());
                container.addView(makeChip(label + ": " + e.getValue()));
            }
        }
    }

    private TextView makeChip(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(getColor(R.color.primary));
        tv.setTextSize(12);
        tv.setBackgroundResource(R.drawable.bg_entry_chip);
        int padH = (int) (10 * getResources().getDisplayMetrics().density);
        int padV = (int) (6 * getResources().getDisplayMetrics().density);
        tv.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = (int) (6 * getResources().getDisplayMetrics().density);
        tv.setLayoutParams(lp);
        return tv;
    }

    // ── Sync status icon ─────────────────────────────────────
    private void loadSyncStatus() {
        ImageView iv = findViewById(R.id.ivSyncStatus);
        iv.setImageResource(txn.isSynced()
                ? android.R.drawable.presence_online
                : android.R.drawable.presence_away);
    }

    // ── Created By / Last Edited By (from transaction_audit_log) ────
    private void loadCreatedEditedInfo() {
        List<AuditLog> history = auditDao.findByTransactionId(txnId); // ASC order

        TextView tvCreatedBy = findViewById(R.id.tvCreatedBy);
        TextView tvCreatedAt = findViewById(R.id.tvCreatedAt);
        View rowLastEdited = findViewById(R.id.rowLastEdited);
        View dividerLastEdited = findViewById(R.id.dividerLastEdited);
        TextView tvLastEditedBy = findViewById(R.id.tvLastEditedBy);
        TextView tvLastEditedAt = findViewById(R.id.tvLastEditedAt);

        if (history.isEmpty()) {
            tvCreatedBy.setText("You");
            tvCreatedAt.setText("");
            rowLastEdited.setVisibility(View.GONE);
            dividerLastEdited.setVisibility(View.GONE);
            return;
        }

        AuditLog created = history.get(0);
        tvCreatedBy.setText("You");
        tvCreatedAt.setText(created.getChangedAt() != null ? "On " + created.getChangedAt().format(DISPLAY_FMT) : "");

        if (history.size() > 1) {
            AuditLog lastEdit = history.get(history.size() - 1);
            rowLastEdited.setVisibility(View.VISIBLE);
            dividerLastEdited.setVisibility(View.VISIBLE);
            tvLastEditedBy.setText("You");
            tvLastEditedAt.setText(lastEdit.getChangedAt() != null ? "On " + lastEdit.getChangedAt().format(DISPLAY_FMT) : "");
        } else {
            rowLastEdited.setVisibility(View.GONE);
            dividerLastEdited.setVisibility(View.GONE);
        }
    }

    // ── 3-dot menu: Move / Copy / Delete ─────────────────────
    private void showEntryMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "📦 Move Entry");
        menu.getMenu().add(0, 2, 1, "📋 Copy Entry");
        menu.getMenu().add(0, 3, 2, "🗑 Delete Entry");
        menu.getMenu().add(0, 4, 3, "↩ Refund Entry");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) showMoveDialog();
            else if (item.getItemId() == 2) showCopyDialog();
            else if (item.getItemId() == 3) showDeleteConfirm();
            else if (item.getItemId() == 4) showRefundConfirm();
            return true;
        });
        menu.show();
    }

    private void showMoveDialog() {
        List<CashBook> books = bookDao.findAll();
        String[] names = new String[books.size()];
        int currentIdx = 0;
        for (int i = 0; i < books.size(); i++) {
            names[i] = books.get(i).getName();
            if (books.get(i).getId() == txn.getBookId()) currentIdx = i;
        }

        new AlertDialog.Builder(this)
                .setTitle("Move Entry To")
                .setSingleChoiceItems(names, currentIdx, null)
                .setPositiveButton("Move", (d, w) -> {
                    int sel = ((AlertDialog) d).getListView().getCheckedItemPosition();
                    CashBook target = books.get(sel);
                    if (target.getId() == txn.getBookId()) {
                        Toast.makeText(this, "Already in this cashbook", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Transaction moved = copyOf(txn);
                    moved.setId(txnId);
                    moved.setBookId(target.getId());
                    txnDao.update(txn, moved);
                    Toast.makeText(this, "Moved to \"" + target.getName() + "\"", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── 1. Target CashBook தேர்வு செய்யும் முதல் Popup ──
    private void showCopyDialog() {
        List<CashBook> books = bookDao.findAll();
        String[] opts = new String[books.size() + 1];
        opts[0] = "This cashbook (same book)";
        for (int i = 0; i < books.size(); i++) opts[i + 1] = books.get(i).getName();

        new AlertDialog.Builder(this)
                .setTitle("Copy Entry To")
                .setItems(opts, (d, which) -> {
                    int targetBookId = which == 0 ? txn.getBookId() : books.get(which - 1).getId();
                    // CashBook தேர்வு செய்த பிறகு Date Option Popup திரையிடப்படும்
                    showCopyDateOptionsDialog(targetBookId);
                })
                .show();
    }

    // ── 2. Screen 1-இல் உள்ளது போன்ற Copy Date Options Popup ──
    private void showCopyDateOptionsDialog(int targetBookId) {
        String[] options = new String[]{
                "Copy with today's date",
                "Copy with date of entry"
        };

        // default-ஆக "Copy with today's date" (index 0) தேர்ந்தெடுக்கப்பட்டிருக்கும்
        final int[] selectedOption = {0};

        new AlertDialog.Builder(this)
                .setTitle("Copy")
                .setSingleChoiceItems(options, 0, (dialog, which) -> {
                    selectedOption[0] = which;
                })
                .setPositiveButton("OK", (dialog, which) -> {
                    performCopyEntry(targetBookId, selectedOption[0] == 0);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── 3. தேர்ந்தெடுக்கப்பட்ட Date Option-படி பிரதியை உருவாக்குதல் ──
    private void performCopyEntry(int targetBookId, boolean useTodayDate) {
        Transaction dup = copyOf(txn);
        dup.setBookId(targetBookId);

        if (useTodayDate) {
            dup.setDateTime(LocalDateTime.now()); // இன்றைய தேதி மற்றும் நேரம்
        } else {
            dup.setDateTime(txn.getDateTime()); // அசல் Entry-இன் தேதி
        }

        long newId = txnDao.insert(dup);
        Toast.makeText(this, newId != -1 ? "Copied successfully!" : "Copy failed", Toast.LENGTH_SHORT).show();
    }

    private void showDeleteConfirm() {
        new AlertDialog.Builder(this)
                .setTitle("Delete Entry")
                .setMessage("Permanently delete this " + (txn.getType() == Transaction.Type.INCOME ? "income" : "expense") +
                        " entry? This also removes its attachments and edit history. This can't be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    // TransactionDao.delete() removes the transactions row; the schema's
                    // ON DELETE CASCADE foreign keys (transaction_receipts, transaction_
                    // audit_log, transaction_custom_values -> transactions.id) take care
                    // of the rest, as long as PRAGMA foreign_keys=ON is active (LocalDB
                    // sets this in onCreate/onOpen).
                    txnDao.delete(txnId);
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Refund: creates a reverse-type entry, dated now ──────
    // EXPENSE -> new INCOME entry, category "Refund"
    // INCOME  -> new EXPENSE entry, category "Others" (falls back to the
    //            seeded "Other" category if "Others" doesn't exist)
    private void showRefundConfirm() {
        boolean wasExpense = txn.getType() == Transaction.Type.EXPENSE;
        new AlertDialog.Builder(this)
                .setTitle("Refund Entry")
                .setMessage("Create a reverse " + (wasExpense ? "income" : "expense") +
                        " entry of ₹" + txn.getAmount().toPlainString() + " dated today?")
                .setPositiveButton("Create Refund", (d, w) -> createRefundEntry())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void createRefundEntry() {
        boolean wasExpense = txn.getType() == Transaction.Type.EXPENSE;
        Transaction.Type refundType = wasExpense ? Transaction.Type.INCOME : Transaction.Type.EXPENSE;
        String targetCategoryName = wasExpense ? "Refund" : "Others";

        CategoryDao catDao = new CategoryDao(this);
        Category cat = findOrCreateCategory(catDao, refundType.name(), targetCategoryName);
        if (cat == null) {
            Toast.makeText(this, "Couldn't find/create the \"" + targetCategoryName + "\" category", Toast.LENGTH_LONG).show();
            return;
        }

        Transaction refund = new Transaction();
        refund.setType(refundType);
        refund.setDateTime(LocalDateTime.now());
        refund.setAmount(txn.getAmount());
        refund.setCategoryId(cat.getId());
        refund.setBookId(txn.getBookId());
        String originalNote = txn.getNote() != null ? txn.getNote() : "";
        refund.setNote("Refund : " + originalNote);

        long newId = txnDao.insert(refund);
        Toast.makeText(this, newId != -1 ? "Refund entry created!" : "Failed to create refund entry", Toast.LENGTH_SHORT).show();
    }

    // Case-insensitive lookup by name within the given type; creates the
    // category (common/global scope) if genuinely missing so this never
    // silently fails just because the category hasn't been set up yet.
    private Category findOrCreateCategory(CategoryDao catDao, String type, String name) {
        List<Category> cats = catDao.findByType(type, txn.getBookId());
        for (Category c : cats)
            if (c.getName() != null && c.getName().equalsIgnoreCase(name))
                return c;

        // "Others" specifically also matches the app's seeded "Other" category.
        if ("Others".equalsIgnoreCase(name)) {
            for (Category c : cats)
                if (c.getName() != null && c.getName().equalsIgnoreCase("Other"))
                    return c;
        }

        catDao.insert(name, type, null);
        for (Category c : catDao.findByType(type, txn.getBookId()))
            if (c.getName() != null && c.getName().equalsIgnoreCase(name))
                return c;
        return null;
    }

    private Transaction copyOf(Transaction t) {
        Transaction c = new Transaction();
        c.setType(t.getType());
        c.setDateTime(t.getDateTime());
        c.setAmount(t.getAmount());
        c.setCategoryId(t.getCategoryId());
        c.setSubCategoryId(t.getSubCategoryId());
        c.setNote(t.getNote());
        c.setBookId(t.getBookId());
        c.setCustomValues(t.getCustomValues());
        c.setPaymentType(t.getPaymentType());
        return c;
    }
}