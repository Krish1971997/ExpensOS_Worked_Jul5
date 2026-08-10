package com.expenseos.ui.home;

import android.app.DatePickerDialog;
import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import com.expenseos.R;
import com.expenseos.dao.CashBookDao;
import com.expenseos.dao.CategoryDao;
import com.expenseos.dao.PaymentTypeDao;
import com.expenseos.dao.SubCategoryDao;
import com.expenseos.model.CashBook;
import com.expenseos.model.Category;
import com.expenseos.model.PaymentType;
import com.expenseos.model.SubCategory;
import com.expenseos.model.TransactionFilter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TransactionFilterDialog extends Dialog {

    public interface OnApply {
        void onApply(TransactionFilter filter);
    }

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Integer bookId;
    private final TransactionFilter filter;
    private final OnApply onApply;
    private final int initialTab;
    private final boolean singleFieldMode;

    private TextView tabCashBook, tabDate, tabCategory, tabSubCategory, tabAmount, tabPaymentType;
    private View panelCashBook, panelDate, panelCategory, panelSubCategory, panelAmount, panelPaymentType;
    private LinearLayout cashBookContainer, categoryContainer, subCategoryContainer, paymentTypeContainer;

    private RadioGroup rgDatePreset;
    private LinearLayout layoutSingleDay, layoutDateRange;
    private EditText etSingleDay, etDateFrom, etDateTo;

    private final List<CheckBox> cashBookChecks = new ArrayList<>();
    private List<CashBook> allCashBooks = new ArrayList<>();

    private final List<CheckBox> categoryChecks = new ArrayList<>();
    private final List<CheckBox> subCategoryChecks = new ArrayList<>();
    private final List<CheckBox> paymentTypeChecks = new ArrayList<>();
    private List<Category> allCategories = new ArrayList<>();
    private List<SubCategory> allSubCategories = new ArrayList<>();
    private List<PaymentType> allPaymentTypes = new ArrayList<>();

    private Spinner spAmountOp1, spAmountOp2;
    private EditText etAmount1, etAmount2;

    public TransactionFilterDialog(Context ctx, Integer bookId, TransactionFilter currentFilter, OnApply onApply) {
        this(ctx, bookId, currentFilter, 0, false, onApply);
    }

    public TransactionFilterDialog(Context ctx, Integer bookId, TransactionFilter currentFilter, int tab, OnApply onApply) {
        this(ctx, bookId, currentFilter, tab, false, onApply);
    }

    public TransactionFilterDialog(Context ctx, Integer bookId, TransactionFilter currentFilter, int tab, boolean singleFieldMode, OnApply onApply) {
        super(ctx);
        this.bookId = bookId;
        this.filter = copyOf(currentFilter);
        this.onApply = onApply;
        this.initialTab = tab;
        this.singleFieldMode = singleFieldMode;
    }

    private static TransactionFilter copyOf(TransactionFilter f) {
        TransactionFilter c = new TransactionFilter();
        if (f == null) return c;
        c.setType(f.getType());
        c.setBookId(f.getBookId());
        c.setDateFrom(f.getDateFrom());
        c.setDateTo(f.getDateTo());
        c.setCategoryIds(f.getCategoryIds() != null ? new ArrayList<>(f.getCategoryIds()) : null);
        c.setSubCategoryIds(f.getSubCategoryIds() != null ? new ArrayList<>(f.getSubCategoryIds()) : null);
        c.setAmountOp1(f.getAmountOp1());
        c.setAmount1(f.getAmount1());
        c.setAmountOp2(f.getAmountOp2());
        c.setAmount2(f.getAmount2());
        c.setNoteSearch(f.getNoteSearch());
        c.setPaymentTypes(f.getPaymentTypes() != null ? new ArrayList<>(f.getPaymentTypes()) : null);
        c.setPage(f.getPage());
        c.setPageSize(f.getPageSize());
        c.setSortBy(f.getSortBy());
        c.setSortDir(f.getSortDir());
        return c;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_transaction_filter);
        if (getWindow() != null) {
            getWindow().setLayout(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (getContext().getResources().getDisplayMetrics().heightPixels * 0.85));
        }

        bindViews();
        loadCategoriesAndSubCategories();
        wireTabs();
        wireDatePanel();
        wireAmountPanel();
        prefillFromFilter();

        if (singleFieldMode) {
            findViewById(R.id.tabListContainer).setVisibility(View.GONE);
            findViewById(R.id.dividerTabs).setVisibility(View.GONE);
            String[] titles = {"Cash Book", "Date", "Category", "Sub Category", "Amount", "Payment Type"};
            if (initialTab >= 0 && initialTab < titles.length) {
                ((TextView) findViewById(R.id.dialogTitle)).setText(titles[initialTab]);
            }
        }

        findViewById(R.id.btnFilterClose).setOnClickListener(v -> dismiss());
        findViewById(R.id.btnClearAll).setOnClickListener(v -> {
            TransactionFilter cleared = new TransactionFilter();
            cleared.setBookId(bookId);
            if (onApply != null) onApply.onApply(cleared);
            dismiss();
        });
        findViewById(R.id.btnApplyFilter).setOnClickListener(v -> {
            applyCashBookSelection();
            applyDateSelection();
            applyCategorySelection();
            applyPaymentTypeSelection();
            applyAmountSelection();
            filter.setBookId(bookId);
            if (onApply != null) onApply.onApply(filter);
            dismiss();
        });
    }

    private void bindViews() {
        tabCashBook = findViewById(R.id.tabCashBook);
        tabDate = findViewById(R.id.tabDate);
        tabCategory = findViewById(R.id.tabCategory);
        tabSubCategory = findViewById(R.id.tabSubCategory);
        tabAmount = findViewById(R.id.tabAmount);
        tabPaymentType = findViewById(R.id.tabPaymentType);

        panelCashBook = findViewById(R.id.panelCashBook);
        panelDate = findViewById(R.id.panelDate);
        panelCategory = findViewById(R.id.panelCategory);
        panelSubCategory = findViewById(R.id.panelSubCategory);
        panelAmount = findViewById(R.id.panelAmount);
        panelPaymentType = findViewById(R.id.panelPaymentType);

        cashBookContainer = findViewById(R.id.cashBookContainer);
        categoryContainer = findViewById(R.id.categoryContainer);
        subCategoryContainer = findViewById(R.id.subCategoryContainer);
        paymentTypeContainer = findViewById(R.id.paymentTypeContainer);

        rgDatePreset = findViewById(R.id.rgDatePreset);
        layoutSingleDay = findViewById(R.id.layoutSingleDay);
        layoutDateRange = findViewById(R.id.layoutDateRange);
        etSingleDay = findViewById(R.id.etSingleDay);
        etDateFrom = findViewById(R.id.etDateFrom);
        etDateTo = findViewById(R.id.etDateTo);

        spAmountOp1 = findViewById(R.id.spAmountOp1);
        spAmountOp2 = findViewById(R.id.spAmountOp2);
        etAmount1 = findViewById(R.id.etAmount1);
        etAmount2 = findViewById(R.id.etAmount2);
    }

    // ── Tabs (Index Order Fixed) ─────────────────────────────
    private void wireTabs() {
        tabCashBook.setOnClickListener(v -> selectTab(0));
        tabDate.setOnClickListener(v -> selectTab(1));
        tabCategory.setOnClickListener(v -> selectTab(2));
        tabSubCategory.setOnClickListener(v -> selectTab(3));
        tabAmount.setOnClickListener(v -> selectTab(4));
        tabPaymentType.setOnClickListener(v -> selectTab(5));
        selectTab(initialTab);
    }

    private void selectTab(int index) {
        TextView[] tabs = {tabCashBook, tabDate, tabCategory, tabSubCategory, tabAmount, tabPaymentType};
        View[] panels = {panelCashBook, panelDate, panelCategory, panelSubCategory, panelAmount, panelPaymentType};

        int selColor = getContext().getResources().getColor(R.color.primary);
        int normColor = getContext().getResources().getColor(R.color.text);

        for (int i = 0; i < tabs.length; i++) {
            boolean sel = (i == index);
            if (tabs[i] != null && panels[i] != null) {
                tabs[i].setTextColor(sel ? selColor : normColor);
                tabs[i].setBackgroundColor(sel ? 0xFFE8F0FE : 0x00000000);
                tabs[i].setTypeface(null, sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                panels[i].setVisibility(sel ? View.VISIBLE : View.GONE);
            }
        }
    }

    // ── Date panel ──────────────────────────────────────────
    private void wireDatePanel() {
        rgDatePreset.setOnCheckedChangeListener((group, checkedId) -> {
            layoutSingleDay.setVisibility(checkedId == R.id.rbSingleDay ? View.VISIBLE : View.GONE);
            layoutDateRange.setVisibility(checkedId == R.id.rbDateRange ? View.VISIBLE : View.GONE);
        });

        etSingleDay.setOnClickListener(v -> pickDate(etSingleDay));
        etDateFrom.setOnClickListener(v -> pickDate(etDateFrom));
        etDateTo.setOnClickListener(v -> pickDate(etDateTo));
    }

    private void pickDate(EditText target) {
        LocalDate now = LocalDate.now();
        new DatePickerDialog(getContext(), (view, year, month, day) -> {
            LocalDate picked = LocalDate.of(year, month + 1, day);
            target.setText(picked.format(DATE_FMT));
        }, now.getYear(), now.getMonthValue() - 1, now.getDayOfMonth()).show();
    }

    private void applyDateSelection() {
        int checkedId = rgDatePreset.getCheckedRadioButtonId();
        LocalDate today = LocalDate.now();

        if (checkedId == R.id.rbToday) {
            filter.setDateFrom(today);
            filter.setDateTo(today);
        } else if (checkedId == R.id.rbYesterday) {
            LocalDate y = today.minusDays(1);
            filter.setDateFrom(y);
            filter.setDateTo(y);
        } else if (checkedId == R.id.rbThisMonth) {
            YearMonth ym = YearMonth.from(today);
            filter.setDateFrom(ym.atDay(1));
            filter.setDateTo(ym.atEndOfMonth());
        } else if (checkedId == R.id.rbLastMonth) {
            YearMonth ym = YearMonth.from(today).minusMonths(1);
            filter.setDateFrom(ym.atDay(1));
            filter.setDateTo(ym.atEndOfMonth());
        } else if (checkedId == R.id.rbSingleDay) {
            LocalDate d = parseOrNull(etSingleDay.getText().toString().trim());
            filter.setDateFrom(d);
            filter.setDateTo(d);
        } else if (checkedId == R.id.rbDateRange) {
            filter.setDateFrom(parseOrNull(etDateFrom.getText().toString().trim()));
            filter.setDateTo(parseOrNull(etDateTo.getText().toString().trim()));
        } else {
            filter.setDateFrom(null);
            filter.setDateTo(null);
        }
    }

    private LocalDate parseOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return LocalDate.parse(s, DATE_FMT);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Load Data ───────────────────────────────────────────
    private void loadCategoriesAndSubCategories() {
        CategoryDao categoryDao = new CategoryDao(getContext());
        Map<Integer, Category> merged = new LinkedHashMap<>();

        if (bookId != null && bookId > 0) {
            for (Category c : categoryDao.findByType("INCOME", bookId)) merged.put(c.getId(), c);
            for (Category c : categoryDao.findByType("EXPENSE", bookId)) merged.put(c.getId(), c);
        } else {
            for (Category c : categoryDao.findAllByType("INCOME")) merged.put(c.getId(), c);
            for (Category c : categoryDao.findAllByType("EXPENSE")) merged.put(c.getId(), c);
        }
        allCategories = new ArrayList<>(merged.values());

        SubCategoryDao subCategoryDao = new SubCategoryDao(getContext());
        allSubCategories = subCategoryDao.findAll();

        buildCategoryCheckboxes();
        buildSubCategoryCheckboxes();

        allPaymentTypes = new PaymentTypeDao(getContext()).findAll();
        buildPaymentTypeCheckboxes();

        CashBookDao cashBookDao = new CashBookDao(getContext());
        allCashBooks = cashBookDao.findAll();
        buildCashBookCheckboxes();
    }

    private void buildCashBookCheckboxes() {
        cashBookContainer.removeAllViews();
        cashBookChecks.clear();
        List<Integer> selected = filter.getBookIds();

        for (CashBook cbBook : allCashBooks) {
            CheckBox cb = new CheckBox(getContext());
            cb.setText(cbBook.getName());
            cb.setTag(cbBook.getId());
            cb.setPadding(0, 12, 0, 12);
            if (selected != null && selected.contains(cbBook.getId())) {
                cb.setChecked(true);
            }
            cashBookContainer.addView(cb);
            cashBookChecks.add(cb);
        }
    }

    private void buildCategoryCheckboxes() {
        categoryContainer.removeAllViews();
        categoryChecks.clear();
        List<Integer> selected = filter.getCategoryIds();
        for (Category cat : allCategories) {
            CheckBox cb = new CheckBox(getContext());
            cb.setText(cat.getName());
            cb.setTag(cat.getId());
            cb.setPadding(0, 12, 0, 12);
            if (selected != null && selected.contains(cat.getId())) cb.setChecked(true);
            categoryContainer.addView(cb);
            categoryChecks.add(cb);
        }
        if (allCategories.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText("No categories found for this book.");
            empty.setTextColor(getContext().getResources().getColor(R.color.text_muted));
            categoryContainer.addView(empty);
        }
    }

    private void buildSubCategoryCheckboxes() {
        subCategoryContainer.removeAllViews();
        subCategoryChecks.clear();
        List<Integer> selected = filter.getSubCategoryIds();
        for (SubCategory sub : allSubCategories) {
            CheckBox cb = new CheckBox(getContext());
            cb.setText(sub.getName());
            cb.setTag(sub.getId());
            cb.setPadding(0, 12, 0, 12);
            if (selected != null && selected.contains(sub.getId())) cb.setChecked(true);
            subCategoryContainer.addView(cb);
            subCategoryChecks.add(cb);
        }
        if (allSubCategories.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText("No sub categories found.");
            empty.setTextColor(getContext().getResources().getColor(R.color.text_muted));
            subCategoryContainer.addView(empty);
        }
    }

    private void buildPaymentTypeCheckboxes() {
        paymentTypeContainer.removeAllViews();
        paymentTypeChecks.clear();
        List<String> selected = filter.getPaymentTypes();
        for (PaymentType pt : allPaymentTypes) {
            CheckBox cb = new CheckBox(getContext());
            cb.setText(pt.getName());
            cb.setTag(pt.getName());
            cb.setPadding(0, 12, 0, 12);
            if (selected != null && selected.contains(pt.getName())) cb.setChecked(true);
            paymentTypeContainer.addView(cb);
            paymentTypeChecks.add(cb);
        }
        if (allPaymentTypes.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText("No payment types found.");
            empty.setTextColor(getContext().getResources().getColor(R.color.text_muted));
            paymentTypeContainer.addView(empty);
        }
    }

    private void applyCashBookSelection() {
        List<Integer> selectedBookIds = new ArrayList<>();
        for (CheckBox cb : cashBookChecks) {
            if (cb.isChecked()) {
                selectedBookIds.add((Integer) cb.getTag());
            }
        }
        filter.setBookIds(selectedBookIds.isEmpty() ? null : selectedBookIds);
    }

    private void applyPaymentTypeSelection() {
        List<String> selected = new ArrayList<>();
        for (CheckBox cb : paymentTypeChecks)
            if (cb.isChecked()) selected.add((String) cb.getTag());
        filter.setPaymentTypes(selected.isEmpty() ? null : selected);
    }

    private void applyCategorySelection() {
        List<Integer> catIds = new ArrayList<>();
        for (CheckBox cb : categoryChecks)
            if (cb.isChecked()) catIds.add((Integer) cb.getTag());
        filter.setCategoryIds(catIds.isEmpty() ? null : catIds);

        List<Integer> subIds = new ArrayList<>();
        for (CheckBox cb : subCategoryChecks)
            if (cb.isChecked()) subIds.add((Integer) cb.getTag());
        filter.setSubCategoryIds(subIds.isEmpty() ? null : subIds);
    }

    // ── Amount panel ────────────────────────────────────────
    private void wireAmountPanel() {
        String[] ops1 = {"=", ">", ">=", "<", "<="};
        ArrayAdapter<String> op1Adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, ops1);
        op1Adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spAmountOp1.setAdapter(op1Adapter);

        String[] ops2 = {"(none)", ">=", "<=", ">", "<", "="};
        ArrayAdapter<String> op2Adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, ops2);
        op2Adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spAmountOp2.setAdapter(op2Adapter);

        spAmountOp2.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                etAmount2.setEnabled(position != 0);
                if (position == 0) etAmount2.setText("");
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        if (filter.getAmountOp1() != null) {
            int idx = java.util.Arrays.asList(ops1).indexOf(filter.getAmountOp1());
            if (idx >= 0) spAmountOp1.setSelection(idx);
        }
        if (filter.getAmount1() != null) etAmount1.setText(filter.getAmount1().toPlainString());
        if (filter.getAmountOp2() != null) {
            int idx = java.util.Arrays.asList(ops2).indexOf(filter.getAmountOp2());
            if (idx >= 0) spAmountOp2.setSelection(idx);
        }
        if (filter.getAmount2() != null) etAmount2.setText(filter.getAmount2().toPlainString());
    }

    private void applyAmountSelection() {
        String amt1Str = etAmount1.getText().toString().trim();
        if (amt1Str.isEmpty()) {
            filter.setAmountOp1(null);
            filter.setAmount1(null);
        } else {
            filter.setAmountOp1((String) spAmountOp1.getSelectedItem());
            filter.setAmount1(new BigDecimal(amt1Str));
        }

        String op2 = (String) spAmountOp2.getSelectedItem();
        String amt2Str = etAmount2.getText().toString().trim();
        if ("(none)".equals(op2) || amt2Str.isEmpty()) {
            filter.setAmountOp2(null);
            filter.setAmount2(null);
        } else {
            filter.setAmountOp2(op2);
            filter.setAmount2(new BigDecimal(amt2Str));
        }
    }

    private void prefillFromFilter() {
        if (filter.getDateFrom() != null && filter.getDateFrom().equals(filter.getDateTo())) {
            if (filter.getDateFrom().equals(LocalDate.now())) {
                rgDatePreset.check(R.id.rbToday);
            } else if (filter.getDateFrom().equals(LocalDate.now().minusDays(1))) {
                rgDatePreset.check(R.id.rbYesterday);
            } else {
                rgDatePreset.check(R.id.rbSingleDay);
                etSingleDay.setText(filter.getDateFrom().format(DATE_FMT));
            }
        } else if (filter.getDateFrom() != null || filter.getDateTo() != null) {
            rgDatePreset.check(R.id.rbDateRange);
            if (filter.getDateFrom() != null)
                etDateFrom.setText(filter.getDateFrom().format(DATE_FMT));
            if (filter.getDateTo() != null) etDateTo.setText(filter.getDateTo().format(DATE_FMT));
        } else {
            rgDatePreset.check(R.id.rbAllTime);
        }
    }
}