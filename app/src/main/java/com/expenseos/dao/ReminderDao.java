package com.expenseos.dao;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.expenseos.db.LocalDB;
import com.expenseos.model.Reminder;

import java.util.ArrayList;
import java.util.List;

public class ReminderDao {
    private final LocalDB db;

    public ReminderDao(Context ctx) {
        db = LocalDB.getInstance(ctx);
    }

    public List<Reminder> findAll() {
        List<Reminder> out = new ArrayList<>();
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT id, name FROM reminders ORDER BY name", null)) {
            while (c.moveToNext()) out.add(new Reminder(c.getLong(0), c.getString(1)));
        }
        return out;
    }

    /**
     * Returns existing reminder id if name already exists, else inserts and returns new id.
     */
    public long insertOrGet(String name) {
        SQLiteDatabase wdb = db.getWritableDatabase();
        try (Cursor c = wdb.rawQuery("SELECT id FROM reminders WHERE name=? COLLATE NOCASE", new String[]{name})) {
            if (c.moveToFirst()) return c.getLong(0);
        }
        long id = db.getNextId("reminders");
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("name", name);
        wdb.insert("reminders", null, cv);
        return id;
    }

    public void delete(long id) {
        db.getWritableDatabase().delete("reminders", "id=?", new String[]{String.valueOf(id)});
    }
}