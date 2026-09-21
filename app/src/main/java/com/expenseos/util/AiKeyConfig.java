package com.expenseos.util;

/** One API key entry: the secret plus a free-text description (e.g. an email). */
public class AiKeyConfig {
    public String key = "";
    public String desc = "";

    public AiKeyConfig() {
    }

    public AiKeyConfig(String key, String desc) {
        this.key = key == null ? "" : key;
        this.desc = desc == null ? "" : desc;
    }
}
