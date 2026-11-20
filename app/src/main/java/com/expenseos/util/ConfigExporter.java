package com.expenseos.util;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Builds catalyst-config.json from the values saved in the app and shares it via the Android share sheet.
 * The file contains secrets in plain text, so it lives only in the cache dir and is never written to public storage.
 */
public final class ConfigExporter {

    // TODO: copy these three from your current catalyst-config.json so the generated file matches it exactly
    private static final String FUNCTION_NAME = "expenseos_api";
    private static final String STACK = "java17";
    private static final String MAIN_CLASS = "REPLACE_WITH_YOUR_execution.main_VALUE";

    private static final String PREFS = "expenseos_export";
    private static final String PREF_ALLOWED_EMAILS = "allowed_emails";

    private ConfigExporter() {
    }

    /**
     * Entry point for the toolbar button.
     */
    public static void start(Activity a) {
        // ALLOWED_EMAILS lives only in the Catalyst function, so ask once and remember it on the phone
        String saved = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_ALLOWED_EMAILS, "");
        EditText et = new EditText(a);
        et.setHint("ALLOWED_EMAILS (comma separated)");
        et.setText(saved);
        new AlertDialog.Builder(a)
                .setTitle("Generate catalyst-config.json")
                .setMessage("Uses the values already saved in this app. Save each config section first.")
                .setView(et)
                .setPositiveButton("Generate & Share", (d, w) -> {
                    String allowed = et.getText().toString().trim();
                    a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putString(PREF_ALLOWED_EMAILS, allowed).apply();
                    try {
                        share(a, build(a, allowed));
                    } catch (Exception e) {
                        Toast.makeText(a, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    static String build(Context ctx, String allowedEmails) throws Exception {
        AppConfig c = AppConfig.get(ctx);
        JSONObject env = new JSONObject();

        put(env, "DB_URL", c.getDbUrl());
        put(env, "DB_USER", c.getDbUser());
        put(env, "DB_PASSWORD", c.getDbPassword());
        put(env, "ALLOWED_EMAILS", allowedEmails);
        put(env, "GMAIL_FROM", c.getGmailFrom());
        put(env, "GMAIL_APP_PASS", c.getGmailAppPass());
        put(env, "SCHEDULER_ALERT_EMAIL", c.getSchedulerAlertEmail());
        put(env, "ZOHO_CLIENT_ID", c.getZohoClientId());
        put(env, "ZOHO_CLIENT_SECRET", c.getZohoClientSecret());
        put(env, "ZOHO_REFRESH_TOKEN", c.getZohoRefreshToken());
        put(env, "WORKDRIVE_FOLDER_ID", c.getWorkdriveFolderId());
        put(env, "IMPORT_EXCEL_FILE_NAME", c.getImportExcelFileNamesRaw());
        put(env, "ODOO_URL", c.getOdooUrl());
        put(env, "ODOO_DB", c.getOdooDb());
        put(env, "ODOO_USER", c.getOdooUser());
        put(env, "ODOO_APIKEY", c.getOdooApiKey());
        put(env, "SUPABASE_ANON_KEY", c.getSupabaseAnonKey());
        put(env, "SUPABASE_EMAIL", c.getSupabaseEmail());
        put(env, "SUPABASE_PASSWORD", c.getSupabasePassword());

        // AI: one AI_CONFIG variable holds all models and keys (old AI_PROVIDER / AI_*_KEY vars are not needed)
        AiConfigStore.AiConfig ai = new AiConfigStore(ctx).load();
        if (ai != null && !ai.models.isEmpty()) put(env, "AI_CONFIG", AiConfigStore.serialize(ai));

        JSONObject deployment = new JSONObject();
        deployment.put("name", FUNCTION_NAME);
        deployment.put("stack", STACK);
        deployment.put("type", "basic");
        deployment.put("env_variables", env);

        JSONObject execution = new JSONObject();
        execution.put("main", MAIN_CLASS);

        JSONObject root = new JSONObject();
        root.put("deployment", deployment);
        root.put("execution", execution);

        // org.json escapes "/" as "\/" which is valid but ugly, so undo it
        return root.toString(2).replace("\\/", "/");
    }

    private static void put(JSONObject o, String k, String v) throws Exception {
        if (v != null && !v.trim().isEmpty()) o.put(k, v);
    }

    private static void share(Activity a, String json) throws Exception {
        File dir = new File(a.getCacheDir(), "shared");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("Cannot create cache dir");
        File f = new File(dir, "catalyst-config.json");
        try (FileOutputStream out = new FileOutputStream(f, false)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        Uri uri = FileProvider.getUriForFile(a, a.getPackageName() + ".fileprovider", f);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.setClipData(ClipData.newRawUri("catalyst-config.json", uri));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        a.startActivity(Intent.createChooser(i, "Share catalyst-config.json"));
    }
}