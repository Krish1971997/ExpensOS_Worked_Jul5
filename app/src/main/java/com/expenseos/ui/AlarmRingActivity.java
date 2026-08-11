package com.expenseos.ui;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Vibrator;

import androidx.appcompat.app.AppCompatActivity;

import com.expenseos.R;
import com.expenseos.dao.TaskDao;
import com.expenseos.model.Task;
import com.expenseos.receiver.AlarmReceiver;

public class AlarmRingActivity extends AppCompatActivity {

    private Ringtone ringtone;
    private Vibrator vibrator;
    private long taskId, eventReminderId;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        // Show over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = getSystemService(KeyguardManager.class);
            if (km != null) km.requestDismissKeyguard(this, null);
        } else {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        setContentView(R.layout.activity_alarm_ring);

        taskId = getIntent().getLongExtra("task_id", 0);
        eventReminderId = getIntent().getLongExtra("event_reminder_id", 0);

        Task task = new TaskDao(this).findById(taskId);
        ((android.widget.TextView) findViewById(R.id.tvAlarmTaskName))
                .setText(task != null ? task.getName() : "Task Alarm");

        ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM));
        if (ringtone != null) ringtone.play();
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null) vibrator.vibrate(new long[]{0, 500, 500}, 0);

        findViewById(R.id.btnSnooze).setOnClickListener(v -> snooze());
        findViewById(R.id.btnDismiss).setOnClickListener(v -> dismiss());
    }

    @SuppressLint("ScheduleExactAlarm")
    private void snooze() {
        stopAlert();
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(this, AlarmReceiver.class);
        intent.putExtra("task_id", taskId);
        intent.putExtra("event_reminder_id", eventReminderId);
        PendingIntent pi = PendingIntent.getBroadcast(this, (int) (40000 + taskId), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long triggerAt = System.currentTimeMillis() + 10 * 60 * 1000L; // 10 min snooze
        if (am != null) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        finish();
    }

    private void dismiss() {
        stopAlert();
        finish();
    }

    private void stopAlert() {
        if (ringtone != null && ringtone.isPlaying()) ringtone.stop();
        if (vibrator != null) vibrator.cancel();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAlert();
    }
}