package com.expenseos.util;

/**
 * One concrete (provider → model → api key) attempt unit, in priority order.
 */
public record AiCandidate(String provider, String model, String apiKey, String keyDesc,
                          int keyIndex) {
    public AiCandidate(String provider, String model, String apiKey, String keyDesc, int keyIndex) {
        this.provider = provider;
        this.model = model;
        this.apiKey = apiKey;
        this.keyDesc = keyDesc == null ? "" : keyDesc;
        this.keyIndex = keyIndex;
    }

    public String providerLabel() {
        return providerLabel(provider);
    }

    public static String providerLabel(String p) {
        if (p == null) return "AI";
        switch (p) {
            case AppConfig.PROVIDER_OPENAI:
                return "OpenAI";
            case AppConfig.PROVIDER_GROK:
                return "Grok";
            case AppConfig.PROVIDER_CLAUDE:
                return "Claude";
            case AppConfig.PROVIDER_GENSPARK:
                return "Genspark";
            default:
                return "Gemini";
        }
    }

    /**
     * Short masked key hint, e.g. "AQ…7f2a" — safe for UI / logs / error text.
     */
    public static String mask(String key) {
        if (key == null || key.isBlank()) return "•••";
        String t = key.trim();
        if (t.length() <= 8) return "•••";
        return t.substring(0, 2) + "…" + t.substring(t.length() - 4);
    }

    /**
     * Human-readable identity of this candidate — never exposes the full key.
     */
    public String maskedLabel() {
        return providerLabel() + " · " + model + " · key #" + (keyIndex + 1)
                + (keyDesc.isEmpty() ? "" : " (" + keyDesc + ")")
                + " [" + mask(apiKey) + "]";
    }

    /**
     * Short label for the "switching to…" UI hint, e.g. "Gemini - key 2".
     */
    public String switchLabel() {
        return providerLabel() + " - key " + (keyIndex + 1);
    }
}
