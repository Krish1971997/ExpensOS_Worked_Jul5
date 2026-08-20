package com.expenseos.dao;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.expenseos.db.LocalDB;
import com.expenseos.model.SubCategory;

import java.util.ArrayList;
import java.util.List;

public class SubCategoryDao {
    private final Context ctx;
    private final LocalDB helper;
    private final SQLiteDatabase db;

    public SubCategoryDao(Context ctx) {
        this.ctx = ctx;
        helper = LocalDB.getInstance(ctx);
        db = helper.getWritableDatabase();
    }

    public List<SubCategory> findAll() {
        List<SubCategory> list = new ArrayList<>();
        Cursor c = db.rawQuery(
                "SELECT id,name,category_id FROM sub_categories ORDER BY category_id,name", null);
        while (c.moveToNext())
            list.add(new SubCategory(c.getInt(0), c.getString(1), c.getInt(2)));
        c.close();
        return list;
    }

    public List<SubCategory> findByCategoryId(int catId) {
        List<SubCategory> list = new ArrayList<>();
        Cursor c = db.rawQuery(
                "SELECT id,name,category_id FROM sub_categories WHERE category_id=? ORDER BY name",
                new String[]{String.valueOf(catId)});
        while (c.moveToNext())
            list.add(new SubCategory(c.getInt(0), c.getString(1), c.getInt(2)));
        c.close();
        return list;
    }

    public void insert(String name, int catId) {
        long id = helper.getNextId("sub_categories");
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("name", name.trim());
        cv.put("category_id", catId);
        cv.put("synced", 0);
        db.insertOrThrow("sub_categories", null, cv);
    }

    public void update(int id, String newName) {
        ContentValues cv = new ContentValues();
        cv.put("name", newName.trim());
        db.update("sub_categories", cv, "id=?", new String[]{String.valueOf(id)});
    }

    public void delete(int id) {
        try (Cursor c = db.rawQuery("SELECT name, category_id FROM sub_categories WHERE id=?",
                new String[]{String.valueOf(id)})) {
            if (c.moveToFirst()) {
                int categoryId = c.getInt(1);
                Integer subBookId = null;
                try (Cursor bc = db.rawQuery("SELECT book_id FROM categories WHERE id=?",
                        new String[]{String.valueOf(categoryId)})) {
                    if (bc.moveToFirst() && !bc.isNull(0)) subBookId = bc.getInt(0);
                }
                org.json.JSONObject row = new org.json.JSONObject();
                try {
                    row.put("name", c.getString(0));
                    row.put("category_id", categoryId);

                    org.json.JSONArray txnIds = new org.json.JSONArray();
                    try (Cursor tc = db.rawQuery("SELECT id FROM transactions WHERE sub_cat_id=?",
                            new String[]{String.valueOf(id)})) {
                        while (tc.moveToNext()) txnIds.put(tc.getInt(0));
                    }
                    row.put("unlinked_transaction_ids", txnIds);
                } catch (org.json.JSONException ignored) {
                }
                new RecycleBinDao(ctx).put("sub_categories", id, subBookId, row);
            }
        }
        db.execSQL("UPDATE transactions SET sub_cat_id=NULL WHERE sub_cat_id=?",
                new Object[]{id});
        db.delete("sub_categories", "id=?", new String[]{String.valueOf(id)});
    }

    public void updateParentCategory(int subCategoryId, int newCategoryId) {
        ContentValues cv = new ContentValues();
        cv.put("category_id", newCategoryId);
        cv.put("synced", 0);
        db.update("sub_categories", cv, "id=?", new String[]{String.valueOf(subCategoryId)});
    }
}