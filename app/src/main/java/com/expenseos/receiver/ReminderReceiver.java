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
import com.expenseos.ui.MainActivity;
import com.expenseos.util.ReminderScheduler;

public class ReminderReceiver extends BroadcastReceiver {

    private static final String CHANNEL_ID = "daily_reminder";
    private static final int NOTIFICATION_ID = 9001;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        createChannel(ctx);

        Intent openApp = new Intent(ctx, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(ctx, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(ctx.getString(R.string.app_name))
                .setContentText("Have you recorded your transactions today?")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(contentIntent);

        // API 33+ requires POST_NOTIFICATIONS to actually be granted at
        // runtime — guard against a SecurityException if the user denied it.
        boolean canPost = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        if (canPost) {
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, builder.build());
        }

        // One-shot alarms don't repeat on their own — re-arm for tomorrow.
        ReminderScheduler.scheduleDaily9PM(ctx);
    }

    private void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Daily Reminder", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Reminds you to record today's transactions");
            NotificationManager manager = ctx.getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }
}
