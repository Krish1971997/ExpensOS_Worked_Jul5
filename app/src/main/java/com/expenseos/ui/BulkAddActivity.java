package com.expenseos.ui;

import android.app.AlertDialog;
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
import android.widget.ListPopupWindow;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.expenseos.R;
import com.expenseos.adapter.NoteSuggestionAdapter;
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
import com.google.android.material.button.MaterialButtonToggleGroup;

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

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
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

    private LinearLayout previewContainer;
    private TextView tvIncome, tvExpense, tvNet, tvCount, tvPreviewCount, tvResult, tvCancelEdit, btnAddMore;
    private Button btnSaveAll;
    private ScrollView svBulk;
    private MaterialButtonToggleGroup typeToggle;
    private boolean typeSwitching = false;

    // One entry form (a BulkRow, so every existing helper keeps working) + the
    // transactions already added to the preview. editingIndex >= 0 = edit mode.
    private BulkRow form;
    private final List<BulkItem> items = new ArrayList<>();
    private int editingIndex = -1;
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

        svBulk = findViewById(R.id.svBulk);
        previewContainer = findViewById(R.id.llBulkPreview);
        tvIncome = findViewById(R.id.tvBulkIncome);
        tvExpense = findViewById(R.id.tvBulkExpense);
        tvNet = findViewById(R.id.tvBulkNet);
        tvCount = findViewById(R.id.tvBulkCount);
        tvPreviewCount = findViewById(R.id.tvBulkPreviewCount);
        tvResult = findViewById(R.id.tvBulkResult);
        tvCancelEdit = findViewById(R.id.tvBulkCancelEdit);
        btnAddMore = findViewById(R.id.btnBulkAddMore);
        btnSaveAll = findViewById(R.id.btnBulkSaveAllBottom);
        typeToggle = findViewById(R.id.toggleBulkType);

        findViewById(R.id.btnBulkBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnBulkCancel).setOnClickListener(v -> finish());
        findViewById(R.id.btnBulkClear).setOnClickListener(v -> confirmClearAll());
        btnAddMore.setOnClickListener(v -> onAddMoreClicked());
        tvCancelEdit.setOnClickListener(v -> {
            editingIndex = -1;
            resetForm();
            refreshPreview();
        });
        btnSaveAll.setOnClickListener(v -> saveAll());

        bindForm();
        refreshPreview();
    }

    // ══════════════════════════════════════════════════════
    // Single entry form — every helper below runs against `form`
    // ══════════════════════════════════════════════════════
    private void bindForm() {
        form = new BulkRow();
        form.tvDateTime = findViewById(R.id.tvBulkDateTime);
        form.etAmount = findViewById(R.id.etBulkAmount);
        form.spCategory = findViewById(R.id.spBulkCategory);
        form.subWrap = findViewById(R.id.llBulkSubWrap);
        form.spSubCategory = findViewById(R.id.spBulkSubCategory);
        form.spPaymentType = findViewById(R.id.spBulkPaymentType);
        form.etNote = findViewById(R.id.etBulkNote);
        form.btnMic = findViewById(R.id.btnBulkMic);
        form.tvKwSuggestion = findViewById(R.id.tvBulkKwSuggestion);
        form.btnAttach = findViewById(R.id.btnBulkAttach);
        form.btnCalc = findViewById(R.id.btnBulkCalc);
        form.attachmentList = findViewById(R.id.llBulkAttachList);
        form.customFieldsContainer = findViewById(R.id.llBulkCustomFields);

        form.date = LocalDate.now();
        form.time = LocalTime.now();
        updateRowDateTimeText(form);
        form.tvDateTime.setOnClickListener(x -> showDatePicker(form));

        typeToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (typeSwitching || !isChecked) return;
            applyType(checkedId == R.id.btnBulkTypeIncome ? "INCOME" : "EXPENSE");
        });
        applyType("EXPENSE");
        loadPaymentTypesForRow(form);
        wireDescriptionAutoSuggest(form);

        form.btnMic.setOnClickListener(x -> startVoiceInput(form));
        form.btnAttach.setOnClickListener(x -> pickAttachment(form));
        form.btnCalc.setOnClickListener(x ->
                CalculatorDialog.show(this, form.etAmount.getText().toString(), resultText ->
                        form.etAmount.setText(resultText)));
    }

    /**
     * Type changed: reload categories + custom fields. Payment type is left alone (carry-over).
     */
    private void applyType(String type) {
        form.type = type;
        loadCategoriesForRow(form, type);
        setSubVisible(form, false);
        clearCustomFieldsForRow(form);
        loadCustomFieldsForRow(form, type);
    }

    private void setTypeProgrammatic(String type) {
        typeSwitching = true;
        typeToggle.check("INCOME".equals(type) ? R.id.btnBulkTypeIncome : R.id.btnBulkTypeExpense);
        typeSwitching = false;
        applyType(type);
    }

    private void setSubVisible(BulkRow row, boolean visible) {
        int v = visible ? View.VISIBLE : View.GONE;
        row.spSubCategory.setVisibility(v);
        if (row.subWrap != null) row.subWrap.setVisibility(v);
    }

    private void selectPaymentByName(BulkRow row, String name) {
        if (name == null) return;
        for (int i = 0; i < row.spPaymentType.getCount(); i++) {
            Object o = row.spPaymentType.getItemAtPosition(i);
            if (o instanceof PaymentType && name.equals(((PaymentType) o).getName())) {
                row.spPaymentType.setSelection(i);
                return;
            }
        }
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    // ══════════════════════════════════════════════════════
    // Add / edit / delete / preview
    // ══════════════════════════════════════════════════════
    private void onAddMoreClicked() {
        BulkItem item = buildItemFromForm();
        if (item == null) return; // validation toast already shown
        if (editingIndex >= 0) items.set(editingIndex, item);
        else items.add(item);
        editingIndex = -1;
        resetForm(); // carries date/time + payment type of the LAST item into the next entry
        refreshPreview();
    }

    private BulkItem buildItemFromForm() {
        String amtText = form.etAmount.getText().toString().trim();
        if (amtText.isEmpty()) {
            toast("Enter amount");
            return null;
        }
        try {
            new BigDecimal(amtText);
        } catch (Exception e) {
            toast("Enter a valid amount");
            return null;
        }
        Category cat = (Category) form.spCategory.getSelectedItem();
        if (cat == null || cat.getId() == 0) {
            toast("Select category");
            return null;
        }
        SubCategory sub = null;
        if (form.spSubCategory.getVisibility() == View.VISIBLE) {
            sub = (SubCategory) form.spSubCategory.getSelectedItem();
            if (sub == null || sub.getId() == 0) {
                toast("Select sub-category");
                return null;
            }
        }
        PaymentType pt = (PaymentType) form.spPaymentType.getSelectedItem();
        if (pt == null) {
            toast("Select payment type");
            return null;
        }
        String bookValidationError = AppConfig.validatePaymentTypeForBook(
                AppConfig.get(this).getActiveBookName(), pt.getName());
        if (bookValidationError != null) {
            toast(bookValidationError);
            return null;
        }

        BulkItem it = new BulkItem();
        it.type = form.type;
        it.date = form.date;
        it.time = form.time;
        it.amount = amtText;
        it.categoryId = cat.getId();
        it.categoryName = String.valueOf(cat);
        it.subCategoryId = sub != null ? sub.getId() : 0;
        it.subCategoryName = sub != null ? String.valueOf(sub) : null;
        it.paymentType = pt.getName();
        it.note = form.etNote.getText().toString().trim();
        for (Map.Entry<String, EditText> e : form.customFieldInputs.entrySet())
            it.customValues.put(e.getKey(), e.getValue().getText().toString().trim());
        it.attachments = new ArrayList<>(form.pendingAttachments);
        return it;
    }

    /**
     * Blank form; type + date/time + payment type come from the last added item.
     */
    private void resetForm() {
        BulkItem base = items.isEmpty() ? null : items.get(items.size() - 1);
        setTypeProgrammatic(base != null ? base.type : form.type); // also clears category/sub/custom fields
        if (base != null) {
            form.date = base.date;
            form.time = base.time;
            selectPaymentByName(form, base.paymentType);
        } else {
            form.date = LocalDate.now();
            form.time = LocalTime.now();
            loadPaymentTypesForRow(form);
        }
        updateRowDateTimeText(form);

        form.etAmount.setText("");
        form.etNote.setText("");
        form.tvKwSuggestion.setVisibility(View.GONE);
        form.pendingSuggestion = null;
        form.pendingSubCategoryId = null;
        form.pendingAttachments.clear();
        form.attachmentList.removeAllViews();

        btnAddMore.setText("+  Add More Transaction");
        tvCancelEdit.setVisibility(View.GONE);
        form.etAmount.requestFocus();
    }

    /**
     * Preview card tapped → load that transaction back into the form.
     */
    private void startEdit(int index) {
        BulkItem it = items.get(index);
        editingIndex = index;

        setTypeProgrammatic(it.type);
        form.date = it.date;
        form.time = it.time;
        updateRowDateTimeText(form);
        selectPaymentByName(form, it.paymentType);

        form.etAmount.setText(it.amount);
        form.suppressNoteSuggestion = true;
        form.etNote.setText(it.note);
        form.tvKwSuggestion.setVisibility(View.GONE);
        form.pendingSuggestion = null;

        // category → sub-category cascade (same mechanism keyword-suggest uses)
        form.pendingSubCategoryId = it.subCategoryId > 0 ? it.subCategoryId : null;
        for (int i = 0; i < form.cachedCats.size(); i++) {
            if (form.cachedCats.get(i).getId() == it.categoryId) {
                form.spCategory.setSelection(i + 1);
                break;
            }
        }

        for (Map.Entry<String, String> e : it.customValues.entrySet()) {
            EditText et = form.customFieldInputs.get(e.getKey());
            if (et != null) et.setText(e.getValue());
        }

        form.pendingAttachments.clear();
        form.attachmentList.removeAllViews();
        for (PendingAttachment pa : it.attachments) {
            form.pendingAttachments.add(pa);
            addPendingAttachmentRow(form, pa);
        }

        btnAddMore.setText("✓  Update Transaction");
        tvCancelEdit.setVisibility(View.VISIBLE);
        refreshPreview();
        svBulk.post(() -> svBulk.smoothScrollTo(0, 0));
    }

    private void confirmDelete(int idx) {
        if (idx < 0 || idx >= items.size()) return;
        BulkItem it = items.get(idx);
        String summary = ("INCOME".equals(it.type) ? "+₹" : "−₹") + it.amount + "  •  " + it.categoryName
                + (it.subCategoryName != null ? " ▸ " + it.subCategoryName : "");
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Delete transaction?")
                .setMessage("#" + (idx + 1) + "  " + summary + "\n\nThis will be removed from the list.")
                .setPositiveButton("Delete", (d, w) -> deleteItem(idx))
                .setNegativeButton("Cancel", null)
                .create();
        dlg.setOnShowListener(d ->
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.red)));
        dlg.show();
    }

    private void deleteItem(int idx) {
        items.remove(idx);
        if (editingIndex == idx) {
            editingIndex = -1;
            resetForm();
        } else if (editingIndex > idx) {
            editingIndex--;
        }
        refreshPreview();
    }

    private void refreshPreview() {
        previewContainer.removeAllViews();
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            BulkItem it = items.get(i);
            View card = LayoutInflater.from(this).inflate(R.layout.item_bulk_preview, previewContainer, false);

            ((TextView) card.findViewById(R.id.tvPrevIndex)).setText(String.valueOf(i + 1));

            boolean income = "INCOME".equals(it.type);

            // Left accent capsule — same drawables TransactionAdapter uses
            card.findViewById(R.id.viewPrevTypeBadge).setBackgroundResource(
                    income ? R.drawable.bg_badge_income : R.drawable.bg_badge_expense);

            TextView tvAmt = card.findViewById(R.id.tvPrevAmount);
            tvAmt.setText((income ? "+₹" : "−₹") + it.amount);
            tvAmt.setTextColor(getColor(income ? R.color.green : R.color.red));

            // Chips: Category / SubCategory / Payment type
            ((TextView) card.findViewById(R.id.tvPrevCategory)).setText(it.categoryName);

            TextView tvSub = card.findViewById(R.id.tvPrevSubCategory);
            boolean hasSub = it.subCategoryName != null && !it.subCategoryName.isEmpty();
            tvSub.setText(hasSub ? it.subCategoryName : "");
            tvSub.setVisibility(hasSub ? View.VISIBLE : View.GONE);

            TextView tvPay = card.findViewById(R.id.tvPrevPayment);
            boolean hasPay = it.paymentType != null && !it.paymentType.isEmpty();
            tvPay.setText(hasPay ? it.paymentType : "");
            tvPay.setVisibility(hasPay ? View.VISIBLE : View.GONE);

            ((TextView) card.findViewById(R.id.tvPrevNote)).setText(it.note.isEmpty() ? "—" : it.note);

            // Payment type chip-ku poyiduchu, so meta-la date/time + attachments mattum
            ((TextView) card.findViewById(R.id.tvPrevMeta)).setText(
                    it.date.format(DATE_FMT) + " " + it.time.format(TIME_FMT)
                            + (it.attachments.isEmpty() ? "" : "  •  📎 " + it.attachments.size()));

            card.setAlpha(idx == editingIndex ? 0.5f : 1f);
            card.setOnClickListener(v -> startEdit(idx)); // tap anywhere on the card = edit mode
            card.findViewById(R.id.btnPrevDelete).setOnClickListener(v -> confirmDelete(idx));
            previewContainer.addView(card);
        }
        tvPreviewCount.setText(items.size() + " items");
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
                    setSubVisible(row, false);
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
            setSubVisible(row, false);
        } else {
            setSubVisible(row, true);
            List<SubCategory> spinnerItems = new ArrayList<>(row.cachedSubCats);
            if (spinnerItems.size() > 1) {
                spinnerItems.add(0, new SubCategory(0, "Select Sub Category", catId));
            }
            ArrayAdapter<SubCategory> adp = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, spinnerItems);
            adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            row.spSubCategory.setAdapter(adp);

            if (row.pendingSubCategoryId != null) {
                for (int i = 0; i < spinnerItems.size(); i++) {
                    if (spinnerItems.get(i).getId() == row.pendingSubCategoryId) {
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
    // Keyword auto-suggest & Past Note autocomplete (per row)
    // ══════════════════════════════════════════════════════
    private void wireDescriptionAutoSuggest(BulkRow row) {
        row.noteSuggestAdapter = new NoteSuggestionAdapter(this, new ArrayList<>());
        row.noteSuggestPopup = new ListPopupWindow(this);
        row.noteSuggestPopup.setAnchorView(row.etNote);
        row.noteSuggestPopup.setAdapter(row.noteSuggestAdapter);
        row.noteSuggestPopup.setModal(false);
        row.noteSuggestPopup.setInputMethodMode(ListPopupWindow.INPUT_METHOD_NEEDED);

        row.etNote.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                if (row.suppressNoteSuggestion) {
                    row.suppressNoteSuggestion = false;
                    return;
                }
                if (row.suggestRunnable != null) mainHandler.removeCallbacks(row.suggestRunnable);
                String text = e.toString();
                row.suggestRunnable = () -> {
                    showKeywordSuggestion(row, text);
                    showNoteSuggestions(row, text);
                };
                mainHandler.postDelayed(row.suggestRunnable, 250);
            }
        });

        row.noteSuggestPopup.setOnItemClickListener((parent, view, position, id) -> {
            String picked = row.noteSuggestAdapter.getItem(position);
            if (picked != null) {
                row.suppressNoteSuggestion = true;
                row.etNote.setText(picked);
                row.etNote.setSelection(picked.length());
            }
            row.noteSuggestPopup.dismiss();
        });

        row.etNote.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus && row.noteSuggestPopup != null) {
                row.noteSuggestPopup.dismiss();
            }
        });

        row.tvKwSuggestion.setOnClickListener(v -> applyPendingSuggestion(row));
    }

    private boolean suggestionMatchesCurrentSelection(BulkRow row, KeywordMapping match) {
        if (row.spCategory.getSelectedItem() == null || row.spCategory.getSelectedItemPosition() == 0) {
            return false;
        }
        Category selCat = (Category) row.spCategory.getSelectedItem();
        if (selCat.getId() != match.getCategoryId()) return false;

        if (match.getSubCategoryId() == null) {
            if (row.spSubCategory.getVisibility() != View.VISIBLE) return true;
            SubCategory selSub = (SubCategory) row.spSubCategory.getSelectedItem();
            return selSub == null || selSub.getId() == 0;
        } else {
            if (row.spSubCategory.getVisibility() != View.VISIBLE) return false;
            SubCategory selSub = (SubCategory) row.spSubCategory.getSelectedItem();
            return selSub != null && selSub.getId() == match.getSubCategoryId();
        }
    }

    private void showKeywordSuggestion(BulkRow row, String note) {
        if (note == null || note.trim().length() < 3) {
            row.pendingSuggestion = null;
            row.tvKwSuggestion.setVisibility(View.GONE);
            return;
        }
        String type = row.type;
        KeywordMapping match = kwDao.suggest(note.trim(), type, bookId);
        if (match == null || suggestionMatchesCurrentSelection(row, match)) {
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

    private void showNoteSuggestions(BulkRow row, String text) {
        String trimmed = text.trim();
        if (trimmed.length() < 2 || !row.etNote.hasFocus()) {
            if (row.noteSuggestPopup != null) row.noteSuggestPopup.dismiss();
            return;
        }
        List<String> matches = txnDao.findDistinctNotesContaining(trimmed, bookId, 8);
        if (matches.isEmpty() || (matches.size() == 1 && matches.get(0).equalsIgnoreCase(trimmed))) {
            if (row.noteSuggestPopup != null) row.noteSuggestPopup.dismiss();
            return;
        }
        row.noteSuggestAdapter.clear();
        row.noteSuggestAdapter.addAll(matches);
        row.noteSuggestAdapter.setQuery(trimmed);
        row.noteSuggestAdapter.notifyDataSetChanged();
        row.noteSuggestPopup.show();
    }

    private void applyPendingSuggestion(BulkRow row) {
        if (row.pendingSuggestion == null) return;
        row.pendingSubCategoryId = row.pendingSuggestion.getSubCategoryId();
        int targetCatId = row.pendingSuggestion.getCategoryId();
        int targetPos = 0;
        for (int i = 0; i < row.cachedCats.size(); i++) {
            if (row.cachedCats.get(i).getId() == targetCatId) {
                targetPos = i + 1;
                break;
            }
        }
        if (targetPos > 0) {
            if (row.spCategory.getSelectedItemPosition() == targetPos) {
                loadSubCategoriesForRow(row, targetCatId);
            } else {
                row.spCategory.setSelection(targetPos);
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

    private void updateSummary() {
        double income = 0, expense = 0;
        for (BulkItem it : items) {
            double amt = parseAmt(it.amount);
            if ("INCOME".equals(it.type)) income += amt;
            else expense += amt;
        }
        double net = income - expense;
        tvIncome.setText("↑ ₹" + String.format("%.2f", income));
        tvExpense.setText("↓ ₹" + String.format("%.2f", expense));
        tvNet.setText("₹" + String.format("%.2f", net));
        tvNet.setTextColor(getColor(net >= 0 ? R.color.green : R.color.red));
        tvCount.setText("Total Items : " + items.size());
    }

    private void clearAll() {
        if (form.noteSuggestPopup != null && form.noteSuggestPopup.isShowing()) {
            form.noteSuggestPopup.dismiss();
        }
        items.clear();
        editingIndex = -1;
        resetForm();
        refreshPreview();
    }

    private void saveAll() {
        // A filled-in form that was never "added" still counts — add it first.
        if (!form.etAmount.getText().toString().trim().isEmpty()) {
            BulkItem pending = buildItemFromForm();
            if (pending == null) return;
            if (editingIndex >= 0) items.set(editingIndex, pending);
            else items.add(pending);
            editingIndex = -1;
            resetForm();
            refreshPreview();
        }
        if (items.isEmpty()) {
            toast("Add at least one transaction");
            return;
        }

        btnSaveAll.setEnabled(false);
        btnSaveAll.setText("Saving…");
        tvResult.setVisibility(View.GONE);

        final List<BulkItem> order = new ArrayList<>(items);
        List<Transaction> toSave = new ArrayList<>();
        for (BulkItem it : order) {
            Transaction t = new Transaction();
            t.setType(Transaction.Type.valueOf(it.type));
            t.setDateTime(LocalDateTime.of(it.date, it.time));
            t.setAmount(new BigDecimal(it.amount));
            t.setCategoryId(it.categoryId);
            t.setSubCategoryId(it.subCategoryId);
            t.setNote(it.note);
            t.setBookId(bookId);
            t.setPaymentType(it.paymentType);
            t.setCustomValues(new LinkedHashMap<>(it.customValues));
            toSave.add(t);
        }

        exec.execute(() -> {
            List<BulkItem> failedItems = new ArrayList<>();
            int saved = 0;
            for (int i = 0; i < toSave.size(); i++) {
                Transaction t = toSave.get(i);
                BulkItem it = order.get(i);
                try {
                    long newId = txnDao.insert(t);
                    if (newId == -1) throw new RuntimeException("insert failed");
                    txnDao.saveCustomValues((int) newId, t.getCustomValues());
                    for (PendingAttachment pa : it.attachments) {
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
                    failedItems.add(it);
                }
            }
            final int s = saved, f = failedItems.size();
            mainHandler.post(() -> {
                btnSaveAll.setEnabled(true);
                btnSaveAll.setText("✓ Save All");
                tvResult.setVisibility(View.VISIBLE);
                tvResult.setText(s + " saved" + (f > 0 ? ", " + f + " failed" : ""));
                tvResult.setTextColor(getColor(f == 0 ? R.color.green : R.color.red));
                // Only failed ones stay in the preview, so a retry never duplicates saved rows.
                items.clear();
                items.addAll(failedItems);
                editingIndex = -1;
                resetForm();
                refreshPreview();
                if (f == 0) Toast.makeText(this, "All saved!", Toast.LENGTH_SHORT).show();
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
        if (form != null && form.noteSuggestPopup != null && form.noteSuggestPopup.isShowing()) {
            form.noteSuggestPopup.dismiss();
        }
        exec.shutdown();
    }

    private record PendingAttachment(String name, String mimeType, byte[] bytes) {
    }

    /**
     * One transaction already added to the preview list.
     */
    static class BulkItem {
        String type = "EXPENSE";
        LocalDate date;
        LocalTime time;
        String amount = "";
        int categoryId;
        String categoryName = "";
        int subCategoryId;          // 0 = none
        String subCategoryName;     // null = none
        String paymentType = "";
        String note = "";
        Map<String, String> customValues = new LinkedHashMap<>();
        List<PendingAttachment> attachments = new ArrayList<>();
    }

    static class BulkRow {
        View rootView, subWrap;
        String type = "EXPENSE";
        Spinner spCategory, spSubCategory, spPaymentType;
        TextView tvDateTime, btnMic, tvKwSuggestion, btnExpandToggle;
        EditText etAmount, etNote;
        View btnCalc, btnAttach, btnDel;
        LinearLayout expandPanel, attachmentList, customFieldsContainer;

        ListPopupWindow noteSuggestPopup;
        NoteSuggestionAdapter noteSuggestAdapter;
        boolean suppressNoteSuggestion = false;

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

    private void confirmClearAll() {
        // Nothing to clear — no need to bother the user with a popup
        if (items.isEmpty() && form.etAmount.getText().toString().trim().isEmpty()) return;

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Clear all transactions?")
                .setMessage("All " + items.size() + " item(s) in the preview will be removed.\n\nThis can't be undone.")
                .setPositiveButton("Clear all", (d, w) -> clearAll())
                .setNegativeButton("Cancel", null)
                .create();
        dlg.setOnShowListener(d ->
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.red)));
        dlg.show();
    }
}