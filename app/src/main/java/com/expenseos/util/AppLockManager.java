package com.expenseos.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * App-lock state + PIN storage (salted PBKDF2 hash only, raw PIN is never stored).
 */
public final class AppLockManager {

    public enum Result {OK, WRONG, LOCKED_OUT}

    public static final int MAX_FREE_ATTEMPTS = 5;
    public static final int DEFAULT_TIMEOUT_SEC = 60;

    private static final String PREF = "expenseos_lock";
    private static final String K_ENABLED = "lock.enabled";
    private static final String K_SALT = "lock.salt";
    private static final String K_HASH = "lock.hash";
    private static final String K_BIOMETRIC = "lock.biometric";
    private static final String K_TIMEOUT = "lock.timeout.sec";
    private static final String K_FAILS = "lock.fails";
    private static final String K_LOCK_UNTIL = "lock.until";
    private static final int ITERATIONS = 100_000;

    private static volatile boolean unlocked = false;
    private static volatile boolean lockScreenShowing = false;
    private static volatile long backgroundAt = 0L;

    private AppLockManager() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ── Settings ─────────────────────────────────────────────
    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(K_ENABLED, false);
    }

    public static boolean isBiometricEnabled(Context ctx) {
        return prefs(ctx).getBoolean(K_BIOMETRIC, true);
    }

    public static void setBiometricEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(K_BIOMETRIC, on).apply();
    }

    /**
     * Seconds the app may stay in background before it asks for the PIN again (0 = always).
     */
    public static int getTimeoutSeconds(Context ctx) {
        return prefs(ctx).getInt(K_TIMEOUT, DEFAULT_TIMEOUT_SEC);
    }

    public static void setTimeoutSeconds(Context ctx, int sec) {
        prefs(ctx).edit().putInt(K_TIMEOUT, Math.max(0, sec)).apply();
    }

    // ── Session state ────────────────────────────────────────
    public static boolean shouldLock(Context ctx) {
        return isEnabled(ctx) && !unlocked;
    }

    public static void markUnlocked(Context ctx) {
        unlocked = true;
        prefs(ctx).edit().putInt(K_FAILS, 0).putLong(K_LOCK_UNTIL, 0).apply();
    }

    public static boolean isLockScreenShowing() {
        return lockScreenShowing;
    }

    public static void setLockScreenShowing(boolean showing) {
        lockScreenShowing = showing;
    }

    public static void onAppBackgrounded() {
        backgroundAt = SystemClock.elapsedRealtime();
    }

    public static void onAppForegrounded(Context ctx) {
        long bg = backgroundAt;
        backgroundAt = 0L;
        if (bg == 0L || !unlocked || !isEnabled(ctx)) return;
        long awayMs = SystemClock.elapsedRealtime() - bg;
        if (awayMs >= getTimeoutSeconds(ctx) * 1000L) unlocked = false;
    }

    // ── PIN ──────────────────────────────────────────────────
    public static void setPin(Context ctx, String pin) {
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            byte[] h = hash(pin, salt);
            prefs(ctx).edit()
                    .putString(K_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString(K_HASH, Base64.encodeToString(h, Base64.NO_WRAP))
                    .putBoolean(K_ENABLED, true)
                    .putInt(K_FAILS, 0)
                    .putLong(K_LOCK_UNTIL, 0)
                    .apply();
        } catch (Exception e) {
            throw new IllegalStateException("PIN hashing failed", e);
        }
    }

    public static void disable(Context ctx) {
        prefs(ctx).edit()
                .putBoolean(K_ENABLED, false)
                .remove(K_SALT).remove(K_HASH)
                .putInt(K_FAILS, 0).putLong(K_LOCK_UNTIL, 0)
                .apply();
        unlocked = true;
    }

    public static Result verifyPin(Context ctx, String pin) {
        if (lockoutRemainingMs(ctx) > 0) return Result.LOCKED_OUT;
        SharedPreferences p = prefs(ctx);
        String saltB64 = p.getString(K_SALT, null);
        String hashB64 = p.getString(K_HASH, null);
        if (saltB64 != null && hashB64 != null) {
            try {
                byte[] expected = Base64.decode(hashB64, Base64.NO_WRAP);
                byte[] actual = hash(pin, Base64.decode(saltB64, Base64.NO_WRAP));
                if (MessageDigest.isEqual(expected, actual)) {
                    p.edit().putInt(K_FAILS, 0).putLong(K_LOCK_UNTIL, 0).apply();
                    return Result.OK;
                }
            } catch (Exception ignored) {
                // fall through = treated as a wrong attempt
            }
        }
        int fails = p.getInt(K_FAILS, 0) + 1;
        SharedPreferences.Editor e = p.edit().putInt(K_FAILS, fails);
        if (fails >= MAX_FREE_ATTEMPTS) {
            long delay = Math.min(30_000L << Math.min(fails - MAX_FREE_ATTEMPTS, 5), 15 * 60_000L);
            e.putLong(K_LOCK_UNTIL, System.currentTimeMillis() + delay);
        }
        e.apply();
        return fails >= MAX_FREE_ATTEMPTS ? Result.LOCKED_OUT : Result.WRONG;
    }

    public static int attemptsLeft(Context ctx) {
        return Math.max(0, MAX_FREE_ATTEMPTS - prefs(ctx).getInt(K_FAILS, 0));
    }

    public static long lockoutRemainingMs(Context ctx) {
        long until = prefs(ctx).getLong(K_LOCK_UNTIL, 0);
        return Math.max(0, until - System.currentTimeMillis());
    }

    private static byte[] hash(String pin, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    }
}