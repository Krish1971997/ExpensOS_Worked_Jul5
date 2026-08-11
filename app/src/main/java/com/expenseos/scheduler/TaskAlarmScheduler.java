package com.expenseos.scheduler;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;

import com.expenseos.dao.EventDao;
import com.expenseos.db.LocalDB;
import com.expenseos.model.EventReminder;
import com.expenseos.receiver.AlarmReceiver;
import com.expenseos.receiver.TaskNotificationReceiver;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Computes each reminder's trigger date from: task_date ± event.offsetDays,
 * then ± eventReminder.offsetDays, at eventReminder's time — and schedules/
 * cancels the AlarmManager entries. Called from AddTaskActivity on save,
 * edit, and delete.
 */
public class TaskAlarmScheduler {

    private static final DateTimeFormatter TASK_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static void scheduleForTask(Context ctx, long taskId, String taskDateTimeStr, List<Long> eventIds) {
        cancelForTask(ctx, taskId); // clear old alarms first (handles edit)

        LocalDateTime taskDateTime = LocalDateTime.parse(taskDateTimeStr, TASK_FMT);
        EventDao eventDao = new EventDao(ctx);

        for (Long eventId : eventIds) {
            com.expenseos.model.Event event = findEvent(eventDao, eventId);
            if (event == null) continue;

            LocalDateTime baseDate = "AFTER".equals(event.getOffsetDirection())
                    ? taskDateTime.plusDays(event.getOffsetDays())
                    : taskDateTime.minusDays(event.getOffsetDays());

            scheduleType(ctx, taskId, eventDao, eventId, "NOTIFICATION", baseDate);
            scheduleType(ctx, taskId, eventDao, eventId, "ALARM", baseDate);
        }
    }

    private static void scheduleType(Context ctx, long taskId, EventDao eventDao, long eventId,
                                     String type, LocalDateTime baseDate) {
        for (EventReminder er : eventDao.findReminders(eventId, type)) {
            LocalDateTime trigger = "AFTER".equals(er.getOffsetDirection())
                    ? baseDate.plusDays(er.getOffsetDays())
                    : baseDate.minusDays(er.getOffsetDays());
            trigger = trigger.withHour(er.getTimeHour()).withMinute(er.getTimeMinute()).withSecond(0);

            if (trigger.isBefore(LocalDateTime.now())) continue; // don't schedule past-due alarms

            int requestCode = nextRequestCode(ctx);
            PendingIntent pi = buildPendingIntent(ctx, requestCode, taskId, er.getId(), type);

            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            long triggerMillis = trigger.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            boolean canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || (am != null && am.canScheduleExactAlarms());
            if (am != null) {
                if (canExact)
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pi);
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pi);
            }

            recordAlarm(ctx, taskId, er.getId(), requestCode, trigger.format(TASK_FMT), type);
        }
    }

    private static PendingIntent buildPendingIntent(Context ctx, int requestCode, long taskId, long eventReminderId, String type) {
        Intent intent = new Intent(ctx, "ALARM".equals(type) ? AlarmReceiver.class : TaskNotificationReceiver.class);
        intent.putExtra("task_id", taskId);
        intent.putExtra("event_reminder_id", eventReminderId);
        return PendingIntent.getBroadcast(ctx, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void cancelForTask(Context ctx, long taskId) {
        LocalDB db = LocalDB.getInstance(ctx);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT request_code, type FROM task_alarms WHERE task_id=?", new String[]{String.valueOf(taskId)})) {
            while (c.moveToNext()) {
                int reqCode = c.getInt(0);
                String type = c.getString(1);
                Intent intent = new Intent(ctx, "ALARM".equals(type) ? AlarmReceiver.class : TaskNotificationReceiver.class);
                PendingIntent pi = PendingIntent.getBroadcast(ctx, reqCode, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                if (am != null) am.cancel(pi);
            }
        }
        db.getWritableDatabase().delete("task_alarms", "task_id=?", new String[]{String.valueOf(taskId)});
    }

    private static void recordAlarm(Context ctx, long taskId, long eventReminderId, int requestCode, String triggerAt, String type) {
        SQLiteDatabase wdb = LocalDB.getInstance(ctx).getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("id", LocalDB.getInstance(ctx).getNextId("task_alarms"));
        cv.put("task_id", taskId);
        cv.put("event_reminder_id", eventReminderId);
        cv.put("request_code", requestCode);
        cv.put("trigger_at", triggerAt);
        cv.put("type", type);
        wdb.insert("task_alarms", null, cv);
    }

    private static int nextRequestCode(Context ctx) {
        // simple unique code: base offset + running counter stored via id_sequences
        return (int) (20000 + LocalDB.getInstance(ctx).getNextId("task_alarms"));
    }

    private static com.expenseos.model.Event findEvent(EventDao dao, long eventId) {
        for (com.expenseos.model.Event e : dao.findAll()) if (e.getId() == eventId) return e;
        return null;
    }
}