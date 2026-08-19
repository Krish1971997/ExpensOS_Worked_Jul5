package com.expenseos.ui.home;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ActionMode;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

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
import com.expenseos.sync.SyncManager;
import com.expenseos.ui.MainActivity;
import com.expenseos.util.AppConfig;
import com.expenseos.util.DownloadsSaver;
import com.expenseos.util.ReportGenerator;

import java.io.File;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class HomeFragment extends Fragment {

    private TextView tvTotalIncome, tvTotalExpense, tvNetBalance;
    private RecyclerView rvTransactions;
    private SwipeRefreshLayout swipeRefresh;
    private TransactionAdapter adapter;
    private final List<Transaction> transactions = new ArrayList<>();

    private EditText etSearch;
    private ImageButton btnFilter;
    private TextView chipDate, chipCategory, chipSubCategory, chipAmount, chipPaymentType;
    private View rowViewReports, rowStats;
    private TextView tvEntryCount, btnSortField, btnSortToggle;
    private boolean sortAscending = false;
    private String currentSortBy = "date"; // default sort field

    private final TransactionFilter currentFilter = new TransactionFilter();

    private ActionMode actionMode;
    private static final int REQ_STORAGE_PERM = 3001;
    private String pendingExportAction; // "csv" or "pdf-save", resumed after a permission grant

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_home, container, false);

        tvTotalIncome = root.findViewById(R.id.tv_total_income);
        tvTotalExpense = root.findViewById(R.id.tv_total_expense);
        tvNetBalance = root.findViewById(R.id.tv_net_balance);
        swipeRefresh = root.findViewById(R.id.swipe_refresh);
        rvTransactions = root.findViewById(R.id.rv_transactions);
        etSearch = root.findViewById(R.id.etTxnSearch);
        btnFilter = root.findViewById(R.id.btnTxnFilter);
        chipDate = root.findViewById(R.id.chipDate);
        chipCategory = root.findViewById(R.id.chipCategory);
        chipSubCategory = root.findViewById(R.id.chipSubCategory);
        chipAmount = root.findViewById(R.id.chipAmount);
        chipPaymentType = root.findViewById(R.id.chipPaymentType);
        rowViewReports = root.findViewById(R.id.rowViewReports);
        rowStats = root.findViewById(R.id.rowStats);
        tvEntryCount = root.findViewById(R.id.tvEntryCount);
        btnSortField = root.findViewById(R.id.btnSortField);
        btnSortToggle = root.findViewById(R.id.btnSortToggle);

        // Sticky Date Header View Initialization
        TextView tvStickyDateHeader = root.findViewById(R.id.tvStickyDateHeader);

        btnSortField.setOnClickListener(v -> showSortMenu(v));

        btnSortToggle.setOnClickListener(v -> {
            sortAscending = !sortAscending;
            currentFilter.setSortDir(sortAscending ? "asc" : "desc");
            updateSortToggleText();
            loadTransactions();
        });

        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        rvTransactions.setLayoutManager(layoutManager);

        adapter = new TransactionAdapter(requireContext(), transactions, null, txn -> {
            Intent i = new Intent(requireContext(), com.expenseos.ui.EntryDetailActivity.class);
            i.putExtra("txnId", txn.getId());
            startActivity(i);
        });
        adapter.setOnSelectionChanged(this::onSelectionChanged);
        rvTransactions.setAdapter(adapter);

        // ── Sticky Date Header Scroll Listener ────────────────────────────
        rvTransactions.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);

                if (layoutManager != null) {
                    int firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition();

                    if (firstVisibleItemPosition != RecyclerView.NO_POSITION && !transactions.isEmpty()) {
                        Transaction currentTxn = transactions.get(firstVisibleItemPosition);

                        if (currentTxn != null && currentTxn.getFormattedDate() != null && tvStickyDateHeader != null) {
                            tvStickyDateHeader.setText(currentTxn.getFormattedDate());
                        }
                    }
                }
            }
        });
        // ──────────────────────────────────────────────────────────────────

        currentFilter.setPageSize(Integer.MAX_VALUE);
        currentFilter.setSortBy(currentSortBy);
        currentFilter.setSortDir("desc");

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

        // ── Index Matching Fixed ───────────────────────────
        btnFilter.setOnClickListener(v -> openFilterDialog(1, false));       // Date
        chipDate.setOnClickListener(v -> openFilterDialog(1, true));          // Date = Index 1
        chipCategory.setOnClickListener(v -> openFilterDialog(2, true));      // Category = Index 2
        chipSubCategory.setOnClickListener(v -> openFilterDialog(3, true));   // Sub Category = Index 3
        chipAmount.setOnClickListener(v -> openFilterDialog(4, true));        // Amount = Index 4
        chipPaymentType.setOnClickListener(v -> openFilterDialog(5, true));    // Payment Type = Index 5
        // ───────────────────────────────────────────────────

        rowViewReports.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), com.expenseos.ui.GenerateReportActivity.class)));
        rowStats.setOnClickListener(v -> {
            Intent i = new Intent(requireContext(), com.expenseos.ui.StatsActivity.class);
            i.putExtra("scopeBookId", AppConfig.get(requireContext()).getActiveBookId());
            startActivity(i);
        });

        Button btnIncome = root.findViewById(R.id.btn_add_income);
        Button btnExpense = root.findViewById(R.id.btn_add_expense);
        Button btnSyncCloud = root.findViewById(R.id.btn_sync_cloud);
        Button btnFetchCloud = root.findViewById(R.id.btn_fetch_cloud);

        btnIncome.setOnClickListener(v -> openEntryScreen(Transaction.Type.INCOME));
        btnExpense.setOnClickListener(v -> openEntryScreen(Transaction.Type.EXPENSE));

        btnSyncCloud.setOnClickListener(v -> {
            btnSyncCloud.setEnabled(false);
            btnSyncCloud.setText("Syncing…");
            SyncManager.get().syncToCloud(requireContext(), (ok, summary) -> {
                btnSyncCloud.setEnabled(true);
                btnSyncCloud.setText("↑ Sync to Cloud");
                Toast.makeText(getContext(), ok ? "✔ " + summary : "✘ " + summary, Toast.LENGTH_LONG).show();
                loadTransactions();
            });
        });

        btnFetchCloud.setOnClickListener(v -> {
            btnFetchCloud.setEnabled(false);
            btnFetchCloud.setText("Fetching…");
            SyncManager.get().fetchFromCloud(requireContext(), (ok, summary) -> {
                btnFetchCloud.setEnabled(true);
                btnFetchCloud.setText("↓ Fetch from Cloud");
                Toast.makeText(getContext(), ok ? "✔ " + summary : "✘ " + summary, Toast.LENGTH_LONG).show();
                loadTransactions();
            });
        });

        swipeRefresh.setOnRefreshListener(() -> {
            loadTransactions();
            swipeRefresh.setRefreshing(false);
        });

        loadTransactions();
        return root;
    }

    public void refreshData() {
        loadTransactions();
    }

    private void loadTransactions() {
        int bookId = AppConfig.get(requireContext()).getActiveBookId();
        TransactionDao dao = new TransactionDao(requireContext());

        currentFilter.setBookId(bookId);
        currentFilter.setType(null);
        List<Transaction> all = dao.findByFilter(currentFilter);

        BigDecimal income = BigDecimal.ZERO, expense = BigDecimal.ZERO;
        for (Transaction t : all) {
            if (t.getType() == Transaction.Type.INCOME)
                income = income.add(t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO);
            else expense = expense.add(t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO);
        }
        BigDecimal balance = income.subtract(expense);

        tvTotalIncome.setText("₹" + income.toPlainString());
        tvTotalExpense.setText("₹" + expense.toPlainString());
        tvNetBalance.setText("₹" + balance.toPlainString());

        transactions.clear();
        transactions.addAll(all);
        adapter.setData(transactions);
        refreshFilterChips();
        tvEntryCount.setText("Showing " + all.size() + " entries");

        if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).updateBookLabel();
    }

    private void openFilterDialog(int tab, boolean singleField) {
        int bookId = AppConfig.get(requireContext()).getActiveBookId();
        new TransactionFilterDialog(requireContext(), bookId, currentFilter, tab, singleField, appliedFilter -> {
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

        int normalColor = getResources().getColor(R.color.text);
        int activeColor = getResources().getColor(R.color.primary);
        boolean dateActive = currentFilter.getDateFrom() != null || currentFilter.getDateTo() != null;
        setChipActive(chipDate, dateActive, normalColor, activeColor);
        setChipActive(chipCategory, catCount > 0, normalColor, activeColor);
        setChipActive(chipSubCategory, subCount > 0, normalColor, activeColor);
        setChipActive(chipAmount, currentFilter.getAmount1() != null, normalColor, activeColor);
        int ptCount = currentFilter.getPaymentTypes() != null ? currentFilter.getPaymentTypes().size() : 0;
        chipPaymentType.setText(ptCount == 0 ? "Payment Type ▾" : "Payment Type (" + ptCount + ") ▾");
        setChipActive(chipPaymentType, ptCount > 0, normalColor, activeColor);
    }

    private void setChipActive(TextView chip, boolean active, int normalColor, int activeColor) {
        chip.setTextColor(active ? activeColor : normalColor);
        chip.setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private void openEntryScreen(Transaction.Type type) {
        Intent i = new Intent(requireContext(), com.expenseos.ui.TransactionEntryActivity.class);
        i.putExtra("type", type.name());
        startActivity(i);
    }

    private void showSortMenu(View v) {
        androidx.appcompat.widget.PopupMenu popup = new androidx.appcompat.widget.PopupMenu(requireContext(), v);
        popup.getMenu().add(0, 1, 0, "Date");
        popup.getMenu().add(0, 2, 1, "Amount");
        popup.getMenu().add(0, 3, 2, "Category");
        popup.getMenu().add(0, 4, 3, "Subcategory");
        popup.getMenu().add(0, 5, 4, "Type");
        popup.getMenu().add(0, 6, 5, "Note");

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    currentSortBy = "date";
                    btnSortField.setText("Date ▾");
                    break;
                case 2:
                    currentSortBy = "amount";
                    btnSortField.setText("Amount ▾");
                    break;
                case 3:
                    currentSortBy = "category";
                    btnSortField.setText("Category ▾");
                    break;
                case 4:
                    currentSortBy = "subcategory";
                    btnSortField.setText("Subcategory ▾");
                    break;
                case 5:
                    currentSortBy = "type";
                    btnSortField.setText("Type ▾");
                    break;
                case 6:
                    currentSortBy = "note";
                    btnSortField.setText("Note ▾");
                    break;
                default:
                    return false;
            }
            currentFilter.setSortBy(currentSortBy);
            updateSortToggleText();
            loadTransactions();
            return true;
        });
        popup.show();
    }

    private void updateSortToggleText() {
        if ("date".equals(currentSortBy)) {
            btnSortToggle.setText(sortAscending ? "↑ Oldest" : "↓ Newest");
        } else {
            btnSortToggle.setText(sortAscending ? "↑ ASC" : "↓ DESC");
        }
    }

    // NEW — replace with this (adds everything below onResume(), before the final class brace)
    @Override
    public void onResume() {
        super.onResume();
        loadTransactions();
    }

    // ══════════════════════════════════════════════════════
    // Long-press multi-select
    // ══════════════════════════════════════════════════════
    private void onSelectionChanged(int count) {
        if (count == 0) {
            if (actionMode != null) actionMode.finish();
            return;
        }
        if (actionMode == null) {
            actionMode = ((androidx.appcompat.app.AppCompatActivity) requireActivity())
                    .startSupportActionMode(selectionActionModeCallback);
        }
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
                // One button does both — toggles to "select none" once
                // everything's already checked.
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

    // ── Delete (double confirm) ─────────────────────────────
    private void confirmDeleteSelected() {
        List<Transaction> selected = adapter.getSelectedTransactions();
        if (selected.isEmpty()) return;
        String n = selected.size() + " transaction" + (selected.size() > 1 ? "s" : "");
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete " + n + "?")
                .setMessage("This can't be undone.")
                .setPositiveButton("Delete", (d, w) -> new AlertDialog.Builder(requireContext())
                        .setTitle("Are you sure?")
                        .setMessage("Confirm deleting " + n + ". This is permanent.")
                        .setPositiveButton("Yes, delete", (d2, w2) -> {
                            TransactionDao dao = new TransactionDao(requireContext());
                            for (Transaction t : selected) dao.delete(t.getId());
                            Toast.makeText(requireContext(), selected.size() + " deleted", Toast.LENGTH_SHORT).show();
                            if (actionMode != null) actionMode.finish();
                            loadTransactions();
                        })
                        .setNegativeButton("Cancel", null)
                        .show())
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Bulk Edit: Category / Sub-Category / Payment Type ──
    // Each field defaults to "No change". Sub-Category is special: if the
    // user changes Category, the old sub-category id no longer belongs to
    // the new category, so "No change" is removed from that spinner and the
    // user must explicitly pick "None" or one of the new category's own
    // sub-categories — never silently carried over.
    private void showBulkEditDialog() {
        List<Transaction> selected = adapter.getSelectedTransactions();
        if (selected.isEmpty()) return;

        int bookId = AppConfig.get(requireContext()).getActiveBookId();
        CategoryDao catDao = new CategoryDao(requireContext());
        SubCategoryDao subCatDao = new SubCategoryDao(requireContext());
        PaymentTypeDao payDao = new PaymentTypeDao(requireContext());

        LinearLayout form = new LinearLayout(requireContext());
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        form.setPadding(pad, pad, pad, pad);

        TextView note = new TextView(requireContext());
        note.setText("Only the fields you change here get applied — leave a field on \"No change\" to keep it as-is.");
        note.setTextSize(11);
        note.setTextColor(getResources().getColor(R.color.text_muted, null));
        form.addView(note);

        // Category — union of INCOME+EXPENSE so it covers a mixed selection
        form.addView(sectionLabel("Category", pad));
        Spinner spCat = new Spinner(requireContext());
        form.addView(spCat);

        List<Category> allCats = new ArrayList<>();
        allCats.addAll(catDao.findByType("EXPENSE", bookId));
        allCats.addAll(catDao.findByType("INCOME", bookId));
        List<Category> catOptions = new ArrayList<>();
        catOptions.add(new Category(0, "No change", "", null));
        catOptions.addAll(allCats);
        ArrayAdapter<Category> catAdp = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, catOptions);
        catAdp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCat.setAdapter(catAdp);

        // Sub-Category — reloads whenever Category changes
        form.addView(sectionLabel("Sub-Category", pad));
        Spinner spSub = new Spinner(requireContext());
        form.addView(spSub);

        Runnable refreshSub = () -> {
            int pos = spCat.getSelectedItemPosition();
            List<SubCategory> subs = new ArrayList<>();
            if (pos == 0) {
                // Category isn't changing — can't safely guess one shared
                // category across a mixed selection, so leave it untouched too.
                subs.add(new SubCategory(0, "No change", 0));
                ArrayAdapter<SubCategory> subAdp = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, subs);
                subAdp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spSub.setAdapter(subAdp);
                spSub.setEnabled(false);
                return;
            }
            Category picked = catOptions.get(pos);
            subs.add(new SubCategory(0, "None", picked.getId()));
            subs.addAll(subCatDao.findByCategoryId(picked.getId()));
            ArrayAdapter<SubCategory> subAdp = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, subs);
            subAdp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spSub.setAdapter(subAdp);
            spSub.setEnabled(true); // live whenever a real category is picked, even with just "None" + 0 sub-cats
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

        // Payment Type — plain flat list, own placeholder handled as a String
        // (avoids needing to fabricate a fake PaymentType instance).
        form.addView(sectionLabel("Payment Type", pad));
        Spinner spPay = new Spinner(requireContext());
        form.addView(spPay);

        List<PaymentType> realPayTypes = payDao.findAll();
        List<String> payNames = new ArrayList<>();
        payNames.add("No change");
        for (PaymentType pt : realPayTypes) payNames.add(pt.getName());
        ArrayAdapter<String> payAdp = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, payNames);
        payAdp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spPay.setAdapter(payAdp);

        new AlertDialog.Builder(requireContext())
                .setTitle("Edit " + selected.size() + " transaction" + (selected.size() > 1 ? "s" : ""))
                .setView(form)
                .setPositiveButton("Apply", (d, w) -> {
                    Category newCat = spCat.getSelectedItemPosition() > 0 ? catOptions.get(spCat.getSelectedItemPosition()) : null;
                    boolean subTouched = newCat != null; // touching Category always resolves Sub-Category too
                    SubCategory pickedSub = (SubCategory) spSub.getSelectedItem();
                    Integer newSubId = subTouched ? (pickedSub != null ? pickedSub.getId() : 0) : null;
                    String newPaymentType = spPay.getSelectedItemPosition() > 0 ? payNames.get(spPay.getSelectedItemPosition()) : null;

                    if (newCat == null && newPaymentType == null) {
                        Toast.makeText(requireContext(), "Nothing to change", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    confirmBulkEdit(selected, newCat, subTouched, newSubId, newPaymentType);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private TextView sectionLabel(String text, int topMargin) {
        TextView t = new TextView(requireContext());
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

        new AlertDialog.Builder(requireContext())
                .setTitle("Apply to " + selected.size() + " transaction" + (selected.size() > 1 ? "s" : "") + "?")
                .setMessage(changes.toString())
                .setPositiveButton("Continue", (d, w) -> new AlertDialog.Builder(requireContext())
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
        TransactionDao dao = new TransactionDao(requireContext());
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
        Toast.makeText(requireContext(), selected.size() + " transaction" +
                (selected.size() > 1 ? "s" : "") + " updated", Toast.LENGTH_SHORT).show();
        if (actionMode != null) actionMode.finish();
        loadTransactions();
    }

    // ── Move to another cashbook (double confirm) ───────────
    private void showMoveDialog() {
        List<Transaction> selected = adapter.getSelectedTransactions();
        if (selected.isEmpty()) return;
        int currentBookId = AppConfig.get(requireContext()).getActiveBookId();
        List<CashBook> targets = new ArrayList<>();
        for (CashBook b : new CashBookDao(requireContext()).findAll())
            if (b.getId() != currentBookId) targets.add(b);

        if (targets.isEmpty()) {
            Toast.makeText(requireContext(), "No other cashbook to move to", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] names = new String[targets.size()];
        for (int i = 0; i < targets.size(); i++) names[i] = targets.get(i).getName();

        new AlertDialog.Builder(requireContext())
                .setTitle("Move " + selected.size() + " transaction" + (selected.size() > 1 ? "s" : "") + " to…")
                .setItems(names, (d, which) -> confirmMove(selected, targets.get(which)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmMove(List<Transaction> selected, CashBook target) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Move to \"" + target.getName() + "\"?")
                .setMessage("Are you sure you want to move " + selected.size() + " transaction" +
                        (selected.size() > 1 ? "s" : "") + " to \"" + target.getName() + "\"?")
                .setPositiveButton("Move", (d, w) -> {
                    TransactionDao dao = new TransactionDao(requireContext());
                    for (Transaction t : selected) dao.updateBookId(t.getId(), target.getId());
                    Toast.makeText(requireContext(), selected.size() + " moved to " + target.getName(), Toast.LENGTH_SHORT).show();
                    if (actionMode != null) actionMode.finish();
                    loadTransactions();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Export selected — same PDF (preview/save) + Excel flow as
    // GenerateReportActivity, scoped to just the selected rows ─────
    private void showExportSheet() {
        List<Transaction> selected = adapter.getSelectedTransactions();
        if (selected.isEmpty()) return;

        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext());
        android.widget.LinearLayout container = new android.widget.LinearLayout(requireContext());
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setPadding(0, dp(8), 0, dp(16));

        TextView title = new TextView(requireContext());
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
        android.widget.LinearLayout row = new android.widget.LinearLayout(requireContext());
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(14), dp(20), dp(14));
        row.setClickable(true);
        row.setFocusable(true);
        android.util.TypedValue outValue = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        row.setBackgroundResource(outValue.resourceId);

        TextView tvEmoji = new TextView(requireContext());
        tvEmoji.setText(emoji);
        tvEmoji.setTextSize(18);
        tvEmoji.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(32), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(tvEmoji);

        TextView tvLabel = new TextView(requireContext());
        tvLabel.setText(label);
        tvLabel.setTextSize(16);
        tvLabel.setTextColor(getResources().getColor(R.color.text, null));
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(16);
        tvLabel.setLayoutParams(lp);
        row.addView(tvLabel);

        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void previewPdf(List<Transaction> txns) {
        try {
            File dir = new File(requireContext().getCacheDir(), "reports");
            if (!dir.exists()) dir.mkdirs();
            File pdfFile = new File(dir, "selected_" + System.currentTimeMillis() + ".pdf");
            try (FileOutputStream out = new FileOutputStream(pdfFile)) {
                ReportGenerator.writePdf(txns, ReportGenerator.TYPE_ALL, out);
            }
            Uri uri = FileProvider.getUriForFile(requireContext(),
                    requireContext().getPackageName() + ".fileprovider", pdfFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/pdf");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Couldn't generate preview: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void savePdfToDownloads(List<Transaction> txns) {
        String fileName = "Transactions_" + System.currentTimeMillis() + ".pdf";
        try {
            DownloadsSaver.Result result = DownloadsSaver.save(requireContext(), fileName, "application/pdf",
                    out -> ReportGenerator.writePdf(txns, ReportGenerator.TYPE_ALL, out));
            Toast.makeText(requireContext(), "Saved to " + result.displayLocation, Toast.LENGTH_LONG).show();
            if (actionMode != null) actionMode.finish();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void saveCsvToDownloads(List<Transaction> txns) {
        String fileName = "Transactions_" + System.currentTimeMillis() + ".csv";
        try {
            DownloadsSaver.Result result = DownloadsSaver.save(requireContext(), fileName, "text/csv",
                    out -> ReportGenerator.writeCsv(txns, ReportGenerator.TYPE_ALL, out));
            Toast.makeText(requireContext(), "Saved to " + result.displayLocation, Toast.LENGTH_LONG).show();
            if (actionMode != null) actionMode.finish();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // Storage permission — only needed on API 26-28 (API 29+ uses MediaStore).
    private boolean ensureStoragePermission(String action) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true;
        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) return true;
        pendingExportAction = action;
        requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE_PERM);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_STORAGE_PERM) return;
        List<Transaction> selected = adapter.getSelectedTransactions();
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if ("csv".equals(pendingExportAction)) saveCsvToDownloads(selected);
            else if ("pdf-save".equals(pendingExportAction)) savePdfToDownloads(selected);
        } else {
            Toast.makeText(requireContext(), "Storage permission needed to save to Downloads", Toast.LENGTH_SHORT).show();
        }
        pendingExportAction = null;
    }
}