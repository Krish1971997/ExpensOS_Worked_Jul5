package com.expenseos.receiver;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.expenseos.R;
import com.expenseos.dao.TaskDao;
import com.expenseos.model.Task;
import com.expenseos.ui.AlarmRingActivity;
import com.expenseos.ui.HomeActivity;

/**
 * Fires when a task's ALARM-type reminder triggers.
 * <p>
 * FIX: Android 10+ blocks {@code startActivity()} from background
 * BroadcastReceivers — the old code silently did nothing (and crashed when
 * the receiver wasn't even registered). Now a HIGH-importance notification
 * with a FULL_SCREEN_INTENT carries the ring screen: full-screen intents are
 * explicitly allowed to launch {@link AlarmRingActivity} over the lock
 * screen (USE_FULL_SCREEN_INTENT is declared). A straight startActivity() is
 * still attempted first for the app-in-foreground case.
 */
public class AlarmReceiver extends BroadcastReceiver {

    private static final String CHANNEL_ID = "task_alarm_v3";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        // the alarm's own wakelock ends when onReceive returns — keep the CPU up until AlarmService is running
        android.os.PowerManager pm = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "expenseos:alarm-recv").acquire(15_000L);
        }
        long taskId = intent.getLongExtra("task_id", 0);
        long eventReminderId = intent.getLongExtra("event_reminder_id", 0);
        Task task = new TaskDao(ctx).findById(taskId);
        if (task == null) return; // task deleted — don't ring a stale alarm
        String taskName = task.getName() != null ? task.getName() : "Task alarm";

        // 1 — foreground service plays the tone + vibration and shows the ring notification
        try {
            ContextCompat.startForegroundService(ctx, new Intent(ctx, AlarmService.class)
                    .putExtra("task_id", taskId)
                    .putExtra("event_reminder_id", eventReminderId));
            return;
        } catch (Exception ignored) {
            // service start blocked — fall back to the full-screen notification below
        }

        // 2 — full-screen-intent notification (works from the background / lock screen)
        createChannel(ctx);

        Intent ring = new Intent(ctx, AlarmRingActivity.class);
        ring.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ring.putExtra("task_id", taskId);
        ring.putExtra("event_reminder_id", eventReminderId);
        // fallback: the insistent notification sound rings; the ring screen only shows Snooze / Dismiss
        PendingIntent fullScreenPi = PendingIntent.getActivity(ctx,
                (int) (50000 + taskId), ring,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent openApp = new Intent(ctx, HomeActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(ctx,
                (int) (60000 + taskId), openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("⏰ " + taskName)
                .setContentText("Alarm — swipe to open the ringing screen")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(fullScreenPi, true)
                .setContentIntent(contentPi)
                .setAutoCancel(true);

        boolean canPost = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        if (canPost) {
            android.app.Notification n = b.build();
            n.flags |= android.app.Notification.FLAG_INSISTENT; // repeat the sound until dismissed
            NotificationManagerCompat.from(ctx).notify((int) (70000 + taskId), n);
        }
    }

    private void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Task Alarms",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Full-screen task alarms");
            ch.enableVibration(true);
            // fallback path (service couldn't start): the notification itself has to ring like an alarm
            android.net.Uri tone = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM);
            ch.setSound(tone, new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            NotificationManager m = ctx.getSystemService(NotificationManager.class);
            if (m != null) m.createNotificationChannel(ch);
        }
    }
}
