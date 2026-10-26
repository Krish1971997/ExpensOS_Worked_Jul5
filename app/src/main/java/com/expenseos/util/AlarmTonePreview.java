package com.expenseos.util;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * Plays a short preview of an alarm tone ("" = the phone's default alarm sound).
 */
public final class AlarmTonePreview {

    private static final long PREVIEW_MS = 10000L;
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final Runnable STOP = AlarmTonePreview::stop;
    private static Ringtone current;

    private AlarmTonePreview() {
    }

    /**
     * Starts the preview (restarts if one is already playing); auto-stops after 6 s.
     */
    public static void play(Context ctx, String uri) {
        stop();
        Context app = ctx.getApplicationContext();
        Ringtone r = null;
        try {
            if (uri != null && !uri.isEmpty()) r = RingtoneManager.getRingtone(app, Uri.parse(uri));
            if (r == null) {
                Uri def = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_ALARM);
                if (def == null)
                    def = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_RINGTONE);
                r = RingtoneManager.getRingtone(app, def);
            }
        } catch (Exception ignored) {
        }
        if (r == null) {
            Toast.makeText(app, "Can't play this tone", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            r.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM) // same stream as the real alarm → alarm volume applies
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) r.setLooping(true);
            r.play();
            current = r;
            HANDLER.postDelayed(STOP, PREVIEW_MS);
        } catch (Exception e) {
            Toast.makeText(app, "Can't play this tone", Toast.LENGTH_SHORT).show();
        }
    }

    public static void stop() {
        HANDLER.removeCallbacks(STOP);
        if (current != null) {
            try {
                current.stop();
            } catch (Exception ignored) {
            }
            current = null;
        }
    }
}