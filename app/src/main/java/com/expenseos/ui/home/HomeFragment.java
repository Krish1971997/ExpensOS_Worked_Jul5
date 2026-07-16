package com.expenseos.ui.home;

import android.app.AlertDialog;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.expenseos.ui.MainActivity;
import com.expenseos.R;
import com.expenseos.adapter.TransactionAdapter;
import com.expenseos.dao.LocalDatabase;
import com.expenseos.dao.TransactionDao;
import com.expenseos.model.Category;
import com.expenseos.model.SubCategory;
import com.expenseos.model.Transaction;
import com.expenseos.model.TransactionFilter;
import com.expenseos.sync.SyncManager;
import com.expenseos.util.AppConfig;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HomeFragment extends Fragment {

    // et_date/et_amount fields are filled as "yyyy-MM-dd HH:mm:ss" (space
    // separator) — LocalDateTime.parse(String) with no formatter expects
    // strict ISO-8601 (a literal 'T'), so it must be parsed with this
    // explicit pattern to match.
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TextView tvTotalIncome, tvTotalExpense, tvNetBalance;
    private RecyclerView rvTransactions;
    private SwipeRefreshLayout swipeRefresh;
    private TransactionAdapter adapter;
    private List<Transaction> transactions = new ArrayList<>();

    private EditText etSearch;
    private ImageButton btnFilter;
    private TextView chipDate, chipCategory, chipSubCategory, chipAmount;

    // Persists across searches/filter-dialog opens for this fragment instance.
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

        rvTransactions.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new TransactionAdapter(requireContext(), transactions, null, txn -> showEditDialog(txn));
        rvTransactions.setAdapter(adapter);

        currentFilter.setPageSize(Integer.MAX_VALUE);

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

        btnFilter.setOnClickListener(v -> openFilterDialog(0, false));
        chipDate.setOnClickListener(v -> openFilterDialog(0, true));
        chipCategory.setOnClickListener(v -> openFilterDialog(1, true));
        chipSubCategory.setOnClickListener(v -> openFilterDialog(2, true));
        chipAmount.setOnClickListener(v -> openFilterDialog(3, true));

        Button btnIncome = root.findViewById(R.id.btn_add_income);
        Button btnExpense = root.findViewById(R.id.btn_add_expense);
        Button btnSyncCloud = root.findViewById(R.id.btn_sync_cloud);
        Button btnFetchCloud = root.findViewById(R.id.btn_fetch_cloud);

        btnIncome.setOnClickListener(v -> showAddDialog(Transaction.Type.INCOME));
        btnExpense.setOnClickListener(v -> showAddDialog(Transaction.Type.EXPENSE));

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

    // Called externally (e.g. HomeActivity's sync button) to refresh the
    // list/totals in place without recreating the fragment.
    public void refreshData() {
        loadTransactions();
    }

    private void loadTransactions() {
        int bookId = AppConfig.get(requireContext()).getActiveBookId();
        TransactionDao dao = new TransactionDao(requireContext());

        currentFilter.setBookId(bookId);
        currentFilter.setType(null); // this screen always shows both income & expense
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

        // Update toolbar book label
        if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).updateBookLabel();
    }

    // tab: 0=Date, 1=Category, 2=Sub Category, 3=Amount.
    // singleField=true (chip tap) locks the dialog to just that tab;
    // singleField=false (filter icon tap) shows the full tabbed dialog.
    private void openFilterDialog(int tab, boolean singleField) {
        int bookId = AppConfig.get(requireContext()).getActiveBookId();
        new TransactionFilterDialog(requireContext(), bookId, currentFilter, tab, singleField, appliedFilter -> {
            // keep noteSearch box in sync with whatever the dialog produced
            // (dialog doesn't touch noteSearch, so this just re-applies our field)
            appliedFilter.setNoteSearch(currentFilter.getNoteSearch());
            currentFilter.setDateFrom(appliedFilter.getDateFrom());
            currentFilter.setDateTo(appliedFilter.getDateTo());
            currentFilter.setCategoryIds(appliedFilter.getCategoryIds());
            currentFilter.setSubCategoryIds(appliedFilter.getSubCategoryIds());
            currentFilter.setAmountOp1(appliedFilter.getAmountOp1());
            currentFilter.setAmount1(appliedFilter.getAmount1());
            currentFilter.setAmountOp2(appliedFilter.getAmountOp2());
            currentFilter.setAmount2(appliedFilter.getAmount2());
            loadTransactions();
        }).show();
    }

    // Updates each pill's label to reflect the active filter, e.g.
    // "Date: This Month", "Category (2)", "Amount: >=10". Falls back to the
    // plain field name when that field isn't filtered.
    private void refreshFilterChips() {
        // Date
        if (currentFilter.getDateFrom() == null && currentFilter.getDateTo() == null) {
            chipDate.setText("Date ▾");
        } else if (currentFilter.getDateFrom() != null && currentFilter.getDateFrom().equals(currentFilter.getDateTo())) {
            chipDate.setText("Date: " + currentFilter.getDateFrom() + " ▾");
        } else {
            chipDate.setText("Date: range ▾");
        }

        // Category
        int catCount = currentFilter.getCategoryIds() != null ? currentFilter.getCategoryIds().size() : 0;
        chipCategory.setText(catCount == 0 ? "Category ▾" : "Category (" + catCount + ") ▾");

        // Sub Category
        int subCount = currentFilter.getSubCategoryIds() != null ? currentFilter.getSubCategoryIds().size() : 0;
        chipSubCategory.setText(subCount == 0 ? "Sub Category ▾" : "Sub Category (" + subCount + ") ▾");

        // Amount
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
    }

    private void setChipActive(TextView chip, boolean active, int normalColor, int activeColor) {
        chip.setTextColor(active ? activeColor : normalColor);
        chip.setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private void showAddDialog(Transaction.Type type) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_add_transaction, null);
        builder.setView(dialogView);
        builder.setTitle("+ Add " + (type == Transaction.Type.INCOME ? "Income" : "Expense"));

        EditText etAmount = dialogView.findViewById(R.id.et_amount);
        EditText etNote = dialogView.findViewById(R.id.et_note);
        EditText etDate = dialogView.findViewById(R.id.et_date);
        Spinner spCategory = dialogView.findViewById(R.id.sp_category);
        Spinner spSubCat = dialogView.findViewById(R.id.sp_subcategory);

        // Set today
        etDate.setText(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));

        // Load categories (filter by type)
        List<Category> cats = loadCategories(type);
        ArrayAdapter<Category> catAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, cats);
        catAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCategory.setAdapter(catAdapter);

        // Load sub-categories
        List<SubCategory> subCats = loadSubCategories();
        ArrayAdapter<SubCategory> subAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, subCats);
        subAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSubCat.setAdapter(subAdapter);

        builder.setPositiveButton("Save", (dlg, which) -> {
            String amtStr = etAmount.getText().toString().trim();
            if (amtStr.isEmpty()) {
                Toast.makeText(getContext(), "Enter amount", Toast.LENGTH_SHORT).show();
                return;
            }
            Transaction t = new Transaction();
            t.setType(type);
            t.setAmount(new BigDecimal(amtStr));
            t.setNote(etNote.getText().toString().trim());
            t.setDateTime(LocalDateTime.parse(etDate.getText().toString().trim(), DATE_FMT));
            t.setBookId(AppConfig.get(requireContext()).getActiveBookId());

            if (!cats.isEmpty() && spCategory.getSelectedItem() != null) {
                Category cat = (Category) spCategory.getSelectedItem();
                t.setCategoryId(cat.getId());
            }
            if (!subCats.isEmpty() && spSubCat.getSelectedItem() != null) {
                SubCategory sub = (SubCategory) spSubCat.getSelectedItem();
                t.setSubCategoryId(sub.getId());
            }

            new TransactionDao(requireContext()).insert(t);
            Toast.makeText(getContext(), "Saved locally. Sync to upload.", Toast.LENGTH_SHORT).show();
            loadTransactions();
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showEditDialog(Transaction txn) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_add_transaction, null);
        builder.setView(dialogView);
        builder.setTitle("Edit Transaction");

        EditText etAmount = dialogView.findViewById(R.id.et_amount);
        EditText etNote = dialogView.findViewById(R.id.et_note);
        EditText etDate = dialogView.findViewById(R.id.et_date);
        Spinner spCategory = dialogView.findViewById(R.id.sp_category);
        Spinner spSubCat = dialogView.findViewById(R.id.sp_subcategory);

        etAmount.setText(txn.getAmount() != null ? txn.getAmount().toPlainString() : "");
        etNote.setText(txn.getNote());
        etDate.setText(txn.getDateTime() != null ? txn.getDateTime().format(DATE_FMT) : "");

        List<Category> cats = loadCategories(txn.getType());
        ArrayAdapter<Category> catAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, cats);
        catAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCategory.setAdapter(catAdapter);
        for (int i = 0; i < cats.size(); i++) {
            if (cats.get(i).getId() == txn.getCategoryId()) {
                spCategory.setSelection(i);
                break;
            }
        }

        List<SubCategory> subCats = loadSubCategories();
        ArrayAdapter<SubCategory> subAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, subCats);
        subAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSubCat.setAdapter(subAdapter);

        builder.setPositiveButton("Save", (dlg, which) -> {
            TransactionDao dao = new TransactionDao(requireContext());
            Transaction oldT = dao.findById(txn.getId());

            txn.setAmount(new BigDecimal(etAmount.getText().toString().trim()));
            txn.setNote(etNote.getText().toString().trim());
            txn.setDateTime(LocalDateTime.parse(etDate.getText().toString().trim(), DATE_FMT));
            if (!cats.isEmpty() && spCategory.getSelectedItem() != null)
                txn.setCategoryId(((Category) spCategory.getSelectedItem()).getId());

            dao.update(oldT, txn);
            Toast.makeText(getContext(), "Updated locally.", Toast.LENGTH_SHORT).show();
            loadTransactions();
        });
        builder.setNeutralButton("Delete", (dlg, which) -> new AlertDialog.Builder(requireContext()).setTitle("Delete?").setMessage("Delete this transaction?").setPositiveButton("Delete", (d2, w2) -> {
            new TransactionDao(requireContext()).delete(txn.getId());
            loadTransactions();
        }).setNegativeButton("Cancel", null).show());
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private List<Category> loadCategories(Transaction.Type type) {
        List<Category> list = new ArrayList<>();
        SQLiteDatabase db = LocalDatabase.get(requireContext()).getReadableDatabase();
        String typeStr = (type == Transaction.Type.INCOME) ? "INCOME" : "EXPENSE";
        Cursor c = db.rawQuery("SELECT id, name, type FROM categories WHERE type=?", new String[]{typeStr});
        while (c.moveToNext()) {
            list.add(new Category(c.getInt(0), c.getString(1), c.getString(2)));
        }
        c.close();
        return list;
    }

    private List<SubCategory> loadSubCategories() {
        List<SubCategory> list = new ArrayList<>();
        SQLiteDatabase db = LocalDatabase.get(requireContext()).getReadableDatabase();
        Cursor c = db.rawQuery("SELECT id, name, category_id FROM sub_categories", null);
        while (c.moveToNext()) {
            list.add(new SubCategory(c.getInt(0), c.getString(1), c.getInt(2), ""));
        }
        c.close();
        return list;
    }
}
