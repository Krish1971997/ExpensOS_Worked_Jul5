package com.expenseos.dao;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.expenseos.db.LocalDB;
import com.expenseos.model.Task;

import java.util.ArrayList;
import java.util.List;

public class TaskDao {
    private final LocalDB db;

    public TaskDao(Context ctx) {
        db = LocalDB.getInstance(ctx);
    }

    /**
     * Tasks whose date falls on the given yyyy-MM-dd.
     */
    public List<Task> findByDate(String yyyyMmDd) {
        List<Task> out = new ArrayList<>();
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT id,name,task_datetime,description,color,google_event_id FROM tasks " +
                        "WHERE task_datetime LIKE ? ORDER BY task_datetime",
                new String[]{yyyyMmDd + "%"})) {
            while (c.moveToNext()) out.add(fromCursor(c));
        }
        return out;
    }

    /**
     * All task dates in a yyyy-MM range, for showing dots on the month grid.
     */
    public List<String> findDatesInMonth(String yyyyMm) {
        List<String> out = new ArrayList<>();
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT DISTINCT substr(task_datetime,1,10) FROM tasks WHERE task_datetime LIKE ?",
                new String[]{yyyyMm + "%"})) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    public Task findById(long id) {
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT id,name,task_datetime,description,color,google_event_id FROM tasks WHERE id=?",
                new String[]{String.valueOf(id)})) {
            if (c.moveToFirst()) return fromCursor(c);
        }
        return null;
    }

    public List<Long> findEventIds(long taskId) {
        List<Long> out = new ArrayList<>();
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT event_id FROM task_events WHERE task_id=?", new String[]{String.valueOf(taskId)})) {
            while (c.moveToNext()) out.add(c.getLong(0));
        }
        return out;
    }

    public long insert(Task t) {
        SQLiteDatabase wdb = db.getWritableDatabase();
        long id = db.getNextId("tasks");
        ContentValues cv = toCv(t);
        cv.put("id", id);
        wdb.insert("tasks", null, cv);
        saveEventLinks(id, t.getEventIds());
        return id;
    }

    public void update(Task t) {
        SQLiteDatabase wdb = db.getWritableDatabase();
        wdb.update("tasks", toCv(t), "id=?", new String[]{String.valueOf(t.getId())});
        saveEventLinks(t.getId(), t.getEventIds());
    }

    public void updateGoogleEventId(long taskId, String googleEventId) {
        ContentValues cv = new ContentValues();
        cv.put("google_event_id", googleEventId);
        db.getWritableDatabase().update("tasks", cv, "id=?", new String[]{String.valueOf(taskId)});
    }

    public void delete(long id) {
        db.getWritableDatabase().execSQL("PRAGMA foreign_keys = ON;");
        db.getWritableDatabase().delete("tasks", "id=?", new String[]{String.valueOf(id)});
    }

    private void saveEventLinks(long taskId, List<Long> eventIds) {
        SQLiteDatabase wdb = db.getWritableDatabase();
        wdb.delete("task_events", "task_id=?", new String[]{String.valueOf(taskId)});
        if (eventIds == null) return;
        for (Long eid : eventIds) {
            ContentValues cv = new ContentValues();
            cv.put("id", db.getNextId("task_events"));
            cv.put("task_id", taskId);
            cv.put("event_id", eid);
            wdb.insert("task_events", null, cv);
        }
    }

    private ContentValues toCv(Task t) {
        ContentValues cv = new ContentValues();
        cv.put("name", t.getName());
        cv.put("task_datetime", t.getTaskDateTime());
        cv.put("description", t.getDescription());
        cv.put("color", t.getColor());
        if (t.getGoogleEventId() != null) cv.put("google_event_id", t.getGoogleEventId());
        return cv;
    }

    private Task fromCursor(Cursor c) {
        Task t = new Task();
        t.setId(c.getLong(0));
        t.setName(c.getString(1));
        t.setTaskDateTime(c.getString(2));
        t.setDescription(c.getString(3));
        t.setColor(c.getString(4));
        t.setGoogleEventId(c.getString(5));
        return t;
    }
}