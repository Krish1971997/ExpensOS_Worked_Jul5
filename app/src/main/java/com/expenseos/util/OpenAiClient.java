package com.expenseos.util;

import android.content.Context;

import java.util.Collections;
import java.util.List;

public class OpenAiClient extends OpenAiCompatibleClient {

    public OpenAiClient(Context ctx) {
        super(
                ctx,
                new AiCandidate(
                        AppConfig.PROVIDER_OPENAI,
                        AppConfig.get(ctx).getAiModel(AppConfig.PROVIDER_OPENAI),
                        AppConfig.get(ctx).getAiKey(AppConfig.PROVIDER_OPENAI),
                        "",
                        0
                ),
                "https://api.openai.com/v1/chat/completions"
        );
    }

    @Override
    public List<String> getLastChartPaths() {
        return Collections.emptyList();
    }
}