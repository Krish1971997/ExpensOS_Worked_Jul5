package com.expenseos.ui;

import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
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

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.dao.CategoryDao;
import com.expenseos.dao.ColumnDefinitionDao;
import com.expenseos.dao.KeywordMappingDao;
import com.expenseos.dao.PaymentTypeDao;
import com.expenseos.dao.SubCategoryDao;
import com.expenseos.db.LocalDB;
import com.expenseos.model.Category;
import com.expenseos.model.ColumnDefinition;
import com.expenseos.model.KeywordMapping;
import com.expenseos.model.SubCategory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Two launch modes, controlled by Intent extras:
 * <p>
 * GLOBAL (default — launched from the Cashbooks list, no book context yet):
 * new Intent(this, SettingsActivity.class)
 * -> only common (book_id IS NULL) categories/sub-categories are shown or
 * addable. The "This book only" scope option never appears.
 * <p>
 * CASHBOOK-SCOPED (launched from inside a cashbook, e.g. HomeActivity or the
 * transaction entry screen's gear icon):
 * new Intent(this, SettingsActivity.class)
 * .putExtra("bookScoped", true)
 * .putExtra("bookId", activeBookId)   // must be > 0
 * .putExtra("startTab", 2)            // optional: 0=Cat,1=Sub-Cat,2=Columns
 * -> category "Add" dialog shows a scope dropdown: "Common (all books)"
 * (default/first option) and "This book only" (second option).
 * If bookId isn't a valid positive number, this activity shows an
 * error and finishes immediately.
 * <p>
 * Sub-categories aren't separately book-scoped in the schema (they belong
 * to a category, which is itself common or book-specific) — no scope
 * dropdown for them, but the Add dialog does let you pick which parent
 * category a new sub-category belongs to (this was previously easy to lose
 * track of, so the list now also shows the parent category name on each row).
 * <p>
 * Custom columns (column_definitions) have no book_id column in the current
 * schema, so they're global-only regardless of launch mode.
 */
public class SettingsActivity extends AppCompatActivity {

    private boolean bookScoped;
    private int bookId; // 0 = common/global scope

    private CategoryDao catDao;
    private SubCategoryDao scDao;
    private ColumnDefinitionDao colDao;
    private PaymentTypeDao payDao;
    private KeywordMappingDao kwDao;

    // ── Categories tab state ──
    private int catSubTab = 0; // 0=INCOME, 1=EXPENSE
    private String catSearch = "";
    private String paySearch = "";
    private boolean paySearchWired = false; // guards against stacking duplicate TextWatchers
    private TextView tabCatIncome, tabCatExpense;
    private EditText etCatSearch;

    // ── Sub-Categories tab state ──
// ── Sub-Categories tab state ──
    private int subCatSubTab = 0; // 0=INCOME, 1=EXPENSE
    private String subCatSearch = "";
    private TextView tabSubIncome, tabSubExpense;
    private EditText etSubCatSearch;

    // ── Keywords tab state (auto-suggest category/sub-category from description) ──
    private String kwSearch = "";
    private int titleTapCount = 0;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_settings);

        catDao = new CategoryDao(this);
        scDao = new SubCategoryDao(this);
        colDao = new ColumnDefinitionDao(this);
        payDao = new PaymentTypeDao(this);
        kwDao = new KeywordMappingDao(this);

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

        // Hidden SQL Console trigger (Tap 7 times)
        tvMode.setOnClickListener(v -> {
            titleTapCount++;
            if (titleTapCount >= 7) {
                titleTapCount = 0;
                Toast.makeText(this, "Opening SQL Console...", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(this, SqlConsoleActivity.class));
            }
        });

        findViewById(R.id.btnBackSettings).setOnClickListener(v -> finish());

        bindCategoryViews();
        bindSubCategoryViews();
        bindKeywordViews();
        setupTabs();

        int startTab = getIntent().getIntExtra("startTab", 0);
        switchTab(startTab);
    }

    // ── Top-level Tabs: Categories | Sub-Categories | Columns ───
    private void setupTabs() {
        findViewById(R.id.btnTabCat).setOnClickListener(v -> switchTab(0));
        findViewById(R.id.btnTabSub).setOnClickListener(v -> switchTab(1));
        findViewById(R.id.btnTabColumns).setOnClickListener(v -> switchTab(2));
        findViewById(R.id.btnTabPaymentTypes).setOnClickListener(v -> switchTab(3));
        findViewById(R.id.btnTabKeywords).setOnClickListener(v -> switchTab(4));
    }

    private void switchTab(int tab) {
        findViewById(R.id.panelCategories).setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        findViewById(R.id.panelSubCategories).setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        findViewById(R.id.panelColumns).setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        findViewById(R.id.panelPaymentTypes).setVisibility(tab == 3 ? View.VISIBLE : View.GONE);
        findViewById(R.id.panelKeywords).setVisibility(tab == 4 ? View.VISIBLE : View.GONE);

        Button btnCat = findViewById(R.id.btnTabCat);
        Button btnSub = findViewById(R.id.btnTabSub);
        Button btnCol = findViewById(R.id.btnTabColumns);
        Button btnPay = findViewById(R.id.btnTabPaymentTypes);
        Button btnKw = findViewById(R.id.btnTabKeywords);

        int activeColor = getColor(R.color.primary);
        int inactiveColor = getColor(R.color.text_muted);
        btnCat.setTextColor(tab == 0 ? activeColor : inactiveColor);
        btnCat.setBackgroundResource(tab == 0 ? R.drawable.bg_tab_active : android.R.color.transparent);
        btnSub.setTextColor(tab == 1 ? activeColor : inactiveColor);
        btnSub.setBackgroundResource(tab == 1 ? R.drawable.bg_tab_active : android.R.color.transparent);
        btnCol.setTextColor(tab == 2 ? activeColor : inactiveColor);
        btnCol.setBackgroundResource(tab == 2 ? R.drawable.bg_tab_active : android.R.color.transparent);
        btnPay.setTextColor(tab == 3 ? activeColor : inactiveColor);
        btnPay.setBackgroundResource(tab == 3 ? R.drawable.bg_tab_active : android.R.color.transparent);
        btnKw.setTextColor(tab == 4 ? activeColor : inactiveColor);
        btnKw.setBackgroundResource(tab == 4 ? R.drawable.bg_tab_active : android.R.color.transparent);

        if (tab == 0) loadCategoryList();
        if (tab == 1) loadSubCategoryList();
        if (tab == 2) loadColumnsTab();
        if (tab == 3) loadPaymentTypesTab();
        if (tab == 4) loadKeywordsTab();
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
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                catSearch = e.toString().trim();
                loadCategoryList();
            }
        });

        findViewById(R.id.btnCatAdd).setOnClickListener(v -> showAddCategoryDialog());
    }

    private void setCatSubTab(int tab) {
        catSubTab = tab;
        tabCatIncome.setTextColor(getColor(tab == 0 ? R.color.green : R.color.text_muted));
        tabCatIncome.setBackgroundResource(tab == 0 ? R.drawable.bg_tab_active : android.R.color.transparent);
        tabCatExpense.setTextColor(getColor(tab == 1 ? R.color.red : R.color.text_muted));
        tabCatExpense.setBackgroundResource(tab == 1 ? R.drawable.bg_tab_active : android.R.color.transparent);
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
            // Default to "This book only" here — adding a category from a
            // specific cashbook's Settings screen means that's almost always
            // the intent, and leaving "Common" as the silent default is what
            // caused categories to accidentally end up global.
            spScope.setSelection(1);
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

    // Long-press on any list row's name shows its raw DB id via a Toast.
    // Kept out of the normal UI — ids aren't meant to be visible day-to-day,
    // this is just a quick peek for debugging/support when someone needs it.
    private void attachIdPeek(View target, String label, int id) {
        attachIdPeek(target, label, "ID: " + id);
    }

    private void attachIdPeek(View target, String label, String idInfo) {
        target.setOnLongClickListener(v -> {
            Toast.makeText(SettingsActivity.this, label + " → " + idInfo, Toast.LENGTH_SHORT).show();
            return true;
        });
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
            attachIdPeek(h.tvName, c.getName(), c.getId());

            // Long-press the name to peek the category ID — kept off the
            // normal UI on purpose; user shouldn't see raw ids day-to-day,
            // but devs/support need a quick way to check it when debugging.
            h.tvName.setOnLongClickListener(v -> {
                Toast.makeText(SettingsActivity.this,
                        c.getName() + " → ID: " + c.getId(),
                        Toast.LENGTH_SHORT).show();
                return true;
            });

            h.btnEdit.setOnClickListener(v -> showEditCategoryDialog(c));

            h.btnDel.setOnClickListener(v ->
                    new AlertDialog.Builder(SettingsActivity.this)
                            .setTitle("Delete Payment Type")
                            .setMessage("Delete \"" + c.getName() + "\"?")
                            .setPositiveButton("Delete", (d, w) -> {
                                payDao.delete(c.getId());
                                int idx = list.indexOf(c);
                                if (idx >= 0) {
                                    list.remove(idx);
                                    notifyItemRemoved(idx);
                                }
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
    // KEYWORDS TAB — description keyword -> Category/Sub-category,
    // used by TransactionEntryActivity to auto-pick while typing the note.
    // ══════════════════════════════════════════════════════

    private void bindKeywordViews() {
        EditText etKwSearch = findViewById(R.id.etKwSearch);
        etKwSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                kwSearch = e.toString().trim();
                loadKeywordsTab();
            }
        });
        findViewById(R.id.btnKwAdd).setOnClickListener(v -> showKeywordDialog(null));
    }

    // NEW
    private void loadKeywordsTab() {
        List<KeywordMapping> all = kwDao.findAll(bookScoped && bookId > 0 ? bookId : null);

        // Case-2 dupes only: same keyword+type but different category/sub-category
        // pair. Grouping by keyword+type+category+subcategory means Case-1
        // (chocobar/ice cream/ice all -> Snacks/Ice) never gets flagged.
        Map<String, Integer> keywordTypeCount = new HashMap<>();
        Map<String, Integer> keywordTypeTargetCount = new HashMap<>();
        for (KeywordMapping k : all) {
            String kwKey = k.getKeyword().toLowerCase(Locale.ROOT) + "|" + k.getType();
            String targetKey = kwKey + "|" + k.getCategoryId() + "|" + k.getSubCategoryId();
            keywordTypeCount.put(kwKey, keywordTypeCount.getOrDefault(kwKey, 0) + 1);
            keywordTypeTargetCount.put(targetKey, keywordTypeTargetCount.getOrDefault(targetKey, 0) + 1);
        }
        Map<String, Boolean> conflict = new HashMap<>();
        for (KeywordMapping k : all) {
            String kwKey = k.getKeyword().toLowerCase(Locale.ROOT) + "|" + k.getType();
            String targetKey = kwKey + "|" + k.getCategoryId() + "|" + k.getSubCategoryId();
            // conflict = same keyword appears with more distinct targets than just this one
            boolean hasConflict = keywordTypeCount.get(kwKey) > keywordTypeTargetCount.get(targetKey);
            conflict.put(kwKey, conflict.getOrDefault(kwKey, false) || hasConflict);
        }

        List<KeywordMapping> filtered = new ArrayList<>();
        for (KeywordMapping k : all) {
            if (kwSearch.isEmpty() || k.getKeyword().toLowerCase(Locale.ROOT).contains(kwSearch.toLowerCase(Locale.ROOT)))
                filtered.add(k);
        }
        RecyclerView rv = findViewById(R.id.rvKeywordList);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new KeywordAdapter(filtered, conflict));
    }

    // existing == null -> Add, else Edit. Combined so the category/sub-category
    // cascade logic isn't duplicated between the two flows. Scope (Common/This
    // book only) is only offered on Add — changing scope after creation isn't
    // supported here, same as CategoryDao's separate updateScope() approach.
    // NEW (full method)
    private void showKeywordDialog(KeywordMapping existing) {
        android.widget.LinearLayout form = new android.widget.LinearLayout(this);
        form.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad, pad, pad);

        EditText etKeyword = new EditText(this);
        etKeyword.setHint("Keyword, e.g. lunch, uber, netflix");
        if (existing != null) {
            etKeyword.setText(existing.getKeyword());
            etKeyword.setSelection(existing.getKeyword().length());
        }
        form.addView(etKeyword);

        TextView lblType = new TextView(this);
        lblType.setText("Type");
        lblType.setTextSize(11);
        lblType.setPadding(0, pad, 0, 0);
        form.addView(lblType);

        Spinner spType = new Spinner(this);
        ArrayAdapter<String> typeAdp = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[]{"Expense", "Income"});
        typeAdp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spType.setAdapter(typeAdp);
        if (existing != null) spType.setSelection(existing.getType().equals("INCOME") ? 1 : 0);
        form.addView(spType);

        TextView lblCat = new TextView(this);
        lblCat.setText("Category");
        lblCat.setTextSize(11);
        lblCat.setPadding(0, pad, 0, 0);
        form.addView(lblCat);

        Spinner spCat = new Spinner(this);
        form.addView(spCat);

        TextView lblSub = new TextView(this);
        lblSub.setText("Sub-Category (optional)");
        lblSub.setTextSize(11);
        lblSub.setPadding(0, pad, 0, 0);
        form.addView(lblSub);

        Spinner spSub = new Spinner(this);
        form.addView(spSub);

        // NEW — reference to the scope spinner, filled in once it's built below,
        // so the category listener can reach it.
        final Spinner[] spScopeRef = new Spinner[1];

        Runnable[] refreshSubRef = new Runnable[1];
        // Category list always starts with a "Select Category" placeholder —
        // for Add it's the real default (nothing pre-picked); for Edit it's
        // skipped straight past since we select the saved category below.
        Runnable refreshCat = () -> {
            String type = spType.getSelectedItemPosition() == 0 ? "EXPENSE" : "INCOME";
            List<Category> realCats = catDao.findByType(type, bookScoped && bookId > 0 ? bookId : null);
            List<Category> withPlaceholder = new ArrayList<>();
            withPlaceholder.add(new Category(0, "Select Category", type, null));
            withPlaceholder.addAll(realCats);

            ArrayAdapter<Category> catAdp = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, withPlaceholder);
            catAdp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spCat.setAdapter(catAdp);

            if (existing != null) {
                for (int i = 0; i < realCats.size(); i++) {
                    if (realCats.get(i).getId() == existing.getCategoryId()) {
                        spCat.setSelection(i + 1); // +1 for the placeholder row
                        break;
                    }
                }
            } else {
                spCat.setSelection(0); // stays on "Select Category"
            }
            refreshSubRef[0].run();
        };
        // Sub-category only loads once a real category is picked (position > 0)
        // — never queries/loads against the placeholder.
        refreshSubRef[0] = () -> {
            List<SubCategory> subs = new ArrayList<>();
            subs.add(new SubCategory(0, "None", 0));
            if (spCat.getSelectedItemPosition() > 0) {
                subs.addAll(scDao.findByCategoryId(((Category) spCat.getSelectedItem()).getId()));
            }
            ArrayAdapter<SubCategory> subAdp = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, subs);
            subAdp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spSub.setAdapter(subAdp);
            if (existing != null && existing.getSubCategoryId() != null) {
                for (int i = 0; i < subs.size(); i++) {
                    if (subs.get(i).getId() == existing.getSubCategoryId()) {
                        spSub.setSelection(i);
                        break;
                    }
                }
            }
        };
        refreshCat.run();

        spType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                refreshCat.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        spCat.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                refreshSubRef[0].run();
                enforceScopeForCategory(spCat, spScopeRef[0]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });

        // Scope — shown for both Add and Edit now, so a wrong pick can be
        // corrected later instead of forcing a delete + re-add.
        Spinner spScope = null;
        TextView tvScopeLocked = null;
        if (bookScoped) {
            TextView lblScope = new TextView(this);
            lblScope.setText("Cashbook Scope");
            lblScope.setTextSize(11);
            lblScope.setPadding(0, pad, 0, 0);
            form.addView(lblScope);

            spScope = new Spinner(this);
            LinearLayout.LayoutParams scopeLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            scopeLp.topMargin = (int) (4 * getResources().getDisplayMetrics().density);
            spScope.setLayoutParams(scopeLp);
            ArrayAdapter<String> scopeAdp = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item,
                    new String[]{"Common (all books)", "This book only"});
            scopeAdp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spScope.setAdapter(scopeAdp);
            spScope.setSelection(existing != null ? (existing.isCommon() ? 0 : 1) : 0); // default: Common
            form.addView(spScope);

            tvScopeLocked = new TextView(this);
            tvScopeLocked.setTextSize(10);
            tvScopeLocked.setTextColor(getColor(R.color.amber));
            tvScopeLocked.setPadding(0, (int) (2 * getResources().getDisplayMetrics().density), 0, 0);
            tvScopeLocked.setVisibility(View.GONE);
            form.addView(tvScopeLocked);

            spScopeRef[0] = spScope;
            enforceScopeForCategory(spCat, spScope, tvScopeLocked); // in case Edit pre-selected a book-only category
        }

        Spinner finalSpScope = spScope;
        TextView finalTvScopeLocked = tvScopeLocked;

        new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Add Keyword Mapping" : "Edit Keyword Mapping")
                .setView(form)
                .setPositiveButton("Save", (d, w) -> {
                    String keyword = etKeyword.getText().toString().trim();
                    if (keyword.isEmpty() || spCat.getSelectedItemPosition() == 0) {
                        Toast.makeText(this, "Pick a keyword and a category", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String type = spType.getSelectedItemPosition() == 0 ? "EXPENSE" : "INCOME";

                    Category selectedCat = (Category) spCat.getSelectedItem();
                    boolean catIsBookSpecific = selectedCat.getBookId() != null;
                    boolean scopeIsCommon = finalSpScope == null || finalSpScope.getSelectedItemPosition() == 0;
                    if (catIsBookSpecific && scopeIsCommon) {
                        // Safety net — UI already locks this, but guard the save too.
                        Toast.makeText(this, "\"" + selectedCat.getName() +
                                "\" is a this-book-only category — this mapping can't be Common.", Toast.LENGTH_LONG).show();
                        return;
                    }

                    List<KeywordMapping> dupes = kwDao.findByKeyword(keyword, type,
                            bookScoped && bookId > 0 ? bookId : null,
                            existing != null ? existing.getId() : null);
                    if (!dupes.isEmpty()) {
                        Toast.makeText(this, "⚠ \"" + keyword + "\" already has " + dupes.size() +
                                        " mapping(s) saved. Edit or delete the existing one instead of adding another.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }

                    int catId = ((Category) spCat.getSelectedItem()).getId();
                    SubCategory sub = (SubCategory) spSub.getSelectedItem();
                    Integer subId = (sub != null && sub.getId() > 0) ? sub.getId() : null;
                    boolean thisBookOnly = finalSpScope != null && finalSpScope.getSelectedItemPosition() == 1;
                    Integer scopeBookId = thisBookOnly ? bookId : null;

                    if (existing == null) {
                        kwDao.insert(keyword, type, catId, subId, scopeBookId);
                        Toast.makeText(this, "Keyword mapping added!", Toast.LENGTH_SHORT).show();
                    } else {
                        kwDao.update(existing.getId(), keyword, catId, subId, scopeBookId);
                        Toast.makeText(this, "Keyword mapping updated!", Toast.LENGTH_SHORT).show();
                    }
                    loadKeywordsTab();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // enforceScopeForCategory() — puthu helper method add பண்ணுங்க (showKeywordDialog()-க்கு கீழே)
    // A keyword mapping can't be "Common" while its target category is
    // book-specific — a common mapping fires from every book, and the
    // category simply won't exist in the others. Lock the scope spinner to
    // "This book only" (and explain why) whenever that's the case.
    private void enforceScopeForCategory(Spinner spCat, Spinner spScope) {
        enforceScopeForCategory(spCat, spScope, null);
    }

    private void enforceScopeForCategory(Spinner spCat, Spinner spScope, TextView tvLocked) {
        if (spScope == null || spCat.getSelectedItemPosition() <= 0) return;
        Category selected = (Category) spCat.getSelectedItem();
        boolean bookSpecific = selected != null && selected.getBookId() != null;
        spScope.setEnabled(!bookSpecific);
        if (bookSpecific) spScope.setSelection(1); // This book only
        if (tvLocked != null) {
            tvLocked.setVisibility(bookSpecific ? View.VISIBLE : View.GONE);
            tvLocked.setText(bookSpecific
                    ? "⚠ \"" + selected.getName() + "\" is a this-book-only category — scope locked to This book only."
                    : "");
        }
    }

    // NEW
    class KeywordAdapter extends RecyclerView.Adapter<KeywordAdapter.VH> {
        private final List<KeywordMapping> list;
        private final Map<String, Boolean> conflict;

        KeywordAdapter(List<KeywordMapping> list, Map<String, Boolean> conflict) {
            this.list = list;
            this.conflict = conflict;
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvKeyword, tvTarget, tvScope;
            ImageButton btnEdit;
            Button btnDel;

            VH(View v) {
                super(v);
                tvKeyword = v.findViewById(R.id.tvKwKeyword);
                tvTarget = v.findViewById(R.id.tvKwTarget);
                tvScope = v.findViewById(R.id.tvKwScope);
                btnEdit = v.findViewById(R.id.btnKwEdit);
                btnDel = v.findViewById(R.id.btnKwDel);
            }
        }

        @Override
        public VH onCreateViewHolder(ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_keyword_mapping, p, false));
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            KeywordMapping k = list.get(pos);
            h.tvKeyword.setText("\"" + k.getKeyword() + "\"");
            String target = k.getCategoryName()
                    + (k.getSubCategoryName() != null ? " ▸ " + k.getSubCategoryName() : "")
                    + "  (" + (k.getType().equals("INCOME") ? "Income" : "Expense") + ")";
// NEW
            String kwKey = k.getKeyword().toLowerCase(Locale.ROOT) + "|" + k.getType();
            if (Boolean.TRUE.equals(conflict.get(kwKey))) {
                h.tvTarget.setText("→ " + target + "\n⚠ \"" + k.getKeyword() + "\" maps to more than one category/sub-category — remove the extra mapping");
                h.tvTarget.setTextColor(getColor(R.color.red));
            } else {
                h.tvTarget.setText("→ " + target);
                h.tvTarget.setTextColor(getColor(R.color.text_muted));
            }
            h.tvScope.setText(k.isCommon() ? "Common" : "Book only");
            h.tvScope.setTextColor(getColor(k.isCommon() ? R.color.primary : R.color.amber));
            attachIdPeek(h.tvKeyword, k.getKeyword(), k.getId());

            h.btnEdit.setOnClickListener(v -> showKeywordDialog(k));

            h.btnDel.setOnClickListener(v ->
                    new AlertDialog.Builder(SettingsActivity.this)
                            .setTitle("Delete Keyword")
                            .setMessage("Delete \"" + k.getKeyword() + "\"? Already-saved transactions won't be affected.")
                            .setPositiveButton("Delete", (d, w) -> {
                                kwDao.delete(k.getId());
                                int idx = list.indexOf(k);
                                if (idx >= 0) {
                                    list.remove(idx);
                                    notifyItemRemoved(idx);
                                }
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
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
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

    /**
     * Categories of the active sub-tab's type, used both for the list's
     * category-name lookup and for the Add dialog's parent-category spinner.
     */
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

    // ── Edit Sub-Category — name + parent category ─────────────────────
    // Mirrors showEditCategoryDialog()'s reasoning: fixes a sub-category
    // created under the wrong parent (e.g. "Ice" meant for Snacks, mapped to
    // Food by mistake) without deleting/recreating it — the sub-category id
    // stays the same, so already-mapped transactions keep pointing at it.
    private void showEditSubCategoryDialog(SubCategory sc) {
        List<Category> cats = categoriesForSubCatTab();

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
        for (int i = 0; i < cats.size(); i++) {
            if (cats.get(i).getId() == sc.getParentCategoryId()) {
                spParent.setSelection(i); // pre-select its CURRENT parent
                break;
            }
        }
        form.addView(spParent);

        TextView lbl2 = new TextView(this);
        lbl2.setText("Sub-category name");
        lbl2.setTextSize(11);
        lbl2.setPadding(0, pad, 0, 0);
        form.addView(lbl2);

        EditText etName = new EditText(this);
        etName.setText(sc.getName());
        etName.setSelection(sc.getName().length());
        form.addView(etName);

        new AlertDialog.Builder(this)
                .setTitle("Edit Sub-Category")
                .setView(form)
                .setPositiveButton("Save", (d, w) -> {
                    String newName = etName.getText().toString().trim();
                    if (newName.isEmpty() || spParent.getSelectedItem() == null) return;
                    scDao.update(sc.getId(), newName);
                    int newCatId = ((Category) spParent.getSelectedItem()).getId();
                    if (newCatId != sc.getParentCategoryId()) {
                        scDao.updateParentCategory(sc.getId(), newCatId);
                        // Keep already-recorded transactions consistent — their
                        // stored category_id must follow the sub-category to its
                        // new parent, otherwise a txn would still show the OLD
                        // category (e.g. Food) even though its sub-category
                        // (Ice) now belongs to Snacks.
                        SQLiteDatabase db = LocalDB.getInstance(this).getWritableDatabase();
                        db.execSQL("UPDATE transactions SET category_id = ? WHERE sub_categories_id = ?",
                                new Object[]{newCatId, sc.getId()});
                    }
                    loadSubCategoryList();
                    Toast.makeText(this, "Sub-category updated!", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
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
            attachIdPeek(h.tvName, sc.getName(),
                    "Sub-Cat ID: " + sc.getId() + "  |  Parent Cat ID: " + sc.getParentCategoryId());

            h.btnEdit.setOnClickListener(v -> showEditSubCategoryDialog(sc));

            h.btnDel.setOnClickListener(v ->
                    new AlertDialog.Builder(SettingsActivity.this)
                            .setTitle("Delete Sub-Category")
                            .setMessage("Delete \"" + sc.getName() + "\"?")
                            .setPositiveButton("Delete", (d, w) -> {
                                scDao.delete(sc.getId());
                                int idx = list.indexOf(sc);
                                if (idx >= 0) {
                                    list.remove(idx);
                                    notifyItemRemoved(idx);
                                }
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
            attachIdPeek(h.tvName, col.getColName(), col.getId());

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
                                int idx = list.indexOf(col);
                                if (idx >= 0) {
                                    list.remove(idx);
                                    notifyItemRemoved(idx);
                                }
                            })
                            .setNegativeButton("Cancel", null)
                            .show());
        }

        @Override
        public int getItemCount() {
            return list.size();
        }
    }

    // ── Edit Category — name + scope (Common <-> This book only) ──────
    // Categories are the only one of the four editable lists that carry a
    // scope, so this gets its own dialog instead of the generic rename one
    // below — lets you fix a category that was accidentally created as
    // Common when it should've been book-specific (or vice versa), without
    // having to delete and recreate it (which would orphan its transactions'
    // category link).
    private void showEditCategoryDialog(Category c) {
        android.widget.LinearLayout form = new android.widget.LinearLayout(this);
        form.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad, pad, pad);

        EditText etName = new EditText(this);
        etName.setText(c.getName());
        etName.setSelection(c.getName().length());
        form.addView(etName);

        Spinner spScope = null;
        if (bookScoped) {
            spScope = new Spinner(this);
            ArrayAdapter<String> adp = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item,
                    new String[]{"Common (all books)", "This book only"});
            adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spScope.setAdapter(adp);
            spScope.setSelection(c.isCommon() ? 0 : 1); // pre-select its CURRENT scope
            form.addView(spScope);
        }

        Spinner finalSpScope = spScope;
        new AlertDialog.Builder(this)
                .setTitle("Edit Category")
                .setView(form)
                .setPositiveButton("Save", (d, w) -> {
                    String newName = etName.getText().toString().trim();
                    if (newName.isEmpty()) return;
                    catDao.update(c.getId(), newName);
                    if (finalSpScope != null) {
                        boolean thisBookOnly = finalSpScope.getSelectedItemPosition() == 1;
                        // Only a risk going Common -> this-book-only: narrowing
                        // scope would silently drop this category out of any
                        // OTHER cashbook that already has transactions on it.
                        if (thisBookOnly && c.isCommon()) {
                            java.util.Map<String, Integer> otherBooks = booksUsingCategory(c.getId(), bookId);
                            if (!otherBooks.isEmpty()) {
                                showScopeBlockedDialog(otherBooks);
                                return;
                            }
                        }
                        catDao.update(c.getId(), newName);
                        catDao.updateScope(c.getId(), thisBookOnly ? bookId : null);
                    } else {
                        catDao.update(c.getId(), newName);
                    }
                    loadCategoryList();
                    Toast.makeText(this, "Category updated!", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // Books (other than the current one) that have transactions on this
    // category, with how many each — used to block a Common -> book-only
    // scope change that would otherwise silently orphan those cashbooks'
    // transactions from the category.
    private java.util.Map<String, Integer> booksUsingCategory(int categoryId, int excludeBookId) {
        java.util.Map<String, Integer> result = new java.util.LinkedHashMap<>();
        SQLiteDatabase db = LocalDB.getInstance(this).getReadableDatabase();
        String sql = "SELECT cb.name, COUNT(*) FROM transactions t " +
                "JOIN cash_books cb ON cb.id = t.book_id " +
                "WHERE t.category_id = ? AND t.book_id != ? " +
                "GROUP BY t.book_id, cb.name";
        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(categoryId), String.valueOf(excludeBookId)})) {
            while (c.moveToNext()) result.put(c.getString(0), c.getInt(1));
        }
        return result;
    }

    private void showScopeBlockedDialog(java.util.Map<String, Integer> otherBooks) {
        StringBuilder sb = new StringBuilder("This category is still used in other cashbooks:\n\n");
        for (java.util.Map.Entry<String, Integer> e : otherBooks.entrySet())
            sb.append("• ").append(e.getKey()).append(" — ").append(e.getValue())
                    .append(e.getValue() == 1 ? " transaction\n" : " transactions\n");
        sb.append("\nMaking it \"This book only\" would remove it from those cashbooks. Move or delete those transactions first, or keep it Common.");
        new AlertDialog.Builder(this)
                .setTitle("Can't change scope")
                .setMessage(sb.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    // ── Rename helper (shared by sub-category/column/payment type edit icon) ──
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

    // ══════════════════════════════════════════════════════
// PAYMENT TYPES TAB
// ══════════════════════════════════════════════════════

    private void loadPaymentTypesTab() {
        if (!paySearchWired) {
            paySearchWired = true;
            EditText etSearch = findViewById(R.id.etPaySearch);
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void onTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void afterTextChanged(Editable e) {
                    paySearch = e.toString().trim();
                    loadPaymentTypesTab();
                }
            });
            findViewById(R.id.btnAddPaymentType).setOnClickListener(v -> showAddPaymentTypeDialog());
        }

        List<com.expenseos.model.PaymentType> all = payDao.findAll();
        List<com.expenseos.model.PaymentType> filtered = new ArrayList<>();
        for (com.expenseos.model.PaymentType pt : all) {
            if (paySearch.isEmpty() || pt.getName().toLowerCase(Locale.ROOT).contains(paySearch.toLowerCase(Locale.ROOT)))
                filtered.add(pt);
        }

        RecyclerView rv = findViewById(R.id.rvPaymentTypes);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new PaymentTypeAdapter(filtered));
    }

    private void showAddPaymentTypeDialog() {
        EditText etName = new EditText(this);
        etName.setHint("e.g. HDFC Credit Card");
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        etName.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(this)
                .setTitle("Add Payment Type")
                .setView(etName)
                .setPositiveButton("Add", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) return;
                    payDao.insert(name);
                    loadPaymentTypesTab();
                    Toast.makeText(this, "Payment type added!", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    class PaymentTypeAdapter extends RecyclerView.Adapter<PaymentTypeAdapter.VH> {
        private final List<com.expenseos.model.PaymentType> list;

        PaymentTypeAdapter(List<com.expenseos.model.PaymentType> list) {
            this.list = list;
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvDefaultBadge; // tvDefaultBadge சேர்க்கப்பட்டுள்ளது
            View btnEdit, btnDel;

            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tvPaymentTypeName);
                tvDefaultBadge = v.findViewById(R.id.tvDefaultBadge); // Bind badge view
                btnEdit = v.findViewById(R.id.btnPaymentTypeEdit);
                btnDel = v.findViewById(R.id.btnPaymentTypeDel);
            }
        }

        @Override
        public VH onCreateViewHolder(ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_payment_type, p, false));
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            com.expenseos.model.PaymentType pt = list.get(pos);

            // 1. Text Concatenation-க்கு பதிலாக Name மட்டும் வைக்கப்படுகிறது
            h.tvName.setText(pt.getName());

// 2. Default status-ஐப் பொறுத்து Badge காட்டுவது/மறைப்பது
            if (pt.isDefault()) {
                h.tvDefaultBadge.setVisibility(View.VISIBLE);
            } else {
                h.tvDefaultBadge.setVisibility(View.GONE);
            }
            attachIdPeek(h.tvName, pt.getName(), pt.getId());

            h.itemView.setOnClickListener(v -> {
                payDao.setDefault(pt.getId());
                loadPaymentTypesTab();
            });

            h.btnEdit.setOnClickListener(v ->
                    showRenameDialog("Rename Payment Type", pt.getName(),
                            SettingsActivity.this::loadPaymentTypesTab,
                            newName -> payDao.update(pt.getId(), newName)));

            h.btnDel.setOnClickListener(v ->
                    new AlertDialog.Builder(SettingsActivity.this)
                            .setTitle("Delete Payment Type")
                            .setMessage("Delete \"" + pt.getName() + "\"?")
                            .setPositiveButton("Delete", (d, w) -> {
                                payDao.delete(pt.getId());
                                int idx = list.indexOf(pt);
                                if (idx >= 0) {
                                    list.remove(idx);
                                    notifyItemRemoved(idx);
                                }
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