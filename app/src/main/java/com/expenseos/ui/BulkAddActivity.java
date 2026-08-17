package com.expenseos.ui;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.expenseos.R;
import com.expenseos.dao.CategoryDao;
import com.expenseos.dao.ColumnDefinitionDao;
import com.expenseos.dao.KeywordMappingDao;
import com.expenseos.dao.PaymentTypeDao;
import com.expenseos.dao.ReceiptDao;
import com.expenseos.dao.SubCategoryDao;
import com.expenseos.dao.TransactionDao;
import com.expenseos.model.Category;
import com.expenseos.model.ColumnDefinition;
import com.expenseos.model.KeywordMapping;
import com.expenseos.model.PaymentType;
import com.expenseos.model.Receipt;
import com.expenseos.model.SubCategory;
import com.expenseos.model.Transaction;
import com.expenseos.util.AppConfig;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bulk Add Transactions — add multiple rows at once, each row carrying every
 * field TransactionEntryActivity supports (Date/Time pickers, Payment Type,
 * Category -> Sub-Category cascade, Note + Mic, keyword auto-suggest,
 * Attach Image/PDF, custom fields). Header stays compact; the rest lives in
 * a per-row expandable panel (▾/▴) so a screen full of rows stays scannable.
 */
public class BulkAddActivity extends AppCompatActivity {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("hh:mm a");

    private static final int REQ_ATTACH = 2001;
    private static final int REQ_SPEECH = 2002;
    private static final int REQ_CAMERA = 2003;

    private int bookId;
    private TransactionDao txnDao;
    private CategoryDao catDao;
    private SubCategoryDao subCatDao;
    private ColumnDefinitionDao colDefDao;
    private ReceiptDao receiptDao;
    private PaymentTypeDao payDao;
    private KeywordMappingDao kwDao;

    private LinearLayout rowContainer;
    private TextView tvSummary, tvResult;
    private Button btnSaveAll;

    private final List<BulkRow> rows = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    // Which row initiated the in-flight attach/camera/speech intent —
    // set right before launching, consumed in onActivityResult. Needed
    // because these results are activity-level but must land on one row.
    private BulkRow activeRow;
    private Uri pendingCameraUri;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_bulk_add);

        bookId = AppConfig.get(this).getActiveBookId();
        txnDao = new TransactionDao(this);
        catDao = new CategoryDao(this);
        subCatDao = new SubCategoryDao(this);
        colDefDao = new ColumnDefinitionDao(this);
        receiptDao = new ReceiptDao(this);
        payDao = new PaymentTypeDao(this);
        kwDao = new KeywordMappingDao(this);

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
        findViewById(R.id.btnBulkSaveAllBottom).setOnClickListener(v -> saveAll());

        addRow();
        addRow();
        addRow();
    }

    // ══════════════════════════════════════════════════════
    // ADD ROW
    // ══════════════════════════════════════════════════════
    private void addRow() {
        View v = LayoutInflater.from(this).inflate(R.layout.item_bulk_row, rowContainer, false);

        BulkRow row = new BulkRow();
        row.rootView = v;
        row.spType = v.findViewById(R.id.spBulkType);
        row.tvDateTime = v.findViewById(R.id.tvBulkDateTime);
        row.etAmount = v.findViewById(R.id.etBulkAmount);
        row.spCategory = v.findViewById(R.id.spBulkCategory);
        row.btnExpandToggle = v.findViewById(R.id.btnBulkExpand);
        row.btnDel = v.findViewById(R.id.btnBulkRowDel);
        row.expandPanel = v.findViewById(R.id.llBulkExpand);
        row.spSubCategory = v.findViewById(R.id.spBulkSubCategory);
        row.spPaymentType = v.findViewById(R.id.spBulkPaymentType);
        row.etNote = v.findViewById(R.id.etBulkNote);
        row.btnMic = v.findViewById(R.id.btnBulkMic);
        row.tvKwSuggestion = v.findViewById(R.id.tvBulkKwSuggestion);
        row.btnAttach = v.findViewById(R.id.btnBulkAttach);
        row.btnCalc = v.findViewById(R.id.btnBulkCalc);
        row.attachmentList = v.findViewById(R.id.llBulkAttachList);
        row.customFieldsContainer = v.findViewById(R.id.llBulkCustomFields);

        // Type spinner
        ArrayAdapter<String> typeAdp = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[]{"EXPENSE", "INCOME"});
        typeAdp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        row.spType.setAdapter(typeAdp);

        row.date = LocalDate.now();
        row.time = LocalTime.now();
        updateRowDateTimeText(row);

        row.tvDateTime.setOnClickListener(x -> showDatePicker(row));

        row.spType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View vw, int pos, long id) {
                String type = pos == 1 ? "INCOME" : "EXPENSE";
                loadCategoriesForRow(row, type);
                clearCustomFieldsForRow(row);
                loadCustomFieldsForRow(row, type);
                loadPaymentTypesForRow(row);
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        loadCategoriesForRow(row, "EXPENSE"); // default
        loadCustomFieldsForRow(row, "EXPENSE");
        loadPaymentTypesForRow(row);
        wireDescriptionAutoSuggest(row);

        row.btnExpandToggle.setOnClickListener(x -> {
            row.expanded = !row.expanded;
            row.expandPanel.setVisibility(row.expanded ? View.VISIBLE : View.GONE);
            row.btnExpandToggle.setText(row.expanded ? "▴" : "▾");
        });

        row.btnMic.setOnClickListener(x -> startVoiceInput(row));
        row.btnAttach.setOnClickListener(x -> pickAttachment(row));
        row.btnCalc.setOnClickListener(x ->
                CalculatorDialog.show(this, row.etAmount.getText().toString(), resultText ->
                        row.etAmount.setText(resultText)));

        row.btnDel.setOnClickListener(x -> {
            rows.remove(row);
            rowContainer.removeView(v);
            updateSummary();
        });

        row.etAmount.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(Editable e) {
                updateSummary();
            }

            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }
        });

        rows.add(row);
        rowContainer.addView(v);
        updateSummary();
    }

    // ══════════════════════════════════════════════════════
    // Date / Time pickers
    // ══════════════════════════════════════════════════════
// NEW
    private void showDatePicker(BulkRow row) {
        new DatePickerDialog(this, (view, y, m, d) -> {
            row.date = LocalDate.of(y, m + 1, d);
            // Post instead of calling directly — the DatePickerDialog is
            // still in the middle of dismissing when onDateSet() fires, so
            // opening TimePickerDialog synchronously here can silently
            // fail to show on some devices. Posting waits one frame.
            mainHandler.post(() -> showTimePicker(row));
        }, row.date.getYear(), row.date.getMonthValue() - 1, row.date.getDayOfMonth()).show();
    }

    private void showTimePicker(BulkRow row) {
        new TimePickerDialog(this, (view, h, min) -> {
            row.time = LocalTime.of(h, min);
            updateRowDateTimeText(row);
        }, row.time.getHour(), row.time.getMinute(), false).show();
    }

    private void updateRowDateTimeText(BulkRow row) {
        row.tvDateTime.setText(row.date.format(DATE_FMT) + " " + row.time.format(TIME_FMT));
    }

    // ══════════════════════════════════════════════════════
    // Category -> Sub-Category cascade (mirrors TransactionEntryActivity)
    // ══════════════════════════════════════════════════════
    private void loadCategoriesForRow(BulkRow row, String type) {
        row.cachedCats = catDao.findByType(type, bookId);
        List<Category> withPlaceholder = new ArrayList<>();
        withPlaceholder.add(new Category(0, "Select Category", type, null));
        withPlaceholder.addAll(row.cachedCats);
        ArrayAdapter<Category> adp = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, withPlaceholder) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                v.setSingleLine(true);
                v.setEllipsize(TextUtils.TruncateAt.END);
                v.setTextSize(12);
                return v;
            }
        };
        adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        row.spCategory.setAdapter(adp);
        row.spCategory.setSelection(0);

        row.spCategory.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos == 0) {
                    row.spSubCategory.setVisibility(View.GONE);
                } else if (pos - 1 < row.cachedCats.size()) {
                    loadSubCategoriesForRow(row, row.cachedCats.get(pos - 1).getId());
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
    }

    private void loadSubCategoriesForRow(BulkRow row, int catId) {
        row.cachedSubCats = subCatDao.findByCategoryId(catId);
        if (row.cachedSubCats.isEmpty()) {
            row.spSubCategory.setVisibility(View.GONE);
        } else {
            row.spSubCategory.setVisibility(View.VISIBLE);
            if (row.cachedSubCats.size() > 1) {
                row.cachedSubCats.add(0, new SubCategory(0, "Select Sub Category", catId));
            }
            ArrayAdapter<SubCategory> adp = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, row.cachedSubCats);
            adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            row.spSubCategory.setAdapter(adp);

            if (row.pendingSubCategoryId != null) {
                for (int i = 0; i < row.cachedSubCats.size(); i++) {
                    if (row.cachedSubCats.get(i).getId() == row.pendingSubCategoryId) {
                        row.spSubCategory.setSelection(i);
                        break;
                    }
                }
                row.pendingSubCategoryId = null;
            }
        }
    }

    // ══════════════════════════════════════════════════════
    // Payment Type
    // ══════════════════════════════════════════════════════
    private void loadPaymentTypesForRow(BulkRow row) {
        List<PaymentType> types = payDao.findAll();
        ArrayAdapter<PaymentType> adp = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, types);
        adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        row.spPaymentType.setAdapter(adp);
        int pos = 0;
        for (int i = 0; i < types.size(); i++)
            if (types.get(i).isDefault()) {
                pos = i;
                break;
            }
        row.spPaymentType.setSelection(pos);
    }

    // ══════════════════════════════════════════════════════
    // Keyword auto-suggest (per row, same tap-to-apply flow as
    // TransactionEntryActivity's wireDescriptionAutoSuggest)
    // ══════════════════════════════════════════════════════
    private void wireDescriptionAutoSuggest(BulkRow row) {
        row.etNote.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                if (row.suggestRunnable != null) mainHandler.removeCallbacks(row.suggestRunnable);
                String text = e.toString();
                row.suggestRunnable = () -> showKeywordSuggestion(row, text);
                mainHandler.postDelayed(row.suggestRunnable, 350);
            }
        });
        row.tvKwSuggestion.setOnClickListener(v -> applyPendingSuggestion(row));
    }

    private void showKeywordSuggestion(BulkRow row, String note) {
        if (note == null || note.trim().length() < 3 || row.spCategory.getSelectedItemPosition() != 0) {
            row.pendingSuggestion = null;
            row.tvKwSuggestion.setVisibility(View.GONE);
            return;
        }
        String type = row.spType.getSelectedItemPosition() == 1 ? "INCOME" : "EXPENSE";
        KeywordMapping match = kwDao.suggest(note.trim(), type, bookId);
        if (match == null) {
            row.pendingSuggestion = null;
            row.tvKwSuggestion.setVisibility(View.GONE);
            return;
        }
        row.pendingSuggestion = match;
        row.tvKwSuggestion.setText("💡 " + match.getCategoryName() +
                (match.getSubCategoryName() != null ? " ▸ " + match.getSubCategoryName() : "") +
                " — tap to apply");
        row.tvKwSuggestion.setVisibility(View.VISIBLE);
    }

    private void applyPendingSuggestion(BulkRow row) {
        if (row.pendingSuggestion == null) return;
        row.pendingSubCategoryId = row.pendingSuggestion.getSubCategoryId();
        for (int i = 0; i < row.cachedCats.size(); i++) {
            if (row.cachedCats.get(i).getId() == row.pendingSuggestion.getCategoryId()) {
                row.spCategory.setSelection(i + 1);
                break;
            }
        }
        row.tvKwSuggestion.setVisibility(View.GONE);
        row.pendingSuggestion = null;
    }

    // ══════════════════════════════════════════════════════
    // Custom fields (per row, per type — mirrors loadCustomFieldsForType)
    // ══════════════════════════════════════════════════════
    private void loadCustomFieldsForRow(BulkRow row, String type) {
        for (ColumnDefinition cd : colDefDao.findByType(type)) {
            addCustomFieldRow(row, cd);
        }
    }

    private void addCustomFieldRow(BulkRow row, ColumnDefinition cd) {
        if (row.customFieldInputs.containsKey(cd.getColKey())) return;

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wrapLp.topMargin = dp(8);
        wrap.setLayoutParams(wrapLp);

        TextView label = new TextView(this);
        label.setText(cd.getColName());
        label.setTextColor(getColor(R.color.primary));
        label.setTextSize(11);
        wrap.addView(label);

        EditText input = new EditText(this);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        inputLp.topMargin = dp(2);
        input.setLayoutParams(inputLp);
        input.setBackgroundResource(R.drawable.bg_input_box);
        input.setPadding(dp(10), 0, dp(10), 0);
        input.setTextSize(12);
        wrap.addView(input);

        row.customFieldsContainer.addView(wrap);
        row.customFieldInputs.put(cd.getColKey(), input);
    }

    private void clearCustomFieldsForRow(BulkRow row) {
        row.customFieldsContainer.removeAllViews();
        row.customFieldInputs.clear();
    }

    // ══════════════════════════════════════════════════════
    // Voice input
    // ══════════════════════════════════════════════════════
    private void startVoiceInput(BulkRow row) {
        activeRow = row;
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak note…");
        try {
            startActivityForResult(intent, REQ_SPEECH);
        } catch (Exception e) {
            Toast.makeText(this, "Voice input not available on this device", Toast.LENGTH_SHORT).show();
        }
    }

    // ══════════════════════════════════════════════════════
    // Attach Image / PDF — same 3-option sheet as TransactionEntryActivity
    // ══════════════════════════════════════════════════════
    private void pickAttachment(BulkRow row) {
        activeRow = row;
        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
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
            pendingCameraUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photoFile);

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
        if (resultCode != RESULT_OK || data == null || activeRow == null) return;
        BulkRow row = activeRow;

        if (requestCode == REQ_ATTACH && data.getData() != null) {
            Uri uri = data.getData();
            try {
                String name = queryFileName(uri);
                String type = getContentResolver().getType(uri);
                byte[] bytes = readBytes(uri);
                PendingAttachment pa = new PendingAttachment(name, type, bytes);
                row.pendingAttachments.add(pa);
                addPendingAttachmentRow(row, pa);
            } catch (Exception e) {
                Toast.makeText(this, "Couldn't read file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQ_CAMERA && pendingCameraUri != null) {
            try {
                byte[] bytes = readBytes(pendingCameraUri);
                String name = "receipt_" + System.currentTimeMillis() + ".jpg";
                PendingAttachment pa = new PendingAttachment(name, "image/jpeg", bytes);
                row.pendingAttachments.add(pa);
                addPendingAttachmentRow(row, pa);
            } catch (Exception e) {
                Toast.makeText(this, "Couldn't read photo: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
            pendingCameraUri = null;
        } else if (requestCode == REQ_SPEECH) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                String current = row.etNote.getText().toString().trim();
                row.etNote.setText(current.isEmpty() ? results.get(0) : current + " " + results.get(0));
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

    private void addPendingAttachmentRow(BulkRow row, PendingAttachment pa) {
        LinearLayout rowView = buildAttachmentRow(row, "📎 " + pa.name, () -> row.pendingAttachments.remove(pa));
        row.attachmentList.addView(rowView);
    }

    private LinearLayout buildAttachmentRow(BulkRow row, String text, Runnable onRemove) {
        LinearLayout attRow = new LinearLayout(this);
        attRow.setOrientation(LinearLayout.HORIZONTAL);
        attRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32));
        lp.topMargin = dp(4);
        attRow.setLayoutParams(lp);

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(getColor(R.color.text_secondary));
        tv.setTextSize(11);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        attRow.addView(tv);

        TextView remove = new TextView(this);
        remove.setText("✕");
        remove.setTextColor(getColor(R.color.red));
        remove.setPadding(dp(8), dp(4), dp(8), dp(4));
        remove.setOnClickListener(v -> {
            onRemove.run();
            row.attachmentList.removeView(attRow);
        });
        attRow.addView(remove);
        return attRow;
    }

    // ══════════════════════════════════════════════════════
    // Summary / Clear / Save
    // ══════════════════════════════════════════════════════
    private void updateSummary() {
        double income = 0, expense = 0;
        for (BulkRow r : rows) {
            double amt = parseAmt(r.etAmount.getText().toString());
            String type = r.spType.getSelectedItemPosition() == 1 ? "INCOME" : "EXPENSE";
            if ("INCOME".equals(type)) income += amt;
            else expense += amt;
        }
        tvSummary.setText(rows.size() + " rows  |  ↑₹" + String.format("%.2f", income) +
                "  ↓₹" + String.format("%.2f", expense) +
                "  Net₹" + String.format("%.2f", income - expense));
    }

    private void clearAll() {
        rows.clear();
        rowContainer.removeAllViews();
        addRow();
        addRow();
        addRow();
        updateSummary();
    }

    private void saveAll() {
        for (BulkRow r : rows) {
            if (r.etAmount.getText().toString().trim().isEmpty()) {
                Toast.makeText(this, "Fill all Amount fields", Toast.LENGTH_SHORT).show();
                return;
            }
            if (r.spCategory.getSelectedItem() == null || ((Category) r.spCategory.getSelectedItem()).getId() == 0) {
                Toast.makeText(this, "Select category for each row", Toast.LENGTH_SHORT).show();
                return;
            }
            if (r.spSubCategory.getVisibility() == View.VISIBLE) {
                SubCategory sub = (SubCategory) r.spSubCategory.getSelectedItem();
                if (sub == null || sub.getId() == 0) {
                    Toast.makeText(this, "Select sub-category for each row that needs one", Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            if (r.spPaymentType.getSelectedItem() == null) {
                Toast.makeText(this, "Select payment type for each row", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        btnSaveAll.setEnabled(false);
        btnSaveAll.setText("Saving…");
        tvResult.setVisibility(View.GONE);

        List<Transaction> toSave = new ArrayList<>();
        List<BulkRow> rowOrder = new ArrayList<>(rows);
        for (BulkRow r : rowOrder) {
            String type = r.spType.getSelectedItemPosition() == 1 ? "INCOME" : "EXPENSE";
            Category cat = (Category) r.spCategory.getSelectedItem();
            SubCategory sub = (r.spSubCategory.getVisibility() == View.VISIBLE) ? (SubCategory) r.spSubCategory.getSelectedItem() : null;

            Transaction t = new Transaction();
            t.setType(Transaction.Type.valueOf(type));
            t.setDateTime(LocalDateTime.of(r.date, r.time));
            t.setAmount(new BigDecimal(r.etAmount.getText().toString().trim()));
            t.setCategoryId(cat.getId());
            t.setSubCategoryId(sub != null ? sub.getId() : 0);
            t.setNote(r.etNote.getText().toString().trim());
            t.setBookId(bookId);
            t.setPaymentType(((PaymentType) r.spPaymentType.getSelectedItem()).getName());

            Map<String, String> customValues = new LinkedHashMap<>();
            for (Map.Entry<String, EditText> e : r.customFieldInputs.entrySet())
                customValues.put(e.getKey(), e.getValue().getText().toString().trim());
            t.setCustomValues(customValues);

            toSave.add(t);
        }

        exec.execute(() -> {
            int saved = 0, failed = 0;
            for (int i = 0; i < toSave.size(); i++) {
                Transaction t = toSave.get(i);
                BulkRow r = rowOrder.get(i);
                try {
                    long newId = txnDao.insert(t);
                    if (newId == -1) throw new RuntimeException("insert failed");
                    txnDao.saveCustomValues((int) newId, t.getCustomValues());
                    for (PendingAttachment pa : r.pendingAttachments) {
                        Receipt rec = new Receipt();
                        rec.setTransactionId((int) newId);
                        rec.setFileName(pa.name);
                        rec.setFileType(pa.mimeType);
                        rec.setFileData(pa.bytes);
                        rec.setFileSize(pa.bytes != null ? pa.bytes.length : 0);
                        receiptDao.insert(rec);
                    }
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
                    clearAll();
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

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdown();
    }

    private record PendingAttachment(String name, String mimeType, byte[] bytes) {
    }

    static class BulkRow {
        View rootView;
        Spinner spType, spCategory, spSubCategory, spPaymentType;
        TextView tvDateTime, btnMic, tvKwSuggestion, btnExpandToggle;
        EditText etAmount, etNote;
        View btnCalc, btnAttach, btnDel;
        LinearLayout expandPanel, attachmentList, customFieldsContainer;

        LocalDate date = LocalDate.now();
        LocalTime time = LocalTime.now();
        List<Category> cachedCats = new ArrayList<>();
        List<SubCategory> cachedSubCats = new ArrayList<>();
        Map<String, EditText> customFieldInputs = new LinkedHashMap<>();
        List<PendingAttachment> pendingAttachments = new ArrayList<>();
        KeywordMapping pendingSuggestion;
        Integer pendingSubCategoryId;
        Runnable suggestRunnable;
        boolean expanded = true;
    }
}