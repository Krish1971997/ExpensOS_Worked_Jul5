package com.expenseos.dao;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.expenseos.db.LocalDB;
import com.expenseos.model.ColumnDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * Android/SQLite port of the web ColumnDefinitionDAO.
 * Assumes a UNIQUE(col_key, type) index on column_definitions, matching the
 * original ON CONFLICT (col_key, type) DO NOTHING behaviour via CONFLICT_IGNORE.
 */
public class ColumnDefinitionDao {

    private final SQLiteDatabase db;

    public ColumnDefinitionDao(Context ctx) {
        db = LocalDB.getInstance(ctx).getWritableDatabase();
    }

    public List<ColumnDefinition> findByType(String type) {
        String sql = "SELECT id, col_name, col_key, type FROM column_definitions WHERE type=? ORDER BY id";
        List<ColumnDefinition> list = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, new String[]{type})) {
            while (c.moveToNext())
                list.add(new ColumnDefinition(
                        c.getInt(c.getColumnIndexOrThrow("id")),
                        c.getString(c.getColumnIndexOrThrow("col_name")),
                        c.getString(c.getColumnIndexOrThrow("col_key")),
                        c.getString(c.getColumnIndexOrThrow("type"))));
        }
        return list;
    }

    public void insert(String colName, String type) {
        String colKey = colName.trim().toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        ContentValues cv = new ContentValues();
        cv.put("col_name", colName.trim());
        cv.put("col_key", colKey);
        cv.put("type", type);
        db.insertWithOnConflict("column_definitions", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public void delete(int id) {
        db.beginTransaction();
        try {
            db.delete("transaction_custom_values", "col_def_id = ?", new String[]{String.valueOf(id)});
            db.delete("column_definitions", "id = ?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
}
