package com.expenseos.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.dao.CashBookDao;
import com.expenseos.dao.CategoryDao;
import com.expenseos.dao.SubCategoryDao;
import com.expenseos.dao.TransactionDao;
import com.expenseos.model.CashBook;
import com.expenseos.model.Category;
import com.expenseos.model.SubCategory;
import com.expenseos.model.Transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Daily Breakfast/Lunch/Dinner expense grid for the current month's
 * auto-created "<Month> <Year> Food" cashbook (SchedulerWorker.runCashBook()
 * creates it — same pattern as the other 3 monthly books). Category
 * "Food" and its Breakfast/Lunch/Dinner sub-categories are COMMON
 * (book_id IS NULL), shared across every cashbook, per how this app
 * already sets up categories.
 * <p>
 * Fixed transaction times per meal: Breakfast 9:00 AM, Lunch 1:00 PM,
 * Dinner 8:30 PM. Payment type always "Cash". Save upserts the matching
 * transaction per cell (create if none exists for that date+meal, update
 * the amount if one does, delete it if cleared back to 0). Refresh
 * re-reads from the DB, so entries added directly via the normal
 * Add-Entry screen for this cashbook show up here too.
 */
public class FoodTrackerActivity extends AppCompatActivity {

    private static final DateTimeFormatter MONTH_NAME_FMT = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final LocalTime BREAKFAST_TIME = LocalTime.of(9, 0);
    private static final LocalTime LUNCH_TIME = LocalTime.of(13, 0);
    private static final LocalTime DINNER_TIME = LocalTime.of(20, 30);

    private TransactionDao txnDao;
    private CashBook foodBook;
    private int foodCategoryId;
    private int breakfastSubId, lunchSubId, dinnerSubId;

    private final List<DayRow> rows = new ArrayList<>();
    private FoodGridAdapter adapter;
    private TextView tvMonthTitle, tvGrandTotal;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_food_tracker);

        txnDao = new TransactionDao(this);
        tvMonthTitle = findViewById(R.id.tvFoodTrackerMonth);
        tvGrandTotal = findViewById(R.id.tvFoodTrackerGrandTotal);

        RecyclerView rv = findViewById(R.id.rvFoodTracker);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FoodGridAdapter();
        rv.setAdapter(adapter);

        findViewById(R.id.btnFoodTrackerBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnFoodTrackerRefresh).setOnClickListener(v -> loadData());
        findViewById(R.id.btnFoodTrackerSave).setOnClickListener(v -> saveAll());

        ensureCategoriesAndBook();
        loadData();
    }

    // ── Ensure "Food" category + Breakfast/Lunch/Dinner sub-categories
    // (common, book_id NULL) and this month's "<Month> <Year> Food"
    // cashbook all exist — auto-creates anything missing so this screen
    // works even before the scheduler's next 12:05 AM run. ──────────
    private void ensureCategoriesAndBook() {
        CategoryDao catDao = new CategoryDao(this);
        SubCategoryDao subDao = new SubCategoryDao(this);
        CashBookDao bookDao = new CashBookDao(this);

        Category food = findCommonCategoryByName(catDao, "Food");
        if (food == null) {
            catDao.insert("Food", "EXPENSE", null);
            food = findCommonCategoryByName(catDao, "Food");
        }
        foodCategoryId = food.getId();

        breakfastSubId = ensureSubCategory(subDao, foodCategoryId, "Breakfast");
        lunchSubId = ensureSubCategory(subDao, foodCategoryId, "Lunch");
        dinnerSubId = ensureSubCategory(subDao, foodCategoryId, "Dinner");

        String bookName = YearMonth.now().atDay(1).format(MONTH_NAME_FMT) + " Food";
        CashBook existing = null;
        for (CashBook b : bookDao.findAll())
            if (bookName.equalsIgnoreCase(b.getName())) {
                existing = b;
                break;
            }
        if (existing == null) {
            long id = bookDao.insert(bookName, "Auto-created — daily food tracker");
            existing = bookDao.findById((int) id);
        }
        foodBook = existing;
        tvMonthTitle.setText(bookName);
    }

    private Category findCommonCategoryByName(CategoryDao dao, String name) {
        List<Category> matches = dao.findByName(name, "EXPENSE", null, null);
        return matches.isEmpty() ? null : matches.get(0);
    }

    private int ensureSubCategory(SubCategoryDao dao, int catId, String name) {
        for (SubCategory sc : dao.findByCategoryId(catId))
            if (name.equalsIgnoreCase(sc.getName())) return sc.getId();
        dao.insert(name, catId);
        for (SubCategory sc : dao.findByCategoryId(catId))
            if (name.equalsIgnoreCase(sc.getName())) return sc.getId();
        return 0;
    }

    // ── Load / Refresh ──────────────────────────────────────────────
    private void loadData() {
        Map<String, Transaction> existing = txnDao.findFoodEntriesForBook(foodBook.getId(), foodCategoryId);

        YearMonth ym = YearMonth.now();
        rows.clear();
        BigDecimal grand = BigDecimal.ZERO;
        for (int day = 1; day <= ym.lengthOfMonth(); day++) {
            LocalDate date = ym.atDay(day);
            DayRow row = new DayRow();
            row.date = date;
            row.breakfast = existing.get(keyFor(date, "BREAKFAST"));
            row.lunch = existing.get(keyFor(date, "LUNCH"));
            row.dinner = existing.get(keyFor(date, "DINNER"));
            rows.add(row);
            grand = grand.add(amt(row.breakfast)).add(amt(row.lunch)).add(amt(row.dinner));
        }
        adapter.notifyDataSetChanged();
        tvGrandTotal.setText("₹" + grand.toPlainString());
    }

    private String keyFor(LocalDate date, String meal) {
        return date + "|" + meal;
    }

    private BigDecimal amt(Transaction t) {
        return t != null ? t.getAmount() : BigDecimal.ZERO;
    }

    // ── Save — walks every row, upserts/deletes as needed per cell ────
    private void saveAll() {
        for (DayRow row : rows) {
            upsertCell(row.date, "Breakfast", breakfastSubId, BREAKFAST_TIME, row.breakfast, row.editedBreakfast);
            upsertCell(row.date, "Lunch", lunchSubId, LUNCH_TIME, row.lunch, row.editedLunch);
            upsertCell(row.date, "Dinner", dinnerSubId, DINNER_TIME, row.dinner, row.editedDinner);
        }
        Toast.makeText(this, "✓ Saved!", Toast.LENGTH_SHORT).show();
        loadData(); // fresh state — resolves ids for newly-inserted cells
    }

    private void upsertCell(LocalDate date, String mealName, int subCategoryId, LocalTime time,
                            Transaction existing, BigDecimal editedValue) {
        if (editedValue == null) return; // cell never touched this session

        boolean isZero = editedValue.compareTo(BigDecimal.ZERO) == 0;

        if (existing == null) {
            if (isZero) return;
            Transaction t = new Transaction();
            t.setType(Transaction.Type.EXPENSE);
            t.setDateTime(LocalDateTime.of(date, time));
            t.setAmount(editedValue);
            t.setCategoryId(foodCategoryId);
            t.setSubCategoryId(subCategoryId);
            t.setNote(mealName);
            t.setBookId(foodBook.getId());
            t.setPaymentType("Cash");
            txnDao.insert(t);
        } else {
            if (isZero) {
                txnDao.delete(existing.getId());
                return;
            }
            if (existing.getAmount().compareTo(editedValue) == 0) return; // unchanged
            Transaction updatedT = new Transaction();
            updatedT.setId(existing.getId());
            updatedT.setType(Transaction.Type.EXPENSE);
            updatedT.setDateTime(LocalDateTime.of(date, time));
            updatedT.setAmount(editedValue);
            updatedT.setCategoryId(foodCategoryId);
            updatedT.setSubCategoryId(subCategoryId);
            updatedT.setNote(mealName);
            updatedT.setBookId(foodBook.getId());
            updatedT.setPaymentType("Cash");
            txnDao.update(existing, updatedT);
        }
    }

    // ── Row model — editedX persists at row-level (not ViewHolder-level)
    // so scroll-recycling never loses an unsaved edit. ────────────────
    private static class DayRow {
        LocalDate date;
        Transaction breakfast, lunch, dinner;
        BigDecimal editedBreakfast, editedLunch, editedDinner; // null = untouched this session
    }

    // ── Adapter ─────────────────────────────────────────────────────
    private class FoodGridAdapter extends RecyclerView.Adapter<FoodGridAdapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            TextView tvSNo, tvDate, tvDay, tvTotal;
            EditText etBreakfast, etLunch, etDinner;

            VH(View v) {
                super(v);
                tvSNo = v.findViewById(R.id.tvFtSNo);
                tvDate = v.findViewById(R.id.tvFtDate);
                tvDay = v.findViewById(R.id.tvFtDay);
                etBreakfast = v.findViewById(R.id.etFtBreakfast);
                etLunch = v.findViewById(R.id.etFtLunch);
                etDinner = v.findViewById(R.id.etFtDinner);
                tvTotal = v.findViewById(R.id.tvFtTotal);
            }
        }

        @Override
        public VH onCreateViewHolder(ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_food_tracker_row, p, false));
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            DayRow row = rows.get(pos);

            h.tvSNo.setText(String.valueOf(pos + 1));
            h.tvDate.setText(row.date.format(DATE_FMT));
            h.tvDay.setText(row.date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH));

            bindAmountCell(h.etBreakfast, row.breakfast, row.editedBreakfast, v -> {
                row.editedBreakfast = v;
                updateRowTotal(h, row);
            });
            bindAmountCell(h.etLunch, row.lunch, row.editedLunch, v -> {
                row.editedLunch = v;
                updateRowTotal(h, row);
            });
            bindAmountCell(h.etDinner, row.dinner, row.editedDinner, v -> {
                row.editedDinner = v;
                updateRowTotal(h, row);
            });

            updateRowTotal(h, row);
        }

        private void updateRowTotal(VH h, DayRow row) {
            BigDecimal b = row.editedBreakfast != null ? row.editedBreakfast : amt(row.breakfast);
            BigDecimal l = row.editedLunch != null ? row.editedLunch : amt(row.lunch);
            BigDecimal d = row.editedDinner != null ? row.editedDinner : amt(row.dinner);
            h.tvTotal.setText("₹" + b.add(l).add(d).toPlainString());
        }

        // Row reuse-la old listener thirumba fire aagama irukka, bind
        // panna munnadi previous watcher-ah remove pannitu puthusa attach pannurom.
        private void bindAmountCell(EditText et, Transaction existing, BigDecimal edited, Consumer<BigDecimal> onChange) {
            Object prevTag = et.getTag();
            if (prevTag instanceof TextWatcher) et.removeTextChangedListener((TextWatcher) prevTag);

            String display = edited != null ? plain(edited) : (existing != null ? plain(existing.getAmount()) : "0");
            et.setText(display);
            et.setSelection(et.getText().length());

            // Highlight Cell if value is non-zero
            updateCellHighlight(et, display);

            TextWatcher watcher = new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void onTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    BigDecimal val;
                    try {
                        val = s.length() == 0 ? BigDecimal.ZERO : new BigDecimal(s.toString());
                    } catch (NumberFormatException e) {
                        val = BigDecimal.ZERO;
                    }

                    // Value change ஆகும் போது background color-ஐ update செய்ய
                    updateCellHighlight(et, s.toString());
                    onChange.accept(val);
                }
            };
            et.addTextChangedListener(watcher);
            et.setTag(watcher);
        }

        // Cell background highlight helper method
        private void updateCellHighlight(EditText et, String textVal) {
            try {
                BigDecimal val = (textVal == null || textVal.trim().isEmpty()) ? BigDecimal.ZERO : new BigDecimal(textVal.trim());
                if (val.compareTo(BigDecimal.ZERO) > 0) {
                    et.setBackgroundResource(R.drawable.bg_input_box_yellow); // Non-zero -> Yellow
                } else {
                    et.setBackgroundResource(R.drawable.bg_input_box); // Zero -> Default Box
                }
            } catch (NumberFormatException e) {
                et.setBackgroundResource(R.drawable.bg_input_box);
            }
        }

        private String plain(BigDecimal b) {
            return b.stripTrailingZeros().toPlainString();
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }
}