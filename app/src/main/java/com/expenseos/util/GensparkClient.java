package com.expenseos.util;

import android.content.Context;

/**
 * Genspark exposes an OpenAI-compatible chat-completions endpoint, so this
 * is just a thin OpenAiCompatibleClient with the production endpoint
 * hard-coded. No hardcoded API key — keys come from prefs via AppConfig.
 */
public class GensparkClient extends OpenAiCompatibleClient {

    // Public endpoint; model defaults to "genspark-instruct" in AppConfig.
    public GensparkClient(Context ctx) {
        super(ctx, AppConfig.PROVIDER_GENSPARK, "https://api.genspark.ai/v1/chat/completions");
    }

    @Override
    protected String providerLabel() {
        return "Genspark";
    }
}
