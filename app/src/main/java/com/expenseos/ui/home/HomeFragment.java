package com.expenseos.ui.home;

import android.app.AlertDialog;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
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

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_home, container, false);

        tvTotalIncome = root.findViewById(R.id.tv_total_income);
        tvTotalExpense = root.findViewById(R.id.tv_total_expense);
        tvNetBalance = root.findViewById(R.id.tv_net_balance);
        swipeRefresh = root.findViewById(R.id.swipe_refresh);
        rvTransactions = root.findViewById(R.id.rv_transactions);

        rvTransactions.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new TransactionAdapter(requireContext(), transactions, null, txn -> showEditDialog(txn));
        rvTransactions.setAdapter(adapter);

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
        List<Transaction> all = dao.findAll(null, 1, Integer.MAX_VALUE, bookId);

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
        adapter.notifyDataSetChanged();

        // Update toolbar book label
        if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).updateBookLabel();
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
//            t.setDateTime(etDate.getText().toString().trim());
//            String dt = t.getString(etDate.getText().toString().trim());
//            if (dt != null) {
            t.setDateTime(LocalDateTime.parse(etDate.getText().toString().trim(), DATE_FMT));
//            }
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
//            txn.setTxnDatetime(etDate.getText().toString().trim());
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