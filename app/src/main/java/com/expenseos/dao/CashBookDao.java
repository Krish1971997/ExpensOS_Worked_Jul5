package com.expenseos.dao;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.expenseos.db.LocalDB;
import com.expenseos.model.CashBook;

import java.util.ArrayList;
import java.util.List;

public class CashBookDao {
    private final SQLiteDatabase db;

    public CashBookDao(Context ctx) {
        db = LocalDB.getInstance(ctx).getWritableDatabase();
    }

    public List<CashBook> findAll() {
        List<CashBook> list = new ArrayList<>();
        Cursor c = db.rawQuery(
                "SELECT id,name,description,created_at,is_active FROM cash_books ORDER BY created_at DESC", null);
        while (c.moveToNext()) {
            CashBook b = new CashBook();
            b.setId(c.getInt(0));
            b.setName(c.getString(1));
            b.setDescription(c.getString(2));
            b.setCreatedAt(c.getString(3));
            b.setActive(c.getInt(4) == 1);
            list.add(b);
        }
        c.close();
        return list;
    }

    public CashBook findById(int id) {
        Cursor c = db.rawQuery(
                "SELECT id,name,description,created_at FROM cash_books WHERE id=?",
                new String[]{String.valueOf(id)});
        if (c.moveToFirst()) {
            CashBook b = new CashBook(c.getInt(0), c.getString(1), c.getString(2));
            b.setCreatedAt(c.getString(3));
            c.close();
            return b;
        }
        c.close();
        return null;
    }

    public long insert(String name, String description) {
        ContentValues cv = new ContentValues();
        cv.put("name", name.trim());
        cv.put("description", description != null ? description.trim() : "");
        cv.put("synced", 0);
        return db.insertOrThrow("cash_books", null, cv);
    }

    // Income + Expense totals for a book
    public double[] getSummary(int bookId) {
        Cursor c = db.rawQuery(
                "SELECT " +
                        "SUM(CASE WHEN type='INCOME'  THEN amount ELSE 0 END)," +
                        "SUM(CASE WHEN type='EXPENSE' THEN amount ELSE 0 END) " +
                        "FROM transactions WHERE book_id=?",
                new String[]{String.valueOf(bookId)});
        double[] result = {0.0, 0.0};
        if (c.moveToFirst()) {
            result[0] = c.getDouble(0);
            result[1] = c.getDouble(1);
        }
        c.close();
        return result;
    }
}