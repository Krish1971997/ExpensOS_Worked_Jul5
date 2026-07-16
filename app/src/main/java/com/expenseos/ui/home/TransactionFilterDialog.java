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
import com.expenseos.dao.CategoryDao;
import com.expenseos.dao.SubCategoryDao;
import com.expenseos.model.Category;
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

/**
 * Tabbed filter dialog (Date / Category / Sub Category / Amount) matching
 * the web app's filter panel. Works on a defensive copy of the filter
 * passed in, so Cancel/back-press doesn't mutate the caller's live filter.
 *
 * ASSUMPTION: Category.getName() / SubCategory.getName() exist (these
 * model classes weren't shared with me — if the getter is named
 * differently, e.g. getCategoryName(), rename the calls below).
 */
public class TransactionFilterDialog extends Dialog {

    public interface OnApply {
        void onApply(TransactionFilter filter);
    }

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final int bookId;
    private final TransactionFilter filter;
    private final OnApply onApply;
    private final int initialTab;
    private final boolean singleFieldMode;

    private TextView tabDate, tabCategory, tabSubCategory, tabAmount;
    private View panelDate, panelCategory, panelSubCategory, panelAmount;

    private RadioGroup rgDatePreset;
    private LinearLayout layoutSingleDay, layoutDateRange;
    private EditText etSingleDay, etDateFrom, etDateTo;

    private LinearLayout categoryContainer, subCategoryContainer;
    private final List<CheckBox> categoryChecks = new ArrayList<>();
    private final List<CheckBox> subCategoryChecks = new ArrayList<>();
    private List<Category> allCategories = new ArrayList<>();
    private List<SubCategory> allSubCategories = new ArrayList<>();

    private Spinner spAmountOp1, spAmountOp2;
    private EditText etAmount1, etAmount2;

    // In-dialog "which date preset is active" — not part of TransactionFilter
    // itself (that only stores the resolved dateFrom/dateTo), but we need it
    // to know which radio button to preselect when reopening the dialog.
    private String activeDatePreset = "all";

    public TransactionFilterDialog(Context ctx, int bookId, TransactionFilter currentFilter, OnApply onApply) {
        this(ctx, bookId, currentFilter, 0, false, onApply);
    }

    // tab: 0=Date, 1=Category, 2=Sub Category, 3=Amount — used by the "Filter"
    // icon button, opens the full dialog with all tabs visible.
    public TransactionFilterDialog(Context ctx, int bookId, TransactionFilter currentFilter, int tab, OnApply onApply) {
        this(ctx, bookId, currentFilter, tab, false, onApply);
    }

    // singleFieldMode=true hides the left tab list entirely, locking the
    // dialog to just `tab`'s panel — used by the individual filter chips
    // (e.g. tapping the "Date" chip only lets you pick a date, nothing else).
    public TransactionFilterDialog(Context ctx, int bookId, TransactionFilter currentFilter, int tab, boolean singleFieldMode, OnApply onApply) {
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
            String[] titles = {"Date", "Category", "Sub Category", "Amount"};
            ((TextView) findViewById(R.id.dialogTitle)).setText(titles[initialTab]);
        }

        findViewById(R.id.btnFilterClose).setOnClickListener(v -> dismiss());
        findViewById(R.id.btnClearAll).setOnClickListener(v -> {
            TransactionFilter cleared = new TransactionFilter();
            cleared.setBookId(bookId);
            if (onApply != null) onApply.onApply(cleared);
            dismiss();
        });
        findViewById(R.id.btnApplyFilter).setOnClickListener(v -> {
            applyDateSelection();
            applyCategorySelection();
            applyAmountSelection();
            filter.setBookId(bookId);
            if (onApply != null) onApply.onApply(filter);
            dismiss();
        });
    }

    private void bindViews() {
        tabDate = findViewById(R.id.tabDate);
        tabCategory = findViewById(R.id.tabCategory);
        tabSubCategory = findViewById(R.id.tabSubCategory);
        tabAmount = findViewById(R.id.tabAmount);

        panelDate = findViewById(R.id.panelDate);
        panelCategory = findViewById(R.id.panelCategory);
        panelSubCategory = findViewById(R.id.panelSubCategory);
        panelAmount = findViewById(R.id.panelAmount);

        rgDatePreset = findViewById(R.id.rgDatePreset);
        layoutSingleDay = findViewById(R.id.layoutSingleDay);
        layoutDateRange = findViewById(R.id.layoutDateRange);
        etSingleDay = findViewById(R.id.etSingleDay);
        etDateFrom = findViewById(R.id.etDateFrom);
        etDateTo = findViewById(R.id.etDateTo);

        categoryContainer = findViewById(R.id.categoryContainer);
        subCategoryContainer = findViewById(R.id.subCategoryContainer);

        spAmountOp1 = findViewById(R.id.spAmountOp1);
        spAmountOp2 = findViewById(R.id.spAmountOp2);
        etAmount1 = findViewById(R.id.etAmount1);
        etAmount2 = findViewById(R.id.etAmount2);
    }

    // ── Tabs ────────────────────────────────────────────────
    private void wireTabs() {
        tabDate.setOnClickListener(v -> selectTab(0));
        tabCategory.setOnClickListener(v -> selectTab(1));
        tabSubCategory.setOnClickListener(v -> selectTab(2));
        tabAmount.setOnClickListener(v -> selectTab(3));
        selectTab(initialTab);
    }

    private void selectTab(int index) {
        TextView[] tabs = {tabDate, tabCategory, tabSubCategory, tabAmount};
        View[] panels = {panelDate, panelCategory, panelSubCategory, panelAmount};
        int selColor = getContext().getResources().getColor(R.color.primary);
        int normColor = getContext().getResources().getColor(R.color.text);
        for (int i = 0; i < tabs.length; i++) {
            boolean sel = i == index;
            tabs[i].setTextColor(sel ? selColor : normColor);
            tabs[i].setBackgroundColor(sel ? 0xFFE8F0FE : 0x00000000);
            tabs[i].setTypeface(null, sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            panels[i].setVisibility(sel ? View.VISIBLE : View.GONE);
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
            // All Time
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

    // ── Category / Sub Category panels ─────────────────────
    private void loadCategoriesAndSubCategories() {
        CategoryDao categoryDao = new CategoryDao(getContext());
        Map<Integer, Category> merged = new LinkedHashMap<>();
        for (Category c : categoryDao.findByType("INCOME", bookId)) merged.put(c.getId(), c);
        for (Category c : categoryDao.findByType("EXPENSE", bookId)) merged.put(c.getId(), c);
        allCategories = new ArrayList<>(merged.values());

        SubCategoryDao subCategoryDao = new SubCategoryDao(getContext());
        allSubCategories = subCategoryDao.findAll();

        buildCategoryCheckboxes();
        buildSubCategoryCheckboxes();
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

    // ── Prefill dialog fields from the incoming filter ──────
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
            if (filter.getDateFrom() != null) etDateFrom.setText(filter.getDateFrom().format(DATE_FMT));
            if (filter.getDateTo() != null) etDateTo.setText(filter.getDateTo().format(DATE_FMT));
        } else {
            rgDatePreset.check(R.id.rbAllTime);
        }
    }
}