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
import com.expenseos.ui.HomeActivity;

public class TaskNotificationReceiver extends BroadcastReceiver {

    private static final String CHANNEL_ID = "task_reminder";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        createChannel(ctx);
        long taskId = intent.getLongExtra("task_id", 0);
        Task task = new TaskDao(ctx).findById(taskId);
        String title = task != null ? task.getName() : "Task reminder";

        Intent openApp = new Intent(ctx, HomeActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, (int) taskId, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(task != null && task.getDescription() != null ? task.getDescription() : "")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi);

        boolean canPost = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        if (canPost) NotificationManagerCompat.from(ctx).notify((int) (30000 + taskId), b.build());
    }

    private void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Task Reminders", NotificationManager.IMPORTANCE_HIGH);
            NotificationManager m = ctx.getSystemService(NotificationManager.class);
            if (m != null) m.createNotificationChannel(ch);
        }
    }
}