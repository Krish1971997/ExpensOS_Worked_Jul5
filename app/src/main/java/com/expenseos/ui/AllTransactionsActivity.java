package com.expenseos.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.adapter.TransactionAdapter;
import com.expenseos.dao.TransactionDao;
import com.expenseos.model.Transaction;
import com.expenseos.model.TransactionFilter;
import com.expenseos.ui.home.TransactionFilterDialog;

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
    private TextView chipDate, chipCategory, chipSubCategory, chipAmount, chipPaymentType;
    private TextView btnSortToggle;
    private boolean sortAscending = false;

    private final TransactionFilter currentFilter = new TransactionFilter();

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
        tvEntryCount = findViewById(R.id.tvEntryCount);
        btnSortToggle = findViewById(R.id.btnSortToggle);

        rvTransactions.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TransactionAdapter(this, transactions, null, txn -> {
            Intent i = new Intent(this, EntryDetailActivity.class);
            i.putExtra("txnId", txn.getId());
            startActivity(i);
        }, true); // showBookLabel = true
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
    }

    private void setChipActive(TextView chip, boolean active, int normalColor, int activeColor) {
        chip.setTextColor(active ? activeColor : normalColor);
        chip.setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }
}