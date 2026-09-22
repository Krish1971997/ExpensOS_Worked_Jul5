package com.expenseos.util;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistence for the multi-model / multi-key AI configuration.
 *
 * Storage format (SharedPreferences "expenseos_config", one JSON blob):
 *   ai.config.v2 = {"activeProvider":"gemini","models":[
 *       {"provider":"gemini","model":"gemini-2.0-flash",
 *        "keys":[{"key":"…","desc":"test@gmail.com"}, …]}, …]}
 *
 * Backward compatibility: on first read the legacy per-provider slots
 * (ai.key.<provider> / ai.model.<provider>, one key+model per provider) are
 * migrated into the v2 structure (one model, one key each) — the legacy keys
 * are kept intact so an upgrade never destroys existing settings.
 *
 * NOTE: keys are stored the same way the existing app already stores its
 * single API key (SharedPreferences). This preserves the existing security
 * design; no new exposure is introduced.
 */
public class AiConfigStore {

    private static final String KEY_V2 = "ai.config.v2";
    private static final String LEGACY_ACTIVE = "ai.provider"; // old active-provider flag

    private final SharedPreferences prefs;

    public AiConfigStore(Context ctx) {
        this.prefs = ctx.getApplicationContext()
                .getSharedPreferences("expenseos_config", Context.MODE_PRIVATE);
    }

    /** In-memory working copy (edited by the Config UI, persisted on save). */
    public static class AiConfig {
        public String activeProvider = AppConfig.PROVIDER_GEMINI;
        public final List<AiModelConfig> models = new ArrayList<>();

        public AiConfig copy() {
            AiConfig c = new AiConfig();
            c.activeProvider = activeProvider;
            for (AiModelConfig m : models) c.models.add(m.copy());
            return c;
        }
    }

    /** Loads the config, migrating legacy storage transparently. Never returns null. */
    public synchronized AiConfig load() {
        String raw = prefs.getString(KEY_V2, null);
        if (raw != null && !raw.isBlank()) {
            try {
                AiConfig cfg = parse(raw);
                ensureSane(cfg);
                return cfg;
            } catch (Exception ignored) {
                // fall through to legacy migration
            }
        }
        AiConfig cfg = migrateLegacy();
        save(cfg);
        return cfg;
    }

    public synchronized void save(AiConfig cfg) {
        try {
            prefs.edit().putString(KEY_V2, serialize(cfg)).apply();
            if (cfg.activeProvider != null && !cfg.activeProvider.isBlank()) {
                // keep the legacy flag in sync for any older readers
                prefs.edit().putString(LEGACY_ACTIVE, cfg.activeProvider).apply();
            }
        } catch (Exception ignored) {
        }
    }

    // ── JSON ─────────────────────────────────────────────────────────
    public static String serialize(AiConfig cfg) throws Exception {
        JSONObject root = new JSONObject();
        root.put("activeProvider", cfg.activeProvider);
        JSONArray arr = new JSONArray();
        for (AiModelConfig m : cfg.models) {
            JSONObject mo = new JSONObject();
            mo.put("provider", m.provider);
            mo.put("model", m.model);
            JSONArray ks = new JSONArray();
            for (AiKeyConfig k : m.keys) {
                JSONObject ko = new JSONObject();
                ko.put("key", k.key == null ? "" : k.key);
                ko.put("desc", k.desc == null ? "" : k.desc);
                ks.put(ko);
            }
            mo.put("keys", ks);
            arr.put(mo);
        }
        root.put("models", arr);
        return root.toString();
    }

    public static AiConfig parse(String raw) throws Exception {
        JSONObject root = new JSONObject(raw);
        AiConfig cfg = new AiConfig();
        cfg.activeProvider = root.optString("activeProvider", AppConfig.PROVIDER_GEMINI);
        JSONArray arr = root.optJSONArray("models");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject mo = arr.getJSONObject(i);
                AiModelConfig m = new AiModelConfig(
                        mo.optString("provider", AppConfig.PROVIDER_GEMINI),
                        mo.optString("model", ""));
                JSONArray ks = mo.optJSONArray("keys");
                if (ks != null) {
                    for (int j = 0; j < ks.length(); j++) {
                        JSONObject ko = ks.getJSONObject(j);
                        m.keys.add(new AiKeyConfig(ko.optString("key", ""), ko.optString("desc", "")));
                    }
                }
                cfg.models.add(m);
            }
        }
        return cfg;
    }

    private static void ensureSane(AiConfig cfg) {
        if (cfg.activeProvider == null || cfg.activeProvider.isBlank())
            cfg.activeProvider = AppConfig.PROVIDER_GEMINI;
        // drop models without a model name; drop blank keys
        List<AiModelConfig> keep = new ArrayList<>();
        for (AiModelConfig m : cfg.models) {
            if (m.model == null || m.model.isBlank()) continue;
            m.keys.removeIf(k -> k.key == null || k.key.isBlank());
            keep.add(m);
        }
        cfg.models.clear();
        cfg.models.addAll(keep);
    }

    /**
     * Reads the legacy per-provider slots and converts them into the new
     * structure — one model per provider, one key each, in a fixed default
     * priority (gemini → openai → grok → claude → genspark). Legacy keys are
     * NOT removed, so a reinstall/rollback keeps working.
     */
    private AiConfig migrateLegacy() {
        AiConfig cfg = new AiConfig();
        String[] providers = {
                AppConfig.PROVIDER_GEMINI, AppConfig.PROVIDER_OPENAI, AppConfig.PROVIDER_GROK,
                AppConfig.PROVIDER_CLAUDE, AppConfig.PROVIDER_GENSPARK
        };
        for (String p : providers) {
            String key = prefs.getString("ai.key." + p, "");
            String model = prefs.getString("ai.model." + p, "");
            if (key == null || key.isBlank()) continue;
            if (model == null || model.isBlank()) model = new AppConfig(null).defaultModelFor(p);
            AiModelConfig m = new AiModelConfig(p, model);
            m.keys.add(new AiKeyConfig(key, ""));
            cfg.models.add(m);
        }
        cfg.activeProvider = prefs.getString(LEGACY_ACTIVE, AppConfig.PROVIDER_GEMINI);
        return cfg;
    }
}
