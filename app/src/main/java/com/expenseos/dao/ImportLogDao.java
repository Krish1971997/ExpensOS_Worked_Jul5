package com.expenseos.dao;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.expenseos.db.LocalDB;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class ImportLogDao {

    public static class Entry {
        public String at, action, status, message;
        public int total, success, failed;
    }

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final SQLiteDatabase db;

    public ImportLogDao(Context ctx) {
        db = LocalDB.getInstance(ctx).getWritableDatabase();
    }

    public void insert(String action, int total, int success, int failed, String status, String message) {
        ContentValues cv = new ContentValues();
        cv.put("imported_at", LocalDateTime.now().format(TS_FMT));
        cv.put("action", action);
        cv.put("total_rows", total);
        cv.put("success_count", success);
        cv.put("failed_count", failed);
        cv.put("status", status);
        cv.put("message", message);
        db.insert("import_log", null, cv);
    }

    public List<Entry> findRecent(int limit) {
        List<Entry> list = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT imported_at, action, total_rows, success_count, failed_count, status, message " +
                "FROM import_log ORDER BY id DESC LIMIT " + limit, null)) {
            while (c.moveToNext()) {
                Entry e = new Entry();
                e.at = c.getString(0);
                e.action = c.getString(1);
                e.total = c.getInt(2);
                e.success = c.getInt(3);
                e.failed = c.getInt(4);
                e.status = c.getString(5);
                e.message = c.getString(6);
                list.add(e);
            }
        }
        return list;
    }
}