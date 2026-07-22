package com.expenseos.sync;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.expenseos.util.AppConfig;

import java.util.Calendar;

public class BackupScheduler {

    public static void scheduleDaily(Context ctx) {
        AppConfig cfg = AppConfig.get(ctx);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, cfg.getBackupHour());
        cal.set(Calendar.MINUTE, cfg.getBackupMinute());
        cal.set(Calendar.SECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis())
            cal.add(Calendar.DAY_OF_YEAR, 1); // already passed today → tomorrow

        Intent intent = new Intent(ctx, AutoBackupReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 1001, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        am.setRepeating(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(),
                AlarmManager.INTERVAL_DAY, pi);
    }

    public static class AutoBackupReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            BackupManager.get().createBackupScheduled(ctx.getApplicationContext());
        }
    }
}