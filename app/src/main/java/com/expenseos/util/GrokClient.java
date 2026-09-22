package com.expenseos.util;

import android.content.Context;

public class GrokClient extends OpenAiCompatibleClient {
    public GrokClient(Context ctx) {
        super(ctx, AppConfig.PROVIDER_GROK, "https://api.x.ai/v1/chat/completions");
    }

    @Override
    protected String providerLabel() {
        return "Grok";
    }
}