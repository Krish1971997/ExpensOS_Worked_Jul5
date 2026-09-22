package com.expenseos.util;

import android.content.Context;

import java.util.Collections;
import java.util.List;

public class GrokClient extends OpenAiCompatibleClient {

    public GrokClient(Context ctx) {
        super(
                ctx,
                new AiCandidate(
                        AppConfig.PROVIDER_GROK,
                        AppConfig.get(ctx).getAiModel(AppConfig.PROVIDER_GROK),
                        AppConfig.get(ctx).getAiKey(AppConfig.PROVIDER_GROK),
                        "",
                        0
                ),
                "https://api.x.ai/v1/chat/completions"
        );
    }

    @Override
    public List<String> getLastChartPaths() {
        return Collections.emptyList();
    }
}