package com.expenseos.util;

import android.content.Context;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

/**
 * Downloads the Catalyst env variables and saves them through AppConfig (so SecretStore encryption applies).
 */
public final class ConfigDownloader {

    private static final String[] AI_PROVIDERS = {
            AppConfig.PROVIDER_GEMINI, AppConfig.PROVIDER_OPENAI, AppConfig.PROVIDER_GROK,
            AppConfig.PROVIDER_CLAUDE, AppConfig.PROVIDER_GENSPARK};

    private ConfigDownloader() {
    }

    /**
     * Background thread only. Returns how many settings groups were saved.
     */
    public static int download(Context ctx) throws Exception {
        JSONObject v = CatalystClient.get(ctx, "/config").getJSONObject("values");
        if (v.optString("DB_URL", "").isEmpty())
            throw new IOException("DB_URL env variable is missing in the Catalyst function");

        AppConfig cfg = AppConfig.get(ctx);
        int groups = 0;

        cfg.setDb(v.optString("DB_URL", cfg.getDbUrl()),
                v.optString("DB_USER", cfg.getDbUser()),
                v.optString("DB_PASSWORD", cfg.getDbPassword()));
        groups++;

        if (any(v, "GMAIL_FROM", "GMAIL_APP_PASS")) {
            cfg.setGmail(v.optString("GMAIL_FROM", cfg.getGmailFrom()),
                    v.optString("GMAIL_APP_PASS", cfg.getGmailAppPass()));
            groups++;
        }
        if (v.has("SCHEDULER_ALERT_EMAIL")) {
            cfg.setSchedulerAlertEmail(v.getString("SCHEDULER_ALERT_EMAIL"));
            groups++;
        }
        if (any(v, "ZOHO_CLIENT_ID", "ZOHO_CLIENT_SECRET", "ZOHO_REFRESH_TOKEN", "WORKDRIVE_FOLDER_ID")) {
            cfg.setZoho(v.optString("ZOHO_CLIENT_ID", cfg.getZohoClientId()),
                    v.optString("ZOHO_CLIENT_SECRET", cfg.getZohoClientSecret()),
                    v.optString("ZOHO_REFRESH_TOKEN", cfg.getZohoRefreshToken()),
                    v.optString("WORKDRIVE_FOLDER_ID", cfg.getWorkdriveFolderId()));
            groups++;
        }
        if (v.has("IMPORT_EXCEL_FILE_NAME")) {
            cfg.setImportExcelFileName(v.getString("IMPORT_EXCEL_FILE_NAME"));
            groups++;
        }
        if (any(v, "ODOO_URL", "ODOO_DB", "ODOO_USER", "ODOO_APIKEY")) {
            cfg.setOdoo(v.optString("ODOO_URL", cfg.getOdooUrl()),
                    v.optString("ODOO_DB", cfg.getOdooDb()),
                    v.optString("ODOO_USER", cfg.getOdooUser()),
                    v.optString("ODOO_APIKEY", cfg.getOdooApiKey()));
            groups++;
        }

        // Supabase Storage: only the secrets travel through the Catalyst config endpoint.
        // The (non-secret) url + bucket are restored from the cloud app_config table.
        if (any(v, "SUPABASE_ANON_KEY", "SUPABASE_EMAIL", "SUPABASE_PASSWORD")) {
            cfg.setSupabase(cfg.getSupabaseUrl(), cfg.getSupabaseBucket(),
                    v.optString("SUPABASE_ANON_KEY", cfg.getSupabaseAnonKey()),
                    v.optString("SUPABASE_EMAIL", cfg.getSupabaseEmail()),
                    v.optString("SUPABASE_PASSWORD", cfg.getSupabasePassword()));
            groups++;
        }

        // AI: AI_CONFIG (full multi-model / multi-key JSON) wins over the old single-key variables
        String aiJson = v.optString("AI_CONFIG", "").trim();
        if (!aiJson.isEmpty()) {
            AiConfigStore.AiConfig ai;
            try {
                ai = AiConfigStore.parse(aiJson);
            } catch (Exception e) {
                throw new IOException("AI_CONFIG env variable is not valid JSON: " + e.getMessage());
            }
            if (ai.models.isEmpty()) throw new IOException("AI_CONFIG has no models");
            new AiConfigStore(ctx).save(ai);
            groups++;
        } else {
            // Old style: one key + model per provider. setAiConfig() also makes that provider active,
            // so the chosen AI_PROVIDER is applied last.
            for (String p : AI_PROVIDERS) {
                String key = v.optString(aiVar(p, "KEY"), "");
                if (key.isEmpty()) continue;
                cfg.setAiConfig(key, v.optString(aiVar(p, "MODEL"), ""), p);
                groups++;
            }
            String active = v.optString("AI_PROVIDER", "").trim().toLowerCase(Locale.ROOT);
            String activeKey = active.isEmpty() ? "" : v.optString(aiVar(active, "KEY"), "");
            if (!activeKey.isEmpty())
                cfg.setAiConfig(activeKey, v.optString(aiVar(active, "MODEL"), ""), active);
        }

        return groups;
    }

    private static boolean any(JSONObject v, String... keys) {
        for (String k : keys) if (v.has(k)) return true;
        return false;
    }

    private static String aiVar(String provider, String field) {
        return "AI_" + provider.toUpperCase(Locale.ROOT) + "_" + field;
    }
}