package com.expenseos.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.text.Editable;
import android.text.TextWatcher;
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
import com.expenseos.dao.ColumnDefinitionDao;
import com.expenseos.dao.SubCategoryDao;
import com.expenseos.model.Category;
import com.expenseos.model.ColumnDefinition;
import com.expenseos.model.SubCategory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Two launch modes, controlled by Intent extras:
 *
 *  GLOBAL (default — launched from the Cashbooks list, no book context yet):
 *    new Intent(this, SettingsActivity.class)
 *    -> only common (book_id IS NULL) categories/sub-categories are shown or
 *       addable. The "This book only" scope option never appears.
 *
 *  CASHBOOK-SCOPED (launched from inside a cashbook, e.g. HomeActivity or the
 *  transaction entry screen's gear icon):
 *    new Intent(this, SettingsActivity.class)
 *        .putExtra("bookScoped", true)
 *        .putExtra("bookId", activeBookId)   // must be > 0
 *        .putExtra("startTab", 2)            // optional: 0=Cat,1=Sub-Cat,2=Columns
 *    -> category "Add" dialog shows a scope dropdown: "Common (all books)"
 *       (default/first option) and "This book only" (second option).
 *       If bookId isn't a valid positive number, this activity shows an
 *       error and finishes immediately.
 *
 * Sub-categories aren't separately book-scoped in the schema (they belong
 * to a category, which is itself common or book-specific) — no scope
 * dropdown for them, but the Add dialog does let you pick which parent
 * category a new sub-category belongs to (this was previously easy to lose
 * track of, so the list now also shows the parent category name on each row).
 *
 * Custom columns (column_definitions) have no book_id column in the current
 * schema, so they're global-only regardless of launch mode.
 */
public class SettingsActivity extends AppCompatActivity {

    private boolean bookScoped;
    private int bookId; // 0 = common/global scope

    private CategoryDao catDao;
    private SubCategoryDao scDao;
    private ColumnDefinitionDao colDao;

    // ── Categories tab state ──
    private int catSubTab = 0; // 0=INCOME, 1=EXPENSE
    private String catSearch = "";
    private TextView tabCatIncome, tabCatExpense;
    private EditText etCatSearch;

    // ── Sub-Categories tab state ──
    private int subCatSubTab = 0; // 0=INCOME, 1=EXPENSE
    private String subCatSearch = "";
    private TextView tabSubIncome, tabSubExpense;
    private EditText etSubCatSearch;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_settings);

        catDao = new CategoryDao(this);
        scDao = new SubCategoryDao(this);
        colDao = new ColumnDefinitionDao(this);

        bookScoped = getIntent().getBooleanExtra("bookScoped", false);

        if (bookScoped) {
            bookId = getIntent().getIntExtra("bookId", -1);
            if (bookId <= 0) {
                Toast.makeText(this,
                        "No active cashbook found — can't open cashbook settings.",
                        Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        } else {
            bookId = 0;
        }

        TextView tvMode = findViewById(R.id.tvSettingsMode);
        tvMode.setText(bookScoped
                ? "📘 Cashbook Settings — applies to this book (or Common)"
                : "🌐 Global Settings — common categories only");

        findViewById(R.id.btnBackSettings).setOnClickListener(v -> finish());

        bindCategoryViews();
        bindSubCategoryViews();
        setupTabs();

        int startTab = getIntent().getIntExtra("startTab", 0);
        switchTab(startTab);
    }

    // ── Top-level Tabs: Categories | Sub-Categories | Columns ───
    private void setupTabs() {
        findViewById(R.id.btnTabCat).setOnClickListener(v -> switchTab(0));
        findViewById(R.id.btnTabSub).setOnClickListener(v -> switchTab(1));
        findViewById(R.id.btnTabColumns).setOnClickListener(v -> switchTab(2));
    }

    private void switchTab(int tab) {
        findViewById(R.id.panelCategories).setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        findViewById(R.id.panelSubCategories).setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        findViewById(R.id.panelColumns).setVisibility(tab == 2 ? View.VISIBLE : View.GONE);

        Button btnCat = findViewById(R.id.btnTabCat);
        Button btnSub = findViewById(R.id.btnTabSub);
        Button btnCol = findViewById(R.id.btnTabColumns);
        int activeColor = getColor(R.color.primary);
        int inactiveColor = getColor(R.color.text_muted);
        btnCat.setTextColor(tab == 0 ? activeColor : inactiveColor);
        btnSub.setTextColor(tab == 1 ? activeColor : inactiveColor);
        btnCol.setTextColor(tab == 2 ? activeColor : inactiveColor);

        if (tab == 0) loadCategoryList();
        if (tab == 1) loadSubCategoryList();
        if (tab == 2) loadColumnsTab();
    }

    // ══════════════════════════════════════════════════════
    // CATEGORIES TAB
    // ══════════════════════════════════════════════════════

    private void bindCategoryViews() {
        tabCatIncome = findViewById(R.id.tabCatIncome);
        tabCatExpense = findViewById(R.id.tabCatExpense);
        etCatSearch = findViewById(R.id.etCatSearch);

        tabCatIncome.setOnClickListener(v -> setCatSubTab(0));
        tabCatExpense.setOnClickListener(v -> setCatSubTab(1));

        etCatSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                catSearch = e.toString().trim();
                loadCategoryList();
            }
        });

        findViewById(R.id.btnCatAdd).setOnClickListener(v -> showAddCategoryDialog());
    }

    private void setCatSubTab(int tab) {
        catSubTab = tab;
        tabCatIncome.setTextColor(getColor(tab == 0 ? R.color.green : R.color.text_muted));
        tabCatIncome.setTypeface(null, tab == 0 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        tabCatExpense.setTextColor(getColor(tab == 1 ? R.color.red : R.color.text_muted));
        tabCatExpense.setTypeface(null, tab == 1 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        etCatSearch.setHint("Search " + (tab == 0 ? "income" : "expense") + " category");
        etCatSearch.setText("");
        loadCategoryList();
    }

    private void loadCategoryList() {
        String type = catSubTab == 0 ? "INCOME" : "EXPENSE";
        List<Category> all = catDao.findByType(type, bookScoped && bookId > 0 ? bookId : null);

        List<Category> filtered = new ArrayList<>();
        for (Category c : all) {
            if (catSearch.isEmpty() || c.getName().toLowerCase(Locale.ROOT).contains(catSearch.toLowerCase(Locale.ROOT)))
                filtered.add(c);
        }

        RecyclerView rv = findViewById(R.id.rvCategoryList);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new CategoryListAdapter(filtered));
    }

    private void showAddCategoryDialog() {
        String type = catSubTab == 0 ? "INCOME" : "EXPENSE";

        android.widget.LinearLayout form = new android.widget.LinearLayout(this);
        form.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad, pad, pad);

        EditText etName = new EditText(this);
        etName.setHint((catSubTab == 0 ? "Income" : "Expense") + " category name");
        form.addView(etName);

        Spinner spScope = null;
        if (bookScoped) {
            spScope = new Spinner(this);
            ArrayAdapter<String> adp = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item,
                    new String[]{"Common (all books)", "This book only"});
            adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spScope.setAdapter(adp);
            form.addView(spScope);
        }

        Spinner finalSpScope = spScope;
        new AlertDialog.Builder(this)
                .setTitle("Add " + (catSubTab == 0 ? "Income" : "Expense") + " Category")
                .setView(form)
                .setPositiveButton("Save", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) return;
                    boolean thisBookOnly = finalSpScope != null && finalSpScope.getSelectedItemPosition() == 1;
                    catDao.insert(name, type, thisBookOnly ? bookId : null);
                    loadCategoryList();
                    Toast.makeText(this, "Category added!", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    class CategoryListAdapter extends RecyclerView.Adapter<CategoryListAdapter.VH> {
        private final List<Category> list;

        CategoryListAdapter(List<Category> list) {
            this.list = list;
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvScope;
            View btnEdit;
            Button btnDel;

            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tvCatName);
                tvScope = v.findViewById(R.id.tvCatScope);
                btnEdit = v.findViewById(R.id.btnCatEdit);
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

            h.btnEdit.setOnClickListener(v ->
                    showRenameDialog("Rename Category", c.getName(),
                            SettingsActivity.this::loadCategoryList,
                            newName -> catDao.update(c.getId(), newName)));

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

    // ══════════════════════════════════════════════════════
    // SUB-CATEGORIES TAB
    // ══════════════════════════════════════════════════════

    private void bindSubCategoryViews() {
        tabSubIncome = findViewById(R.id.tabSubIncome);
        tabSubExpense = findViewById(R.id.tabSubExpense);
        etSubCatSearch = findViewById(R.id.etSubCatSearch);

        tabSubIncome.setOnClickListener(v -> setSubCatSubTab(0));
        tabSubExpense.setOnClickListener(v -> setSubCatSubTab(1));

        etSubCatSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                subCatSearch = e.toString().trim();
                loadSubCategoryList();
            }
        });

        findViewById(R.id.btnSubCatAdd).setOnClickListener(v -> showAddSubCategoryDialog());
    }

    private void setSubCatSubTab(int tab) {
        subCatSubTab = tab;
        tabSubIncome.setTextColor(getColor(tab == 0 ? R.color.green : R.color.text_muted));
        tabSubIncome.setTypeface(null, tab == 0 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        tabSubExpense.setTextColor(getColor(tab == 1 ? R.color.red : R.color.text_muted));
        tabSubExpense.setTypeface(null, tab == 1 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        etSubCatSearch.setHint("Search " + (tab == 0 ? "income" : "expense") + " sub category");
        etSubCatSearch.setText("");
        loadSubCategoryList();
    }

    /** Categories of the active sub-tab's type, used both for the list's
     * category-name lookup and for the Add dialog's parent-category spinner. */
    private List<Category> categoriesForSubCatTab() {
        String type = subCatSubTab == 0 ? "INCOME" : "EXPENSE";
        return catDao.findByType(type, bookScoped && bookId > 0 ? bookId : null);
    }

    private void loadSubCategoryList() {
        List<Category> cats = categoriesForSubCatTab();
        Map<Integer, String> catNameById = new HashMap<>();
        for (Category c : cats) catNameById.put(c.getId(), c.getName());

        List<SubCategory> matched = new ArrayList<>();
        for (Category c : cats) {
            for (SubCategory sc : scDao.findByCategoryId(c.getId())) {
                if (subCatSearch.isEmpty() ||
                        sc.getName().toLowerCase(Locale.ROOT).contains(subCatSearch.toLowerCase(Locale.ROOT)))
                    matched.add(sc);
            }
        }

        RecyclerView rv = findViewById(R.id.rvSubCategoryList);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new SubCatAdapter(matched, catNameById));
    }

    private void showAddSubCategoryDialog() {
        List<Category> cats = categoriesForSubCatTab();
        if (cats.isEmpty()) {
            Toast.makeText(this,
                    "Add a " + (subCatSubTab == 0 ? "income" : "expense") + " category first.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        android.widget.LinearLayout form = new android.widget.LinearLayout(this);
        form.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad, pad, pad);

        TextView lbl1 = new TextView(this);
        lbl1.setText("Category");
        lbl1.setTextSize(11);
        form.addView(lbl1);

        Spinner spParent = new Spinner(this);
        ArrayAdapter<Category> catAdp = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, cats);
        catAdp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spParent.setAdapter(catAdp);
        form.addView(spParent);

        TextView lbl2 = new TextView(this);
        lbl2.setText("Sub-category name");
        lbl2.setTextSize(11);
        lbl2.setPadding(0, pad, 0, 0);
        form.addView(lbl2);

        EditText etName = new EditText(this);
        etName.setHint("e.g. Dinner, Netflix");
        form.addView(etName);

        new AlertDialog.Builder(this)
                .setTitle("Add Sub-Category")
                .setView(form)
                .setPositiveButton("Save", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty() || spParent.getSelectedItem() == null) return;
                    int catId = ((Category) spParent.getSelectedItem()).getId();
                    scDao.insert(name, catId);
                    loadSubCategoryList();
                    Toast.makeText(this, "Sub-category added!", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    class SubCatAdapter extends RecyclerView.Adapter<SubCatAdapter.VH> {
        private final List<SubCategory> list;
        private final Map<Integer, String> catNameById;

        SubCatAdapter(List<SubCategory> list, Map<Integer, String> catNameById) {
            this.list = list;
            this.catNameById = catNameById;
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvCategory, tvName;
            View btnEdit;
            Button btnDel;

            VH(View v) {
                super(v);
                tvCategory = v.findViewById(R.id.tvSubCatCategory);
                tvName = v.findViewById(R.id.tvSubCatName);
                btnEdit = v.findViewById(R.id.btnSubCatEdit);
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
            h.tvCategory.setText(catNameById.getOrDefault(sc.getParentCategoryId(), "?"));
            h.tvName.setText(sc.getName());

            h.btnEdit.setOnClickListener(v ->
                    showRenameDialog("Rename Sub-Category", sc.getName(),
                            SettingsActivity.this::loadSubCategoryList,
                            newName -> scDao.update(sc.getId(), newName)));

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

    // ══════════════════════════════════════════════════════
    // CUSTOM COLUMNS TAB (global-only, see class-level note)
    // ══════════════════════════════════════════════════════

    private void loadColumnsTab() {
        loadColumnList("INCOME", R.id.rvIncomeColumns);
        loadColumnList("EXPENSE", R.id.rvExpenseColumns);

        findViewById(R.id.btnAddIncomeCol).setOnClickListener(v -> addColumn(R.id.etNewIncomeCol, "INCOME"));
        findViewById(R.id.btnAddExpenseCol).setOnClickListener(v -> addColumn(R.id.etNewExpenseCol, "EXPENSE"));
    }

    private void loadColumnList(String type, int rvId) {
        List<ColumnDefinition> cols = colDao.findByType(type);
        RecyclerView rv = findViewById(rvId);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new ColumnAdapter(cols));
    }

    private void addColumn(int etId, String type) {
        EditText et = findViewById(etId);
        String name = et.getText().toString().trim();
        if (name.isEmpty()) return;
        colDao.insert(name, type);
        et.setText("");
        loadColumnList(type, type.equals("INCOME") ? R.id.rvIncomeColumns : R.id.rvExpenseColumns);
        Toast.makeText(this, "Column added!", Toast.LENGTH_SHORT).show();
    }

    class ColumnAdapter extends RecyclerView.Adapter<ColumnAdapter.VH> {
        private final List<ColumnDefinition> list;

        ColumnAdapter(List<ColumnDefinition> list) {
            this.list = list;
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvName;
            View btnEdit;
            Button btnDel;

            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tvColName);
                btnEdit = v.findViewById(R.id.btnColEdit);
                btnDel = v.findViewById(R.id.btnColDel);
            }
        }

        @Override
        public VH onCreateViewHolder(ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_column_definition, p, false));
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            ColumnDefinition col = list.get(pos);
            h.tvName.setText(col.getColName());

            h.btnEdit.setOnClickListener(v ->
                    showRenameDialog("Rename Column", col.getColName(),
                            SettingsActivity.this::loadColumnsTab,
                            newName -> colDao.update(col.getId(), newName)));

            h.btnDel.setOnClickListener(v ->
                    new AlertDialog.Builder(SettingsActivity.this)
                            .setTitle("Delete Column")
                            .setMessage("Delete \"" + col.getColName() + "\"? " +
                                    "Existing values for it will also be removed.")
                            .setPositiveButton("Delete", (d, w) -> {
                                colDao.delete(col.getId());
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

    // ── Rename helper (shared by category/sub-category/column edit icon) ──
    private void showRenameDialog(String title, String currentName, Runnable onRenamed, java.util.function.Consumer<String> doRename) {
        EditText et = new EditText(this);
        et.setText(currentName);
        et.setSelection(currentName.length());
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    String newName = et.getText().toString().trim();
                    if (newName.isEmpty()) return;
                    doRename.accept(newName);
                    onRenamed.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}