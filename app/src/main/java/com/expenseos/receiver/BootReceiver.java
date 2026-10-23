package com.expenseos.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.expenseos.scheduler.TaskAlarmRescheduler;
import com.expenseos.util.ReminderScheduler;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(a)) {
            ReminderScheduler.scheduleDaily9PM(ctx);
            // FIX: AlarmManager alarms are CLEARED when the device shuts down.
            // Without this, every task notification/alarm silently dies after
            // a reboot. Recompute all of them from the config tables.
            TaskAlarmRescheduler.rescheduleAllAsync(ctx);
        }
    }
}
