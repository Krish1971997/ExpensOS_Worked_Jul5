package com.expenseos.dao;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.expenseos.db.LocalDB;
import com.expenseos.model.Event;
import com.expenseos.model.EventReminder;

import java.util.ArrayList;
import java.util.List;

public class EventDao {
    private final Context ctx;
    private final LocalDB db;

    public EventDao(Context ctx) {
        this.ctx = ctx;
        db = LocalDB.getInstance(ctx);
    }

    public List<Event> findAll() {
        List<Event> out = new ArrayList<>();
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT id,name,offset_direction,offset_days,header FROM events ORDER BY name", null)) {
            while (c.moveToNext()) {
                Event e = new Event();
                e.setId(c.getLong(0));
                e.setName(c.getString(1));
                e.setOffsetDirection(c.getString(2));
                e.setOffsetDays(c.getInt(3));
                e.setHeader(c.getString(4));
                out.add(e);
            }
        }
        return out;
    }

    public long insert(Event e) {
        SQLiteDatabase wdb = db.getWritableDatabase();
        long id = db.getNextId("events");
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("name", e.getName());
        cv.put("offset_direction", e.getOffsetDirection());
        cv.put("offset_days", e.getOffsetDays());
        cv.put("header", e.getHeader());
        wdb.insert("events", null, cv);
        return id;
    }

    public void update(Event e) {
        ContentValues cv = new ContentValues();
        cv.put("name", e.getName());
        cv.put("offset_direction", e.getOffsetDirection());
        cv.put("offset_days", e.getOffsetDays());
        cv.put("header", e.getHeader());
        cv.put("updated_at", "datetime('now')");
        db.getWritableDatabase().update("events", cv, "id=?", new String[]{String.valueOf(e.getId())});
    }

    /**
     * Cascade delete: event_reminders + task_events rows referencing this event go too (FK ON DELETE CASCADE).
     */
    public void delete(long eventId) {
        SQLiteDatabase wdb = db.getWritableDatabase();
        try (Cursor c = wdb.rawQuery("SELECT name, offset_direction, offset_days, header FROM events WHERE id=?",
                new String[]{String.valueOf(eventId)})) {
            if (c.moveToFirst()) {
                org.json.JSONObject row = new org.json.JSONObject();
                try {
                    row.put("name", c.getString(0));
                    row.put("offset_direction", c.getString(1));
                    row.put("offset_days", c.getInt(2));
                    row.put("header", c.isNull(3) ? org.json.JSONObject.NULL : c.getString(3));
                } catch (org.json.JSONException ignored) {
                }
                new RecycleBinDao(ctx).put("events", (int) eventId, row);
            }
        }
        wdb.execSQL("PRAGMA foreign_keys = ON;");
        wdb.delete("events", "id=?", new String[]{String.valueOf(eventId)});
    }

    // ── event_reminders ──────────────────────────────────
    public List<EventReminder> findReminders(long eventId, String type) {
        List<EventReminder> out = new ArrayList<>();
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT er.id, er.event_id, er.reminder_id, r.name " +
                        "FROM event_reminders er JOIN reminders r ON r.id = er.reminder_id " +
                        "WHERE er.event_id=? AND er.type=?",
                new String[]{String.valueOf(eventId), type})) {
            while (c.moveToNext()) {
                EventReminder er = new EventReminder();
                er.setId(c.getLong(0));
                er.setEventId(c.getLong(1));
                er.setReminderId(c.getLong(2));
                er.setReminderName(c.getString(3));
                er.setType(type);
                out.add(er);
            }
        }
        return out;
    }

    /**
     * Sets (or replaces) the single NOTIFICATION or ALARM reminder link for an event.
     */
    public void setReminder(long eventId, String type, long reminderId) {
        SQLiteDatabase wdb = db.getWritableDatabase();
        wdb.delete("event_reminders", "event_id=? AND type=?", new String[]{String.valueOf(eventId), type});
        ContentValues cv = new ContentValues();
        cv.put("id", db.getNextId("event_reminders"));
        cv.put("event_id", eventId);
        cv.put("reminder_id", reminderId);
        cv.put("type", type);
        wdb.insert("event_reminders", null, cv);
    }
}