package com.expenseos.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.adapter.TransactionAdapter;
import com.expenseos.dao.CashBookDao;
import com.expenseos.dao.CategoryDao;
import com.expenseos.dao.PaymentTypeDao;
import com.expenseos.dao.SubCategoryDao;
import com.expenseos.dao.TransactionDao;
import com.expenseos.model.CashBook;
import com.expenseos.model.Category;
import com.expenseos.model.PaymentType;
import com.expenseos.model.SubCategory;
import com.expenseos.model.Transaction;
import com.expenseos.model.TransactionFilter;
import com.expenseos.ui.home.TransactionFilterDialog;
import com.expenseos.util.DownloadsSaver;
import com.expenseos.util.ReportGenerator;

import java.io.File;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class AllTransactionsActivity extends AppCompatActivity {

    private TextView tvTotalIncome, tvTotalExpense, tvNetBalance, tvEntryCount;
    private TextView chipCashBook;
    private RecyclerView rvTransactions;
    private TransactionAdapter adapter;
    private final List<Transaction> transactions = new ArrayList<>();

    private EditText etSearch;
    private ImageButton btnFilter;
    private TextView chipDate, chipCategory, chipSubCategory, chipAmount, chipPaymentType, chipAttachment;
    private TextView btnSortToggle;
    private boolean sortAscending = false;

    private final TransactionFilter currentFilter = new TransactionFilter();

    private ActionMode actionMode;
    private static final int REQ_STORAGE_PERM = 3002;
    private String pendingExportAction; // "csv" / "pdf-save"

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_all_transactions);

        findViewById(R.id.btnAllTxnBack).setOnClickListener(v -> finish());

        tvTotalIncome = findViewById(R.id.tv_total_income);
        tvTotalExpense = findViewById(R.id.tv_total_expense);
        tvNetBalance = findViewById(R.id.tv_net_balance);
        rvTransactions = findViewById(R.id.rv_transactions);
        etSearch = findViewById(R.id.etTxnSearch);
        btnFilter = findViewById(R.id.btnTxnFilter);
        chipCashBook = findViewById(R.id.chipCashBook);
        chipDate = findViewById(R.id.chipDate);
        chipCategory = findViewById(R.id.chipCategory);
        chipSubCategory = findViewById(R.id.chipSubCategory);
        chipAmount = findViewById(R.id.chipAmount);
        chipPaymentType = findViewById(R.id.chipPaymentType);
        chipAttachment = findViewById(R.id.chipAttachment);
        tvEntryCount = findViewById(R.id.tvEntryCount);
        btnSortToggle = findViewById(R.id.btnSortToggle);

        rvTransactions.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TransactionAdapter(this, transactions, null, txn -> {
            Intent i = new Intent(this, EntryDetailActivity.class);
            i.putExtra("txnId", txn.getId());
            startActivity(i);
        }, true); // showBookLabel = true
        adapter.setOnSelectionChanged(this::onSelectionChanged);
        com.expenseos.util.UiUtils.tuneList(rvTransactions);
        rvTransactions.setAdapter(adapter);

        currentFilter.setPageSize(Integer.MAX_VALUE);
        currentFilter.setBookId(null);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                String q = e.toString().trim();
                currentFilter.setNoteSearch(q.isEmpty() ? null : q);
                loadTransactions();
            }
        });

        // 🟢 Filter Button Listener சேர்க்கப்பட்டுள்ளது:
        btnFilter.setOnClickListener(v -> openFilterDialog(0, false));

        // Chips Click Listeners:
        chipCashBook.setOnClickListener(v -> openFilterDialog(0, true));
        chipDate.setOnClickListener(v -> openFilterDialog(1, true));
        chipCategory.setOnClickListener(v -> openFilterDialog(2, true));
        chipSubCategory.setOnClickListener(v -> openFilterDialog(3, true));
        chipAmount.setOnClickListener(v -> openFilterDialog(4, true));
        chipPaymentType.setOnClickListener(v -> openFilterDialog(5, true));
        chipAttachment.setOnClickListener(v -> openFilterDialog(6, true));

        btnSortToggle.setOnClickListener(v -> {
            sortAscending = !sortAscending;
            currentFilter.setSortDir(sortAscending ? "asc" : "desc");
            btnSortToggle.setText(sortAscending ? "↑" : "↓");
            loadTransactions();
        });

        loadTransactions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadTransactions();
    }

    private void loadTransactions() {
        TransactionDao dao = new TransactionDao(this);
        currentFilter.setType(null);
        List<Transaction> all = dao.findByFilter(currentFilter);

        BigDecimal income = BigDecimal.ZERO, expense = BigDecimal.ZERO;
        for (Transaction t : all) {
            if (t.getType() == Transaction.Type.INCOME)
                income = income.add(t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO);
            else
                expense = expense.add(t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO);
        }
        BigDecimal balance = income.subtract(expense);

        tvTotalIncome.setText("₹" + income.toPlainString());
        tvTotalExpense.setText("₹" + expense.toPlainString());
        tvNetBalance.setText("₹" + balance.toPlainString());

        transactions.clear();
        transactions.addAll(all);
        adapter.setData(transactions);
        refreshFilterChips();
        tvEntryCount.setText("Showing " + all.size() + " entries (all books)");
    }

    private void openFilterDialog(int tab, boolean singleField) {
        new TransactionFilterDialog(this, null, currentFilter, tab, singleField, appliedFilter -> {
            appliedFilter.setNoteSearch(currentFilter.getNoteSearch());
            currentFilter.setDateFrom(appliedFilter.getDateFrom());
            currentFilter.setDateTo(appliedFilter.getDateTo());
            currentFilter.setCategoryIds(appliedFilter.getCategoryIds());
            currentFilter.setSubCategoryIds(appliedFilter.getSubCategoryIds());
            currentFilter.setAmountOp1(appliedFilter.getAmountOp1());
            currentFilter.setAmount1(appliedFilter.getAmount1());
            currentFilter.setAmountOp2(appliedFilter.getAmountOp2());
            currentFilter.setAmount2(appliedFilter.getAmount2());
            currentFilter.setPaymentTypes(appliedFilter.getPaymentTypes());

            // 🟢 ADD THIS LINE:
            currentFilter.setHasAttachment(appliedFilter.getHasAttachment());

            currentFilter.setBookIds(appliedFilter.getBookIds());
            currentFilter.setBookId(null);

            loadTransactions();
        }).show();
    }

    private void refreshFilterChips() {
        if (currentFilter.getDateFrom() == null && currentFilter.getDateTo() == null) {
            chipDate.setText("Date ▾");
        } else if (currentFilter.getDateFrom() != null && currentFilter.getDateFrom().equals(currentFilter.getDateTo())) {
            chipDate.setText("Date: " + currentFilter.getDateFrom() + " ▾");
        } else {
            chipDate.setText("Date: range ▾");
        }

        int catCount = currentFilter.getCategoryIds() != null ? currentFilter.getCategoryIds().size() : 0;
        chipCategory.setText(catCount == 0 ? "Category ▾" : "Category (" + catCount + ") ▾");

        int subCount = currentFilter.getSubCategoryIds() != null ? currentFilter.getSubCategoryIds().size() : 0;
        chipSubCategory.setText(subCount == 0 ? "Sub Category ▾" : "Sub Category (" + subCount + ") ▾");

        if (currentFilter.getAmount1() == null) {
            chipAmount.setText("Amount ▾");
        } else {
            String label = currentFilter.getAmountOp1() + currentFilter.getAmount1().toPlainString();
            if (currentFilter.getAmount2() != null)
                label += " " + currentFilter.getAmountOp2() + currentFilter.getAmount2().toPlainString();
            chipAmount.setText("Amount: " + label + " ▾");
        }

        int ptCount = currentFilter.getPaymentTypes() != null ? currentFilter.getPaymentTypes().size() : 0;
        chipPaymentType.setText(ptCount == 0 ? "Payment Type ▾" : "Payment Type (" + ptCount + ") ▾");

        Boolean hasAtt = currentFilter.getHasAttachment();
        chipAttachment.setText(hasAtt == null ? "Attachment ▾" : (hasAtt ? "Attachment: Yes ▾" : "Attachment: No ▾"));

        int cbCount = currentFilter.getBookIds() != null ? currentFilter.getBookIds().size() : 0;
        chipCashBook.setText(cbCount == 0 ? "Cashbook ▾" : "Cashbook (" + cbCount + ") ▾");

        int normalColor = getResources().getColor(R.color.text, null);
        int activeColor = getResources().getColor(R.color.primary, null);

        setChipActive(chipCashBook, cbCount > 0, normalColor, activeColor);
        boolean dateActive = currentFilter.getDateFrom() != null || currentFilter.getDateTo() != null;
        setChipActive(chipDate, dateActive, normalColor, activeColor);
        setChipActive(chipCategory, catCount > 0, normalColor, activeColor);
        setChipActive(chipSubCategory, subCount > 0, normalColor, activeColor);
        setChipActive(chipAmount, currentFilter.getAmount1() != null, normalColor, activeColor);
        setChipActive(chipPaymentType, ptCount > 0, normalColor, activeColor);
        setChipActive(chipAttachment, hasAtt != null, normalColor, activeColor);
    }

    private void setChipActive(TextView chip, boolean active, int normalColor, int activeColor) {
        chip.setTextColor(active ? activeColor : normalColor);
        chip.setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    // ══════════════════════════════════════════════════════
    // Long-press multi-select (same options as Cashbook page)
    // ══════════════════════════════════════════════════════
    private void onSelectionChanged(int count) {
        if (count == 0) {
            if (actionMode != null) actionMode.finish();
            return;
        }
        if (actionMode == null) actionMode = startSupportActionMode(selectionActionModeCallback);
        if (actionMode != null) actionMode.setTitle(count + " selected");
    }

    private final ActionMode.Callback selectionActionModeCallback = new ActionMode.Callback() {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mode.getMenuInflater().inflate(R.menu.menu_txn_selection, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            int id = item.getItemId();
            if (id == R.id.action_select_all) {
                if (adapter.getSelectedTransactions().size() >= adapter.getSelectableCount())
                    adapter.clearSelection();
                else
                    adapter.selectAll();
                return true;
            } else if (id == R.id.action_delete) {
                confirmDeleteSelected();
                return true;
            } else if (id == R.id.action_edit) {
                showBulkEditDialog();
                return true;
            } else if (id == R.id.action_move) {
                showMoveDialog();
                return true;
            } else if (id == R.id.action_export) {
                showExportSheet();
                return true;
            }
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            adapter.clearSelection();
            actionMode = null;
        }
    };

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // Selection la ella txn-um oru cashbook-a? Appadi na adhoda id, illa na null.
    private Integer singleBookId(List<Transaction> sel) {
        if (sel.isEmpty()) return null;
        Integer first = Integer.valueOf(sel.get(0).getBookId());
        for (Transaction t : sel)
            if (!first.equals(Integer.valueOf(t.getBookId()))) return null;
        return first;
    }

    // ── Delete (double confirm) ─────────────────────────────
    private void confirmDeleteSelected() {
        List<Transaction> selected = adapter.getSelectedTransactions();
        if (selected.isEmpty()) return;
        String n = selected.size() + " transaction" + (selected.size() > 1 ? "s" : "");
        new AlertDialog.Builder(this)
                .setTitle("Delete " + n + "?")
                .setMessage("This can't be undone.")
                .setPositiveButton("Delete", (d, w) -> new AlertDialog.Builder(this)
                        .setTitle("Are you sure?")
                        .setMessage("Confirm deleting " + n + ". This is permanent.")
                        .setPositiveButton("Yes, delete", (d2, w2) -> {
                            TransactionDao dao = new TransactionDao(this);
                            for (Transaction t : selected) dao.delete(t.getId());
                            com.expenseos.util.UiUtils.toast(this, selected.size() + " deleted");
                            if (actionMode != null) actionMode.finish();
                            loadTransactions();
                        })
                        .setNegativeButton("Cancel", null)
                        .show())
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Bulk Edit (Category / Sub-Category / Payment Type) ──
    // Category-um per-cashbook nala, edit panna oru cashbook transactions mattum select panniruntha dhaan allow.
    private void showBulkEditDialog() {
        List<Transaction> selected = adapter.getSelectedTransactions();
        if (selected.isEmpty()) return;

        Integer bookIdObj = singleBookId(selected);
        if (bookIdObj == null) {
            com.expenseos.util.UiUtils.toast(this, "Edit panna oru cashbook transactions mattum select pannunga");
            return;
        }
        int bookId = bookIdObj;
        CategoryDao catDao = new CategoryDao(this);
        SubCategoryDao subCatDao = new SubCategoryDao(this);
        PaymentTypeDao payDao = new PaymentTypeDao(this);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        form.setPadding(pad, pad, pad, pad);

        TextView note = new TextView(this);
        note.setText("Only the fields you change here get applied — leave a field on \"No change\" to keep it as-is.");
        note.setTextSize(11);
        note.setTextColor(getResources().getColor(R.color.text_muted, null));
        form.addView(note);

        form.addView(sectionLabel("Category", pad));
        Spinner spCat = new Spinner(this);
        form.addView(spCat);

        List<Category> allCats = new ArrayList<>();
        allCats.addAll(catDao.findByType("EXPENSE", bookId));
        allCats.addAll(catDao.findByType("INCOME", bookId));
        List<Category> catOptions = new ArrayList<>();
        catOptions.add(new Category(0, "No change", "", null));
        catOptions.addAll(allCats);
        ArrayAdapter<Category> catAdp = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, catOptions);
        catAdp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCat.setAdapter(catAdp);

        form.addView(sectionLabel("Sub-Category", pad));
        Spinner spSub = new Spinner(this);
        form.addView(spSub);

        Runnable refreshSub = () -> {
            int pos = spCat.getSelectedItemPosition();
            List<SubCategory> subs = new ArrayList<>();
            if (pos == 0) {
                subs.add(new SubCategory(0, "No change", 0));
                ArrayAdapter<SubCategory> subAdp = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, subs);
                subAdp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spSub.setAdapter(subAdp);
                spSub.setEnabled(false);
                return;
            }
            Category picked = catOptions.get(pos);
            subs.add(new SubCategory(0, "None", picked.getId()));
            subs.addAll(subCatDao.findByCategoryId(picked.getId()));
            ArrayAdapter<SubCategory> subAdp = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, subs);
            subAdp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spSub.setAdapter(subAdp);
            spSub.setEnabled(true);
        };
        refreshSub.run();
        spCat.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                refreshSub.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        form.addView(sectionLabel("Payment Type", pad));
        Spinner spPay = new Spinner(this);
        form.addView(spPay);

        List<PaymentType> realPayTypes = payDao.findAll();
        List<String> payNames = new ArrayList<>();
        payNames.add("No change");
        for (PaymentType pt : realPayTypes) payNames.add(pt.getName());
        ArrayAdapter<String> payAdp = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, payNames);
        payAdp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spPay.setAdapter(payAdp);

        new AlertDialog.Builder(this)
                .setTitle("Edit " + selected.size() + " transaction" + (selected.size() > 1 ? "s" : ""))
                .setView(form)
                .setPositiveButton("Apply", (d, w) -> {
                    Category newCat = spCat.getSelectedItemPosition() > 0 ? catOptions.get(spCat.getSelectedItemPosition()) : null;
                    boolean subTouched = newCat != null;
                    SubCategory pickedSub = (SubCategory) spSub.getSelectedItem();
                    Integer newSubId = subTouched ? (pickedSub != null ? pickedSub.getId() : 0) : null;
                    String newPaymentType = spPay.getSelectedItemPosition() > 0 ? payNames.get(spPay.getSelectedItemPosition()) : null;

                    if (newCat == null && newPaymentType == null) {
                        com.expenseos.util.UiUtils.toast(this, "Nothing to change");
                        return;
                    }
                    confirmBulkEdit(selected, newCat, subTouched, newSubId, newPaymentType);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private TextView sectionLabel(String text, int topMargin) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11);
        t.setTextColor(getResources().getColor(R.color.text_muted, null));
        t.setPadding(0, topMargin, 0, 0);
        return t;
    }

    private void confirmBulkEdit(List<Transaction> selected, Category newCat, boolean subTouched,
                                 Integer newSubId, String newPaymentType) {
        StringBuilder changes = new StringBuilder();
        if (newCat != null) changes.append("• Category → ").append(newCat.getName()).append("\n");
        if (subTouched)
            changes.append("• Sub-Category → ").append(newSubId != null && newSubId > 0 ? "(selected)" : "None").append("\n");
        if (newPaymentType != null)
            changes.append("• Payment Type → ").append(newPaymentType).append("\n");

        new AlertDialog.Builder(this)
                .setTitle("Apply to " + selected.size() + " transaction" + (selected.size() > 1 ? "s" : "") + "?")
                .setMessage(changes.toString())
                .setPositiveButton("Continue", (d, w) -> new AlertDialog.Builder(this)
                        .setTitle("Are you sure?")
                        .setMessage("This updates " + selected.size() + " transaction" +
                                (selected.size() > 1 ? "s" : "") + ". Continue?")
                        .setPositiveButton("Yes, apply", (d2, w2) ->
                                applyBulkEdit(selected, newCat, subTouched, newSubId, newPaymentType))
                        .setNegativeButton("Cancel", null)
                        .show())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void applyBulkEdit(List<Transaction> selected, Category newCat, boolean subTouched,
                               Integer newSubId, String newPaymentType) {
        TransactionDao dao = new TransactionDao(this);
        for (Transaction oldT : selected) {
            Transaction newT = new Transaction();
            newT.setType(oldT.getType());
            newT.setDateTime(oldT.getDateTime());
            newT.setAmount(oldT.getAmount());
            newT.setNote(oldT.getNote());
            newT.setBookId(oldT.getBookId());
            newT.setCategoryId(newCat != null ? newCat.getId() : oldT.getCategoryId());
            newT.setSubCategoryId(subTouched ? (newSubId != null ? newSubId : 0) : oldT.getSubCategoryId());
            newT.setPaymentType(newPaymentType != null ? newPaymentType : oldT.getPaymentType());
            dao.update(oldT, newT);
        }
        com.expenseos.util.UiUtils.toast(this, selected.size() + " transaction" +
                (selected.size() > 1 ? "s" : "") + " updated");
        if (actionMode != null) actionMode.finish();
        loadTransactions();
    }

    // ── Move to another cashbook (double confirm) ───────────
    private void showMoveDialog() {
        List<Transaction> selected = adapter.getSelectedTransactions();
        if (selected.isEmpty()) return;
        Integer currentBookId = singleBookId(selected); // null = mixed books → ella book-um list aagum
        List<CashBook> targets = new ArrayList<>();
        for (CashBook b : new CashBookDao(this).findAll())
            if (currentBookId == null || b.getId() != currentBookId) targets.add(b);

        if (targets.isEmpty()) {
            com.expenseos.util.UiUtils.toast(this, "No other cashbook to move to");
            return;
        }

        String[] names = new String[targets.size()];
        for (int i = 0; i < targets.size(); i++) names[i] = targets.get(i).getName();

        new AlertDialog.Builder(this)
                .setTitle("Move " + selected.size() + " transaction" + (selected.size() > 1 ? "s" : "") + " to…")
                .setItems(names, (d, which) -> confirmMove(selected, targets.get(which)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmMove(List<Transaction> selected, CashBook target) {
        new AlertDialog.Builder(this)
                .setTitle("Move to \"" + target.getName() + "\"?")
                .setMessage("Are you sure you want to move " + selected.size() + " transaction" +
                        (selected.size() > 1 ? "s" : "") + " to \"" + target.getName() + "\"?")
                .setPositiveButton("Move", (d, w) -> {
                    TransactionDao dao = new TransactionDao(this);
                    for (Transaction t : selected)
                        if (t.getBookId() != target.getId())
                            dao.updateBookId(t.getId(), target.getId());
                    com.expenseos.util.UiUtils.toast(this, selected.size() + " moved to " + target.getName());
                    if (actionMode != null) actionMode.finish();
                    loadTransactions();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Export selected (PDF preview / PDF save / CSV) ──────
    private void showExportSheet() {
        List<Transaction> selected = adapter.getSelectedTransactions();
        if (selected.isEmpty()) return;

        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, dp(8), 0, dp(16));

        TextView title = new TextView(this);
        title.setText("Export " + selected.size() + " transaction" + (selected.size() > 1 ? "s" : ""));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(dp(20), dp(12), dp(20), dp(12));
        container.addView(title);

        container.addView(exportSheetOption("👁", "Show PDF Preview", () -> {
            sheet.dismiss();
            previewPdf(selected);
        }));
        container.addView(exportSheetOption("⬇", "Save PDF to Downloads", () -> {
            sheet.dismiss();
            if (ensureStoragePermission("pdf-save")) savePdfToDownloads(selected);
        }));
        container.addView(exportSheetOption("📊", "Save Excel (CSV) to Downloads", () -> {
            sheet.dismiss();
            if (ensureStoragePermission("csv")) saveCsvToDownloads(selected);
        }));

        sheet.setContentView(container);
        sheet.show();
    }

    private View exportSheetOption(String emoji, String label, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(14), dp(20), dp(14));
        row.setClickable(true);
        row.setFocusable(true);
        android.util.TypedValue outValue = new android.util.TypedValue();
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
        tvLabel.setTextColor(getResources().getColor(R.color.text, null));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(16);
        tvLabel.setLayoutParams(lp);
        row.addView(tvLabel);

        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private void previewPdf(List<Transaction> txns) {
        try {
            File dir = new File(getCacheDir(), "reports");
            if (!dir.exists()) dir.mkdirs();
            File pdfFile = new File(dir, "selected_" + System.currentTimeMillis() + ".pdf");
            try (FileOutputStream out = new FileOutputStream(pdfFile)) {
                ReportGenerator.writePdf(txns, ReportGenerator.TYPE_ALL, out);
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pdfFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/pdf");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            com.expenseos.util.UiUtils.toast(this, "Couldn't generate preview: " + e.getMessage());
        }
    }

    private void savePdfToDownloads(List<Transaction> txns) {
        String fileName = "Transactions_" + System.currentTimeMillis() + ".pdf";
        try {
            DownloadsSaver.Result result = DownloadsSaver.save(this, fileName, "application/pdf",
                    out -> ReportGenerator.writePdf(txns, ReportGenerator.TYPE_ALL, out));
            com.expenseos.util.UiUtils.toast(this, "Saved to " + result.displayLocation);
            if (actionMode != null) actionMode.finish();
        } catch (Exception e) {
            com.expenseos.util.UiUtils.toast(this, "Failed: " + e.getMessage());
        }
    }

    private void saveCsvToDownloads(List<Transaction> txns) {
        String fileName = "Transactions_" + System.currentTimeMillis() + ".csv";
        try {
            DownloadsSaver.Result result = DownloadsSaver.save(this, fileName, "text/csv",
                    out -> ReportGenerator.writeCsv(txns, ReportGenerator.TYPE_ALL, out));
            com.expenseos.util.UiUtils.toast(this, "Saved to " + result.displayLocation);
            if (actionMode != null) actionMode.finish();
        } catch (Exception e) {
            com.expenseos.util.UiUtils.toast(this, "Failed: " + e.getMessage());
        }
    }

    private boolean ensureStoragePermission(String action) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true;
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) return true;
        pendingExportAction = action;
        androidx.core.app.ActivityCompat.requestPermissions(this,
                new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE_PERM);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_STORAGE_PERM) return;
        List<Transaction> selected = adapter.getSelectedTransactions();
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if ("csv".equals(pendingExportAction)) saveCsvToDownloads(selected);
            else if ("pdf-save".equals(pendingExportAction)) savePdfToDownloads(selected);
        } else {
            com.expenseos.util.UiUtils.toast(this, "Storage permission needed to save to Downloads");
        }
        pendingExportAction = null;
    }
}