package com.expenseos.util;

import android.content.Context;

import java.util.Collections;
import java.util.List;

public class OpenAiClient extends OpenAiCompatibleClient {
    public OpenAiClient(Context ctx) {
        super(ctx, AppConfig.PROVIDER_OPENAI, "https://api.openai.com/v1/chat/completions");
    }

    @Override
    protected String providerLabel() {
        return "OpenAI";
    }

    @Override
    public List<String> getLastChartPaths() {
        return Collections.emptyList();
    }
}