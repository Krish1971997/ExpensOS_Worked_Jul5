package com.expenseos.ui;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
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

import com.expenseos.R;
import com.expenseos.dao.CategoryDao;
import com.expenseos.dao.ColumnDefinitionDao;
import com.expenseos.dao.ReceiptDao;
import com.expenseos.dao.SubCategoryDao;
import com.expenseos.dao.TransactionDao;
import com.expenseos.model.Category;
import com.expenseos.model.ColumnDefinition;
import com.expenseos.model.Receipt;
import com.expenseos.model.SubCategory;
import com.expenseos.model.Transaction;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full-screen replacement for the old "+Add Income"/"+Add Expense" popup
 * dialogs. Supports switching between Income/Expense (add mode only —
 * changing type mid-edit would orphan the category/subcategory), category ->
 * subcategory cascade, dynamic custom fields (picked from already-defined
 * column_definitions only — this screen never creates new field
 * definitions), and receipt attachments.
 */
public class TransactionEntryActivity extends AppCompatActivity {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("hh:mm a");
    private static final int REQ_ATTACH = 1001;
    private static final int REQ_SPEECH = 1002;

    private int bookId;
    private int txnId = -1; // -1 = add mode
    private Transaction.Type currentType = Transaction.Type.INCOME;
    private Transaction editingOriginal;

    private TransactionDao txnDao;
    private CategoryDao catDao;
    private SubCategoryDao subCatDao;
    private ColumnDefinitionDao colDefDao;
    private ReceiptDao receiptDao;

    private LocalDate selectedDate = LocalDate.now();
    private LocalTime selectedTime = LocalTime.now();

    private List<Category> currentCategories = new ArrayList<>();
    private List<SubCategory> currentSubCategories = new ArrayList<>();

    // col_key -> input, for whichever custom fields are currently on screen
    private final Map<String, EditText> customFieldInputs = new LinkedHashMap<>();
    private final List<PendingAttachment> pendingAttachments = new ArrayList<>();

    private TextView tvTitle, btnBack, btnFieldSettings, tabIncome, tabExpense, tvDate, tvTime, tvSubCategoryLabel, btnMic;
    private LinearLayout boxDate, boxTime, btnAttach, attachmentList, customFieldsContainer;
    private EditText etAmount, etNote;
    private Spinner spCategory, spSubCategory;
    private Button btnAddMoreFields, btnSaveAddNew, btnSave;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_transaction_entry);

//        SharedPreferences prefs = getSharedPreferences("expenseos_prefs", MODE_PRIVATE);
//        int bookId = prefs.getInt("active_book_id", 0);
        int bookId = com.expenseos.util.AppConfig.get(this).getActiveBookId();

        txnDao = new TransactionDao(this);
        catDao = new CategoryDao(this);
        subCatDao = new SubCategoryDao(this);
        colDefDao = new ColumnDefinitionDao(this);
        receiptDao = new ReceiptDao(this);

        bindViews();
        setupClicks();

        txnId = getIntent().getIntExtra("txnId", -1);

        if (txnId > 0) {
            loadForEdit();
        } else {
            String typeExtra = getIntent().getStringExtra("type");
            currentType = "EXPENSE".equals(typeExtra) ? Transaction.Type.EXPENSE : Transaction.Type.INCOME;
            applyTypeUI();
            loadCategoriesForType();
            updateDateTimeText();
        }
    }

    private void bindViews() {
        tvTitle = findViewById(R.id.tvTitle);
        btnBack = findViewById(R.id.btnBack);
        btnFieldSettings = findViewById(R.id.btnFieldSettings);
        tabIncome = findViewById(R.id.tabIncome);
        tabExpense = findViewById(R.id.tabExpense);
        boxDate = findViewById(R.id.boxDate);
        boxTime = findViewById(R.id.boxTime);
        tvDate = findViewById(R.id.tvDate);
        tvTime = findViewById(R.id.tvTime);
        etAmount = findViewById(R.id.etAmount);
        etNote = findViewById(R.id.etNote);
        btnMic = findViewById(R.id.btnMic);
        btnAttach = findViewById(R.id.btnAttach);
        attachmentList = findViewById(R.id.attachmentList);
        spCategory = findViewById(R.id.spCategory);
        spSubCategory = findViewById(R.id.spSubCategory);
        tvSubCategoryLabel = findViewById(R.id.tvSubCategoryLabel);
        customFieldsContainer = findViewById(R.id.customFieldsContainer);
        btnAddMoreFields = findViewById(R.id.btnAddMoreFields);
        btnSaveAddNew = findViewById(R.id.btnSaveAddNew);
        btnSave = findViewById(R.id.btnSave);
    }

    private void setupClicks() {
        btnBack.setOnClickListener(v -> finish());
        btnFieldSettings.setOnClickListener(v ->
                Toast.makeText(this, "Manage custom fields — coming soon", Toast.LENGTH_SHORT).show());

        tabIncome.setOnClickListener(v -> switchType(Transaction.Type.INCOME));
        tabExpense.setOnClickListener(v -> switchType(Transaction.Type.EXPENSE));

        boxDate.setOnClickListener(v -> showDatePicker());
        boxTime.setOnClickListener(v -> showTimePicker());

        btnMic.setOnClickListener(v -> startVoiceInput());
        btnAttach.setOnClickListener(v -> pickAttachment());

        btnAddMoreFields.setOnClickListener(v -> showAddFieldsPicker());

        btnSaveAddNew.setOnClickListener(v -> save(true));
        btnSave.setOnClickListener(v -> save(false));
    }

    // ── Income/Expense tab switch (add mode only) ──────────
    private void switchType(Transaction.Type type) {
        if (txnId > 0) {
            Toast.makeText(this, "Type can't be changed while editing", Toast.LENGTH_SHORT).show();
            return;
        }
        if (type == currentType) return;
        currentType = type;
        applyTypeUI();
        loadCategoriesForType();
        clearCustomFields();
    }

    private void applyTypeUI() {
        boolean income = currentType == Transaction.Type.INCOME;
        String prefix = txnId > 0 ? "Edit " : "+ Add ";
        tvTitle.setText(prefix + (income ? "Income" : "Expense"));
        tvTitle.setTextColor(getColor(income ? R.color.green : R.color.red));
        etAmount.setTextColor(getColor(income ? R.color.green : R.color.red));

        tabIncome.setTextColor(getColor(income ? R.color.green : R.color.text_muted));
        tabIncome.setTypeface(null, income ? Typeface.BOLD : Typeface.NORMAL);
        tabExpense.setTextColor(getColor(!income ? R.color.red : R.color.text_muted));
        tabExpense.setTypeface(null, !income ? Typeface.BOLD : Typeface.NORMAL);

        if (txnId > 0) {
            tabIncome.setAlpha(0.4f);
            tabExpense.setAlpha(0.4f);
        }

        // Tint just this EditText's border — mutate() so it doesn't affect
        // the other views sharing the same bg_input_box drawable resource.
        Object bg = etAmount.getBackground();
        if (bg instanceof GradientDrawable) {
            GradientDrawable gd = (GradientDrawable) ((GradientDrawable) bg).mutate();
            gd.setStroke((int) (1.5f * getResources().getDisplayMetrics().density),
                    getColor(income ? R.color.green : R.color.red));
            etAmount.setBackground(gd);
        }
    }

    // ── Date / Time pickers — plain system dialogs, so they automatically
    // pick up the app's theme (colorPrimary etc.) without any custom styling ──
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
        tvDate.setText(selectedDate.format(DATE_FMT));
        tvTime.setText(selectedTime.format(TIME_FMT));
    }

    // ── Category -> Sub-category cascade ───────────────────
    private void loadCategoriesForType() {
        currentCategories = catDao.findByType(currentType.name(), bookId);
        ArrayAdapter<Category> adp = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, currentCategories);
        adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCategory.setAdapter(adp);

        spCategory.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos >= 0 && pos < currentCategories.size())
                    loadSubCategoriesFor(currentCategories.get(pos).getId());
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        if (!currentCategories.isEmpty())
            loadSubCategoriesFor(currentCategories.get(0).getId());
        else {
            spSubCategory.setVisibility(View.GONE);
            tvSubCategoryLabel.setVisibility(View.GONE);
        }
    }

    // Only shows the sub-category field when the chosen category actually
    // has sub-categories — some categories have none, some have several.
    private void loadSubCategoriesFor(int catId) {
        currentSubCategories = subCatDao.findByCategoryId(catId);
        if (currentSubCategories.isEmpty()) {
            spSubCategory.setVisibility(View.GONE);
            tvSubCategoryLabel.setVisibility(View.GONE);
        } else {
            spSubCategory.setVisibility(View.VISIBLE);
            tvSubCategoryLabel.setVisibility(View.VISIBLE);
            ArrayAdapter<SubCategory> adp = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, currentSubCategories);
            adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spSubCategory.setAdapter(adp);
        }
    }

    // ── Custom fields — "Add More Fields" only ever picks from fields that
    // already exist in column_definitions; it never defines new ones ─────
    private void showAddFieldsPicker() {
        List<ColumnDefinition> all = colDefDao.findByType(currentType.name());
        List<ColumnDefinition> available = new ArrayList<>();
        for (ColumnDefinition cd : all)
            if (!customFieldInputs.containsKey(cd.getColKey()))
                available.add(cd);

        if (available.isEmpty()) {
            Toast.makeText(this, "No more fields available for this type.", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] names = new String[available.size()];
        for (int i = 0; i < available.size(); i++) names[i] = available.get(i).getColName();
        boolean[] checked = new boolean[available.size()];

        new AlertDialog.Builder(this)
                .setTitle("Add Fields")
                .setMultiChoiceItems(names, checked, (dlg, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Add", (dlg, w) -> {
                    for (int i = 0; i < available.size(); i++)
                        if (checked[i]) addCustomFieldRow(available.get(i), null);
                })
                .setNegativeButton("Cancel", null)
                .show();
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

    private void clearCustomFields() {
        customFieldsContainer.removeAllViews();
        customFieldInputs.clear();
    }

    // ── Voice input for Remark ──────────────────────────────
    private void startVoiceInput() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak remark…");
        try {
            startActivityForResult(intent, REQ_SPEECH);
        } catch (Exception e) {
            Toast.makeText(this, "Voice input not available on this device", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Attach Image or PDF ─────────────────────────────────
    private void pickAttachment() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf"});
        startActivityForResult(Intent.createChooser(intent, "Attach Image or PDF"), REQ_ATTACH);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQ_ATTACH && data.getData() != null) {
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
        } else if (requestCode == REQ_SPEECH) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                String current = etNote.getText().toString().trim();
                etNote.setText(current.isEmpty() ? results.get(0) : current + " " + results.get(0));
            }
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
        LinearLayout row = buildAttachmentRow("📎 " + pa.name, () -> {
            pendingAttachments.remove(pa);
        });
        attachmentList.addView(row);
    }

    // Existing (already-saved) receipts in edit mode — remove deletes them
    // from the DB immediately rather than just clearing an in-memory list.
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
        tv.setTextColor(getColor(R.color.text_muted));
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

    // ── Edit mode ────────────────────────────────────────────
    private void loadForEdit() {
        editingOriginal = txnDao.findById(txnId);
        if (editingOriginal == null) {
            finish();
            return;
        }
        currentType = editingOriginal.getType();
        applyTypeUI();

        selectedDate = editingOriginal.getDateTime().toLocalDate();
        selectedTime = editingOriginal.getDateTime().toLocalTime();
        updateDateTimeText();

        etAmount.setText(editingOriginal.getAmount().toPlainString());
        etNote.setText(editingOriginal.getNote());

        currentCategories = catDao.findByType(currentType.name(), bookId);
        ArrayAdapter<Category> adp = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, currentCategories);
        adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCategory.setAdapter(adp);

        int catPos = 0;
        for (int i = 0; i < currentCategories.size(); i++)
            if (currentCategories.get(i).getId() == editingOriginal.getCategoryId()) {
                catPos = i;
                break;
            }
        spCategory.setSelection(catPos);

        if (!currentCategories.isEmpty())
            loadSubCategoriesFor(currentCategories.get(catPos).getId());

        spCategory.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos >= 0 && pos < currentCategories.size())
                    loadSubCategoriesFor(currentCategories.get(pos).getId());
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        if (editingOriginal.getSubCategoryId() > 0) {
            for (int i = 0; i < currentSubCategories.size(); i++)
                if (currentSubCategories.get(i).getId() == editingOriginal.getSubCategoryId()) {
                    spSubCategory.setSelection(i);
                    break;
                }
        }

        // Pre-populate already-saved custom values
        if (!editingOriginal.getCustomValues().isEmpty()) {
            List<ColumnDefinition> defs = colDefDao.findByType(currentType.name());
            Map<String, ColumnDefinition> byKey = new HashMap<>();
            for (ColumnDefinition cd : defs) byKey.put(cd.getColKey(), cd);
            for (Map.Entry<String, String> e : editingOriginal.getCustomValues().entrySet()) {
                ColumnDefinition cd = byKey.get(e.getKey());
                if (cd != null) addCustomFieldRow(cd, e.getValue());
            }
        }

        for (Receipt r : receiptDao.findMetaByTransactionId(txnId))
            addExistingReceiptRow(r);

        btnSaveAddNew.setVisibility(View.GONE); // doesn't apply when editing one specific record
    }

    // ── Save ─────────────────────────────────────────────────
    private void save(boolean addNew) {
        String amtStr = etAmount.getText().toString().trim();
        if (amtStr.isEmpty()) {
            Toast.makeText(this, "Enter amount", Toast.LENGTH_SHORT).show();
            return;
        }
        if (spCategory.getSelectedItem() == null) {
            Toast.makeText(this, "Select a category", Toast.LENGTH_SHORT).show();
            return;
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(amtStr);
        } catch (Exception e) {
            Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show();
            return;
        }

        Category cat = (Category) spCategory.getSelectedItem();
        SubCategory sub = (spSubCategory.getVisibility() == View.VISIBLE && spSubCategory.getSelectedItem() != null)
                ? (SubCategory) spSubCategory.getSelectedItem() : null;

        Transaction t = new Transaction();
        t.setType(currentType);
        t.setDateTime(LocalDateTime.of(selectedDate, selectedTime));
        t.setAmount(amount);
        t.setCategoryId(cat.getId());
        t.setSubCategoryId(sub != null ? sub.getId() : 0);
        t.setNote(etNote.getText().toString().trim());
        t.setBookId(bookId);

        Map<String, String> customValues = new LinkedHashMap<>();
        for (Map.Entry<String, EditText> e : customFieldInputs.entrySet())
            customValues.put(e.getKey(), e.getValue().getText().toString().trim());
        t.setCustomValues(customValues);

        if (txnId > 0) {
            t.setId(txnId);
            txnDao.update(editingOriginal, t);
            txnDao.saveCustomValues(txnId, customValues);
            saveAttachments(txnId);
            Toast.makeText(this, "Updated!", Toast.LENGTH_SHORT).show();
            finish();
        } else {
            long newId = txnDao.insert(t);
            if (newId == -1) {
                Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show();
                return;
            }
            saveAttachments((int) newId);
            Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show();
            if (addNew) resetFormForNewEntry();
            else finish();
        }
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

    private void resetFormForNewEntry() {
        etAmount.setText("");
        etNote.setText("");
        clearCustomFields();
        attachmentList.removeAllViews();
        pendingAttachments.clear();
        selectedDate = LocalDate.now();
        selectedTime = LocalTime.now();
        updateDateTimeText();
        etAmount.requestFocus();
    }

    private static class PendingAttachment {
        final String name, mimeType;
        final byte[] bytes;

        PendingAttachment(String name, String mimeType, byte[] bytes) {
            this.name = name;
            this.mimeType = mimeType;
            this.bytes = bytes;
        }
    }
}