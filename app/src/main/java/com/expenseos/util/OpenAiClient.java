package com.expenseos.util;

import android.content.Context;

public class OpenAiClient extends OpenAiCompatibleClient {
    public OpenAiClient(Context ctx) {
        super(ctx, AppConfig.PROVIDER_OPENAI, "https://api.openai.com/v1/chat/completions");
    }

    @Override
    protected String providerLabel() {
        return "OpenAI";
    }
}