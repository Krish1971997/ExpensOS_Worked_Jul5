package com.expenseos.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Equivalent of web.xml context-params — stored in SharedPreferences.
 * All keys mirror the web.xml param-names exactly.
 */
public class AppConfig {

    private static final String PREF_NAME = "expenseos_config";

    // ── DB keys (mirrors web.xml) ──────────────────────────
    public static final String KEY_DB_URL = "DB_URL";
    public static final String KEY_DB_USER = "DB_USER";
    public static final String KEY_DB_PASSWORD = "DB_PASSWORD";

    // ── Gmail keys ──────────────────────────────────────────
    public static final String KEY_GMAIL_FROM = "GMAIL_FROM";
    public static final String KEY_GMAIL_APP_PASS = "GMAIL_APP_PASS";

    // ── Zoho WorkDrive keys ─────────────────────────────────
    public static final String KEY_ZOHO_CLIENT_ID = "zoho.client.id";
    public static final String KEY_ZOHO_CLIENT_SECRET = "zoho.client.secret";
    public static final String KEY_ZOHO_REFRESH_TOKEN = "zoho.refresh.token";
    public static final String KEY_WORKDRIVE_FOLDER_ID = "workdrive.main.folder.id";

    // ── Backup schedule ─────────────────────────────────────
    public static final String KEY_BACKUP_HOUR = "backup.schedule.hour";
    public static final String KEY_BACKUP_MINUTE = "backup.schedule.minute";

    // ── Session ─────────────────────────────────────────────
    public static final String KEY_SESSION_TIMEOUT = "session.timeout";

    // ── App state ───────────────────────────────────────────
    public static final String KEY_ACTIVE_BOOK_ID = "active_book_id";
    public static final String KEY_ACTIVE_BOOK_NAME = "active_book_name";

    private final SharedPreferences prefs;

    private static AppConfig instance;

    private AppConfig(Context ctx) {
        prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static AppConfig get(Context ctx) {
        if (instance == null) instance = new AppConfig(ctx);
        return instance;
    }

    // ── Getters ──────────────────────────────────────────────
    public String getDbUrl() {
        return prefs.getString(KEY_DB_URL, "");
    }

    public String getDbUser() {
        return prefs.getString(KEY_DB_USER, "");
    }

    public String getDbPassword() {
        return prefs.getString(KEY_DB_PASSWORD, "");
    }

    public String getGmailFrom() {
        return prefs.getString(KEY_GMAIL_FROM, "");
    }

    public String getGmailAppPass() {
        return prefs.getString(KEY_GMAIL_APP_PASS, "");
    }

    public String getZohoClientId() {
        return prefs.getString(KEY_ZOHO_CLIENT_ID, "");
    }

    public String getZohoClientSecret() {
        return prefs.getString(KEY_ZOHO_CLIENT_SECRET, "");
    }

    public String getZohoRefreshToken() {
        return prefs.getString(KEY_ZOHO_REFRESH_TOKEN, "");
    }

    public String getWorkdriveFolderId() {
        return prefs.getString(KEY_WORKDRIVE_FOLDER_ID, "");
    }

    public int getBackupHour() {
        return prefs.getInt(KEY_BACKUP_HOUR, 0);
    }

    public int getBackupMinute() {
        return prefs.getInt(KEY_BACKUP_MINUTE, 0);
    }

    public int getSessionTimeout() {
        return prefs.getInt(KEY_SESSION_TIMEOUT, 60);
    }

    public int getActiveBookId() {
        return prefs.getInt(KEY_ACTIVE_BOOK_ID, 1);
    }

    public String getActiveBookName() {
        return prefs.getString(KEY_ACTIVE_BOOK_NAME, "General");
    }

    // ── Setters ──────────────────────────────────────────────
    public void setDb(String url, String user, String pass) {
        prefs.edit()
                .putString(KEY_DB_URL, url)
                .putString(KEY_DB_USER, user)
                .putString(KEY_DB_PASSWORD, pass)
                .apply();
    }

    public void setGmail(String from, String appPass) {
        prefs.edit()
                .putString(KEY_GMAIL_FROM, from)
                .putString(KEY_GMAIL_APP_PASS, appPass)
                .apply();
    }

    public void setZoho(String clientId, String secret, String refreshToken, String folderId) {
        prefs.edit()
                .putString(KEY_ZOHO_CLIENT_ID, clientId)
                .putString(KEY_ZOHO_CLIENT_SECRET, secret)
                .putString(KEY_ZOHO_REFRESH_TOKEN, refreshToken)
                .putString(KEY_WORKDRIVE_FOLDER_ID, folderId)
                .apply();
    }

    public void setBackupSchedule(int hour, int minute) {
        prefs.edit()
                .putInt(KEY_BACKUP_HOUR, hour)
                .putInt(KEY_BACKUP_MINUTE, minute)
                .apply();
    }

    public void setSessionTimeout(int minutes) {
        prefs.edit().putInt(KEY_SESSION_TIMEOUT, minutes).apply();
    }

    public void setActiveBook(int id, String name) {
        prefs.edit()
                .putInt(KEY_ACTIVE_BOOK_ID, id)
                .putString(KEY_ACTIVE_BOOK_NAME, name)
                .apply();
    }

    public boolean isConfigured() {
        String url = getDbUrl();
        return url != null && !url.isEmpty();
    }
}
