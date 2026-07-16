package com.expenseos.dao;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.expenseos.model.CashBook;
import com.expenseos.model.Transaction;
import com.expenseos.model.TransactionFilter;
import com.expenseos.db.LocalDB;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Android/SQLite port of the web TransactionDAO.
 * <p>
 * Conversion notes:
 * - ::txn_type casts removed (SQLite has no enum types; `type` is plain TEXT).
 * - INSERT ... RETURNING id  ->  db.insert() returns the new row id directly.
 * - NOW()  ->  Java-side LocalDateTime.now(), formatted the same way dates are stored.
 * - TO_CHAR / EXTRACT / DATE_TRUNC  ->  SQLite strftime().
 * - ILIKE  ->  LIKE (SQLite's LIKE is already case-insensitive for ASCII).
 * - INTERVAL arithmetic  ->  datetime('now', '-' || ? || ' months').
 * - ON CONFLICT ... DO UPDATE  ->  insertWithOnConflict(..., CONFLICT_REPLACE),
 * which requires a UNIQUE index on (transaction_id, col_def_id) resp.
 * (table_name, record_id) in the local schema, matching the original constraints.
 * - All amounts are stored as TEXT (BigDecimal.toString()); SQLite's SUM()/comparison
 * operators still evaluate numeric-looking TEXT correctly.
 */
public class TransactionDao {

    private static final String TAG = "TransactionDao";

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "date", "t.txn_datetime", "type", "t.type", "category", "c.name",
            "subcategory", "sc.name", "amount", "t.amount", "note", "t.note");

    private final SQLiteDatabase db;
    private final AuditLogDao auditDao;
    private final Context ctx;

    public TransactionDao(Context ctx) {
        this.ctx = ctx;
        db = LocalDB.getInstance(ctx).getWritableDatabase();
        auditDao = new AuditLogDao(ctx);
    }

    // ── INSERT ────────────────────────────────────────────
    public long insert(Transaction t) {
        long newId = -1;
        db.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            cv.put("type", t.getType().name());
            cv.put("txn_datetime", t.getDateTime().format(TS_FMT));
            cv.put("amount", t.getAmount().toString());
            cv.put("category_id", t.getCategoryId());
            if (t.getSubCategoryId() > 0)
                cv.put("sub_categories_id", t.getSubCategoryId());
            else
                cv.putNull("sub_categories_id");
            cv.put("note", t.getNote());
            if (t.getBookId() > 0)
                cv.put("book_id", t.getBookId());
            else
                cv.putNull("book_id");

            newId = db.insert("transactions", null, cv);
            if (newId != -1) {
                if (!t.getCustomValues().isEmpty())
                    insertCustomValues((int) newId, t.getCustomValues());
                db.setTransactionSuccessful();
            }
        } catch (Exception e) {
            Log.d(TAG, "insert() exception: " + e.getMessage());
        } finally {
            db.endTransaction();
        }
        if (newId != -1)
            auditDao.logCreate((int) newId, "user");
        return newId;
    }

    // ── UPDATE ────────────────────────────────────────────
    public void update(Transaction oldT, Transaction newT) {
        ContentValues cv = new ContentValues();
        cv.put("txn_datetime", newT.getDateTime().format(TS_FMT));
        cv.put("amount", newT.getAmount().toString());
        cv.put("category_id", newT.getCategoryId());
        if (newT.getSubCategoryId() > 0)
            cv.put("sub_categories_id", newT.getSubCategoryId());
        else
            cv.putNull("sub_categories_id");
        cv.put("note", newT.getNote());
        cv.put("book_id", newT.getBookId());
        cv.put("updated_at", LocalDateTime.now().format(TS_FMT));
        db.update("transactions", cv, "id = ?", new String[]{String.valueOf(oldT.getId())});

        if (oldT.getAmount().compareTo(newT.getAmount()) != 0)
            auditDao.logUpdate(oldT.getId(), "user", "amount", "\u20B9" + oldT.getAmount(), "\u20B9" + newT.getAmount());
        if (!oldT.getDateTime().equals(newT.getDateTime()))
            auditDao.logUpdate(oldT.getId(), "user", "datetime", oldT.getDateTime().format(DT_FMT),
                    newT.getDateTime().format(DT_FMT));
        if (oldT.getCategoryId() != newT.getCategoryId())
            auditDao.logUpdate(oldT.getId(), "user", "category", nvl(oldT.getCategoryName()),
                    nvl(newT.getCategoryName()));
        if (oldT.getSubCategoryId() != newT.getSubCategoryId())
            auditDao.logUpdate(oldT.getId(), "user", "subcategory", nvl(oldT.getSubCategoryName()),
                    nvl(newT.getSubCategoryName()));
        if (!Objects.equals(oldT.getNote(), newT.getNote()))
            auditDao.logUpdate(oldT.getId(), "user", "note", nvl(oldT.getNote()), nvl(newT.getNote()));

        if (!Objects.equals(oldT.getBookId(), newT.getBookId())) {
            CashBookDao cashBookDao = new CashBookDao(ctx);
            auditDao.logUpdate(oldT.getId(), "user", "book",
                    cashBookDao.findById(oldT.getBookId()).getName(),
                    cashBookDao.findById(newT.getBookId()).getName());
        }
    }

    // ── DELETE ────────────────────────────────────────────
    // Also writes a tombstone to deleted_records so a sync service can
    // propagate this delete on the next sync (a plain DELETE leaves no
    // trace for an "updated_at >= ?" sync query to find).
    public void delete(int id) {
        auditDao.logDelete(id, "user");
        db.beginTransaction();
        try {
            db.delete("transactions", "id = ?", new String[]{String.valueOf(id)});
            recordTombstone("transactions", id);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ── Tombstone helper — shared by every DAO's delete() so
    // the sync service can find & propagate deletions. ─────
    // Requires a UNIQUE(table_name, record_id) index on deleted_records.
    private void recordTombstone(String tableName, int recordId) {
        ContentValues cv = new ContentValues();
        cv.put("table_name", tableName);
        cv.put("record_id", recordId);
        cv.put("deleted_at", LocalDateTime.now().format(TS_FMT));
        db.insertWithOnConflict("deleted_records", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ── FIND BY ID ────────────────────────────────────────
    public Transaction findById(int id) {
        String sql = baseSelect() + " WHERE t.id = ?";
        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(id)})) {
            if (c.moveToFirst()) {
                Transaction t = mapRow(c);
                loadCustomValues(List.of(t));
                return t;
            }
            return null;
        }
    }

    // ── ADJACENT (Prev/Next) — for the transaction detail page ────
    // "Prev" = one row NEWER (up) in the same order the list uses
    // (txn_datetime DESC, id DESC as a tiebreaker for equal timestamps).
    // "Next" = one row OLDER (down). Scoped to the same book only —
    // does not currently account for an active list filter.
    public Integer findPrevId(int id, int bookId) {
        return findAdjacentId(id, bookId, true);
    }

    public Integer findNextId(int id, int bookId) {
        return findAdjacentId(id, bookId, false);
    }

    private Integer findAdjacentId(int id, int bookId, boolean newer) {
        String curTs;
        try (Cursor c = db.rawQuery("SELECT txn_datetime FROM transactions WHERE id = ?",
                new String[]{String.valueOf(id)})) {
            if (!c.moveToFirst())
                return null;
            curTs = c.getString(0);
        }

        String sql = newer
                ? "SELECT id FROM transactions WHERE book_id = ? AND (txn_datetime > ? OR (txn_datetime = ? AND id > ?)) ORDER BY txn_datetime ASC, id ASC LIMIT 1"
                : "SELECT id FROM transactions WHERE book_id = ? AND (txn_datetime < ? OR (txn_datetime = ? AND id < ?)) ORDER BY txn_datetime DESC, id DESC LIMIT 1";

        try (Cursor c = db.rawQuery(sql, new String[]{
                String.valueOf(bookId), curTs, curTs, String.valueOf(id)})) {
            return c.moveToFirst() ? c.getInt(0) : null;
        }
    }

    // ── LEGACY find (backward compat) ─────────────────────
    public List<Transaction> findAll(String typeFilter, int page, int pageSize, Integer bookId) {
        TransactionFilter f = new TransactionFilter();
        f.setType(typeFilter);
        f.setBookId(bookId);
        f.setPage(page);
        f.setPageSize(pageSize);
        return findByFilter(f);
    }

    public int count(String typeFilter, Integer bookId) {
        TransactionFilter f = new TransactionFilter();
        f.setType(typeFilter);
        f.setBookId(bookId);
        return countByFilter(f);
    }

    // ── FILTER-BASED SEARCH ───────────────────────────────
    public List<Transaction> findByFilter(TransactionFilter f) {
        BuildResult q = buildSQL(f, false);
        List<Transaction> list = new ArrayList<>();
        try (Cursor c = db.rawQuery(q.sql(), q.params().toArray(new String[0]))) {
            while (c.moveToNext())
                list.add(mapRow(c));
        }
        loadCustomValues(list);
        attachRunningBalances(list, f.getBookId());
        return list;
    }

    // ── RUNNING BALANCE ────────────────────────────────────
    // Sets Transaction.runningBalance = cumulative (income - expense) total,
    // computed in chronological (oldest -> newest) order, matching the old
    // app's behaviour: the balance shown next to a transaction is the book's
    // total AFTER that transaction was posted.
    //
    // `list` itself is left in whatever order the caller's sort/filter
    // produced (e.g. newest-first for the UI) — only the field values are
    // set, via the same object references, so this is safe to call after
    // the query regardless of display order.
    //
    // NOTE: only correct when the filter is scoped to a single book
    // (bookId != null). If bookId is null (viewing across all books),
    // a single running balance doesn't mean much — this method skips the
    // calculation in that case and leaves runningBalance null.
    private void attachRunningBalances(List<Transaction> list, Integer bookId) {
        if (list.isEmpty() || bookId == null || bookId <= 0)
            return;

        try {
            List<Transaction> chronological = new ArrayList<>(list);
            chronological.sort((a, b) -> {
                if (a.getDateTime() == null && b.getDateTime() == null) return 0;
                if (a.getDateTime() == null) return -1; // nulls first, defensive only — shouldn't normally happen
                if (b.getDateTime() == null) return 1;
                int cmp = a.getDateTime().compareTo(b.getDateTime());
                return cmp != 0 ? cmp : Integer.compare(a.getId(), b.getId());
            });

            // TODO: if CashBook has an opening balance field, start from that
            // instead of ZERO, e.g. BigDecimal running = cashBookDao.findById(bookId).getOpeningBalance();
            BigDecimal running = BigDecimal.ZERO;
            for (Transaction t : chronological) {
                if (t.getAmount() == null) {
                    Log.w(TAG, "attachRunningBalances: txn id=" + t.getId() + " has null amount, skipping");
                    continue;
                }
                running = t.getType() == Transaction.Type.INCOME
                        ? running.add(t.getAmount())
                        : running.subtract(t.getAmount());
                t.setRunningBalance(running);
            }
        } catch (Exception e) {
            // Never let a running-balance bug break the transaction list itself —
            // worst case the balance column is blank, not the whole screen.
            Log.e(TAG, "attachRunningBalances() failed: " + e.getMessage(), e);
        }
    }

    public int countByFilter(TransactionFilter f) {
        BuildResult q = buildSQL(f, true);
        try (Cursor c = db.rawQuery(q.sql(), q.params().toArray(new String[0]))) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    // ── ANALYTICS ─────────────────────────────────────────
    public BigDecimal sumByType(String type, Integer bookId) {
        List<String> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT COALESCE(SUM(amount),0) FROM transactions WHERE type=?");
        params.add(type);
        if (bookId != null && bookId > 0) {
            sql.append(" AND book_id=?");
            params.add(String.valueOf(bookId));
        }
        try (Cursor c = db.rawQuery(sql.toString(), params.toArray(new String[0]))) {
            if (!c.moveToFirst())
                return BigDecimal.ZERO;
            String v = c.getString(0);
            return v != null ? new BigDecimal(v) : BigDecimal.ZERO;
        }
    }

    public List<Map<String, Object>> monthlyTrend(int months, Integer bookId) {
        List<String> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT strftime('%Y-%m', txn_datetime) AS ym, "
                        + "SUM(CASE WHEN type='INCOME'  THEN amount ELSE 0 END) AS income, "
                        + "SUM(CASE WHEN type='EXPENSE' THEN amount ELSE 0 END) AS expense "
                        + "FROM transactions "
                        + "WHERE txn_datetime >= datetime('now', '-' || ? || ' months') ");
        params.add(String.valueOf(months));
        if (bookId != null && bookId > 0) {
            sql.append(" AND book_id=? ");
            params.add(String.valueOf(bookId));
        }
        sql.append("GROUP BY ym ORDER BY ym");
        try (Cursor c = db.rawQuery(sql.toString(), params.toArray(new String[0]))) {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (c.moveToNext()) {
                Map<String, Object> m = new LinkedHashMap<>();
                String ym = c.getString(c.getColumnIndexOrThrow("ym"));
                m.put("month", ym != null ? YearMonth.parse(ym).format(DateTimeFormatter.ofPattern("MMM yyyy")) : "");
                m.put("income", toBigDecimal(c, "income"));
                m.put("expense", toBigDecimal(c, "expense"));
                rows.add(m);
            }
            return rows;
        } catch (Exception e) {
            Log.i(TAG, "monthlyTrend(); --> " + e.getMessage());
            return null;
        }
    }

    public List<Map<String, Object>> expenseByCategory(Integer bookId) {
        return categoryBreakdown("EXPENSE", bookId);
    }

    public List<Map<String, Object>> incomeByCategory(Integer bookId) {
        return categoryBreakdown("INCOME", bookId);
    }

    private List<Map<String, Object>> categoryBreakdown(String type, Integer bookId) {
        List<String> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT c.name, COALESCE(SUM(t.amount),0) AS total "
                        + "FROM transactions t JOIN categories c ON t.category_id = c.id "
                        + "WHERE t.type=? ");
        params.add(type);
        if (bookId != null && bookId > 0) {
            sql.append(" AND t.book_id=? ");
            params.add(String.valueOf(bookId));
        }
        sql.append("GROUP BY c.name ORDER BY total DESC");
        try (Cursor c = db.rawQuery(sql.toString(), params.toArray(new String[0]))) {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (c.moveToNext())
                rows.add(Map.of("name", c.getString(c.getColumnIndexOrThrow("name")), "total", toBigDecimal(c, "total")));
            return rows;
        }
    }

    public List<Map<String, Object>> dailyTotals(int year, int month, Integer bookId) {
        List<String> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT date(txn_datetime) AS day, "
                        + "SUM(CASE WHEN type='INCOME'  THEN amount ELSE 0 END) AS income, "
                        + "SUM(CASE WHEN type='EXPENSE' THEN amount ELSE 0 END) AS expense "
                        + "FROM transactions "
                        + "WHERE CAST(strftime('%Y', txn_datetime) AS INTEGER) = ? "
                        + "  AND CAST(strftime('%m', txn_datetime) AS INTEGER) = ? ");
        params.add(String.valueOf(year));
        params.add(String.valueOf(month));
        if (bookId != null && bookId > 0) {
            sql.append(" AND book_id = ? ");
            params.add(String.valueOf(bookId));
        }
        sql.append("GROUP BY date(txn_datetime) ORDER BY day");
        try (Cursor c = db.rawQuery(sql.toString(), params.toArray(new String[0]))) {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (c.moveToNext()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("day", c.getString(c.getColumnIndexOrThrow("day")));
                m.put("income", toBigDecimal(c, "income"));
                m.put("expense", toBigDecimal(c, "expense"));
                rows.add(m);
            }
            return rows;
        }
    }

    // ── Category breakdown for a specific month ──────
    public List<Map<String, Object>> categoryBreakdownByMonth(String type, int year, int month, Integer bookId) {
        List<String> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT c.name AS category, COALESCE(SUM(t.amount), 0) AS total, COUNT(*) AS txn_count "
                        + "FROM transactions t JOIN categories c ON t.category_id = c.id "
                        + "WHERE t.type = ? "
                        + "  AND CAST(strftime('%Y', t.txn_datetime) AS INTEGER) = ? "
                        + "  AND CAST(strftime('%m', t.txn_datetime) AS INTEGER) = ? ");
        params.add(type);
        params.add(String.valueOf(year));
        params.add(String.valueOf(month));
        if (bookId != null && bookId > 0) {
            sql.append(" AND t.book_id = ? ");
            params.add(String.valueOf(bookId));
        }
        sql.append("GROUP BY c.name ORDER BY total DESC");
        try (Cursor c = db.rawQuery(sql.toString(), params.toArray(new String[0]))) {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (c.moveToNext()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("category", c.getString(c.getColumnIndexOrThrow("category")));
                m.put("total", toBigDecimal(c, "total"));
                m.put("txnCount", c.getInt(c.getColumnIndexOrThrow("txn_count")));
                rows.add(m);
            }
            return rows;
        }
    }

    // ── Sub-category breakdown for a specific month ──
    public List<Map<String, Object>> subCategoryBreakdownByMonth(String type, int year, int month, Integer bookId) {
        List<String> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT c.name AS category, COALESCE(sc.name, 'Uncategorized') AS subcategory, "
                        + "COALESCE(SUM(t.amount), 0) AS total, COUNT(*) AS txn_count "
                        + "FROM transactions t "
                        + "JOIN categories c ON t.category_id = c.id "
                        + "LEFT JOIN sub_categories sc ON t.sub_categories_id = sc.id "
                        + "WHERE t.type = ? "
                        + "  AND CAST(strftime('%Y', t.txn_datetime) AS INTEGER) = ? "
                        + "  AND CAST(strftime('%m', t.txn_datetime) AS INTEGER) = ? ");
        params.add(type);
        params.add(String.valueOf(year));
        params.add(String.valueOf(month));
        if (bookId != null && bookId > 0) {
            sql.append(" AND t.book_id = ? ");
            params.add(String.valueOf(bookId));
        }
        sql.append("GROUP BY c.name, sc.name ORDER BY total DESC");
        try (Cursor c = db.rawQuery(sql.toString(), params.toArray(new String[0]))) {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (c.moveToNext()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("category", c.getString(c.getColumnIndexOrThrow("category")));
                m.put("subcategory", c.getString(c.getColumnIndexOrThrow("subcategory")));
                m.put("total", toBigDecimal(c, "total"));
                m.put("txnCount", c.getInt(c.getColumnIndexOrThrow("txn_count")));
                rows.add(m);
            }
            return rows;
        }
    }

    // ── Day-of-week spending pattern ─────────────────
    public List<Map<String, Object>> dayOfWeekPattern(int year, int month, Integer bookId) {
        List<String> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT CAST(strftime('%w', txn_datetime) AS INTEGER) AS dow_num, "
                        + "SUM(CASE WHEN type='INCOME'  THEN amount ELSE 0 END) AS income, "
                        + "SUM(CASE WHEN type='EXPENSE' THEN amount ELSE 0 END) AS expense, "
                        + "COUNT(*) AS txn_count "
                        + "FROM transactions "
                        + "WHERE CAST(strftime('%Y', txn_datetime) AS INTEGER) = ? "
                        + "  AND CAST(strftime('%m', txn_datetime) AS INTEGER) = ? ");
        params.add(String.valueOf(year));
        params.add(String.valueOf(month));
        if (bookId != null && bookId > 0) {
            sql.append(" AND book_id = ? ");
            params.add(String.valueOf(bookId));
        }
        sql.append("GROUP BY dow_num ORDER BY dow_num");

        // Prefill all 7 days with zero (strftime('%w') is 0=Sunday..6=Saturday, same as Postgres EXTRACT(DOW))
        String[] days = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
        Map<Integer, Map<String, Object>> byDow = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dow", i);
            m.put("label", days[i]);
            m.put("income", BigDecimal.ZERO);
            m.put("expense", BigDecimal.ZERO);
            m.put("txnCount", 0);
            byDow.put(i, m);
        }
        try (Cursor c = db.rawQuery(sql.toString(), params.toArray(new String[0]))) {
            while (c.moveToNext()) {
                int dow = c.getInt(c.getColumnIndexOrThrow("dow_num"));
                Map<String, Object> m = byDow.getOrDefault(dow, new LinkedHashMap<>());
                m.put("income", toBigDecimal(c, "income"));
                m.put("expense", toBigDecimal(c, "expense"));
                m.put("txnCount", c.getInt(c.getColumnIndexOrThrow("txn_count")));
                byDow.put(dow, m);
            }
        }
        return new ArrayList<>(byDow.values());
    }

    // ── Weekly totals within a month ─────────────────
    public List<Map<String, Object>> weeklyTotals(int year, int month, Integer bookId) {
        List<String> params = new ArrayList<>();
        // strftime('%W') = week of year, Monday first day (00-53) — closest SQLite equivalent to EXTRACT(WEEK)
        StringBuilder sql = new StringBuilder(
                "SELECT CAST(strftime('%W', txn_datetime) AS INTEGER) AS wk, "
                        + "MIN(date(txn_datetime)) AS week_start, "
                        + "SUM(CASE WHEN type='INCOME'  THEN amount ELSE 0 END) AS income, "
                        + "SUM(CASE WHEN type='EXPENSE' THEN amount ELSE 0 END) AS expense, "
                        + "COUNT(*) AS txn_count "
                        + "FROM transactions "
                        + "WHERE CAST(strftime('%Y', txn_datetime) AS INTEGER) = ? "
                        + "  AND CAST(strftime('%m', txn_datetime) AS INTEGER) = ? ");
        params.add(String.valueOf(year));
        params.add(String.valueOf(month));
        if (bookId != null && bookId > 0) {
            sql.append(" AND book_id = ? ");
            params.add(String.valueOf(bookId));
        }
        sql.append("GROUP BY wk ORDER BY wk");
        try (Cursor c = db.rawQuery(sql.toString(), params.toArray(new String[0]))) {
            List<Map<String, Object>> rows = new ArrayList<>();
            int weekNum = 1;
            while (c.moveToNext()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("week", "Week " + weekNum++);
                m.put("weekStart", c.getString(c.getColumnIndexOrThrow("week_start")));
                m.put("income", toBigDecimal(c, "income"));
                m.put("expense", toBigDecimal(c, "expense"));
                m.put("txnCount", c.getInt(c.getColumnIndexOrThrow("txn_count")));
                rows.add(m);
            }
            return rows;
        }
    }

    // ── Month summary (income, expense, net, txn count) ──
    public Map<String, Object> monthSummary(int year, int month, Integer bookId) {
        List<String> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT "
                        + "SUM(CASE WHEN type='INCOME'  THEN amount ELSE 0 END) AS income, "
                        + "SUM(CASE WHEN type='EXPENSE' THEN amount ELSE 0 END) AS expense, "
                        + "COUNT(*) AS txn_count, "
                        + "COUNT(DISTINCT category_id) AS cat_count "
                        + "FROM transactions "
                        + "WHERE CAST(strftime('%Y', txn_datetime) AS INTEGER) = ? "
                        + "  AND CAST(strftime('%m', txn_datetime) AS INTEGER) = ? ");
        params.add(String.valueOf(year));
        params.add(String.valueOf(month));
        if (bookId != null && bookId > 0) {
            sql.append(" AND book_id = ? ");
            params.add(String.valueOf(bookId));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        try (Cursor c = db.rawQuery(sql.toString(), params.toArray(new String[0]))) {
            if (c.moveToFirst()) {
                BigDecimal inc = toBigDecimal(c, "income");
                BigDecimal exp = toBigDecimal(c, "expense");
                m.put("income", inc);
                m.put("expense", exp);
                m.put("net", inc.subtract(exp));
                m.put("txnCount", c.getInt(c.getColumnIndexOrThrow("txn_count")));
                m.put("catCount", c.getInt(c.getColumnIndexOrThrow("cat_count")));
                m.put("savingsRate",
                        inc.compareTo(BigDecimal.ZERO) > 0
                                ? inc.subtract(exp).multiply(BigDecimal.valueOf(100)).divide(inc, 1, java.math.RoundingMode.HALF_UP)
                                : BigDecimal.ZERO);
            }
        }
        return m;
    }

    /**
     * Sum of amounts for transactions matching the given filter. Filter must have
     * type set (INCOME or EXPENSE) for meaningful results.
     */
    public BigDecimal sumByFilter(TransactionFilter f) {
        BuildResult q = buildSQL(f, true); // gives: SELECT COUNT(*) FROM transactions t WHERE ...
        String sumSql = q.sql().replace("SELECT COUNT(*)", "SELECT COALESCE(SUM(t.amount), 0)");
        try (Cursor c = db.rawQuery(sumSql, q.params().toArray(new String[0]))) {
            if (!c.moveToFirst())
                return BigDecimal.ZERO;
            String v = c.getString(0);
            return v != null ? new BigDecimal(v) : BigDecimal.ZERO;
        }
    }

    // ── SQL BUILDER ───────────────────────────────────────
    private record BuildResult(String sql, List<String> params) {
    }

    private BuildResult buildSQL(TransactionFilter f, boolean countOnly) {
        List<String> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder();

        if (countOnly) {
            sql.append("SELECT COUNT(*) FROM transactions t WHERE 1=1");
        } else {
            sql.append(baseSelect()).append(" WHERE 1=1");
        }

        if (f.getBookId() != null && f.getBookId() > 0) {
            sql.append(" AND t.book_id = ?");
            params.add(String.valueOf(f.getBookId()));
        }
        if (f.getType() != null && !f.getType().isBlank()) {
            sql.append(" AND t.type = ?");
            params.add(f.getType());
        }
        if (f.getDateFrom() != null) {
            sql.append(" AND t.txn_datetime >= ?");
            params.add(f.getDateFrom().atStartOfDay().format(TS_FMT));
        }
        if (f.getDateTo() != null) {
            sql.append(" AND t.txn_datetime < ?");
            params.add(f.getDateTo().plusDays(1).atStartOfDay().format(TS_FMT));
        }
        // Multi-category IN clause
        if (f.getCategoryIds() != null && !f.getCategoryIds().isEmpty()) {
            sql.append(" AND t.category_id IN (");
            for (int i = 0; i < f.getCategoryIds().size(); i++) {
                sql.append(i > 0 ? ",?" : "?");
                params.add(String.valueOf(f.getCategoryIds().get(i)));
            }
            sql.append(")");
        }
        // Multi-subcategory IN clause
        if (f.getSubCategoryIds() != null && !f.getSubCategoryIds().isEmpty()) {
            sql.append(" AND t.sub_categories_id IN (");
            for (int i = 0; i < f.getSubCategoryIds().size(); i++) {
                sql.append(i > 0 ? ",?" : "?");
                params.add(String.valueOf(f.getSubCategoryIds().get(i)));
            }
            sql.append(")");
        }
        // Amount conditions
        if (f.getAmount1() != null && f.getAmountOp1() != null) {
            sql.append(" AND t.amount ").append(TransactionFilter.safeOp(f.getAmountOp1())).append(" ?");
            params.add(f.getAmount1().toString());
        }
        if (f.getAmount2() != null && f.getAmountOp2() != null) {
            sql.append(" AND t.amount ").append(TransactionFilter.safeOp(f.getAmountOp2())).append(" ?");
            params.add(f.getAmount2().toString());
        }
        // Note + amount +custom field LIKE (SQLite LIKE is case-insensitive for ASCII, standing in for ILIKE)
        if (f.getNoteSearch() != null && !f.getNoteSearch().isBlank()) {
            String[] split = f.getNoteSearch().split(";");
            if (split.length > 0)
                sql.append(" AND (");
            for (int i = 0; i < split.length; i++) {
                String searchWord = split[i].trim();
                String like = "%" + searchWord + "%";
                if (i > 0)
                    sql.append(" OR ");
                sql.append(" (t.note LIKE ? OR t.amount LIKE ? OR EXISTS ("
                        + "  SELECT 1 FROM transaction_custom_values tcv"
                        + "  WHERE tcv.transaction_id = t.id AND tcv.value LIKE ?"
                        + ")) ");
                params.add(like);
                params.add(like);
                params.add(like);
            }
            sql.append(" )");
        }
        if (!countOnly) {
            String col = resolveSortColumn(f.getSortBy());
            String dir = "asc".equalsIgnoreCase(f.getSortDir()) ? "ASC" : "DESC";
            sql.append(" ORDER BY ").append(col).append(" ").append(dir).append(", t.id ").append(dir);
            // tiebreaker for equal values
            if (f.getPageSize() < Integer.MAX_VALUE) {
                sql.append(" LIMIT ? OFFSET ?");
                params.add(String.valueOf(f.getPageSize()));
                params.add(String.valueOf((f.getPage() - 1) * f.getPageSize()));
            }
        }

        return new BuildResult(sql.toString(), params);
    }

    private String baseSelect() {
        return "SELECT t.id, t.type, t.txn_datetime, t.amount, t.note, t.book_id, "
                + "c.id AS cat_id, c.name AS cat_name, "
                + "sc.id AS subcat_id, sc.name AS subcat_name "
                + "FROM transactions t "
                + "LEFT JOIN categories c ON t.category_id=c.id "
                + "LEFT JOIN sub_categories sc ON t.sub_categories_id=sc.id";
    }

    // Public entry point for callers that need to persist custom field
    // values outside of insert() — update() doesn't touch custom values on
    // its own, since a transaction's custom fields can be edited without
    // any of its other columns changing.
    public void saveCustomValues(int txnId, Map<String, String> values) {
        insertCustomValues(txnId, values);
    }

    // Requires a UNIQUE(transaction_id, col_def_id) index on transaction_custom_values.
    private void insertCustomValues(int txnId, Map<String, String> values) {
        for (Map.Entry<String, String> e : values.entrySet()) {
            Integer colDefId = null;
            try (Cursor c = db.rawQuery("SELECT id FROM column_definitions WHERE col_key=?",
                    new String[]{e.getKey()})) {
                if (c.moveToFirst())
                    colDefId = c.getInt(0);
            }
            if (colDefId == null)
                continue;
            ContentValues cv = new ContentValues();
            cv.put("transaction_id", txnId);
            cv.put("col_def_id", colDefId);
            cv.put("value", e.getValue());
            db.insertWithOnConflict("transaction_custom_values", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    private StringBuilder buildBaseSelect() {
        return new StringBuilder(
                "SELECT t.id, t.type, t.txn_datetime, t.amount, t.note, t.book_id," +
                        " c.id AS cat_id, c.name AS cat_name," +
                        " sc.id AS subcat_id, sc.name AS subcat_name" +
                        " FROM transactions t" +
                        " LEFT JOIN categories c ON t.category_id = c.id" +
                        " LEFT JOIN sub_categories sc ON t.sub_categories_id = sc.id");
        // ↑ sub_categories_id (transactions column) → sc.id (sub_categories column)
        // sub_categories_id என்று இல்லை — id மட்டும்தான்
    }

    private Transaction mapRow(Cursor c) {
        Transaction t = new Transaction();
        t.setId(c.getInt(c.getColumnIndexOrThrow("id")));
        t.setType(Transaction.Type.valueOf(c.getString(c.getColumnIndexOrThrow("type"))));
        t.setDateTime(LocalDateTime.parse(c.getString(c.getColumnIndexOrThrow("txn_datetime")), TS_FMT));
        t.setAmount(toBigDecimal(c, "amount"));
        t.setNote(c.getString(c.getColumnIndexOrThrow("note")));
        t.setCategoryId(c.getInt(c.getColumnIndexOrThrow("cat_id")));
        t.setCategoryName(c.getString(c.getColumnIndexOrThrow("cat_name")));
        t.setSubCategoryId(c.getInt(c.getColumnIndexOrThrow("subcat_id")));
        t.setSubCategoryName(c.getString(c.getColumnIndexOrThrow("subcat_name")));
        t.setBookId(c.getInt(c.getColumnIndexOrThrow("book_id")));
        return t;
    }

//    private Transaction mapRow(Cursor c) {
//        Transaction t = new Transaction();
//        t.setId(c.getInt(0));
//        t.setType(Transaction.Type.valueOf(c.getString(1)));
//        t.setDateTime(LocalDateTime.parse(c.getString(2)));
//        t.setAmount(new java.math.BigDecimal(c.getString(3)));
//        t.setNote(c.getString(4));
//        t.setBookId(c.getInt(5));
//        // index 6 = cat_id, 7 = cat_name, 8 = subcat_id, 9 = subcat_name
//        t.setCategoryId(c.isNull(6) ? 0 : c.getInt(6));
//        t.setCategoryName(c.getString(7));
//        t.setSubCategoryId(c.isNull(8) ? 0 : c.getInt(8));
//        t.setSubCategoryName(c.getString(9));
//        return t;
//    }

    private void loadCustomValues(List<Transaction> list) {
        if (list.isEmpty())
            return;
        StringJoiner ids = new StringJoiner(",");
        Map<Integer, Transaction> byId = new LinkedHashMap<>();
        for (Transaction t : list) {
            ids.add(String.valueOf(t.getId()));
            byId.put(t.getId(), t);
        }
        String sql = "SELECT tcv.transaction_id, cd.col_key, tcv.value "
                + "FROM transaction_custom_values tcv "
                + "JOIN column_definitions cd ON tcv.col_def_id=cd.id "
                + "WHERE tcv.transaction_id IN (" + ids + ")";
        try (Cursor c = db.rawQuery(sql, null)) {
            while (c.moveToNext()) {
                Transaction t = byId.get(c.getInt(c.getColumnIndexOrThrow("transaction_id")));
                if (t != null)
                    t.addCustomValue(c.getString(c.getColumnIndexOrThrow("col_key")),
                            c.getString(c.getColumnIndexOrThrow("value")));
            }
        }
    }

    private BigDecimal toBigDecimal(Cursor c, String col) {
        String v = c.getString(c.getColumnIndexOrThrow(col));
        return v != null ? new BigDecimal(v) : BigDecimal.ZERO;
    }

    private String nvl(String s) {
        return s != null ? s : "";
    }

    private String resolveSortColumn(String key) {
        return SORT_COLUMNS.getOrDefault(key, "t.txn_datetime");
    }

    public List<Transaction> findRecent(int limit) {

        List<Transaction> list = new ArrayList<>();

        Cursor c = db.rawQuery(
                "SELECT t.*, c.name AS cat_name, sc.name AS subcat_name " +
                        "FROM transactions t " +
                        "LEFT JOIN categories c ON t.category_id=c.id " +
                        "LEFT JOIN sub_categories sc ON t.sub_categories_id=sc.id " +
                        "WHERE t.is_deleted=0 " +
                        "ORDER BY t.txn_datetime DESC " +
                        "LIMIT ?",
                new String[]{String.valueOf(limit)}
        );

        while (c.moveToNext()) {
            list.add(fromCursor(c));
        }
        c.close();
        return list;
    }

    private Transaction fromCursor(Cursor c) {
        Transaction t = new Transaction();
        t.setId(c.getInt(c.getColumnIndexOrThrow("id")));
        String typeStr = c.getString(c.getColumnIndexOrThrow("type"));
        t.setType("EXPENSE".equals(typeStr) ? Transaction.Type.EXPENSE : Transaction.Type.INCOME);
//        t.setDateTime(c.getString(c.getColumnIndexOrThrow("datetime")));
        String dt = c.getString(c.getColumnIndexOrThrow("datetime"));
        if (dt != null) {
            t.setDateTime(LocalDateTime.parse(dt));
        }
        t.setAmount(BigDecimal.valueOf(c.getDouble(c.getColumnIndexOrThrow("amount"))));
        t.setCategoryId(c.getInt(c.getColumnIndexOrThrow("category_id")));
        t.setSubCategoryId(c.getInt(c.getColumnIndexOrThrow("sub_categories_id")));
        t.setNote(c.getString(c.getColumnIndexOrThrow("note")));
        t.setBookId(c.getInt(c.getColumnIndexOrThrow("book_id")));
        t.setSynced(c.getInt(c.getColumnIndexOrThrow("synced")) == 1);
        int catIdx = c.getColumnIndex("cat_name");
        if (catIdx >= 0) t.setCategoryName(c.getString(catIdx));
        int subIdx = c.getColumnIndex("subcat_name");
        if (subIdx >= 0) t.setSubCategoryName(c.getString(subIdx));
        return t;
    }

    // ── SYNC SUPPORT ───────────────────────────────────────
    // Rows not yet pushed to the cloud (synced=0), across all books —
    // SyncManager iterates these and pushes each one.
    public List<Transaction> getPendingSync() {
        String sql = baseSelect() + " WHERE t.synced = 0";
        List<Transaction> list = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, null)) {
            while (c.moveToNext())
                list.add(mapRow(c));
        }
        loadCustomValues(list);
        return list;
    }

    // Called after a successful push. remoteId is the cloud-side row id
    // (freshly generated on INSERT, or equal to localId on UPDATE/DELETE
    // where the remote row already existed). Requires a `remote_id` column
    // on `transactions` — confirm this exists in your schema.
    public void markSynced(int localId, int remoteId) {
        ContentValues cv = new ContentValues();
        cv.put("synced", 1);
        cv.put("remote_id", remoteId);
        cv.putNull("sync_action");
        db.update("transactions", cv, "id = ?", new String[]{String.valueOf(localId)});
    }

    // Called after a successful pull — wipes local rows for this book and
    // reinserts the authoritative copies from the cloud, marked as synced.
    public void replaceAllFromRemote(int bookId, List<Transaction> remote) {
        db.beginTransaction();
        try {
            db.delete("transactions", "book_id = ?", new String[]{String.valueOf(bookId)});
            for (Transaction t : remote) {
                ContentValues cv = new ContentValues();
                cv.put("id", t.getId());
                cv.put("remote_id", t.getId());
                cv.put("type", t.getType().name());
                cv.put("txn_datetime", t.getDateTime() != null ? t.getDateTime().format(TS_FMT) : null);
                cv.put("amount", t.getAmount() != null ? t.getAmount().toString() : "0");
                cv.put("category_id", t.getCategoryId());
                if (t.getSubCategoryId() > 0)
                    cv.put("sub_categories_id", t.getSubCategoryId());
                else
                    cv.putNull("sub_categories_id");
                cv.put("note", t.getNote());
                cv.put("book_id", t.getBookId());
                cv.put("synced", 1);
                cv.putNull("sync_action");
                db.insertWithOnConflict("transactions", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
}