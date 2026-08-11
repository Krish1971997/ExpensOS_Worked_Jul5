package com.expenseos.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.expenseos.ui.AlarmRingActivity;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Intent ring = new Intent(ctx, AlarmRingActivity.class);
        ring.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ring.putExtra("task_id", intent.getLongExtra("task_id", 0));
        ring.putExtra("event_reminder_id", intent.getLongExtra("event_reminder_id", 0));
        ctx.startActivity(ring);
    }
}