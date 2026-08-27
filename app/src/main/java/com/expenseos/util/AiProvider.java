package com.expenseos.util;

import org.json.JSONArray;

public interface AiProvider {
    interface Callback {
        void onResult(String answer);

        void onError(String message);
    }

    /**
     * Blocking — call from a background thread only.
     *
     * @param imagePath absolute path to a local image file to attach (vision), or null for none.
     */
    void ask(String userMessage, String imagePath, JSONArray conversationHistory, Callback cb);

    /**
     * Non-null only if a chart was rendered during the most recent ask() call.
     */
    String getLastChartPath();
}