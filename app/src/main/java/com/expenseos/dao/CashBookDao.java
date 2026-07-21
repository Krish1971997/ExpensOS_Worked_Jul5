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

    private final LocalDB helper;
    private final SQLiteDatabase db;

    public CashBookDao(Context ctx) {
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
            case "name_asc"     -> " ORDER BY b.name ASC";
            case "balance_desc" -> " ORDER BY net_balance DESC";
            case "balance_asc"  -> " ORDER BY net_balance ASC";
            default             -> " ORDER BY COALESCE(t.maxupdated, b.created_at) DESC";
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
        // Only delete if no transactions exist
        db.execSQL("DELETE FROM cash_books WHERE id=? AND NOT EXISTS (SELECT 1 FROM transactions WHERE book_id=?)",
                new Object[]{id, id});
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
    /** Cascade delete: transactions → sub_categories(book-specific) → categories(book-specific) → the book itself. */
    public void deleteCascade(int bookId) {
        db.beginTransaction();
        try {
            // sub_categories under this book's categories
            db.execSQL(
                    "DELETE FROM sub_categories WHERE category_id IN (SELECT id FROM categories WHERE book_id=?)",
                    new Object[]{bookId});
            db.execSQL("DELETE FROM transaction_custom_values WHERE transaction_id IN " +
                    "(SELECT id FROM transactions WHERE book_id=?)", new Object[]{bookId});
            db.execSQL("DELETE FROM transactions WHERE book_id=?", new Object[]{bookId});
            db.execSQL("DELETE FROM categories WHERE book_id=?", new Object[]{bookId});
            db.execSQL("DELETE FROM cash_books WHERE id=?", new Object[]{bookId});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
}