package com.expenseos.dao;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.expenseos.db.LocalDB;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ONE shared category-amount template, used identically by every cash
 * book — reuses the existing budget_allocation_template table (originally
 * per-book) with book_id fixed at GLOBAL_BOOK_ID, a sentinel that no real
 * cash book ever has (book ids start at 1). The "percent" column is
 * repurposed here to hold the flat amount directly — no scaling per book,
 * no migration needed on top of the table that already existed.
 */
public class BudgetTemplateDao {

    private static final int GLOBAL_BOOK_ID = 0;
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LocalDB helper;
    private final SQLiteDatabase db;

    public BudgetTemplateDao(Context ctx) {
        helper = LocalDB.getInstance(ctx);
        db = helper.getWritableDatabase();
    }

    /**
     * category_id -> amount, for building the config table UI and for applying to a book.
     */
    public Map<Integer, BigDecimal> loadGlobalAmounts() {
        Map<Integer, BigDecimal> map = new LinkedHashMap<>();
        try (Cursor c = db.rawQuery(
                "SELECT category_id, percent FROM budget_allocation_template WHERE book_id=?",
                new String[]{String.valueOf(GLOBAL_BOOK_ID)})) {
            while (c.moveToNext()) {
                map.put(c.getInt(0), BigDecimal.valueOf(c.getDouble(1)));
            }
        }
        return map;
    }

    public boolean hasGlobalTemplate() {
        try (Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM budget_allocation_template WHERE book_id=?",
                new String[]{String.valueOf(GLOBAL_BOOK_ID)})) {
            return c.moveToFirst() && c.getInt(0) > 0;
        }
    }

    /**
     * Replaces the whole template with the given category->amount map.
     */
    public void saveGlobalTemplate(Map<Integer, BigDecimal> categoryAmounts) {
        String now = LocalDateTime.now().format(TS_FMT);
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal amt : categoryAmounts.values()) total = total.add(amt);

        db.beginTransaction();
        try {
            db.delete("budget_allocation_template", "book_id=?", new String[]{String.valueOf(GLOBAL_BOOK_ID)});
            for (Map.Entry<Integer, BigDecimal> e : categoryAmounts.entrySet()) {
                long id = helper.getNextId("budget_allocation_template");
                ContentValues cv = new ContentValues();
                cv.put("id", id);
                cv.put("book_id", GLOBAL_BOOK_ID);
                cv.put("category_id", e.getKey());
                cv.put("percent", e.getValue().doubleValue()); // repurposed: holds the flat amount
                cv.put("default_overall_limit", total.doubleValue());
                cv.put("updated_at", now);
                db.insertWithOnConflict("budget_allocation_template", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
}