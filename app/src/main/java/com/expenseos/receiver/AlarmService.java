package com.expenseos.receiver;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.Vibrator;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.expenseos.R;
import com.expenseos.dao.EventDao;
import com.expenseos.dao.TaskDao;
import com.expenseos.model.Task;
import com.expenseos.ui.AlarmRingActivity;
import com.expenseos.util.AlarmToneStore;

/**
 * Rings a task alarm: plays the tone (loop) + vibrates from a foreground
 * service, so the alarm sounds even when the ring screen can't be launched
 * (screen on / app in background). Stopped by AlarmRingActivity
 * (Snooze/Dismiss), the notification's Dismiss action, or after 2 minutes.
 */
public class AlarmService extends Service {

    public static final String ACTION_STOP = "com.expenseos.ALARM_STOP";
    public static final String ACTION_SNOOZE = "com.expenseos.ALARM_SNOOZE";
    public static final String ACTION_CANCEL_SNOOZE = "com.expenseos.ALARM_CANCEL_SNOOZE";
    public static final String ACTION_FINISHED = "com.expenseos.ALARM_FINISHED";
    private static final String CHANNEL_ID = "task_alarm_v2";
    private static final String SNOOZE_CHANNEL = "task_snooze_v2";
    private static final int SNOOZE_NOTIF_BASE = 110000;
    private static final long MAX_RING_MS = 2 * 60 * 1000L;
    private static final long SNOOZE_MS = 10 * 60 * 1000L;

    private long rampMs = 30_000L;       // Config → Alarm → Gradually increase volume (0 = off)
    private boolean vibrationOn = true;  // Config → Alarm → Vibration

    private Ringtone ringtone;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean ringing;
    private long curTaskId, curEventReminderId, ringStartedAt;
    private BroadcastReceiver screenOffReceiver;

    // gradual volume: RAMP_START → 1.0 over RAMP_MS
    private static final float RAMP_START = 0.1f;
    private long rampStartedAt;
    private final Runnable rampStep = new Runnable() {
        @Override
        public void run() {
            if (ringtone == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
            float f = Math.min(1f, (SystemClock.elapsedRealtime() - rampStartedAt) / (float) rampMs);
            try {
                ringtone.setVolume(RAMP_START + (1f - RAMP_START) * f);
            } catch (Exception ignored) {
            }
            if (f < 1f) handler.postDelayed(this, 500);
        }
    };

    private void startVolumeRamp() {
        if (rampMs <= 0) return; // "Off": already at full volume
        rampStartedAt = SystemClock.elapsedRealtime();
        handler.removeCallbacks(rampStep);
        handler.post(rampStep);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SNOOZE.equals(action)) {
            snoozeNow(intent.getLongExtra("task_id", 0), intent.getLongExtra("event_reminder_id", 0));
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL_SNOOZE.equals(action)) {
            cancelSnooze(intent.getLongExtra("task_id", 0), intent.getLongExtra("event_reminder_id", 0));
            if (!ringing) stopSelf();
            return START_NOT_STICKY;
        }

        long taskId = intent != null ? intent.getLongExtra("task_id", 0) : 0;
        long eventReminderId = intent != null ? intent.getLongExtra("event_reminder_id", 0) : 0;
        Task task = new TaskDao(this).findById(taskId);
        String name = task != null && task.getName() != null ? task.getName() : "Task alarm";
        int notifId = (int) (70000 + taskId);

        createChannel();
        Notification n = buildNotification(notifId, taskId, eventReminderId, name);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(notifId, n);
        }

        if (task == null) { // task deleted meanwhile
            stopSelf();
            return START_NOT_STICKY;
        }

        stopAlert(); // replace any alarm already ringing

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "expenseos:alarm");
            wakeLock.acquire(MAX_RING_MS + 5000);
        }

        long eventId = new EventDao(this).findEventIdByEventReminderId(eventReminderId);
        com.expenseos.util.AppConfig cfg = com.expenseos.util.AppConfig.get(this);
        rampMs = cfg.getAlarmVolumeRampSeconds() * 1000L;
        vibrationOn = cfg.isAlarmVibrationEnabled();
        startRing(eventId);

        curTaskId = taskId;
        curEventReminderId = eventReminderId;
        ringing = true;
        ringStartedAt = SystemClock.elapsedRealtime();
        NotificationManagerCompat.from(this).cancel(SNOOZE_NOTIF_BASE + (int) taskId);
        registerScreenOff();

        // Screen off / locked → the full-screen intent opens the ring screen.
        // Screen on → heads-up popup with Snooze / Dismiss buttons.
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::stopSelf, MAX_RING_MS);
        startVolumeRamp(); // must come after removeCallbacksAndMessages(null) above
        return START_NOT_STICKY;
    }

    // ── Snooze (10 min) ──────────────────────────────────────
    private PendingIntent snoozeAlarmPi(long taskId, long eventReminderId) {
        Intent i = new Intent(this, AlarmReceiver.class);
        i.putExtra("task_id", taskId);
        i.putExtra("event_reminder_id", eventReminderId);
        return PendingIntent.getBroadcast(this, (int) (40000 + taskId), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @SuppressLint({"MissingPermission", "ScheduleExactAlarm"})
    private void snoozeNow(long taskId, long eventReminderId) {
        if (taskId == 0) { // power-button path: use the alarm that is ringing
            taskId = curTaskId;
            eventReminderId = curEventReminderId;
        }
        if (taskId == 0) {
            stopSelf();
            return;
        }

        long triggerAt = System.currentTimeMillis() + SNOOZE_MS;
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = snoozeAlarmPi(taskId, eventReminderId);
        if (am != null) {
            try {
                Intent showI = new Intent(this, com.expenseos.ui.HomeActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PendingIntent showPi = PendingIntent.getActivity(this, (int) (98000 + taskId), showI,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerAt, showPi), pi);
            } catch (Exception e) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        }

        Task t = new TaskDao(this).findById(taskId);
        String name = t != null && t.getName() != null ? t.getName() : "Task alarm";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(SNOOZE_CHANNEL, "Snoozed alarms",
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setSound(null, null);
            ch.enableVibration(false);
            NotificationManager m = getSystemService(NotificationManager.class);
            if (m != null) m.createNotificationChannel(ch);
        }
        Intent cancel = new Intent(this, AlarmService.class).setAction(ACTION_CANCEL_SNOOZE)
                .putExtra("task_id", taskId)
                .putExtra("event_reminder_id", eventReminderId);
        PendingIntent cancelPi = PendingIntent.getService(this, (int) (97000 + taskId), cancel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String at = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                .format(new java.util.Date(triggerAt));
        Intent open = new Intent(this, com.expenseos.ui.HomeActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, (int) (98500 + taskId), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new NotificationCompat.Builder(this, SNOOZE_CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("⏰ " + name)
                .setContentText("Snoozed — rings again at " + at)
                .setSubText("Alarm")
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setShowWhen(true)
                .setWhen(triggerAt)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openPi)
                .addAction(0, "Dismiss", cancelPi)
                .build();
        NotificationManagerCompat.from(this).notify(SNOOZE_NOTIF_BASE + (int) taskId, n);

        stopSelf();
    }

    private void cancelSnooze(long taskId, long eventReminderId) {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(snoozeAlarmPi(taskId, eventReminderId));
        NotificationManagerCompat.from(this).cancel(SNOOZE_NOTIF_BASE + (int) taskId);
    }

    // ── Power button = screen off → auto snooze ──────────────
    private void registerScreenOff() {
        if (screenOffReceiver != null) return;
        screenOffReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                // ignore the screen flicker right when the alarm starts
                if (ringing && SystemClock.elapsedRealtime() - ringStartedAt > 3000)
                    snoozeNow(0, 0);
            }
        };
        ContextCompat.registerReceiver(this, screenOffReceiver,
                new IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private Notification buildNotification(int notifId, long taskId, long eventReminderId, String name) {
        Intent ring = new Intent(this, AlarmRingActivity.class);
        ring.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        ring.putExtra("task_id", taskId);
        ring.putExtra("event_reminder_id", eventReminderId);
        PendingIntent ringPi = PendingIntent.getActivity(this, (int) (80000 + taskId), ring,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, AlarmService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, (int) (90000 + taskId), stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent snooze = new Intent(this, AlarmService.class).setAction(ACTION_SNOOZE)
                .putExtra("task_id", taskId)
                .putExtra("event_reminder_id", eventReminderId);
        PendingIntent snoozePi = PendingIntent.getService(this, (int) (95000 + taskId), snooze,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(name)
                .setContentText("Alarm")
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setFullScreenIntent(ringPi, true)
                .setContentIntent(ringPi)
                .addAction(0, "Snooze 10 min", snoozePi)
                .addAction(0, "Dismiss", stopPi)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Task Alarms",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Ringing task alarms");
            ch.setSound(null, null); // the service plays the tone itself
            ch.enableVibration(false);
            NotificationManager m = getSystemService(NotificationManager.class);
            if (m != null) m.createNotificationChannel(ch);
        }
    }

    private void startRing(long eventId) {
        ringtone = buildRingtone(eventId);
        if (ringtone != null) {
            try {
                ringtone.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ringtone.setLooping(true);
                    ringtone.setVolume(rampMs > 0 ? RAMP_START : 1f);
                }
                ringtone.play();
            } catch (Exception ignored) {
            }
        }
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrationOn && vibrator != null) vibrator.vibrate(new long[]{0, 500, 500}, 0);
    }

    /**
     * Event's tone → global tone → phone default alarm → phone ringtone.
     */
    private Ringtone buildRingtone(long eventId) {
        String saved = AlarmToneStore.resolveUri(this, eventId);
        if (saved != null && !saved.isEmpty()) {
            try {
                Ringtone r = RingtoneManager.getRingtone(this, Uri.parse(saved));
                if (r != null) return r;
            } catch (Exception ignored) {
            }
        }
        try {
            Uri def = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
            if (def == null)
                def = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE);
            return RingtoneManager.getRingtone(this, def);
        } catch (Exception e) {
            return null;
        }
    }

    private void stopAlert() {
        if (ringtone != null) {
            try {
                if (ringtone.isPlaying()) ringtone.stop();
            } catch (Exception ignored) {
            }
            ringtone = null;
        }
        if (vibrator != null) vibrator.cancel();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopAlert();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        ringing = false;
        if (screenOffReceiver != null) {
            try {
                unregisterReceiver(screenOffReceiver);
            } catch (Exception ignored) {
            }
            screenOffReceiver = null;
        }
        stopForeground(true);
        // tells the ring screen (if open) to close — dismiss / snooze / power button
        sendBroadcast(new Intent(ACTION_FINISHED).setPackage(getPackageName()));
        super.onDestroy();
    }
}