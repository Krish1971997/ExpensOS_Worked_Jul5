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

    public List<Category> findByType(String type) {
        List<Category> list = new ArrayList<>();
        Cursor c = db.rawQuery(
                "SELECT id,name,type FROM categories WHERE type=? ORDER BY name",
                new String[]{type});
        while (c.moveToNext())
            list.add(new Category(c.getInt(0), c.getString(1), c.getString(2)));
        c.close();
        return list;
    }

    public void insert(String name, String type) {
        ContentValues cv = new ContentValues();
        cv.put("name", name.trim());
        cv.put("type", type);
        cv.put("synced", 0);
        db.insertWithOnConflict("categories", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public void delete(int id) {
        db.execSQL("UPDATE transactions SET category_id=NULL WHERE category_id=?",
                new Object[]{id});
        db.delete("categories", "id=?", new String[]{String.valueOf(id)});
    }
}
