package com.expenseos.util;

import android.content.Context;
import android.content.SharedPreferences;

public class AppConfig {

    private static final String PREF_NAME = "expenseos_config";

    public static final String KEY_DB_URL = "DB_URL";
    public static final String KEY_DB_USER = "DB_USER";
    public static final String KEY_DB_PASSWORD = "DB_PASSWORD";
    public static final String KEY_GMAIL_FROM = "GMAIL_FROM";
    public static final String KEY_GMAIL_APP_PASS = "GMAIL_APP_PASS";
    public static final String KEY_SCHEDULER_ALERT_EMAIL = "scheduler.alert.email";

    // ── AI Assistant keys — one key/model PER provider so switching
    // providers never overwrites another provider's saved credentials ──
    public static final String KEY_AI_PROVIDER = "ai.provider";
    private static final String KEY_AI_KEY_PREFIX = "ai.key.";
    private static final String KEY_AI_MODEL_PREFIX = "ai.model.";

    public static final String PROVIDER_OPENAI = "openai";
    public static final String PROVIDER_GEMINI = "gemini";
    public static final String PROVIDER_GROK = "grok";
    public static final String PROVIDER_CLAUDE = "claude";

    public static final String KEY_ZOHO_CLIENT_ID = "zoho.client.id";
    public static final String KEY_ZOHO_CLIENT_SECRET = "zoho.client.secret";
    public static final String KEY_ZOHO_REFRESH_TOKEN = "zoho.refresh.token";
    public static final String KEY_WORKDRIVE_FOLDER_ID = "workdrive.main.folder.id";
    public static final String KEY_BACKUP_HOUR = "backup.schedule.hour";
    public static final String KEY_BACKUP_MINUTE = "backup.schedule.minute";
    public static final String KEY_SESSION_TIMEOUT = "session.timeout";
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

    public String getSchedulerAlertEmail() {
        return prefs.getString(KEY_SCHEDULER_ALERT_EMAIL, "");
    }

    public String getAiKey(String provider) {
        return prefs.getString(KEY_AI_KEY_PREFIX + provider, "");
    }

    public String getAiModel(String provider) {
        return prefs.getString(KEY_AI_MODEL_PREFIX + provider, defaultModelFor(provider));
    }

    public String getAiProvider() {
        return prefs.getString(KEY_AI_PROVIDER, PROVIDER_GEMINI);
    }

    private String defaultModelFor(String provider) {
        return switch (provider) {
            case PROVIDER_OPENAI -> "gpt-4o-mini";
            case PROVIDER_GROK -> "grok-2-latest";
            case PROVIDER_CLAUDE -> "claude-3-5-sonnet-20241022";
            default -> "gemini-1.5-flash";
        };
    }

    // Kept for source compat with any older callers — routes to the
    // currently selected provider's key/model.
    public String getOpenAiApiKey() {
        return getAiKey(getAiProvider());
    }

    public String getOpenAiModel() {
        return getAiModel(getAiProvider());
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
        prefs.edit().putString(KEY_DB_URL, url).putString(KEY_DB_USER, user).putString(KEY_DB_PASSWORD, pass).apply();
    }

    public void setGmail(String from, String appPass) {
        prefs.edit().putString(KEY_GMAIL_FROM, from).putString(KEY_GMAIL_APP_PASS, appPass).apply();
    }

    public void setSchedulerAlertEmail(String email) {
        prefs.edit().putString(KEY_SCHEDULER_ALERT_EMAIL, email).apply();
    }

    // Saves this key/model under the given provider's own slot, and makes
    // that provider the active one — switching providers later never
    // clobbers another provider's saved credentials.
    public void setAiConfig(String apiKey, String model, String provider) {
        String p = provider == null || provider.isBlank() ? PROVIDER_GEMINI : provider;
        prefs.edit()
                .putString(KEY_AI_KEY_PREFIX + p, apiKey)
                .putString(KEY_AI_MODEL_PREFIX + p, model == null || model.isBlank() ? defaultModelFor(p) : model)
                .putString(KEY_AI_PROVIDER, p)
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
        prefs.edit().putInt(KEY_BACKUP_HOUR, hour).putInt(KEY_BACKUP_MINUTE, minute).apply();
    }

    public void setSessionTimeout(int minutes) {
        prefs.edit().putInt(KEY_SESSION_TIMEOUT, minutes).apply();
    }

    public void setActiveBook(int id, String name) {
        prefs.edit().putInt(KEY_ACTIVE_BOOK_ID, id).putString(KEY_ACTIVE_BOOK_NAME, name).apply();
    }

    public boolean isConfigured() {
        String url = getDbUrl();
        return url != null && !url.isEmpty();
    }
}