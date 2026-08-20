package com.expenseos.dao;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.expenseos.db.LocalDB;
import com.expenseos.model.CashBook;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Android/SQLite port of the web CashBookDAO.
 * amount / created_at / updated_at are stored as TEXT (BigDecimal.toString()
 * and "yyyy-MM-dd HH:mm:ss" respectively); SQLite's SUM()/ORDER BY still work
 * correctly on TEXT columns holding numeric-looking content.
 */
public class CashBookDao {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Context ctx;
    private final LocalDB helper;
    private final SQLiteDatabase db;

    public CashBookDao(Context ctx) {
        this.ctx = ctx;
        helper = LocalDB.getInstance(ctx);
        db = helper.getWritableDatabase();
    }

    // Keep old one for backward compatibility (other screens might call it)
    public List<CashBook> findAll() {
        return findAll(null, null);
    }

    /**
     * @param search partial book-name search (case-insensitive), nullable
     * @param sort   one of: "updated" (default), "name_asc", "balance_desc", "balance_asc", "created"
     */
    public List<CashBook> findAll(String search, String sort) {
        StringBuilder sql = new StringBuilder(
                "SELECT b.id, b.name, b.description, b.created_at, t.maxupdated as updated_at, b.is_active," +
                        " COALESCE(t.income,0) - COALESCE(t.expense,0) AS net_balance" +
                        " FROM cash_books b" +
                        " LEFT JOIN (" +
                        "   SELECT book_id,  MAX(updated_at) as maxupdated , " +
//                        "MAX(GREATEST(created_at, updated_at)) AS updated_at, " +
                        "     SUM(CASE WHEN type='INCOME'  THEN amount ELSE 0 END) AS income," +
                        "     SUM(CASE WHEN type='EXPENSE' THEN amount ELSE 0 END) AS expense" +
                        "   FROM transactions GROUP BY book_id" +
                        " ) t ON t.book_id = b.id");

        List<String> args = new ArrayList<>();
        if (search != null && !search.isEmpty()) {
            sql.append(" WHERE LOWER(b.name) LIKE LOWER(?)");
            args.add("%" + search.trim() + "%");
        }
        sql.append(" GROUP BY b.id, t.maxupdated, t.income, t.expense");

        // Sort — updated_at இல்லாம created_at use பண்றோம்
        String order = switch (sort == null ? "" : sort) {
            case "name_asc" -> " ORDER BY b.name ASC";
            case "balance_desc" -> " ORDER BY net_balance DESC";
            case "balance_asc" -> " ORDER BY net_balance ASC";
            default -> " ORDER BY COALESCE(t.maxupdated, b.created_at) DESC";
        };
        sql.append(order);

        List<CashBook> list = new ArrayList<>();
        Cursor c = db.rawQuery(sql.toString(), args.toArray(new String[0]));
        while (c.moveToNext()) {
            CashBook b = new CashBook();
            b.setId(c.getInt(0));
            b.setName(c.getString(1));
            b.setDescription(c.getString(2));
//            b.setCreatedAt(c.getString(3));
//            b.setUpdatedAt(c.isNull(4) ? null : c.getString(4));   // <-- new, index shifted
            String dateStr = c.getString(3);
            b.setCreatedAt(dateStr != null ? LocalDateTime.parse(dateStr, TS_FMT) : null);

            // Handle updatedAt similarly if it's also a LocalDateTime now
            String updatedStr = c.getString(4);
            b.setUpdatedAt(updatedStr != null ? LocalDateTime.parse(updatedStr, TS_FMT) : null);
            b.setActive(c.getInt(5) == 1);                          // <-- index shifted
            list.add(b);
        }
        c.close();
        return list;
    }

    public CashBook findById(int id) {
        String sql = "SELECT id, name, description, created_at, is_active FROM cash_books WHERE id=?";
        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? mapRow(c) : null;
        }
    }

    public long insert(String name, String description) {
        long id = helper.getNextId("cash_books");
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("name", name.trim());
        cv.put("description", description != null ? description.trim() : "");
        cv.put("created_at", LocalDateTime.now().format(TS_FMT));
        cv.put("updated_at", LocalDateTime.now().format(TS_FMT));
        return db.insert("cash_books", null, cv); // returns new row id, or -1 on failure
    }

    public void update(int id, String name, String description) {
        update(id, name, description, true);
    }

    public void update(int id, String name, String description, boolean active) {
        ContentValues cv = new ContentValues();
        cv.put("name", name.trim());
        cv.put("description", description != null ? description.trim() : "");
        cv.put("is_active", active ? 1 : 0);
        cv.put("updated_at", LocalDateTime.now().format(TS_FMT));
        db.update("cash_books", cv, "id=?", new String[]{String.valueOf(id)});
    }

    public void delete(int id) {
        // Only delete if no transactions exist — same guard as before
        try (Cursor chk = db.rawQuery("SELECT COUNT(*) FROM transactions WHERE book_id=?", new String[]{String.valueOf(id)})) {
            if (chk.moveToFirst() && chk.getInt(0) > 0) return;
        }
        try (Cursor c = db.rawQuery("SELECT name, description, is_active FROM cash_books WHERE id=?",
                new String[]{String.valueOf(id)})) {
            if (c.moveToFirst()) {
                org.json.JSONObject row = new org.json.JSONObject();
                try {
                    row.put("name", c.getString(0));
                    row.put("description", c.isNull(1) ? org.json.JSONObject.NULL : c.getString(1));
                    row.put("is_active", c.getInt(2));
                } catch (org.json.JSONException ignored) {
                }
                new RecycleBinDao(ctx).put("cash_books", id, id, row);
            }
        }
        db.delete("cash_books", "id=?", new String[]{String.valueOf(id)});
    }

    /**
     * Summary stats per book
     */
    public Map<String, BigDecimal> getSummary(int bookId) {
        String sql = "SELECT "
                + "COALESCE(SUM(CASE WHEN type='INCOME'  THEN amount ELSE 0 END), 0) AS income, "
                + "COALESCE(SUM(CASE WHEN type='EXPENSE' THEN amount ELSE 0 END), 0) AS expense "
                + "FROM transactions WHERE book_id = ?";
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(bookId)})) {
            if (c.moveToFirst()) {
                m.put("income", new BigDecimal(c.getString(c.getColumnIndexOrThrow("income"))));
                m.put("expense", new BigDecimal(c.getString(c.getColumnIndexOrThrow("expense"))));
            }
        }
        return m;
    }

    private CashBook mapRow(Cursor c) {
        return new CashBook(
                c.getInt(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("name")),
                c.getString(c.getColumnIndexOrThrow("description")),
                LocalDateTime.parse(c.getString(c.getColumnIndexOrThrow("created_at")), TS_FMT),
//                c.getString(c.getColumnIndexOrThrow("created_at")), // CashBook.createdAt is a String
                c.getInt(c.getColumnIndexOrThrow("is_active")) == 1);
    }

    /**
     * Force delete: removes all transactions + categories (scoped to this book) + the book itself.
     * Call only after the user has explicitly confirmed via exact-name match.
     */
    /**
     * Cascade delete: transactions → sub_categories(book-specific) → categories(book-specific) → the book itself.
     */
    public void deleteCascade(int bookId) {
        RecycleBinDao bin = new RecycleBinDao(ctx);

        try (Cursor c = db.rawQuery("SELECT name, description, is_active FROM cash_books WHERE id=?",
                new String[]{String.valueOf(bookId)})) {
            if (c.moveToFirst()) {
                org.json.JSONObject row = new org.json.JSONObject();
                try {
                    row.put("name", c.getString(0));
                    row.put("description", c.isNull(1) ? org.json.JSONObject.NULL : c.getString(1));
                    row.put("is_active", c.getInt(2));
                } catch (org.json.JSONException ignored) {
                }
                bin.put("cash_books", bookId, bookId, row);
            }
        }

        try (Cursor c = db.rawQuery("SELECT id, name, type, book_id FROM categories WHERE book_id=?",
                new String[]{String.valueOf(bookId)})) {
            while (c.moveToNext()) {
                int catId = c.getInt(0);
                org.json.JSONObject row = new org.json.JSONObject();
                try {
                    row.put("name", c.getString(1));
                    row.put("type", c.getString(2));
                    row.put("book_id", c.getInt(3));

                    org.json.JSONArray subsArr = new org.json.JSONArray();
                    try (Cursor sc = db.rawQuery("SELECT id, name FROM sub_categories WHERE category_id=?",
                            new String[]{String.valueOf(catId)})) {
                        while (sc.moveToNext()) {
                            org.json.JSONObject sObj = new org.json.JSONObject();
                            sObj.put("id", sc.getInt(0));
                            sObj.put("name", sc.getString(1));
                            subsArr.put(sObj);
                        }
                    }
                    row.put("sub_categories_data", subsArr);
                } catch (org.json.JSONException ignored) {
                }
                bin.put("categories", catId, bookId, row);
            }
        }

        try (Cursor c = db.rawQuery(
                "SELECT id, type, txn_datetime, amount, category_id, sub_categories_id, note, payment_type, book_id " +
                        "FROM transactions WHERE book_id=?", new String[]{String.valueOf(bookId)})) {
            while (c.moveToNext()) {
                int txnId = c.getInt(0);
                org.json.JSONObject row = new org.json.JSONObject();
                try {
                    row.put("type", c.getString(1));
                    row.put("txn_datetime", c.getString(2));
                    row.put("amount", c.getString(3));
                    row.put("category_id", c.isNull(4) ? org.json.JSONObject.NULL : c.getInt(4));
                    row.put("sub_categories_id", c.isNull(5) ? org.json.JSONObject.NULL : c.getInt(5));
                    row.put("note", c.isNull(6) ? org.json.JSONObject.NULL : c.getString(6));
                    row.put("payment_type", c.isNull(7) ? org.json.JSONObject.NULL : c.getString(7));
                    row.put("book_id", c.getInt(8));

                    org.json.JSONArray customArr = new org.json.JSONArray();
                    try (Cursor vc = db.rawQuery(
                            "SELECT id, col_def_id, value FROM transaction_custom_values WHERE transaction_id=?",
                            new String[]{String.valueOf(txnId)})) {
                        while (vc.moveToNext()) {
                            org.json.JSONObject vObj = new org.json.JSONObject();
                            vObj.put("id", vc.getInt(0));
                            vObj.put("col_def_id", vc.getInt(1));
                            vObj.put("value", vc.isNull(2) ? org.json.JSONObject.NULL : vc.getString(2));
                            customArr.put(vObj);
                        }
                    }
                    row.put("custom_values_data", customArr);

                    // 🟢 receipts (was missing entirely before — orphaned on cascade delete)
                    org.json.JSONArray receiptsArr = new org.json.JSONArray();
                    try (Cursor rc = db.rawQuery(
                            "SELECT id, file_name, file_type, file_data, file_size FROM transaction_receipts WHERE transaction_id=?",
                            new String[]{String.valueOf(txnId)})) {
                        while (rc.moveToNext()) {
                            org.json.JSONObject rObj = new org.json.JSONObject();
                            rObj.put("id", rc.getInt(0));
                            rObj.put("file_name", rc.getString(1));
                            rObj.put("file_type", rc.getString(2));
                            byte[] blob = rc.getBlob(3);
                            rObj.put("file_data", blob != null
                                    ? android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP)
                                    : org.json.JSONObject.NULL);
                            rObj.put("file_size", rc.getInt(4));
                            receiptsArr.put(rObj);
                        }
                    }
                    row.put("receipts_data", receiptsArr);

                    // 🟢 audit log (also was missing entirely before)
                    org.json.JSONArray auditArr = new org.json.JSONArray();
                    try (Cursor ac = db.rawQuery(
                            "SELECT id, action, changed_by, field_name, old_value, new_value, note, changed_at " +
                                    "FROM transaction_audit_log WHERE transaction_id=?", new String[]{String.valueOf(txnId)})) {
                        while (ac.moveToNext()) {
                            org.json.JSONObject aObj = new org.json.JSONObject();
                            aObj.put("id", ac.getInt(0));
                            aObj.put("action", ac.getString(1));
                            aObj.put("changed_by", ac.getString(2));
                            aObj.put("field_name", ac.getString(3));
                            aObj.put("old_value", ac.getString(4));
                            aObj.put("new_value", ac.getString(5));
                            aObj.put("note", ac.getString(6));
                            aObj.put("changed_at", ac.getString(7));
                            auditArr.put(aObj);
                        }
                    }
                    row.put("audit_data", auditArr);
                } catch (org.json.JSONException ignored) {
                }
                bin.put("transactions", txnId, bookId, row);
            }
        }

        // 🟢 book-specific keyword mappings (book_id IS NULL / common ones are never touched)
        try (Cursor c = db.rawQuery(
                "SELECT id, keyword, type, category_id, sub_category_id FROM keyword_mappings WHERE book_id=?",
                new String[]{String.valueOf(bookId)})) {
            while (c.moveToNext()) {
                org.json.JSONObject row = new org.json.JSONObject();
                try {
                    row.put("keyword", c.getString(1));
                    row.put("type", c.getString(2));
                    row.put("category_id", c.getInt(3));
                    row.put("sub_category_id", c.isNull(4) ? org.json.JSONObject.NULL : c.getInt(4));
                    row.put("book_id", bookId);
                } catch (org.json.JSONException ignored) {
                }
                bin.put("keyword_mappings", c.getInt(0), bookId, row);
            }
        }

        // 🟢 budgets (was never touched at all before — orphaned rows left
        // behind on every cascade delete) + their budget_categories nested in
        try (Cursor c = db.rawQuery("SELECT id, year, month, overall_limit FROM budgets WHERE book_id=?",
                new String[]{String.valueOf(bookId)})) {
            while (c.moveToNext()) {
                int budgetId = c.getInt(0);
                org.json.JSONObject row = new org.json.JSONObject();
                try {
                    row.put("book_id", bookId);
                    row.put("year", c.getInt(1));
                    row.put("month", c.getInt(2));
                    row.put("overall_limit", c.getDouble(3));

                    org.json.JSONArray catsArr = new org.json.JSONArray();
                    try (Cursor bcc = db.rawQuery(
                            "SELECT id, category_id, cat_limit, alert_pct FROM budget_categories WHERE budget_id=?",
                            new String[]{String.valueOf(budgetId)})) {
                        while (bcc.moveToNext()) {
                            org.json.JSONObject bcObj = new org.json.JSONObject();
                            bcObj.put("id", bcc.getInt(0));
                            bcObj.put("category_id", bcc.getInt(1));
                            bcObj.put("cat_limit", bcc.getDouble(2));
                            bcObj.put("alert_pct", bcc.getInt(3));
                            catsArr.put(bcObj);
                        }
                    }
                    row.put("budget_categories_data", catsArr);
                } catch (org.json.JSONException ignored) {
                }
                bin.put("budgets", budgetId, bookId, row);
            }
        }

        db.beginTransaction();

        try {
            db.execSQL("DELETE FROM transaction_receipts WHERE transaction_id IN " +
                    "(SELECT id FROM transactions WHERE book_id=?)", new Object[]{bookId});
            db.execSQL("DELETE FROM transaction_audit_log WHERE transaction_id IN " +
                    "(SELECT id FROM transactions WHERE book_id=?)", new Object[]{bookId});
            db.execSQL("DELETE FROM transaction_custom_values WHERE transaction_id IN " +
                    "(SELECT id FROM transactions WHERE book_id=?)", new Object[]{bookId});
            db.execSQL("DELETE FROM keyword_mappings WHERE book_id=?", new Object[]{bookId});
            db.execSQL("DELETE FROM budget_categories WHERE budget_id IN (SELECT id FROM budgets WHERE book_id=?)",
                    new Object[]{bookId});
            db.execSQL("DELETE FROM budgets WHERE book_id=?", new Object[]{bookId});
            db.execSQL(
                    "DELETE FROM sub_categories WHERE category_id IN (SELECT id FROM categories WHERE book_id=?)",
                    new Object[]{bookId});
            db.execSQL("DELETE FROM transactions WHERE book_id=?", new Object[]{bookId});
            db.execSQL("DELETE FROM categories WHERE book_id=?", new Object[]{bookId});
            db.execSQL("DELETE FROM cash_books WHERE id=?", new Object[]{bookId});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
}