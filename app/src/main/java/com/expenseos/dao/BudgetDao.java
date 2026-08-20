package com.expenseos.dao;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.expenseos.db.LocalDB;
import com.expenseos.model.Budget;
import com.expenseos.model.BudgetCategory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Android/SQLite port of the web BudgetDAO.
 * <p>
 * Conversion notes (same conventions as TransactionDao):
 * - ::txn_type casts removed (SQLite has no enum types; `type` is plain TEXT).
 * - EXTRACT(YEAR/MONTH FROM ...) -> CAST(strftime('%Y'/'%m', ...) AS INTEGER).
 * - NOW() -> datetime('now') in SQL, or Java-side LocalDateTime.now() formatted
 * the same way dates are stored elsewhere ("yyyy-MM-dd HH:mm:ss").
 * - INTERVAL arithmetic -> datetime('now', '-' || ? || ' months').
 * - INSERT ... ON CONFLICT ... DO UPDATE ... RETURNING id has no reliable
 * Android/SQLite equivalent (RETURNING support varies by bundled SQLite
 * version) -> upsert() does a manual "find existing row, then update or
 * insert" instead.
 * - ON CONFLICT (budget_id, category_id) DO UPDATE -> insertWithOnConflict(
 * ..., CONFLICT_REPLACE), which requires the UNIQUE(budget_id, category_id)
 * index that budget_categories already has.
 * - overall_limit / cat_limit are stored as REAL columns (unlike
 * transactions.amount, which the rest of the app stores as TEXT via
 * BigDecimal.toString() into a REAL-affinity column) — written here via
 * BigDecimal.doubleValue() and read back via BigDecimal.valueOf(double),
 * which is safe for currency amounts at this precision.
 */
public class BudgetDao {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Context ctx;
    private final SQLiteDatabase db;

    public BudgetDao(Context ctx) {
        this.ctx = ctx;
        db = LocalDB.getInstance(ctx).getWritableDatabase();
    }

    // ── Upsert month budget ────────────────────────────────
    public int upsert(Budget b) {
        String nowStr = LocalDateTime.now().format(TS_FMT);

        try (Cursor c = db.rawQuery(
                "SELECT id FROM budgets WHERE book_id=? AND year=? AND month=?",
                new String[]{String.valueOf(b.getBookId()), String.valueOf(b.getYear()), String.valueOf(b.getMonth())})) {
            if (c.moveToFirst()) {
                int id = c.getInt(0);
                ContentValues cv = new ContentValues();
                cv.put("overall_limit", b.getOverallLimit().doubleValue());
                cv.put("updated_at", nowStr);
                db.update("budgets", cv, "id=?", new String[]{String.valueOf(id)});
                return id;
            }
        }

        ContentValues cv = new ContentValues();
        cv.put("book_id", b.getBookId());
        cv.put("year", b.getYear());
        cv.put("month", b.getMonth());
        cv.put("overall_limit", b.getOverallLimit().doubleValue());
        cv.put("created_at", nowStr);
        cv.put("updated_at", nowStr);
        return (int) db.insert("budgets", null, cv);
    }

    // ── Upsert category budget ─────────────────────────────
    public void upsertCategory(BudgetCategory bc) {
        ContentValues cv = new ContentValues();
        cv.put("budget_id", bc.getBudgetId());
        cv.put("category_id", bc.getCategoryId());
        cv.put("cat_limit", bc.getCatLimit().doubleValue());
        cv.put("alert_pct", bc.getAlertPct());
        db.insertWithOnConflict("budget_categories", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ── Delete category budget row ─────────────────────────
    public void deleteCategory(int budgetId, int categoryId) {
        try (Cursor c = db.rawQuery(
                "SELECT bc.id, bc.cat_limit, bc.alert_pct, b.book_id " +
                        "FROM budget_categories bc JOIN budgets b ON b.id = bc.budget_id " +
                        "WHERE bc.budget_id=? AND bc.category_id=?",
                new String[]{String.valueOf(budgetId), String.valueOf(categoryId)})) {
            if (c.moveToFirst()) {
                int rowId = c.getInt(0);
                int bookId = c.getInt(3);
                org.json.JSONObject row = new org.json.JSONObject();
                try {
                    row.put("budget_id", budgetId);
                    row.put("category_id", categoryId);
                    row.put("cat_limit", c.getDouble(1));
                    row.put("alert_pct", c.getInt(2));
                } catch (org.json.JSONException ignored) {
                }
                new RecycleBinDao(ctx).put("budget_categories", rowId, bookId, row);
            }
        }
        db.delete("budget_categories", "budget_id=? AND category_id=?",
                new String[]{String.valueOf(budgetId), String.valueOf(categoryId)});
    }

    // ── Find budget for a specific month (with category rows + spent) ──
    public Budget findByMonth(int bookId, int year, int month) {
        String sql = "SELECT id, book_id, year, month, overall_limit, created_at, updated_at " +
                "FROM budgets WHERE book_id=? AND year=? AND month=?";
        try (Cursor c = db.rawQuery(sql, new String[]{
                String.valueOf(bookId), String.valueOf(year), String.valueOf(month)})) {
            if (!c.moveToFirst()) return null;
            Budget b = mapBudget(c);
            b.setCategories(loadCategories(b.getId(), year, month));
            b.setTotalSpent(loadMonthExpense(bookId, year, month, 0));
            BigDecimal remaining = b.getOverallLimit().subtract(
                    b.getTotalSpent() == null ? BigDecimal.ZERO : b.getTotalSpent());
            b.setRemainingAmount(remaining);
            return b;
        }
    }

    // ── List all budgets for a book (summary, no category detail) ──
    public List<Budget> listByBook(int bookId) {
        String sql = "SELECT b.id, b.book_id, b.year, b.month, b.overall_limit, " +
                "b.created_at, b.updated_at, " +
                "COALESCE(SUM(t.amount),0) AS total_spent " +
                "FROM budgets b " +
                "LEFT JOIN transactions t " +
                "       ON t.book_id = b.book_id " +
                "      AND t.type = 'EXPENSE' " +
                "      AND CAST(strftime('%Y', t.txn_datetime) AS INTEGER) = b.year " +
                "      AND CAST(strftime('%m', t.txn_datetime) AS INTEGER) = b.month " +
                "WHERE b.book_id = ? " +
                "GROUP BY b.id " +
                "ORDER BY b.year DESC, b.month DESC";
        List<Budget> list = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(bookId)})) {
            while (c.moveToNext()) {
                Budget b = mapBudget(c);
                BigDecimal spent = BigDecimal.valueOf(c.getDouble(c.getColumnIndexOrThrow("total_spent")));
                b.setTotalSpent(spent);
                b.setRemainingAmount(b.getOverallLimit().subtract(spent));
                list.add(b);
            }
        }
        return list;
    }

    // ── Dashboard: current month budget with alerts ─────────
    public Budget currentMonthBudget(int bookId) {
        java.time.LocalDate now = java.time.LocalDate.now();
        return findByMonth(bookId, now.getYear(), now.getMonthValue());
    }

    // ── Trend: monthly income+expense for last N months ─────
    public List<Map<String, Object>> monthlyTrend(int bookId, int months) {
        String sql = "SELECT " +
                "CAST(strftime('%Y', txn_datetime) AS INTEGER) AS yr, " +
                "CAST(strftime('%m', txn_datetime) AS INTEGER) AS mo, " +
                "SUM(CASE WHEN type='INCOME'  THEN amount ELSE 0 END) AS income, " +
                "SUM(CASE WHEN type='EXPENSE' THEN amount ELSE 0 END) AS expense " +
                "FROM transactions " +
                "WHERE book_id = ? " +
                "  AND txn_datetime >= datetime('now', '-' || ? || ' months') " +
                "GROUP BY yr, mo ORDER BY yr ASC, mo ASC";
        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(bookId), String.valueOf(months)})) {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (c.moveToNext()) {
                Map<String, Object> row = new LinkedHashMap<>();
                int yr = c.getInt(c.getColumnIndexOrThrow("yr"));
                int mo = c.getInt(c.getColumnIndexOrThrow("mo"));
                row.put("yr", yr);
                row.put("mo", mo);
                row.put("label", java.time.Month.of(mo).getDisplayName(
                        java.time.format.TextStyle.SHORT, Locale.ENGLISH) + " " + yr);
                BigDecimal income = BigDecimal.valueOf(c.getDouble(c.getColumnIndexOrThrow("income")));
                BigDecimal expense = BigDecimal.valueOf(c.getDouble(c.getColumnIndexOrThrow("expense")));
                row.put("income", income);
                row.put("expense", expense);
                row.put("net", income.subtract(expense));
                rows.add(row);
            }
            return rows;
        }
    }

    // ── Trend: category-wise monthly breakdown ───────────────
    public List<Map<String, Object>> categoryTrend(int bookId, int months) {
        String sql = "SELECT " +
                "CAST(strftime('%Y', t.txn_datetime) AS INTEGER) AS yr, " +
                "CAST(strftime('%m', t.txn_datetime) AS INTEGER) AS mo, " +
                "c.name AS category, " +
                "SUM(t.amount) AS total " +
                "FROM transactions t " +
                "JOIN categories c ON c.id = t.category_id " +
                "WHERE t.book_id = ? " +
                "  AND t.type = 'EXPENSE' " +
                "  AND t.txn_datetime >= datetime('now', '-' || ? || ' months') " +
                "GROUP BY yr, mo, c.name " +
                "ORDER BY yr ASC, mo ASC, total DESC";
        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(bookId), String.valueOf(months)})) {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (c.moveToNext()) {
                Map<String, Object> row = new LinkedHashMap<>();
                int mo = c.getInt(c.getColumnIndexOrThrow("mo"));
                int yr = c.getInt(c.getColumnIndexOrThrow("yr"));
                row.put("yr", yr);
                row.put("mo", mo);
                row.put("label", java.time.Month.of(mo).getDisplayName(
                        java.time.format.TextStyle.SHORT, Locale.ENGLISH) + " " + yr);
                row.put("category", c.getString(c.getColumnIndexOrThrow("category")));
                row.put("total", BigDecimal.valueOf(c.getDouble(c.getColumnIndexOrThrow("total"))));
                rows.add(row);
            }
            return rows;
        }
    }

    // ── Year-over-year: same months across years ─────────────
    public List<Map<String, Object>> yearOverYear(int bookId) {
        String sql = "SELECT " +
                "CAST(strftime('%Y', txn_datetime) AS INTEGER) AS yr, " +
                "CAST(strftime('%m', txn_datetime) AS INTEGER) AS mo, " +
                "SUM(CASE WHEN type='INCOME'  THEN amount ELSE 0 END) AS income, " +
                "SUM(CASE WHEN type='EXPENSE' THEN amount ELSE 0 END) AS expense " +
                "FROM transactions " +
                "WHERE book_id = ? " +
                "GROUP BY yr, mo ORDER BY mo ASC, yr ASC";
        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(bookId)})) {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (c.moveToNext()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("yr", c.getInt(c.getColumnIndexOrThrow("yr")));
                row.put("mo", c.getInt(c.getColumnIndexOrThrow("mo")));
                row.put("income", BigDecimal.valueOf(c.getDouble(c.getColumnIndexOrThrow("income"))));
                row.put("expense", BigDecimal.valueOf(c.getDouble(c.getColumnIndexOrThrow("expense"))));
                rows.add(row);
            }
            return rows;
        }
    }

    // ── Helpers ────────────────────────────────────────────
    private Budget mapBudget(Cursor c) {
        Budget b = new Budget();
        b.setId(c.getInt(c.getColumnIndexOrThrow("id")));
        b.setBookId(c.getInt(c.getColumnIndexOrThrow("book_id")));
        b.setYear(c.getInt(c.getColumnIndexOrThrow("year")));
        b.setMonth(c.getInt(c.getColumnIndexOrThrow("month")));
        b.setOverallLimit(BigDecimal.valueOf(c.getDouble(c.getColumnIndexOrThrow("overall_limit"))));
        String ca = c.getString(c.getColumnIndexOrThrow("created_at"));
        if (ca != null) b.setCreatedAt(LocalDateTime.parse(ca, TS_FMT));
        String ua = c.getString(c.getColumnIndexOrThrow("updated_at"));
        if (ua != null) b.setUpdatedAt(LocalDateTime.parse(ua, TS_FMT));
        return b;
    }

    private List<BudgetCategory> loadCategories(int budgetId, int year, int month) {
        String sql = "SELECT bc.id, bc.budget_id, bc.category_id, bc.cat_limit, bc.alert_pct, " +
                "c.name AS cat_name, " +
                "COALESCE(SUM(t.amount),0) AS spent " +
                "FROM budget_categories bc " +
                "JOIN categories c ON c.id = bc.category_id " +
                "LEFT JOIN transactions t " +
                "       ON t.category_id = bc.category_id " +
                "      AND t.type = 'EXPENSE' " +
                "      AND CAST(strftime('%Y', t.txn_datetime) AS INTEGER) = ? " +
                "      AND CAST(strftime('%m', t.txn_datetime) AS INTEGER) = ? " +
                "WHERE bc.budget_id = ? " +
                "GROUP BY bc.id, bc.budget_id, bc.category_id, bc.cat_limit, bc.alert_pct, c.name " +
                "ORDER BY c.name";
        try (Cursor c = db.rawQuery(sql, new String[]{
                String.valueOf(year), String.valueOf(month), String.valueOf(budgetId)})) {
            List<BudgetCategory> list = new ArrayList<>();
            while (c.moveToNext()) {
                BudgetCategory bc = new BudgetCategory();
                bc.setId(c.getInt(c.getColumnIndexOrThrow("id")));
                bc.setBudgetId(c.getInt(c.getColumnIndexOrThrow("budget_id")));
                bc.setCategoryId(c.getInt(c.getColumnIndexOrThrow("category_id")));
                bc.setCategoryName(c.getString(c.getColumnIndexOrThrow("cat_name")));
                bc.setCatLimit(BigDecimal.valueOf(c.getDouble(c.getColumnIndexOrThrow("cat_limit"))));
                bc.setAlertPct(c.getInt(c.getColumnIndexOrThrow("alert_pct")));
                BigDecimal spent = BigDecimal.valueOf(c.getDouble(c.getColumnIndexOrThrow("spent")));
                bc.setSpent(spent);
                bc.setRemaining(bc.getCatLimit().subtract(spent));
                list.add(bc);
            }
            return list;
        }
    }

    private BigDecimal loadMonthExpense(int bookId, int year, int month, int catId) {
        StringBuilder sql = new StringBuilder(
                "SELECT COALESCE(SUM(amount),0) FROM transactions " +
                        "WHERE book_id=? AND type='EXPENSE' " +
                        "  AND CAST(strftime('%Y', txn_datetime) AS INTEGER)=? " +
                        "  AND CAST(strftime('%m', txn_datetime) AS INTEGER)=?");
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(bookId));
        args.add(String.valueOf(year));
        args.add(String.valueOf(month));
        if (catId > 0) {
            sql.append(" AND category_id=?");
            args.add(String.valueOf(catId));
        }
        try (Cursor c = db.rawQuery(sql.toString(), args.toArray(new String[0]))) {
            return c.moveToFirst() ? BigDecimal.valueOf(c.getDouble(0)) : BigDecimal.ZERO;
        }
    }
}
