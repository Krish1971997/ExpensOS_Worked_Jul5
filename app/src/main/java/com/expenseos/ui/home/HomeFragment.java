package com.expenseos.ui.home;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.expenseos.R;
import com.expenseos.adapter.TransactionAdapter;
import com.expenseos.dao.TransactionDao;
import com.expenseos.model.Transaction;
import com.expenseos.model.TransactionFilter;
import com.expenseos.sync.SyncManager;
import com.expenseos.ui.MainActivity;
import com.expenseos.util.AppConfig;

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

        btnSortField.setOnClickListener(v -> showSortMenu(v));

        btnSortToggle.setOnClickListener(v -> {
            sortAscending = !sortAscending;
            currentFilter.setSortDir(sortAscending ? "asc" : "desc");
            updateSortToggleText();
            loadTransactions();
        });

        rvTransactions.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new TransactionAdapter(requireContext(), transactions, null, txn -> {
            Intent i = new Intent(requireContext(), com.expenseos.ui.EntryDetailActivity.class);
            i.putExtra("txnId", txn.getId());
            startActivity(i);
        });
        rvTransactions.setAdapter(adapter);

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

    @Override
    public void onResume() {
        super.onResume();
        loadTransactions();
    }
}