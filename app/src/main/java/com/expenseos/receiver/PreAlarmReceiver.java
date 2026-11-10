package com.expenseos.receiver;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.expenseos.dao.TaskDao;
import com.expenseos.db.LocalDB;
import com.expenseos.model.Task;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class PreAlarmReceiver extends BroadcastReceiver {
    public static final String ACTION_OFF = "com.expenseos.ACTION_PRE_ALARM_OFF";
    private static final String CHANNEL_ID = "pre_alarm";

    @Override
    public void onReceive(Context ctx, Intent i) {
        long taskId = i.getLongExtra("task_id", 0);
        long erId = i.getLongExtra("event_reminder_id", 0);
        int alarmCode = i.getIntExtra("alarm_request_code", 0);
        long alarmMillis = i.getLongExtra("alarm_millis", 0);
        int notifId = alarmCode + 1_000_000;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);

        if (ACTION_OFF.equals(i.getAction())) {
            // cancel the real alarm (match by component + request code, same as cancelForTask)
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            PendingIntent alarmPi = PendingIntent.getBroadcast(ctx, alarmCode,
                    new Intent(ctx, AlarmReceiver.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (am != null) am.cancel(alarmPi);
            LocalDB.getInstance(ctx).getWritableDatabase()
                    .delete("task_alarms", "request_code=?", new String[]{String.valueOf(alarmCode)});
            if (nm != null) nm.cancel(notifId);
            Toast.makeText(ctx, "Alarm turned off", Toast.LENGTH_SHORT).show();
            return;
        }

        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Upcoming alarm",
                    NotificationManager.IMPORTANCE_HIGH));
        }
        Task t = new TaskDao(ctx).findById(taskId);
        String when = Instant.ofEpochMilli(alarmMillis).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH));

        Intent off = new Intent(ctx, PreAlarmReceiver.class).setAction(ACTION_OFF)
                .putExtras(i.getExtras() != null ? i.getExtras() : new android.os.Bundle());
        PendingIntent offPi = PendingIntent.getBroadcast(ctx, alarmCode + 2_000_000, off,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        long showFor = i.getIntExtra("pre_minutes", 30) * 60_000L + 60_000L;
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(t != null ? "Alarm · " + t.getName() : "Alarm")
                .setContentText("The " + when + " alarm is about to ring")
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(showFor)      // auto-removes shortly after the alarm time
                .addAction(0, "Off", offPi);
        try {
            nm.notify(notifId, b.build());
        } catch (SecurityException ignored) { // POST_NOTIFICATIONS not granted
        }
    }
}