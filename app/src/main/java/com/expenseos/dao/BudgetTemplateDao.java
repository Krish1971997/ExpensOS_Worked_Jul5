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

public class BudgetTemplateDao {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final LocalDB helper;
    private final SQLiteDatabase db;

    public BudgetTemplateDao(Context ctx) {
        helper = LocalDB.getInstance(ctx);
        db = helper.getWritableDatabase();
    }

    /**
     * category_id -> percent, for building the config table UI
     */
    public Map<Integer, BigDecimal> loadPercents(int bookId) {
        Map<Integer, BigDecimal> map = new LinkedHashMap<>();
        try (Cursor c = db.rawQuery(
                "SELECT category_id, percent FROM budget_allocation_template WHERE book_id=?",
                new String[]{String.valueOf(bookId)})) {
            while (c.moveToNext()) {
                map.put(c.getInt(0), BigDecimal.valueOf(c.getDouble(1)));
            }
        }
        return map;
    }

    public BigDecimal loadDefaultOverallLimit(int bookId) {
        try (Cursor c = db.rawQuery(
                "SELECT default_overall_limit FROM budget_allocation_template WHERE book_id=? LIMIT 1",
                new String[]{String.valueOf(bookId)})) {
            if (c.moveToFirst() && !c.isNull(0)) return BigDecimal.valueOf(c.getDouble(0));
        }
        return null;
    }

    /**
     * Persist the % split + this month's overall limit as the going-forward template.
     */
    public void saveTemplate(int bookId, Map<Integer, BigDecimal> categoryPercents, BigDecimal overallLimit) {
        String now = LocalDateTime.now().format(TS_FMT);
        db.beginTransaction();
        try {
            db.delete("budget_allocation_template", "book_id=?", new String[]{String.valueOf(bookId)});
            for (Map.Entry<Integer, BigDecimal> e : categoryPercents.entrySet()) {
                long id = helper.getNextId("budget_allocation_template");
                ContentValues cv = new ContentValues();
                cv.put("id", id);
                cv.put("book_id", bookId);
                cv.put("category_id", e.getKey());
                cv.put("percent", e.getValue().doubleValue());
                cv.put("default_overall_limit", overallLimit.doubleValue());
                cv.put("updated_at", now);
                db.insertWithOnConflict("budget_allocation_template", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public boolean hasTemplate(int bookId) {
        try (Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM budget_allocation_template WHERE book_id=?",
                new String[]{String.valueOf(bookId)})) {
            return c.moveToFirst() && c.getInt(0) > 0;
        }
    }
}