package com.expenseos.util;

import android.content.Context;

import java.util.Collections;
import java.util.List;

public class GrokClient extends OpenAiCompatibleClient {
    public GrokClient(Context ctx) {
        super(ctx, AppConfig.PROVIDER_GROK, "https://api.x.ai/v1/chat/completions");
    }

    @Override
    protected String providerLabel() {
        return "Grok";
    }

    @Override
    public List<String> getLastChartPaths() {
        return Collections.emptyList();
    }
}