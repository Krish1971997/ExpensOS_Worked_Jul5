package com.expenseos.dao;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.expenseos.db.LocalDB;
import com.expenseos.model.SchedulerConfig;
import com.expenseos.model.SchedulerLog;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Android/SQLite port of the server-side SchedulerDAO (Postgres).
 * Timestamps are stored as TEXT in "yyyy-MM-dd HH:mm:ss" format, matching
 * the rest of this app's DAOs. ids are reserved from LocalDB's
 * id_sequences table instead of relying on AUTOINCREMENT/serial, so they
 * stay app-controlled and won't collide when data is migrated in from the
 * server DB.
 */
public class SchedulerDao {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LocalDB helper;
    private final SQLiteDatabase db;

    public SchedulerDao(Context ctx) {
        helper = LocalDB.getInstance(ctx);
        db = helper.getWritableDatabase();
    }

    // ── List all schedulers ────────────────────────────────────────
    public List<SchedulerConfig> findAll() {
        String sql = "SELECT * FROM schedulers ORDER BY id";
        List<SchedulerConfig> list = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, null)) {
            while (c.moveToNext())
                list.add(mapConfig(c));
        }
        return list;
    }

    // ── Find by name ───────────────────────────────────────────────
    public SchedulerConfig findByName(String name) {
        String sql = "SELECT * FROM schedulers WHERE name=?";
        try (Cursor c = db.rawQuery(sql, new String[]{name})) {
            return c.moveToFirst() ? mapConfig(c) : null;
        }
    }

    // ── Insert a new scheduler ────────────────────────────────────
    public long insertScheduler(String name, String displayName, boolean enabled, String repeatType,
                                String repeatDays, int runHour, int runMinute, LocalDateTime nextRunAt) {
        long id = helper.getNextId("schedulers");
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("name", name.trim());
        cv.put("display_name", displayName.trim());
        cv.put("enabled", enabled ? 1 : 0);
        cv.put("repeat_type", repeatType);
        cv.put("repeat_days", repeatDays);
        cv.put("run_hour", runHour);
        cv.put("run_minute", runMinute);
        if (nextRunAt != null)
            cv.put("next_run_at", nextRunAt.format(TS_FMT));
        else
            cv.putNull("next_run_at");
        cv.put("created_at", LocalDateTime.now().format(TS_FMT));
        cv.put("updated_at", LocalDateTime.now().format(TS_FMT));
        // schedulers.name is UNIQUE — ignore silently on conflict, same as
        // the CONFLICT_IGNORE pattern used elsewhere in this app.
        return db.insertWithOnConflict("schedulers", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    // ── Update scheduler config ────────────────────────────────────
    public void update(SchedulerConfig s) {
        ContentValues cv = new ContentValues();
        cv.put("enabled", s.isEnabled() ? 1 : 0);
        cv.put("repeat_type", s.getRepeatType());
        cv.put("repeat_days", s.getRepeatDays());
        cv.put("run_hour", s.getRunHour());
        cv.put("run_minute", s.getRunMinute());
        if (s.getNextRunAt() != null)
            cv.put("next_run_at", s.getNextRunAt().format(TS_FMT));
        else
            cv.putNull("next_run_at");
        cv.put("updated_at", LocalDateTime.now().format(TS_FMT));
        db.update("schedulers", cv, "id=?", new String[]{String.valueOf(s.getId())});
    }

    // ── Mark run started ───────────────────────────────────────────
    public long logStart(int schedulerId) {
        long logId = helper.getNextId("scheduler_log");
        String now = LocalDateTime.now().format(TS_FMT);

        db.beginTransaction();
        try {
            ContentValues schedCv = new ContentValues();
            schedCv.put("last_run_status", "RUNNING");
            schedCv.put("updated_at", now);
            db.update("schedulers", schedCv, "id=?", new String[]{String.valueOf(schedulerId)});

            ContentValues logCv = new ContentValues();
            logCv.put("id", logId);
            logCv.put("scheduler_id", schedulerId);
            logCv.put("started_at", now);
            logCv.put("status", "RUNNING");
            db.insertOrThrow("scheduler_log", null, logCv);

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return logId;
    }

    // ── Mark run finished ──────────────────────────────────────────
    // NOTE: last_run_at only advances on a real "SUCCESS". On "FAILED"
    // it is left untouched, so the NEXT attempt's sync window still
    // starts from the last time the job actually succeeded — instead
    // of drifting forward on every failed attempt and risking older
    // unsynced data eventually falling outside the 7-day safety window
    // in SchedulerEngine.
    public void logFinish(int logId, int schedulerId, String status, String message, int rowsSynced,
                          LocalDateTime nextRunAt) {
        String now = LocalDateTime.now().format(TS_FMT);

        db.beginTransaction();
        try {
            ContentValues logCv = new ContentValues();
            logCv.put("finished_at", now);
            logCv.put("status", status);
            logCv.put("message", message);
            logCv.put("rows_synced", rowsSynced);
            logCv.put("updated_at", now);
            db.update("scheduler_log", logCv, "id=?", new String[]{String.valueOf(logId)});

            String nextRunStr = nextRunAt != null ? nextRunAt.format(TS_FMT) : null;
            db.execSQL(
                    "UPDATE schedulers SET " +
                            "last_run_at = CASE WHEN ? = 'SUCCESS' THEN datetime('now') ELSE last_run_at END, " +
                            "last_run_status = ?, last_run_msg = ?, next_run_at = ?, updated_at = ? " +
                            "WHERE id = ?",
                    new Object[]{status, status, message, nextRunStr, now, schedulerId});

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ── Recent logs ────────────────────────────────────────────────
    public List<SchedulerLog> recentLogs(int schedulerId, int limit) {
        String sql = "SELECT sl.*, s.display_name AS scheduler_name " +
                "FROM scheduler_log sl " +
                "JOIN schedulers s ON s.id = sl.scheduler_id " +
                "WHERE sl.scheduler_id = ? " +
                "ORDER BY sl.started_at DESC LIMIT ?";
        List<SchedulerLog> list = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(schedulerId), String.valueOf(limit)})) {
            while (c.moveToNext())
                list.add(mapLog(c));
        }
        return list;
    }

    // ── All recent logs (all schedulers) ───────────────────────────
    public List<SchedulerLog> allRecentLogs(int limit) {
        String sql = "SELECT sl.*, s.display_name AS scheduler_name " +
                "FROM scheduler_log sl " +
                "JOIN schedulers s ON s.id = sl.scheduler_id " +
                "ORDER BY sl.started_at DESC LIMIT ?";
        List<SchedulerLog> list = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(limit)})) {
            while (c.moveToNext())
                list.add(mapLog(c));
        }
        return list;
    }

    public List<SchedulerLog> allRecentLogs(int limit, int offset) {
        String sql = "SELECT sl.*, s.display_name AS scheduler_name " +
                "FROM scheduler_log sl " +
                "JOIN schedulers s ON s.id = sl.scheduler_id " +
                "ORDER BY sl.started_at DESC LIMIT ? OFFSET ?";
        List<SchedulerLog> list = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(limit), String.valueOf(offset)})) {
            while (c.moveToNext())
                list.add(mapLog(c));
        }
        return list;
    }

    public int countAllLogs() {
        String sql = "SELECT COUNT(*) FROM scheduler_log";
        try (Cursor c = db.rawQuery(sql, null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    private SchedulerConfig mapConfig(Cursor c) {
        SchedulerConfig s = new SchedulerConfig();
        s.setId(c.getInt(c.getColumnIndexOrThrow("id")));
        s.setName(c.getString(c.getColumnIndexOrThrow("name")));
        s.setDisplayName(c.getString(c.getColumnIndexOrThrow("display_name")));
        s.setEnabled(c.getInt(c.getColumnIndexOrThrow("enabled")) == 1);
        s.setRepeatType(c.getString(c.getColumnIndexOrThrow("repeat_type")));
        s.setRepeatDays(c.getString(c.getColumnIndexOrThrow("repeat_days")));
        s.setRunHour(c.getInt(c.getColumnIndexOrThrow("run_hour")));
        s.setRunMinute(c.getInt(c.getColumnIndexOrThrow("run_minute")));
        s.setLastRunStatus(c.getString(c.getColumnIndexOrThrow("last_run_status")));
        s.setLastRunMsg(c.getString(c.getColumnIndexOrThrow("last_run_msg")));

        String lra = c.getString(c.getColumnIndexOrThrow("last_run_at"));
        if (lra != null) s.setLastRunAt(LocalDateTime.parse(lra, TS_FMT));

        String nra = c.getString(c.getColumnIndexOrThrow("next_run_at"));
        if (nra != null) s.setNextRunAt(LocalDateTime.parse(nra, TS_FMT));

        String ca = c.getString(c.getColumnIndexOrThrow("created_at"));
        if (ca != null) s.setCreatedAt(LocalDateTime.parse(ca, TS_FMT));

        return s;
    }

    private SchedulerLog mapLog(Cursor c) {
        SchedulerLog l = new SchedulerLog();
        l.setId(c.getInt(c.getColumnIndexOrThrow("id")));
        l.setSchedulerId(c.getInt(c.getColumnIndexOrThrow("scheduler_id")));
        l.setSchedulerName(c.getString(c.getColumnIndexOrThrow("scheduler_name")));
        l.setStatus(c.getString(c.getColumnIndexOrThrow("status")));
        l.setMessage(c.getString(c.getColumnIndexOrThrow("message")));
        l.setRowsSynced(c.getInt(c.getColumnIndexOrThrow("rows_synced")));

        String sa = c.getString(c.getColumnIndexOrThrow("started_at"));
        if (sa != null) l.setStartedAt(LocalDateTime.parse(sa, TS_FMT));

        String fa = c.getString(c.getColumnIndexOrThrow("finished_at"));
        if (fa != null) l.setFinishedAt(LocalDateTime.parse(fa, TS_FMT));

        return l;
    }
}
