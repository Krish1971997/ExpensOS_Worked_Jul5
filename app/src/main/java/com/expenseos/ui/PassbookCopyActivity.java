package com.expenseos.ui;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
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
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.expenseos.R;
import com.expenseos.dao.CashBookDao;
import com.expenseos.dao.CategoryDao;
import com.expenseos.dao.KeywordMappingDao;
import com.expenseos.dao.PaymentTypeDao;
import com.expenseos.dao.ReceiptDao;
import com.expenseos.dao.SubCategoryDao;
import com.expenseos.dao.TransactionDao;
import com.expenseos.db.LocalDB;
import com.expenseos.model.CashBook;
import com.expenseos.model.Category;
import com.expenseos.model.KeywordMapping;
import com.expenseos.model.PassbookEntry;
import com.expenseos.model.PaymentType;
import com.expenseos.model.Receipt;
import com.expenseos.model.SubCategory;
import com.expenseos.model.Transaction;
import com.expenseos.util.AppConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Passbook → Copy to Cash Book (review screen).
 * <p>
 * PassbookActivity sends the selected sms_ids + the book picked in the dialog.
 * Every entry becomes one card: Date/Time, Payment Type, Amount are pre-filled
 * from the SMS; the user fills Category / Sub-Category / Description (mic + attach).
 * The Cash Book is auto-filled but editable. Works for 1 entry (card opens
 * expanded) and for many (only the first card is expanded, rest collapsed).
 */
public class PassbookCopyActivity extends AppCompatActivity {

    public static final String EXTRA_SMS_IDS = "sms_ids";
    public static final String EXTRA_BOOK_ID = "book_id";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("hh:mm a");

    private static final int REQ_ATTACH = 3001;
    private static final int REQ_SPEECH = 3002;
    private static final int REQ_CAMERA = 3003;

    private int bookId;
    private List<CashBook> books = new ArrayList<>();

    private TransactionDao txnDao;
    private CategoryDao catDao;
    private SubCategoryDao subCatDao;
    private PaymentTypeDao payDao;
    private KeywordMappingDao kwDao;
    private ReceiptDao receiptDao;

    private ScrollView svCopy;
    private LinearLayout llCards;
    private Spinner spBook;
    private TextView tvIncome, tvExpense, tvNet, tvCount, tvReady, tvResult;
    private Button btnSaveAll;

    private final List<CopyRow> rows = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    // Row that launched the in-flight attach / camera / speech intent.
    private CopyRow activeRow;
    private Uri pendingCameraUri;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_passbook_copy);

        txnDao = new TransactionDao(this);
        catDao = new CategoryDao(this);
        subCatDao = new SubCategoryDao(this);
        payDao = new PaymentTypeDao(this);
        kwDao = new KeywordMappingDao(this);
        receiptDao = new ReceiptDao(this);

        svCopy = findViewById(R.id.svCopy);
        llCards = findViewById(R.id.llCopyCards);
        spBook = findViewById(R.id.spCopyBook);
        tvIncome = findViewById(R.id.tvCopyIncome);
        tvExpense = findViewById(R.id.tvCopyExpense);
        tvNet = findViewById(R.id.tvCopyNet);
        tvCount = findViewById(R.id.tvCopyCount);
        tvReady = findViewById(R.id.tvCopyReady);
        tvResult = findViewById(R.id.tvCopyResult);
        btnSaveAll = findViewById(R.id.btnCopySaveAll);

        findViewById(R.id.btnCopyBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnCopyCancel).setOnClickListener(v -> finish());
        btnSaveAll.setOnClickListener(v -> saveAll());

        bookId = getIntent().getIntExtra(EXTRA_BOOK_ID, 0);
        long[] ids = getIntent().getLongArrayExtra(EXTRA_SMS_IDS);
        if (ids == null || ids.length == 0) {
            finish();
            return;
        }

        setupBookSpinner();
        List<PassbookEntry> entries = loadEntries(ids);
        for (int i = 0; i < entries.size(); i++) {
            CopyRow r = buildRow(entries.get(i), i);
            rows.add(r);
            llCards.addView(r.root);
        }
        // 1 entry → open it; many entries → open only the first one
        if (!rows.isEmpty()) setExpanded(rows.get(0), true);
        updateSummary();
    }

    // ══════════════════════════════════════════════════════
    // Data load
    // ══════════════════════════════════════════════════════
    private List<PassbookEntry> loadEntries(long[] ids) {
        List<PassbookEntry> out = new ArrayList<>();
        StringBuilder ph = new StringBuilder();
        String[] args = new String[ids.length];
        for (int i = 0; i < ids.length; i++) {
            ph.append(i == 0 ? "?" : ",?");
            args[i] = String.valueOf(ids[i]);
        }
        SQLiteDatabase db = LocalDB.getInstance(this).getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT sms_id, type, amount, sender, raw_body, remark, timestamp_millis, copied, payment_type " +
                        "FROM passbook_entries WHERE copied=0 AND sms_id IN (" + ph + ") " +
                        "ORDER BY timestamp_millis DESC", args)) {
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
                out.add(e);
            }
        }
        return out;
    }

    // ══════════════════════════════════════════════════════
    // Cash Book (auto-filled, editable)
    // ══════════════════════════════════════════════════════
    private void setupBookSpinner() {
        books = new CashBookDao(this).findAll();
        List<String> names = new ArrayList<>();
        int sel = 0;
        for (int i = 0; i < books.size(); i++) {
            names.add(books.get(i).getName());
            if (books.get(i).getId() == bookId) sel = i;
        }
        ArrayAdapter<String> adp = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spBook.setAdapter(adp);
        spBook.setSelection(sel);
        if (!books.isEmpty()) bookId = books.get(sel).getId();

        spBook.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= books.size()) return;
                int newId = books.get(pos).getId();
                if (newId == bookId) return; // initial callback / no change
                bookId = newId;
                // Categories belong to a book → reload for every card
                for (CopyRow r : rows) loadCategories(r);
                toast("Categories reloaded for \"" + books.get(pos).getName() + "\"");
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
    }

    private CashBook selectedBook() {
        int pos = spBook.getSelectedItemPosition();
        return (pos >= 0 && pos < books.size()) ? books.get(pos) : null;
    }

    // ══════════════════════════════════════════════════════
    // One card per entry
    // ══════════════════════════════════════════════════════
    private CopyRow buildRow(PassbookEntry e, int index) {
        CopyRow r = new CopyRow();
        r.entry = e;
        r.type = "CREDIT".equals(e.getType()) ? "INCOME" : "EXPENSE";

        LocalDateTime ldt = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(e.getTimestampMillis()), ZoneId.systemDefault());
        r.date = ldt.toLocalDate();
        r.time = ldt.toLocalTime().withSecond(0).withNano(0);

        r.root = getLayoutInflater().inflate(R.layout.item_passbook_copy, llCards, false);
        r.header = r.root.findViewById(R.id.llCardHeader);
        r.body = r.root.findViewById(R.id.llCardBody);
        r.badge = r.root.findViewById(R.id.viewCardBadge);
        r.tvIndex = r.root.findViewById(R.id.tvCardIndex);
        r.tvAmountHead = r.root.findViewById(R.id.tvCardAmount);
        r.tvSummary = r.root.findViewById(R.id.tvCardSummary);
        r.tvStatus = r.root.findViewById(R.id.tvCardStatus);
        r.tvChevron = r.root.findViewById(R.id.tvCardChevron);
        r.tvSms = r.root.findViewById(R.id.tvCardSms);
        r.tvDateTime = r.root.findViewById(R.id.tvCardDateTime);
        r.spPayment = r.root.findViewById(R.id.spCardPayment);
        r.etAmount = r.root.findViewById(R.id.etCardAmount);
        r.spCategory = r.root.findViewById(R.id.spCardCategory);
        r.subWrap = r.root.findViewById(R.id.llCardSubWrap);
        r.spSub = r.root.findViewById(R.id.spCardSub);
        r.etNote = r.root.findViewById(R.id.etCardNote);
        r.btnMic = r.root.findViewById(R.id.btnCardMic);
        r.btnAttach = r.root.findViewById(R.id.btnCardAttach);
        r.tvKw = r.root.findViewById(R.id.tvCardKw);
        r.attachList = r.root.findViewById(R.id.llCardAttachList);

        boolean income = "INCOME".equals(r.type);
        r.tvIndex.setText(String.valueOf(index + 1));
        r.badge.setBackgroundResource(income ? R.drawable.bg_badge_income : R.drawable.bg_badge_expense);
        r.tvAmountHead.setTextColor(getColor(income ? R.color.green : R.color.red));

        // ── Auto-captured fields ──
        r.etAmount.setText(e.getAmount().toPlainString());
        updateDateTimeText(r);
        r.tvDateTime.setOnClickListener(v -> showDatePicker(r));
        loadPaymentTypes(r, e.getPaymentType());

        // SMS remark shown as a hint; tap = use it as description
        String remark = e.getRemark() != null ? e.getRemark() : e.getRawBody();
        r.tvSms.setText("SMS: " + remark + "  •  tap to use as description");
        r.tvSms.setOnClickListener(v -> {
            r.suppressNote = true;
            r.etNote.setText(remark);
            r.etNote.setSelection(r.etNote.getText().length());
            refreshHeader(r);
        });

        // ── User-filled fields ──
        loadCategories(r);
        r.spSub.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                refreshHeader(r);
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        wireNote(r);
        r.etAmount.addTextChangedListener(new SimpleWatcher(() -> {
            refreshHeader(r);
            updateSummary();
        }));

        r.btnMic.setOnClickListener(v -> startVoiceInput(r));
        r.btnAttach.setOnClickListener(v -> pickAttachment(r));
        r.header.setOnClickListener(v -> setExpanded(r, !r.expanded));

        refreshHeader(r);
        return r;
    }

    private void setExpanded(CopyRow r, boolean expanded) {
        r.expanded = expanded;
        r.body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        r.tvChevron.setText(expanded ? "▴" : "▾");
    }

    private void refreshHeader(CopyRow r) {
        boolean income = "INCOME".equals(r.type);
        String amt = r.etAmount.getText().toString().trim();
        r.tvAmountHead.setText((income ? "+₹" : "−₹") + (amt.isEmpty() ? "0" : amt));

        Category cat = selectedCategory(r);
        SubCategory sub = selectedSub(r);
        boolean complete = isComplete(r);
        String note = r.etNote.getText().toString().trim();

        if (cat == null) {
            r.tvSummary.setText("Category needed");
            r.tvSummary.setTextColor(getColor(R.color.red));
        } else {
            StringBuilder sb = new StringBuilder(String.valueOf(cat));
            if (sub != null) sb.append(" ▸ ").append(sub);
            else if (r.spSub.getVisibility() == View.VISIBLE) sb.append(" ▸ sub-category needed");
            if (!note.isEmpty()) sb.append(" • ").append(note);
            r.tvSummary.setText(sb.toString());
            r.tvSummary.setTextColor(getColor(complete ? R.color.text_secondary : R.color.red));
        }
        r.tvStatus.setText(complete ? "✓" : "⚠");
        r.tvStatus.setTextColor(getColor(complete ? R.color.green : R.color.red));
        updateSummary();
    }

    private Category selectedCategory(CopyRow r) {
        Object o = r.spCategory.getSelectedItem();
        if (!(o instanceof Category c)) return null;
        return c.getId() == 0 ? null : c;
    }

    private SubCategory selectedSub(CopyRow r) {
        if (r.spSub.getVisibility() != View.VISIBLE) return null;
        Object o = r.spSub.getSelectedItem();
        if (!(o instanceof SubCategory sc)) return null;
        return sc.getId() == 0 ? null : sc;
    }

    private boolean isComplete(CopyRow r) {
        if (parseAmt(r.etAmount.getText().toString()) <= 0) return false;
        if (selectedCategory(r) == null) return false;
        return r.spSub.getVisibility() != View.VISIBLE || selectedSub(r) != null;
    }

    // ══════════════════════════════════════════════════════
    // Summary (Income / Expense / Net / Total / Ready)
    // ══════════════════════════════════════════════════════
    private void updateSummary() {
        if (tvIncome == null) return;
        double income = 0, expense = 0;
        int ready = 0;
        for (CopyRow r : rows) {
            double a = parseAmt(r.etAmount.getText().toString());
            if ("INCOME".equals(r.type)) income += a;
            else expense += a;
            if (isComplete(r)) ready++;
        }
        double net = income - expense;
        tvIncome.setText("↑ ₹" + String.format(Locale.US, "%.2f", income));
        tvExpense.setText("↓ ₹" + String.format(Locale.US, "%.2f", expense));
        tvNet.setText("₹" + String.format(Locale.US, "%.2f", net));
        tvNet.setTextColor(getColor(net >= 0 ? R.color.green : R.color.red));
        tvCount.setText("Total Items : " + rows.size());
        tvReady.setText(ready + "/" + rows.size() + " ready");
        btnSaveAll.setText("✓ Save All (" + rows.size() + ")");
    }

    // ══════════════════════════════════════════════════════
    // Date / Time
    // ══════════════════════════════════════════════════════
    private void showDatePicker(CopyRow r) {
        new DatePickerDialog(this, (view, y, m, d) -> {
            r.date = LocalDate.of(y, m + 1, d);
            mainHandler.post(() -> showTimePicker(r)); // wait a frame, same as BulkAdd
        }, r.date.getYear(), r.date.getMonthValue() - 1, r.date.getDayOfMonth()).show();
    }

    private void showTimePicker(CopyRow r) {
        new TimePickerDialog(this, (view, h, min) -> {
            r.time = LocalTime.of(h, min);
            updateDateTimeText(r);
        }, r.time.getHour(), r.time.getMinute(), false).show();
    }

    private void updateDateTimeText(CopyRow r) {
        r.tvDateTime.setText(r.date.format(DATE_FMT) + " " + r.time.format(TIME_FMT));
    }

    // ══════════════════════════════════════════════════════
    // Payment type (auto-selected from SMS)
    // ══════════════════════════════════════════════════════
    private void loadPaymentTypes(CopyRow r, String wanted) {
        List<PaymentType> types = payDao.findAll();
        ArrayAdapter<PaymentType> adp = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, types);
        adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        r.spPayment.setAdapter(adp);

        if ("Cash".equalsIgnoreCase(wanted))
            wanted = null; // SMS la irunthu Cash eppavum select aagakoodathu
        int pos = -1, upi = -1, def = 0;
        for (int i = 0; i < types.size(); i++) {
            String n = types.get(i).getName();
            if (types.get(i).isDefault()) def = i;
            if ("UPI".equalsIgnoreCase(n)) upi = i;
            if (wanted != null && wanted.equalsIgnoreCase(n)) pos = i;
        }
        r.spPayment.setSelection(pos >= 0 ? pos : (upi >= 0 ? upi : def));
    }

    // ══════════════════════════════════════════════════════
    // Category → Sub-Category cascade
    // ══════════════════════════════════════════════════════
    private void loadCategories(CopyRow r) {
        r.cats = catDao.findByType(r.type, bookId);
        List<Category> list = new ArrayList<>();
        list.add(new Category(0, "Select Category", r.type, null));
        list.addAll(r.cats);
        ArrayAdapter<Category> adp = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, list) {
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
        r.spCategory.setAdapter(adp);
        r.spCategory.setSelection(0);
        setSubVisible(r, false);

        r.spCategory.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos == 0) {
                    setSubVisible(r, false);
                } else if (pos - 1 < r.cats.size()) {
                    loadSubCategories(r, r.cats.get(pos - 1).getId());
                }
                refreshHeader(r);
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        refreshHeader(r);
    }

    private void loadSubCategories(CopyRow r, int catId) {
        List<SubCategory> subs = subCatDao.findByCategoryId(catId);
        if (subs.isEmpty()) {
            setSubVisible(r, false);
            return;
        }
        setSubVisible(r, true);
        List<SubCategory> items = new ArrayList<>(subs);
        if (items.size() > 1) items.add(0, new SubCategory(0, "Select Sub Category", catId));
        ArrayAdapter<SubCategory> adp = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        r.spSub.setAdapter(adp);

        if (r.pendingSubCategoryId != null) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).getId() == r.pendingSubCategoryId) {
                    r.spSub.setSelection(i);
                    break;
                }
            }
            r.pendingSubCategoryId = null;
        }
        refreshHeader(r);
    }

    private void setSubVisible(CopyRow r, boolean visible) {
        int v = visible ? View.VISIBLE : View.GONE;
        r.spSub.setVisibility(v);
        r.subWrap.setVisibility(v);
    }

    // ══════════════════════════════════════════════════════
    // Description + keyword suggestion
    // ══════════════════════════════════════════════════════
    private void wireNote(CopyRow r) {
        r.etNote.addTextChangedListener(new SimpleWatcher(() -> {
            refreshHeader(r);
            if (r.suppressNote) {
                r.suppressNote = false;
                return;
            }
            if (r.suggestRunnable != null) mainHandler.removeCallbacks(r.suggestRunnable);
            String text = r.etNote.getText().toString();
            r.suggestRunnable = () -> showKeywordSuggestion(r, text);
            mainHandler.postDelayed(r.suggestRunnable, 250);
        }));
        r.tvKw.setOnClickListener(v -> applyPendingSuggestion(r));
    }

    private boolean suggestionMatchesCurrent(CopyRow r, KeywordMapping m) {
        Category sel = selectedCategory(r);
        if (sel == null || sel.getId() != m.getCategoryId()) return false;
        SubCategory sub = selectedSub(r);
        if (m.getSubCategoryId() == null) return sub == null;
        return sub != null && sub.getId() == m.getSubCategoryId();
    }

    private void showKeywordSuggestion(CopyRow r, String note) {
        if (note == null || note.trim().length() < 3) {
            r.pendingSuggestion = null;
            r.tvKw.setVisibility(View.GONE);
            return;
        }
        KeywordMapping m = kwDao.suggest(note.trim(), r.type, bookId);
        if (m == null || suggestionMatchesCurrent(r, m)) {
            r.pendingSuggestion = null;
            r.tvKw.setVisibility(View.GONE);
            return;
        }
        r.pendingSuggestion = m;
        r.tvKw.setText("💡 " + m.getCategoryName()
                + (m.getSubCategoryName() != null ? " ▸ " + m.getSubCategoryName() : "")
                + " — tap to apply");
        r.tvKw.setVisibility(View.VISIBLE);
    }

    private void applyPendingSuggestion(CopyRow r) {
        if (r.pendingSuggestion == null) return;
        r.pendingSubCategoryId = r.pendingSuggestion.getSubCategoryId();
        int targetCatId = r.pendingSuggestion.getCategoryId();
        int targetPos = 0;
        for (int i = 0; i < r.cats.size(); i++) {
            if (r.cats.get(i).getId() == targetCatId) {
                targetPos = i + 1;
                break;
            }
        }
        if (targetPos > 0) {
            if (r.spCategory.getSelectedItemPosition() == targetPos)
                loadSubCategories(r, targetCatId);
            else r.spCategory.setSelection(targetPos);
        }
        r.tvKw.setVisibility(View.GONE);
        r.pendingSuggestion = null;
    }

    // ══════════════════════════════════════════════════════
    // Voice input
    // ══════════════════════════════════════════════════════
    private void startVoiceInput(CopyRow r) {
        activeRow = r;
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak note…");
        try {
            startActivityForResult(intent, REQ_SPEECH);
        } catch (Exception e) {
            toast("Voice input not available on this device");
        }
    }

    // ══════════════════════════════════════════════════════
    // Attach Image / PDF
    // ══════════════════════════════════════════════════════
    private void pickAttachment(CopyRow r) {
        activeRow = r;
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

        container.addView(sheetOption("📷", "Take photo using camera", () -> {
            sheet.dismiss();
            launchCamera();
        }));
        container.addView(sheetOption("🖼", "Choose from gallery", () -> {
            sheet.dismiss();
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            startActivityForResult(i, REQ_ATTACH);
        }));
        container.addView(sheetOption("📄", "Choose PDF", () -> {
            sheet.dismiss();
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("application/pdf");
            startActivityForResult(i, REQ_ATTACH);
        }));
        sheet.setContentView(container);
        sheet.show();
    }

    private View sheetOption(String emoji, String label, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(14), dp(20), dp(14));
        row.setClickable(true);
        row.setFocusable(true);
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);

        TextView e = new TextView(this);
        e.setText(emoji);
        e.setTextSize(18);
        e.setLayoutParams(new LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(e);

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(16);
        l.setTextColor(getColor(R.color.text_primary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(16);
        l.setLayoutParams(lp);
        row.addView(l);

        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private void launchCamera() {
        try {
            File dir = new File(getCacheDir(), "receipts");
            if (!dir.exists()) dir.mkdirs();
            File photo = File.createTempFile("receipt_", ".jpg", dir);
            pendingCameraUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photo);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(intent, REQ_CAMERA);
        } catch (Exception e) {
            toast("Camera not available: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || activeRow == null) return;
        CopyRow r = activeRow;

        // Camera writes to EXTRA_OUTPUT, so `data` is usually null → handle before the null check
        if (requestCode == REQ_CAMERA) {
            if (pendingCameraUri == null) return;
            try {
                byte[] bytes = readBytes(pendingCameraUri);
                addAttachment(r, new PendingAttachment(
                        "receipt_" + System.currentTimeMillis() + ".jpg", "image/jpeg", bytes));
            } catch (Exception e) {
                toast("Couldn't read photo: " + e.getMessage());
            }
            pendingCameraUri = null;
            return;
        }
        if (data == null) return;

        if (requestCode == REQ_ATTACH && data.getData() != null) {
            Uri uri = data.getData();
            try {
                addAttachment(r, new PendingAttachment(
                        queryFileName(uri), getContentResolver().getType(uri), readBytes(uri)));
            } catch (Exception e) {
                toast("Couldn't read file: " + e.getMessage());
            }
        } else if (requestCode == REQ_SPEECH) {
            ArrayList<String> res = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (res != null && !res.isEmpty()) {
                String cur = r.etNote.getText().toString().trim();
                r.etNote.setText(cur.isEmpty() ? res.get(0) : cur + " " + res.get(0));
            }
        }
    }

    private void addAttachment(CopyRow r, PendingAttachment pa) {
        r.attachments.add(pa);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32));
        lp.topMargin = dp(4);
        row.setLayoutParams(lp);

        TextView tv = new TextView(this);
        tv.setText("📎 " + pa.name());
        tv.setTextColor(getColor(R.color.text_secondary));
        tv.setTextSize(11);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);

        TextView remove = new TextView(this);
        remove.setText("✕");
        remove.setTextColor(getColor(R.color.red));
        remove.setPadding(dp(8), dp(4), dp(8), dp(4));
        remove.setOnClickListener(v -> {
            r.attachments.remove(pa);
            r.attachList.removeView(row);
        });
        row.addView(remove);
        r.attachList.addView(row);
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

    // ══════════════════════════════════════════════════════
    // Validate + Save All
    // ══════════════════════════════════════════════════════

    /**
     * @return error text, or null when the card is good to save
     */
    private String validate(CopyRow r, String bookName) {
        double amt = parseAmt(r.etAmount.getText().toString());
        if (amt <= 0) return "Enter a valid amount";
        if (selectedCategory(r) == null) return "Select category";
        if (r.spSub.getVisibility() == View.VISIBLE && selectedSub(r) == null)
            return "Select sub-category";
        PaymentType pt = (PaymentType) r.spPayment.getSelectedItem();
        if (pt == null) return "Select payment type";
        return AppConfig.validatePaymentTypeForBook(bookName, pt.getName()); // null = OK
    }

    private Transaction buildTransaction(CopyRow r) {
        Category cat = selectedCategory(r);
        SubCategory sub = selectedSub(r);
        PaymentType pt = (PaymentType) r.spPayment.getSelectedItem();

        Transaction t = new Transaction();
        t.setType(Transaction.Type.valueOf(r.type));
        t.setDateTime(LocalDateTime.of(r.date, r.time));
        t.setAmount(new BigDecimal(r.etAmount.getText().toString().trim()));
        t.setCategoryId(cat.getId());
        t.setSubCategoryId(sub != null ? sub.getId() : 0);
        t.setNote(r.etNote.getText().toString().trim());
        t.setBookId(bookId);
        t.setPaymentType(pt.getName());
        return t;
    }

    private void saveAll() {
        if (rows.isEmpty()) {
            toast("Nothing to save");
            return;
        }
        CashBook book = selectedBook();
        if (book == null) {
            toast("Select a cash book");
            return;
        }

        // 1) validate every card — jump to the first bad one
        for (int i = 0; i < rows.size(); i++) {
            CopyRow r = rows.get(i);
            String err = validate(r, book.getName());
            if (err != null) {
                setExpanded(r, true);
                svCopy.post(() -> svCopy.smoothScrollTo(0, llCards.getTop() + r.root.getTop()));
                toast("#" + (i + 1) + ": " + err);
                return;
            }
        }

        // 2) snapshot on the UI thread
        final List<CopyRow> order = new ArrayList<>(rows);
        final List<Transaction> toSave = new ArrayList<>();
        final List<List<PendingAttachment>> attach = new ArrayList<>();
        for (CopyRow r : order) {
            toSave.add(buildTransaction(r));
            attach.add(new ArrayList<>(r.attachments));
        }

        btnSaveAll.setEnabled(false);
        btnSaveAll.setText("Saving…");
        tvResult.setVisibility(View.GONE);

        // 3) insert in background; only failed cards stay on screen
        exec.execute(() -> {
            SQLiteDatabase local = LocalDB.getInstance(this).getWritableDatabase();
            List<CopyRow> saved = new ArrayList<>();
            int failed = 0;
            for (int i = 0; i < toSave.size(); i++) {
                try {
                    long newId = txnDao.insert(toSave.get(i));
                    if (newId == -1) throw new RuntimeException("insert failed");
                    for (PendingAttachment pa : attach.get(i)) {
                        Receipt rec = new Receipt();
                        rec.setTransactionId((int) newId);
                        rec.setFileName(pa.name());
                        rec.setFileType(pa.mimeType());
                        rec.setFileData(pa.bytes());
                        rec.setFileSize(pa.bytes() != null ? pa.bytes().length : 0);
                        receiptDao.insert(rec);
                    }
                    ContentValues cv = new ContentValues();
                    cv.put("copied", 1);
                    local.update("passbook_entries", cv, "sms_id=?",
                            new String[]{String.valueOf(order.get(i).entry.getSmsId())});
                    saved.add(order.get(i));

                    try {
                        for (PendingAttachment pa : attach.get(i)) { /* receipt build + receiptDao.insert(rec) */ }
                    } catch (Exception ignored) { /* txn save aayiduchu; attachment mattum fail */ }
                } catch (Exception e) {
                    failed++;
                }
            }
            final int f = failed;
            mainHandler.post(() -> onSaveFinished(saved, f, book.getName()));
        });
    }

    private void onSaveFinished(List<CopyRow> saved, int failed, String bookName) {
        for (CopyRow r : saved) {
            llCards.removeView(r.root);
            rows.remove(r);
        }
        for (int i = 0; i < rows.size(); i++) rows.get(i).tvIndex.setText(String.valueOf(i + 1));
        if (!saved.isEmpty()) setResult(RESULT_OK); // Passbook reloads its list

        btnSaveAll.setEnabled(true);
        updateSummary();

        if (failed == 0) {
            toast("✔ Copied " + saved.size() + " entr" + (saved.size() == 1 ? "y" : "ies") + " to " + bookName);
            finish();
        } else {
            tvResult.setVisibility(View.VISIBLE);
            tvResult.setText(saved.size() + " saved, " + failed + " failed — fix and retry");
            tvResult.setTextColor(getColor(R.color.red));
        }
    }

    // ══════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════
    private double parseAmt(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdown();
    }

    private record SimpleWatcher(Runnable action) implements TextWatcher {

        @Override
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {
        }

        @Override
        public void onTextChanged(CharSequence s, int a, int b, int c) {
        }

        @Override
        public void afterTextChanged(Editable e) {
            action.run();
        }
    }

    private record PendingAttachment(String name, String mimeType, byte[] bytes) {
    }

    private static class CopyRow {
        PassbookEntry entry;
        String type;
        LocalDate date;
        LocalTime time;
        boolean expanded;

        View root, header, body, badge, subWrap;
        TextView tvIndex, tvAmountHead, tvSummary, tvStatus, tvChevron, tvSms, tvDateTime, btnMic, btnAttach, tvKw;
        EditText etAmount, etNote;
        Spinner spPayment, spCategory, spSub;
        LinearLayout attachList;

        List<Category> cats = new ArrayList<>();
        List<PendingAttachment> attachments = new ArrayList<>();
        KeywordMapping pendingSuggestion;
        Integer pendingSubCategoryId;
        Runnable suggestRunnable;
        boolean suppressNote = false;
    }
}
