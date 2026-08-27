package com.expenseos.util;

import android.content.Context;

public class AiClientFactory {
    public static AiProvider create(Context ctx) {
        String provider = AppConfig.get(ctx).getAiProvider();
        return switch (provider) {
            case AppConfig.PROVIDER_OPENAI -> new OpenAiClient(ctx);
            case AppConfig.PROVIDER_GROK -> new GrokClient(ctx);
            case AppConfig.PROVIDER_CLAUDE -> new ClaudeClient(ctx);
            default -> new GeminiClient(ctx);
        };
    }
}