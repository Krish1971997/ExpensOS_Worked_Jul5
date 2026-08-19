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
    private final Context ctx;
    private final LocalDB db;

    public ReminderDao(Context ctx) {
        this.ctx = ctx;
        db = LocalDB.getInstance(ctx);
    }

    public List<Reminder> findAll() {
        List<Reminder> out = new ArrayList<>();
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT id,name,offset_value,offset_unit,time_hour,time_minute FROM reminders ORDER BY name", null)) {
            while (c.moveToNext()) out.add(fromCursor(c));
        }
        return out;
    }

    public Reminder findById(long id) {
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT id,name,offset_value,offset_unit,time_hour,time_minute FROM reminders WHERE id=?",
                new String[]{String.valueOf(id)})) {
            if (c.moveToFirst()) return fromCursor(c);
        }
        return null;
    }

    public long insert(Reminder r) {
        SQLiteDatabase wdb = db.getWritableDatabase();
        long id = db.getNextId("reminders");
        ContentValues cv = toCv(r);
        cv.put("id", id);
        wdb.insert("reminders", null, cv);
        return id;
    }

    public void update(Reminder r) {
        db.getWritableDatabase().update("reminders", toCv(r), "id=?", new String[]{String.valueOf(r.getId())});
    }

    /**
     * Used by "existing or create new" reminder pickers — matches by exact name, else inserts a bare-minimum row.
     */
    public long insertOrGet(String name, int offsetValue, String offsetUnit, int hour, int minute) {
        SQLiteDatabase wdb = db.getWritableDatabase();
        try (Cursor c = wdb.rawQuery("SELECT id FROM reminders WHERE name=? COLLATE NOCASE", new String[]{name})) {
            if (c.moveToFirst()) return c.getLong(0);
        }
        Reminder r = new Reminder();
        r.setName(name);
        r.setOffsetValue(offsetValue);
        r.setOffsetUnit(offsetUnit);
        r.setTimeHour(hour);
        r.setTimeMinute(minute);
        return insert(r);
    }

    /**
     * Cascade delete: event_reminders rows referencing this reminder go too (FK ON DELETE — see note below).
     */
    public void delete(long id) {
        SQLiteDatabase wdb = db.getWritableDatabase();
        try (Cursor c = wdb.rawQuery(
                "SELECT name, offset_value, offset_unit, time_hour, time_minute FROM reminders WHERE id=?",
                new String[]{String.valueOf(id)})) {
            if (c.moveToFirst()) {
                org.json.JSONObject row = new org.json.JSONObject();
                try {
                    row.put("name", c.getString(0));
                    row.put("offset_value", c.getInt(1));
                    row.put("offset_unit", c.getString(2));
                    row.put("time_hour", c.getInt(3));
                    row.put("time_minute", c.getInt(4));
                } catch (org.json.JSONException ignored) {
                }
                new RecycleBinDao(ctx).put("reminders", (int) id, row);
            }
        }
        wdb.delete("event_reminders", "reminder_id=?", new String[]{String.valueOf(id)});
        wdb.delete("reminders", "id=?", new String[]{String.valueOf(id)});
    }

    private ContentValues toCv(Reminder r) {
        ContentValues cv = new ContentValues();
        cv.put("name", r.getName());
        cv.put("offset_value", r.getOffsetValue());
        cv.put("offset_unit", r.getOffsetUnit());
        cv.put("time_hour", r.getTimeHour());
        cv.put("time_minute", r.getTimeMinute());
        return cv;
    }

    private Reminder fromCursor(Cursor c) {
        Reminder r = new Reminder();
        r.setId(c.getLong(0));
        r.setName(c.getString(1));
        r.setOffsetValue(c.getInt(2));
        r.setOffsetUnit(c.getString(3));
        r.setTimeHour(c.getInt(4));
        r.setTimeMinute(c.getInt(5));
        return r;
    }
}