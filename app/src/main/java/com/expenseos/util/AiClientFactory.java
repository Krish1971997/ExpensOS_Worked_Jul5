package com.expenseos.util;

import android.content.Context;

/**
 * Creates single-attempt client instances for one concrete candidate
 * (provider → model → key). Instances are per-turn: they hold fresh
 * ToolDispatcher chart/image state, so a retried turn starts clean.
 */
public class AiClientFactory {

    /**
     * Builds the client for the currently selected default provider (legacy path).
     */
    public static AiProvider create(Context ctx) {
        String provider = AppConfig.get(ctx).getAiProvider();
        return create(ctx, new AiCandidate(provider,
                AppConfig.get(ctx).getAiModel(provider),
                AppConfig.get(ctx).getAiKey(provider), "", 0));
    }

    /**
     * Builds the client for one concrete failover candidate.
     */
    public static AiProvider create(Context ctx, AiCandidate cand) {
        switch (cand.provider()) {
            case AppConfig.PROVIDER_OPENAI:
                return new OpenAiCompatibleClient(ctx, cand);
            case AppConfig.PROVIDER_GROK:
                return new OpenAiCompatibleClient(ctx, cand, "https://api.x.ai/v1/chat/completions");
            case AppConfig.PROVIDER_GENSPARK:
                return new OpenAiCompatibleClient(ctx, cand, "https://api.genspark.ai/v1/chat/completions");
            case AppConfig.PROVIDER_CLAUDE:
                return new ClaudeClient(ctx, cand);
            case AppConfig.PROVIDER_GEMINI:
            default:
                return new GeminiClient(ctx, cand);
        }
    }
}
