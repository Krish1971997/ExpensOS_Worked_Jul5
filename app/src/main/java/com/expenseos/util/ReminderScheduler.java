package com.expenseos.util;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.expenseos.receiver.ReminderReceiver;

import java.util.Calendar;

/**
 * Schedules the "Have you recorded your transactions today?" reminder for
 * 9 PM daily. Android alarms are one-shot and don't survive reboot, so this
 * gets called from three places: app launch (MainActivity), right after the
 * alarm fires (ReminderReceiver re-arms itself for the next day), and after
 * a device reboot (BootReceiver). All three calls are idempotent — the same
 * request code just replaces whatever was previously scheduled.
 */
public class ReminderScheduler {

    private static final int REQUEST_CODE = 9001;
    private static final int REMINDER_HOUR = 21; // 9 PM

    public static void scheduleDaily9PM(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        PendingIntent pi = buildPendingIntent(ctx);

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, REMINDER_HOUR);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_MONTH, 1); // already past 9 PM today
        }

        boolean canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms();
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        } else {
            // User revoked exact-alarm permission in Settings — fall back
            // to an inexact-but-Doze-aware alarm rather than not reminding
            // them at all. May fire a little late, which is fine for this.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        }
    }

    public static void cancel(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(buildPendingIntent(ctx));
    }

    private static PendingIntent buildPendingIntent(Context ctx) {
        Intent intent = new Intent(ctx, ReminderReceiver.class);
        return PendingIntent.getBroadcast(ctx, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
