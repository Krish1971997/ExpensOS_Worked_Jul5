package com.expenseos.receiver;

import android.Manifest;
import android.app.AlarmManager;
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

    private static final String CHANNEL_ID = "task_alarm";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        long taskId = intent.getLongExtra("task_id", 0);
        long eventReminderId = intent.getLongExtra("event_reminder_id", 0);
        Task task = new TaskDao(ctx).findById(taskId);
        String taskName = task != null && task.getName() != null ? task.getName() : "Task alarm";

        // 1 — direct launch (works while the app is already in the foreground)
        try {
            Intent ring = new Intent(ctx, AlarmRingActivity.class);
            ring.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ring.putExtra("task_id", taskId);
            ring.putExtra("event_reminder_id", eventReminderId);
            ctx.startActivity(ring);
        } catch (Exception ignored) {
            // background start blocked — the full-screen notification below still fires
        }

        // 2 — full-screen-intent notification (works from the background / lock screen)
        createChannel(ctx);

        Intent ring = new Intent(ctx, AlarmRingActivity.class);
        ring.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ring.putExtra("task_id", taskId);
        ring.putExtra("event_reminder_id", eventReminderId);
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
            NotificationManagerCompat.from(ctx).notify((int) (70000 + taskId), b.build());
        }
    }

    private void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Task Alarms",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Full-screen task alarms");
            ch.enableVibration(true);
            NotificationManager m = ctx.getSystemService(NotificationManager.class);
            if (m != null) m.createNotificationChannel(ch);
        }
    }
}
