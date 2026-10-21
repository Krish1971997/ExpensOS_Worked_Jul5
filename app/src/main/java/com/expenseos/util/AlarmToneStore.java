package com.expenseos.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Stores the user's chosen alarm tone (Settings → Integrations → Alarm tone).
 * Two sources are supported:
 * <ul>
 *   <li>a ringtone picked from the phone's own tones
 *       ({@code RingtoneManager.ACTION_RINGTONE_PICKER});</li>
 *   <li>an audio file chosen from the file manager
 *       ({@code ACTION_OPEN_DOCUMENT}, persistable read grant).</li>
 * </ul>
 * Empty URI = use the phone's default alarm sound.
 */
public final class AlarmToneStore {

    private static final String PREFS = "alarm_tone";
    private static final String KEY_URI = "tone_uri";
    private static final String KEY_NAME = "tone_name";

    private AlarmToneStore() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void save(Context ctx, String uri, String name) {
        prefs(ctx).edit()
                .putString(KEY_URI, uri == null ? "" : uri)
                .putString(KEY_NAME, name == null || name.isEmpty() ? "Custom tone" : name)
                .apply();
    }

    /** @return the stored tone URI, or "" when the default tone should be used. */
    public static String getUri(Context ctx) {
        return prefs(ctx).getString(KEY_URI, "");
    }

    public static String getName(Context ctx) {
        String uri = getUri(ctx);
        if (uri == null || uri.isEmpty()) return "Default phone alarm tone";
        return prefs(ctx).getString(KEY_NAME, "Custom tone");
    }

    public static void clear(Context ctx) {
        prefs(ctx).edit().remove(KEY_URI).remove(KEY_NAME).apply();
    }
}
