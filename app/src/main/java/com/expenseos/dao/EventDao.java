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
    private final LocalDB db;

    public EventDao(Context ctx) {
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
        wdb.execSQL("PRAGMA foreign_keys = ON;");
        wdb.delete("events", "id=?", new String[]{String.valueOf(eventId)});
    }

    // ── event_reminders ──────────────────────────────────
    public List<EventReminder> findReminders(long eventId, String type) {
        List<EventReminder> out = new ArrayList<>();
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT er.id, er.event_id, er.reminder_id, r.name, er.type, " +
                        "er.offset_direction, er.offset_days, er.time_hour, er.time_minute " +
                        "FROM event_reminders er JOIN reminders r ON r.id = er.reminder_id " +
                        "WHERE er.event_id=? AND er.type=?",
                new String[]{String.valueOf(eventId), type})) {
            while (c.moveToNext()) {
                EventReminder er = new EventReminder();
                er.setId(c.getLong(0));
                er.setEventId(c.getLong(1));
                er.setReminderId(c.getLong(2));
                er.setReminderName(c.getString(3));
                er.setType(c.getString(4));
                er.setOffsetDirection(c.getString(5));
                er.setOffsetDays(c.getInt(6));
                er.setTimeHour(c.getInt(7));
                er.setTimeMinute(c.getInt(8));
                out.add(er);
            }
        }
        return out;
    }

    public void saveReminder(EventReminder er) {
        SQLiteDatabase wdb = db.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("event_id", er.getEventId());
        cv.put("reminder_id", er.getReminderId());
        cv.put("type", er.getType());
        cv.put("offset_direction", er.getOffsetDirection());
        cv.put("offset_days", er.getOffsetDays());
        cv.put("time_hour", er.getTimeHour());
        cv.put("time_minute", er.getTimeMinute());

        if (er.getId() > 0) {
            wdb.update("event_reminders", cv, "id=?", new String[]{String.valueOf(er.getId())});
        } else {
            long id = db.getNextId("event_reminders");
            cv.put("id", id);
            wdb.insert("event_reminders", null, cv);
        }
    }

    /**
     * Replaces all NOTIFICATION or ALARM rows for an event in one go (used by Save on AddEventActivity).
     */
    public void replaceReminders(long eventId, String type, List<EventReminder> list) {
        SQLiteDatabase wdb = db.getWritableDatabase();
        wdb.delete("event_reminders", "event_id=? AND type=?", new String[]{String.valueOf(eventId), type});
        for (EventReminder er : list) {
            er.setId(0);
            er.setEventId(eventId);
            er.setType(type);
            saveReminder(er);
        }
    }
}