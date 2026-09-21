package com.expenseos.util;

import java.util.ArrayList;
import java.util.List;

/** A configured model under a provider, with its ordered list of API keys. */
public class AiModelConfig {
    public String provider = AppConfig.PROVIDER_GEMINI;
    public String model = "";
    public final List<AiKeyConfig> keys = new ArrayList<>();

    public AiModelConfig() {
    }

    public AiModelConfig(String provider, String model) {
        this.provider = provider;
        this.model = model;
    }

    public AiModelConfig copy() {
        AiModelConfig c = new AiModelConfig(provider, model);
        for (AiKeyConfig k : keys) {
            AiKeyConfig nk = new AiKeyConfig();
            nk.key = k.key;
            nk.desc = k.desc;
            c.keys.add(nk);
        }
        return c;
    }
}
