package com.expenseos.dao;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.expenseos.db.LocalDB;
import com.expenseos.model.Category;
import java.util.ArrayList;
import java.util.List;

public class CategoryDao {
    private final SQLiteDatabase db;

    public CategoryDao(Context ctx) {
        db = LocalDB.getInstance(ctx).getWritableDatabase();
    }

    /** Common categories (book_id IS NULL) + book-specific categories */
    public List<Category> findByType(String type, Integer bookId) {
        String sql = "SELECT id, name, type, book_id FROM categories " +
                "WHERE type=? AND (book_id IS NULL" +
                (bookId != null ? " OR book_id=" + bookId : "") +
                ") ORDER BY book_id IS NOT NULL, name";
        List<Category> list = new ArrayList<>();
        Cursor c = db.rawQuery(sql, new String[]{type});
        while (c.moveToNext()) {
            int bId = c.getInt(3);
            Integer bIdVal = c.isNull(3) ? null : bId;
            list.add(new Category(c.getInt(0), c.getString(1), c.getString(2), bIdVal));
        }
        c.close();
        return list;
    }

    /** Backward compat — common categories only */
    public List<Category> findByType(String type) {
        return findByType(type, null);
    }

    /** Insert with optional bookId (null = common) */
    public void insert(String name, String type, Integer bookId) {
        ContentValues cv = new ContentValues();
        cv.put("name",   name.trim());
        cv.put("type",   type);
        if (bookId != null) cv.put("book_id", bookId);
        else                cv.putNull("book_id");
        db.insertWithOnConflict("categories", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public void insert(String name, String type) { insert(name, type, null); }

    public void update(int id, String newName) {
        ContentValues cv = new ContentValues();
        cv.put("name", newName.trim());
        db.update("categories", cv, "id=?", new String[]{String.valueOf(id)});
    }

    public void delete(int id) {
        db.execSQL("UPDATE transactions SET category_id=NULL WHERE category_id=?", new Object[]{id});
        db.delete("categories", "id=?", new String[]{String.valueOf(id)});
    }
}