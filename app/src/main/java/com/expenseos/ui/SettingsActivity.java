package com.expenseos.ui;

import android.content.SharedPreferences;
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

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.dao.CategoryDao;
import com.expenseos.dao.SubCategoryDao;
import com.expenseos.model.Category;
import com.expenseos.model.SubCategory;

import java.util.List;

public class SettingsActivity extends AppCompatActivity {

    private SharedPreferences prefs;
    private int bookId;
    private CategoryDao catDao;
    private SubCategoryDao scDao;

    // Gmail config
    private EditText etGmailFrom, etGmailPass;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("expenseos_prefs", MODE_PRIVATE);
        bookId = prefs.getInt("active_book_id", 0);
        catDao = new CategoryDao(this);
        scDao = new SubCategoryDao(this);

        findViewById(R.id.btnBackSettings).setOnClickListener(v -> finish());

        setupTabs();
        setupGmailSection();
        loadCategoryTab();
    }

    // ── Tabs: Categories | Sub-Categories | Gmail ─────────
    private void setupTabs() {
        findViewById(R.id.btnTabCat).setOnClickListener(v -> switchTab(0));
        findViewById(R.id.btnTabSub).setOnClickListener(v -> switchTab(1));
        findViewById(R.id.btnTabGmail).setOnClickListener(v -> switchTab(2));
        switchTab(0); // default
    }

    private void switchTab(int tab) {
        // Hide all panels
        View catPanel = findViewById(R.id.panelCategories);
        View subPanel = findViewById(R.id.panelSubCategories);
        View gmailPanel = findViewById(R.id.panelGmail);
        catPanel.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        subPanel.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        gmailPanel.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);

        if (tab == 0) loadCategoryTab();
        if (tab == 1) loadSubCategoryTab();
    }

    // ── Category Tab ──────────────────────────────────────
    private void loadCategoryTab() {
        loadCategoryList("INCOME", R.id.rvIncomeCategories);
        loadCategoryList("EXPENSE", R.id.rvExpenseCategories);

        // Add Income Category
        setupAddCategoryRow(
                R.id.etNewIncomeCat, R.id.spIncomeCatScope, R.id.btnAddIncomeCat, "INCOME");

        // Add Expense Category
        setupAddCategoryRow(
                R.id.etNewExpenseCat, R.id.spExpenseCatScope, R.id.btnAddExpenseCat, "EXPENSE");
    }

    private void loadCategoryList(String type, int rvId) {
        List<Category> cats = catDao.findByType(type, bookId > 0 ? bookId : null);
        RecyclerView rv = findViewById(rvId);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new CategoryListAdapter(cats));
    }

    private void setupAddCategoryRow(int etId, int spId, int btnId, String type) {
        EditText et = findViewById(etId);
        Spinner sp = findViewById(spId);
        // Scope spinner: Common | This Book Only
        ArrayAdapter<String> adp = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                bookId > 0
                        ? new String[]{"Common (all books)", "This book only"}
                        : new String[]{"Common (all books)"});
        adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(adp);

        findViewById(btnId).setOnClickListener(v -> {
            String name = et.getText().toString().trim();
            if (name.isEmpty()) return;
            boolean isCustom = sp.getSelectedItemPosition() == 1 && bookId > 0;
            catDao.insert(name, type, isCustom ? bookId : null);
            et.setText("");
            loadCategoryList(type,
                    type.equals("INCOME") ? R.id.rvIncomeCategories : R.id.rvExpenseCategories);
            Toast.makeText(this, "Category added!", Toast.LENGTH_SHORT).show();
        });
    }

    // ── Sub-Category Tab ──────────────────────────────────
    private void loadSubCategoryTab() {
        List<SubCategory> subs = scDao.findAll();
        RecyclerView rv = findViewById(R.id.rvSubCategories);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new SubCatAdapter(subs));

        // Add sub-category
        EditText etSubName = findViewById(R.id.etNewSubCatName);
        Spinner spParent = findViewById(R.id.spSubCatParent);

        // All categories as parent options
        List<Category> allCats = catDao.findByType("INCOME", bookId > 0 ? bookId : null);
        allCats.addAll(catDao.findByType("EXPENSE", bookId > 0 ? bookId : null));
        ArrayAdapter<Category> catAdp = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, allCats);
        catAdp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spParent.setAdapter(catAdp);

        findViewById(R.id.btnAddSubCat).setOnClickListener(v -> {
            String name = etSubName.getText().toString().trim();
            if (name.isEmpty() || spParent.getSelectedItem() == null) return;
            int catId = ((Category) spParent.getSelectedItem()).getId();
            scDao.insert(name, catId);
            etSubName.setText("");
            loadSubCategoryTab();
            Toast.makeText(this, "Sub-category added!", Toast.LENGTH_SHORT).show();
        });
    }

    // ── Gmail Section ─────────────────────────────────────
    private void setupGmailSection() {
        etGmailFrom = findViewById(R.id.etGmailFrom);
        etGmailPass = findViewById(R.id.etGmailPass);
        etGmailFrom.setText(prefs.getString("gmail_from", ""));
        etGmailPass.setText(prefs.getString("gmail_pass", ""));

        findViewById(R.id.btnSaveGmail).setOnClickListener(v -> {
            prefs.edit()
                    .putString("gmail_from", etGmailFrom.getText().toString().trim())
                    .putString("gmail_pass", etGmailPass.getText().toString().trim())
                    .apply();
            Toast.makeText(this, "Gmail config saved!", Toast.LENGTH_SHORT).show();
        });
    }

    // ── Category list adapter ─────────────────────────────
    class CategoryListAdapter extends RecyclerView.Adapter<CategoryListAdapter.VH> {
        private final List<Category> list;

        CategoryListAdapter(List<Category> list) {
            this.list = list;
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvScope;
            Button btnDel;

            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tvCatName);
                tvScope = v.findViewById(R.id.tvCatScope);
                btnDel = v.findViewById(R.id.btnCatDel);
            }
        }

        @Override
        public VH onCreateViewHolder(ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_category, p, false));
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            Category c = list.get(pos);
            h.tvName.setText(c.getName());
            h.tvScope.setText(c.isCommon() ? "Common" : "Book only");
            h.tvScope.setTextColor(getColor(c.isCommon() ? R.color.primary : R.color.amber));

            h.btnDel.setOnClickListener(v ->
                    new AlertDialog.Builder(SettingsActivity.this)
                            .setTitle("Delete Category")
                            .setMessage("Delete \"" + c.getName() + "\"? " +
                                    "Transactions using it won't be affected.")
                            .setPositiveButton("Delete", (d, w) -> {
                                catDao.delete(c.getId());
                                list.remove(pos);
                                notifyItemRemoved(pos);
                            })
                            .setNegativeButton("Cancel", null)
                            .show());
        }

        @Override
        public int getItemCount() {
            return list.size();
        }
    }

    // ── Sub-category list adapter ─────────────────────────
    class SubCatAdapter extends RecyclerView.Adapter<SubCatAdapter.VH> {
        private final List<SubCategory> list;

        SubCatAdapter(List<SubCategory> list) {
            this.list = list;
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvName;
            Button btnDel;

            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tvSubCatName);
                btnDel = v.findViewById(R.id.btnSubCatDel);
            }
        }

        @Override
        public VH onCreateViewHolder(ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_subcategory, p, false));
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            SubCategory sc = list.get(pos);
            h.tvName.setText(sc.getName());
            h.btnDel.setOnClickListener(v ->
                    new AlertDialog.Builder(SettingsActivity.this)
                            .setTitle("Delete Sub-Category")
                            .setMessage("Delete \"" + sc.getName() + "\"?")
                            .setPositiveButton("Delete", (d, w) -> {
                                scDao.delete(sc.getId());
                                list.remove(pos);
                                notifyItemRemoved(pos);
                            })
                            .setNegativeButton("Cancel", null)
                            .show());
        }

        @Override
        public int getItemCount() {
            return list.size();
        }
    }
}