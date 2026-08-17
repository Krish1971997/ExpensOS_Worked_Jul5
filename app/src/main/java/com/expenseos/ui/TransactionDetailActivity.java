package com.expenseos.ui;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.expenseos.R;
import com.expenseos.dao.CashBookDao;
import com.expenseos.dao.CategoryDao;
import com.expenseos.dao.ColumnDefinitionDao;
import com.expenseos.dao.PaymentTypeDao;
import com.expenseos.dao.ReceiptDao;
import com.expenseos.dao.SubCategoryDao;
import com.expenseos.dao.TransactionDao;
import com.expenseos.model.CashBook;
import com.expenseos.model.Category;
import com.expenseos.model.ColumnDefinition;
import com.expenseos.model.PaymentType;
import com.expenseos.model.Receipt;
import com.expenseos.model.SubCategory;
import com.expenseos.model.Transaction;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transaction Detail — the dedicated EDIT screen (reached via
 * EntryDetailActivity's "EDIT ENTRY"). Deliberately mirrors every field and
 * validation in TransactionEntryActivity's add/edit form (amount, date/time,
 * payment type, category -> sub-category cascade, dynamic custom fields,
 * attachments) so editing here is never missing something the Add screen
 * has — plus this screen's own Prev/Next/Duplicate/Move/Delete actions.
 * <p>
 * Only used for editing an existing transaction (txnId is always > 0 here).
 * New-transaction creation continues to go through TransactionEntryActivity
 * unchanged.
 */
public class TransactionDetailActivity extends AppCompatActivity {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("hh:mm a");

    private static final int REQ_ATTACH = 2001;
    private static final int REQ_CAMERA = 2002;

    private int bookId; // active book — scopes Prev/Next cycling, same as before
    private int txnId;
    private Transaction current;
    private LocalDate selectedDate;
    private LocalTime selectedTime;

    private TransactionDao txnDao;
    private CategoryDao catDao;
    private SubCategoryDao scDao;
    private CashBookDao bookDao;
    private ColumnDefinitionDao colDefDao;
    private ReceiptDao receiptDao;
    private PaymentTypeDao payDao;

    private List<Category> currentCategories = new ArrayList<>();
    private List<SubCategory> currentSubCategories = new ArrayList<>();
    private final Map<String, EditText> customFieldInputs = new LinkedHashMap<>();
    private final List<PendingAttachment> pendingAttachments = new ArrayList<>();
    private Uri pendingCameraUri;

    // Edit fields
    private TextView tvDetailTitle, tvDetailType, tvDetailDate, tvDetailTime;
    private LinearLayout boxDetailDate, boxDetailTime, customFieldsContainer, attachmentList, btnDetailAttach;
    private EditText etAmount, etNote;
    private Spinner spCategory, spSubCategory, spPaymentType, spMoveBook;
    private TextView tvSubCategoryLabel;
    private Button btnPrev, btnNext;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_transaction_detail);

        bookId = com.expenseos.util.AppConfig.get(this).getActiveBookId();
        txnId = getIntent().getIntExtra("txnId", -1);

        if (txnId < 0) {
            finish();
            return;
        }

        txnDao = new TransactionDao(this);
        catDao = new CategoryDao(this);
        scDao = new SubCategoryDao(this);
        bookDao = new CashBookDao(this);
        colDefDao = new ColumnDefinitionDao(this);
        receiptDao = new ReceiptDao(this);
        payDao = new PaymentTypeDao(this);

        bindViews();
        setupButtons();
        loadTransaction();
    }

    private void bindViews() {
        tvDetailTitle = findViewById(R.id.tvDetailTitle);
        tvDetailType = findViewById(R.id.tvDetailType);
        boxDetailDate = findViewById(R.id.boxDetailDate);
        boxDetailTime = findViewById(R.id.boxDetailTime);
        tvDetailDate = findViewById(R.id.tvDetailDate);
        tvDetailTime = findViewById(R.id.tvDetailTime);
        etAmount = findViewById(R.id.etDetailAmount);
        etNote = findViewById(R.id.etDetailNote);
        spPaymentType = findViewById(R.id.spDetailPaymentType);
        btnDetailAttach = findViewById(R.id.btnDetailAttach);
        attachmentList = findViewById(R.id.attachmentListDetail);
        spCategory = findViewById(R.id.spDetailCategory);
        spSubCategory = findViewById(R.id.spDetailSubCategory);
        tvSubCategoryLabel = findViewById(R.id.tvDetailSubCategoryLabel);
        customFieldsContainer = findViewById(R.id.customFieldsContainerDetail);
        spMoveBook = findViewById(R.id.spMoveBook);
        btnPrev = findViewById(R.id.btnDetailPrev);
        btnNext = findViewById(R.id.btnDetailNext);
    }

    private void setupButtons() {
        findViewById(R.id.btnDetailBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnDetailSave).setOnClickListener(v -> saveTransaction());
        findViewById(R.id.btnDetailDuplicate).setOnClickListener(v -> showDuplicateDialog());
        findViewById(R.id.btnDetailMove).setOnClickListener(v -> moveTransaction());
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

        btnPrev.setOnClickListener(v -> navigateTo(txnDao.findPrevId(txnId, bookId)));
        btnNext.setOnClickListener(v -> navigateTo(txnDao.findNextId(txnId, bookId)));

        boxDetailDate.setOnClickListener(v -> showDatePicker());
        boxDetailTime.setOnClickListener(v -> showTimePicker());
        btnDetailAttach.setOnClickListener(v -> pickAttachment());
    }

    // ── Date / Time pickers — same UX as the Add/Edit screen ──────────
    private void showDatePicker() {
        new DatePickerDialog(this, (view, y, m, d) -> {
            selectedDate = LocalDate.of(y, m + 1, d);
            updateDateTimeText();
        }, selectedDate.getYear(), selectedDate.getMonthValue() - 1, selectedDate.getDayOfMonth()).show();
    }

    private void showTimePicker() {
        new TimePickerDialog(this, (view, h, min) -> {
            selectedTime = LocalTime.of(h, min);
            updateDateTimeText();
        }, selectedTime.getHour(), selectedTime.getMinute(), false).show();
    }

    private void updateDateTimeText() {
        tvDetailDate.setText(selectedDate.format(DATE_FMT));
        tvDetailTime.setText(selectedTime.format(TIME_FMT));
    }

    // ── Load transaction into form ─────────────────────────
    private void loadTransaction() {
        current = txnDao.findById(txnId);
        if (current == null) {
            finish();
            return;
        }

        // Clear anything left over from a previously-loaded transaction
        // (Prev/Next reuses this same screen instance).
        customFieldsContainer.removeAllViews();
        customFieldInputs.clear();
        attachmentList.removeAllViews();
        pendingAttachments.clear();

        boolean isIncome = current.getType() == Transaction.Type.INCOME;
        tvDetailTitle.setText("Edit " + (isIncome ? "Income" : "Expense"));
        tvDetailType.setText(current.getType().name());
        int typeColor = isIncome ? getColor(R.color.green) : getColor(R.color.red);
        tvDetailType.setTextColor(typeColor);

        // Fields
        etAmount.setText(current.getAmount().toPlainString());
        etAmount.setTextColor(typeColor);
        etNote.setText(current.getNote() != null ? current.getNote() : "");

        LocalDateTime dt = current.getDateTime();
        selectedDate = dt != null ? dt.toLocalDate() : LocalDate.now();
        selectedTime = dt != null ? dt.toLocalTime() : LocalTime.now();
        updateDateTimeText();

        // Payment type — old transactions predating this column default to
        // whatever PaymentTypeDao marks as default (loadPaymentTypes handles
        // the null case).
        loadPaymentTypes(current.getPaymentType());

        // Category spinner — scoped to the transaction's OWN book, not
        // necessarily the currently-active book (it may differ).
        String catType = current.getType().name();
        currentCategories = catDao.findByType(catType, current.getBookId());
        ArrayAdapter<Category> catAdp = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, currentCategories);
        catAdp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCategory.setAdapter(catAdp);
        int catPos = 0;
        for (int i = 0; i < currentCategories.size(); i++) {
            if (currentCategories.get(i).getId() == current.getCategoryId()) {
                catPos = i;
                break;
            }
        }
        spCategory.setSelection(catPos, false);
        if (!currentCategories.isEmpty()) {
            loadSubCategoriesFor(currentCategories.get(catPos).getId());
        }

        // Sub-category cascade — attach listener AFTER initial selection so
        // it only fires on a genuine user change, not the programmatic one above.
        spCategory.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean isInitial = true;

            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (isInitial) {
                    isInitial = false;
                    return;
                }
                if (pos >= 0 && pos < currentCategories.size()) {
                    loadSubCategoriesFor(currentCategories.get(pos).getId());
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        // Custom fields — every field defined for this type, pre-filled
        // where the transaction already has a saved value.
        loadCustomFieldsForType(current.getCustomValues());

        // Attachments — existing (saved) receipts, each removable.
        for (Receipt r : receiptDao.findMetaByTransactionId(txnId))
            addExistingReceiptRow(r);

        // Move-to-book spinner
        List<CashBook> books = bookDao.findAll();
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
        spMoveBook.setTag(books);

        // Prev / Next availability — findPrevId/findNextId return boxed
        // Integer and are null when there's no adjacent transaction, so
        // don't auto-unbox directly (that NPEs).
        Integer prevId = txnDao.findPrevId(txnId, bookId);
        Integer nextId = txnDao.findNextId(txnId, bookId);
        boolean hasPrev = prevId != null && prevId != 0;
        boolean hasNext = nextId != null && nextId != 0;
        btnPrev.setEnabled(hasPrev);
        btnNext.setEnabled(hasNext);
        btnPrev.setAlpha(hasPrev ? 1f : 0.4f);
        btnNext.setAlpha(hasNext ? 1f : 0.4f);
    }

    private void loadSubCategoriesFor(int catId) {
        currentSubCategories = scDao.findByCategoryId(catId);
        if (currentSubCategories.isEmpty()) {
            spSubCategory.setVisibility(View.GONE);
            tvSubCategoryLabel.setVisibility(View.GONE);
            return;
        }
        spSubCategory.setVisibility(View.VISIBLE);
        tvSubCategoryLabel.setVisibility(View.VISIBLE);

        List<SubCategory> withNone = new ArrayList<>();
        withNone.add(new SubCategory(0, "Select Sub Category", catId));
        withNone.addAll(currentSubCategories);
        ArrayAdapter<SubCategory> adp = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, withNone);
        adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSubCategory.setAdapter(adp);

        for (int i = 0; i < withNone.size(); i++) {
            if (withNone.get(i).getId() == current.getSubCategoryId()) {
                spSubCategory.setSelection(i);
                break;
            }
        }
    }

    private void loadPaymentTypes(@Nullable String preselect) {
        List<PaymentType> types = payDao.findAll();
        ArrayAdapter<PaymentType> adp =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, types);
        adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spPaymentType.setAdapter(adp);

        String target = preselect;
        if (target == null || target.isEmpty()) {
            for (PaymentType t : types)
                if (t.isDefault()) {
                    target = t.getName();
                    break;
                }
        }
        if (target == null) target = "UPI";
        int pos = 0;
        for (int i = 0; i < types.size(); i++)
            if (types.get(i).getName().equalsIgnoreCase(target)) {
                pos = i;
                break;
            }
        spPaymentType.setSelection(pos);
    }

    // ── Custom fields — identical behavior to TransactionEntryActivity ──
    private void loadCustomFieldsForType(@Nullable Map<String, String> existingValues) {
        List<ColumnDefinition> defs = colDefDao.findByType(current.getType().name());
        for (ColumnDefinition cd : defs) {
            String existing = existingValues != null ? existingValues.get(cd.getColKey()) : null;
            addCustomFieldRow(cd, existing);
        }
    }

    private void addCustomFieldRow(ColumnDefinition cd, @Nullable String existingValue) {
        if (customFieldInputs.containsKey(cd.getColKey())) return;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(14);
        row.setLayoutParams(rowLp);

        TextView label = new TextView(this);
        label.setText(cd.getColName());
        label.setTextColor(getColor(R.color.primary));
        label.setTextSize(13);
        row.addView(label);

        EditText input = new EditText(this);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        inputLp.topMargin = dp(4);
        input.setLayoutParams(inputLp);
        input.setBackgroundResource(R.drawable.bg_input_box);
        input.setPadding(dp(12), 0, dp(12), 0);
        if (existingValue != null) input.setText(existingValue);
        row.addView(input);

        customFieldsContainer.addView(row);
        customFieldInputs.put(cd.getColKey(), input);
    }

    // ── Attach Image or PDF — same 3-option sheet as the Add/Edit screen ──
    private void pickAttachment() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, dp(8), 0, dp(16));

        TextView title = new TextView(this);
        title.setText("Attach Image or PDF");
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(20), dp(12), dp(20), dp(12));
        container.addView(title);

        container.addView(attachSheetOption("📷", "Take photo using camera", () -> {
            sheet.dismiss();
            launchCamera();
        }));
        container.addView(attachSheetOption("🖼", "Choose from gallery", () -> {
            sheet.dismiss();
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            startActivityForResult(intent, REQ_ATTACH);
        }));
        container.addView(attachSheetOption("📄", "Choose PDF", () -> {
            sheet.dismiss();
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("application/pdf");
            startActivityForResult(intent, REQ_ATTACH);
        }));

        sheet.setContentView(container);
        sheet.show();
    }

    private View attachSheetOption(String emoji, String label, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(14), dp(20), dp(14));
        row.setClickable(true);
        row.setFocusable(true);

        TypedValue outValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        row.setBackgroundResource(outValue.resourceId);

        TextView tvEmoji = new TextView(this);
        tvEmoji.setText(emoji);
        tvEmoji.setTextSize(18);
        tvEmoji.setLayoutParams(new LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(tvEmoji);

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextSize(16);
        tvLabel.setTextColor(getColor(R.color.text_primary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(16);
        tvLabel.setLayoutParams(lp);
        row.addView(tvLabel);

        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private void launchCamera() {
        try {
            File dir = new File(getCacheDir(), "receipts");
            if (!dir.exists()) dir.mkdirs();
            File photoFile = File.createTempFile("receipt_", ".jpg", dir);
            pendingCameraUri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", photoFile);

            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(intent, REQ_CAMERA);
        } catch (Exception e) {
            Toast.makeText(this, "Camera not available: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;

        if (requestCode == REQ_ATTACH && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                String name = queryFileName(uri);
                String type = getContentResolver().getType(uri);
                byte[] bytes = readBytes(uri);
                PendingAttachment pa = new PendingAttachment(name, type, bytes);
                pendingAttachments.add(pa);
                addPendingAttachmentRow(pa);
            } catch (Exception e) {
                Toast.makeText(this, "Couldn't read file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQ_CAMERA && pendingCameraUri != null) {
            try {
                byte[] bytes = readBytes(pendingCameraUri);
                String name = "receipt_" + System.currentTimeMillis() + ".jpg";
                PendingAttachment pa = new PendingAttachment(name, "image/jpeg", bytes);
                pendingAttachments.add(pa);
                addPendingAttachmentRow(pa);
            } catch (Exception e) {
                Toast.makeText(this, "Couldn't read photo: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
            pendingCameraUri = null;
        }
    }

    private String queryFileName(Uri uri) {
        String name = "file";
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        }
        return name;
    }

    private byte[] readBytes(Uri uri) throws Exception {
        try (InputStream is = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while (is != null && (n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private void addPendingAttachmentRow(PendingAttachment pa) {
        LinearLayout row = buildAttachmentRow("📎 " + pa.name, () -> pendingAttachments.remove(pa));
        attachmentList.addView(row);
    }

    // Existing (already-saved) receipts — remove deletes them from the DB
    // immediately, not just from this in-memory list.
    private void addExistingReceiptRow(Receipt r) {
        LinearLayout row = buildAttachmentRow("📎 " + r.getFileName() + " (" + r.getFileSizeDisplay() + ")", null);
        View removeBtn = row.getChildAt(1);
        removeBtn.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Delete attachment?")
                .setMessage(r.getFileName())
                .setPositiveButton("Delete", (d, w) -> {
                    receiptDao.delete(r.getId(), r.getFileName());
                    attachmentList.removeView(row);
                })
                .setNegativeButton("Cancel", null)
                .show());
        attachmentList.addView(row);
    }

    private LinearLayout buildAttachmentRow(String text, @Nullable Runnable onRemove) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(getColor(R.color.text_secondary));
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);

        TextView remove = new TextView(this);
        remove.setText("✕");
        remove.setTextColor(getColor(R.color.red));
        remove.setPadding(dp(8), dp(8), dp(8), dp(8));
        if (onRemove != null) {
            remove.setOnClickListener(v -> {
                onRemove.run();
                attachmentList.removeView(row);
            });
        }
        row.addView(remove);
        return row;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ── Save (update) — same validations as TransactionEntryActivity ──
    private void saveTransaction() {
        String amtStr = etAmount.getText().toString().trim();
        if (amtStr.isEmpty()) {
            Toast.makeText(this, "Enter amount", Toast.LENGTH_SHORT).show();
            return;
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(amtStr);
        } catch (Exception e) {
            Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show();
            return;
        }

        if (spCategory.getSelectedItem() == null) {
            Toast.makeText(this, "Please select a category", Toast.LENGTH_SHORT).show();
            return;
        }
        Category selCat = (Category) spCategory.getSelectedItem();

        SubCategory selSub = null;
        if (spSubCategory.getVisibility() == View.VISIBLE) {
            selSub = (SubCategory) spSubCategory.getSelectedItem();
            if (selSub == null || selSub.getId() == 0) {
                Toast.makeText(this, "Please select a subcategory", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        if (spPaymentType.getSelectedItem() == null) {
            Toast.makeText(this, "Select a payment type", Toast.LENGTH_SHORT).show();
            return;
        }

        // Resolve selected book (move)
        @SuppressWarnings("unchecked")
        List<CashBook> books = (List<CashBook>) spMoveBook.getTag();
        int newBookId = books != null && spMoveBook.getSelectedItemPosition() >= 0
                ? books.get(spMoveBook.getSelectedItemPosition()).getId()
                : current.getBookId();

        Map<String, String> customValues = new LinkedHashMap<>();
        for (Map.Entry<String, EditText> e : customFieldInputs.entrySet())
            customValues.put(e.getKey(), e.getValue().getText().toString().trim());

        Transaction updated = new Transaction();
        updated.setId(txnId);
        updated.setType(current.getType());
        updated.setAmount(amount);
        updated.setCategoryId(selCat.getId());
        updated.setSubCategoryId(selSub != null && selSub.getId() > 0 ? selSub.getId() : 0);
        updated.setNote(etNote.getText().toString().trim());
        updated.setDateTime(LocalDateTime.of(selectedDate, selectedTime));
        updated.setBookId(newBookId);
        updated.setPaymentType(((PaymentType) spPaymentType.getSelectedItem()).getName());
        updated.setCustomValues(customValues);

        txnDao.update(current, updated);
        txnDao.saveCustomValues(txnId, customValues);
        saveAttachments(txnId);
        Toast.makeText(this, "✓ Saved!", Toast.LENGTH_SHORT).show();
        loadTransaction(); // refresh display (also re-renders newly-saved attachments)
    }

    private void saveAttachments(int forTxnId) {
        for (PendingAttachment pa : pendingAttachments) {
            Receipt r = new Receipt();
            r.setTransactionId(forTxnId);
            r.setFileName(pa.name);
            r.setFileType(pa.mimeType);
            r.setFileData(pa.bytes);
            r.setFileSize(pa.bytes != null ? pa.bytes.length : 0);
            receiptDao.insert(r);
        }
        pendingAttachments.clear();
    }

    // ── Duplicate ─────────────────────────────────────────
    private void showDuplicateDialog() {
        String[] opts = {"Copy with today's date", "Copy with original date"};
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
                    dup.setPaymentType(current.getPaymentType());
                    dup.setCustomValues(current.getCustomValues());
                    int newId = (int) txnDao.insert(dup);

                    Toast.makeText(this, "Duplicated! New #" + newId, Toast.LENGTH_SHORT).show();
                    txnId = newId;
                    loadTransaction();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    //test
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
                    moved.setPaymentType(current.getPaymentType());
                    moved.setCustomValues(current.getCustomValues());
                    txnDao.update(current, moved);
                    Toast.makeText(this, "Moved!", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Prev / Next navigation ────────────────────────────
    private void navigateTo(Integer id) {
        if (id == null || id == 0) return;
        txnId = id;
        loadTransaction();
    }

    private record PendingAttachment(String name, String mimeType, byte[] bytes) {
    }
}