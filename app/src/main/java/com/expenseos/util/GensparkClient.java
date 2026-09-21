package com.expenseos.util;

import android.content.Context;

import java.util.Collections;
import java.util.List;

/**
 * Genspark exposes an OpenAI-compatible chat-completions endpoint, so this
 * is just a thin OpenAiCompatibleClient with the production endpoint
 * hard-coded. No hardcoded API key — keys come from prefs via AppConfig.
 */
public class GensparkClient extends OpenAiCompatibleClient {

    public GensparkClient(Context ctx) {
        super(
                ctx,
                new AiCandidate(
                        AppConfig.PROVIDER_GENSPARK,
                        AppConfig.get(ctx).getAiModel(AppConfig.PROVIDER_GENSPARK),
                        AppConfig.get(ctx).getAiKey(AppConfig.PROVIDER_GENSPARK),
                        "",
                        0
                ),
                "https://api.genspark.ai/v1/chat/completions"
        );
    }

    @Override
    public List<String> getLastChartPaths() {
        return Collections.emptyList();
    }
}
