package com.expenseos.ui;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.expenseos.R;
import com.expenseos.dao.TaskDao;
import com.expenseos.model.Task;
import com.expenseos.receiver.AlarmService;

public class AlarmRingActivity extends AppCompatActivity {

    private Ringtone ringtone;
    private Vibrator vibrator;
    private long taskId, eventReminderId, eventId;
    private BroadcastReceiver finishReceiver;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        // Show over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        setContentView(R.layout.activity_alarm_ring);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // AlarmService stops (dismiss / snooze / power button / timeout) → close this screen
        finishReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                finish();
            }
        };
        ContextCompat.registerReceiver(this, finishReceiver,
                new IntentFilter(AlarmService.ACTION_FINISHED), ContextCompat.RECEIVER_NOT_EXPORTED);

        taskId = getIntent().getLongExtra("task_id", 0);
        eventReminderId = getIntent().getLongExtra("event_reminder_id", 0);
        eventId = new com.expenseos.dao.EventDao(this).findEventIdByEventReminderId(eventReminderId);

        Task task = new TaskDao(this).findById(taskId);
        ((android.widget.TextView) findViewById(R.id.tvAlarmTaskName))
                .setText(task != null ? task.getName() : "Task Alarm");

        // AlarmService normally rings; play here only on the fallback path
        boolean playHere = getIntent().getBooleanExtra("play_here", false);
        ringtone = playHere ? buildRingtone() : null;
        if (ringtone != null) {
            try {
                // Route through the ALARM usage so the volume key context and
                // Do Not Disturb treat it as an alarm, not media.
                ringtone.setAudioAttributes(new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ringtone.setLooping(true);
                ringtone.play();
            } catch (Exception ignored) {
            }
        }
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (playHere && vibrator != null) vibrator.vibrate(new long[]{0, 500, 500}, 0);

        findViewById(R.id.btnSnooze).setOnClickListener(v -> snooze());
        findViewById(R.id.btnDismiss).setOnClickListener(v -> dismiss());
        setupSwipe();
    }

    /**
     * Drag the centre circle: left = snooze, right = dismiss (like the Clock app).
     */
    @SuppressLint("ClickableViewAccessibility")
    private void setupSwipe() {
        final View handle = findViewById(R.id.alarmHandle);
        final float[] downX = {0};
        handle.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = e.getRawX();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    v.setTranslationX(e.getRawX() - downX[0]);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    float dx = e.getRawX() - downX[0];
                    float limit = getResources().getDisplayMetrics().density * 90;
                    if (dx <= -limit) snooze();
                    else if (dx >= limit) dismiss();
                    else v.animate().translationX(0).setDuration(150).start();
                    return true;
            }
            return false;
        });
    }

    /**
     * User-picked tone (phone tones picker or a file-manager audio file) if
     * one is saved in {@link AlarmToneStore}; otherwise the phone's default
     * alarm sound. Falls back to the default when a saved file URI is no
     * longer readable.
     */
    private Ringtone buildRingtone() {
        String saved = com.expenseos.util.AlarmToneStore.resolveUri(this, eventId);
        if (saved != null && !saved.isEmpty()) {
            try {
                Ringtone r = RingtoneManager.getRingtone(this, android.net.Uri.parse(saved));
                if (r != null) return r;
            } catch (Exception ignored) {
            }
        }
        try {
            return RingtoneManager.getRingtone(this,
                    RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM));
        } catch (Exception e) {
            return null;
        }
    }

    private void snooze() {
        stopAlert();
        // service schedules the 10-min snooze + shows the countdown notification
        startService(new Intent(this, AlarmService.class)
                .setAction(AlarmService.ACTION_SNOOZE)
                .putExtra("task_id", taskId)
                .putExtra("event_reminder_id", eventReminderId));
        finish();
    }

    private void dismiss() {
        stopAlert();
        startService(new Intent(this, AlarmService.class).setAction(AlarmService.ACTION_STOP));
        finish();
    }

    private void stopAlert() {
        if (ringtone != null && ringtone.isPlaying()) ringtone.stop();
        if (vibrator != null) vibrator.cancel();
        androidx.core.app.NotificationManagerCompat.from(this).cancel((int) (70000 + taskId));
    }

    @Override
    protected void onDestroy() {
        if (finishReceiver != null) {
            try {
                unregisterReceiver(finishReceiver);
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
        stopAlert();
    }
}