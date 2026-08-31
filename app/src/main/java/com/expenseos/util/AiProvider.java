package com.expenseos.util;

import org.json.JSONArray;

public interface AiProvider {
    interface Callback {
        void onResult(String answer);

        void onError(String message);

        /**
         * Called on a background thread whenever the assistant moves to a new step (e.g. "Checking tables…").
         */
        void onProgress(String stage);
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

    /**
     * Non-null only if generate_image was called (via Grok) during the most recent ask() call.
     */
    String getLastImagePath();

}