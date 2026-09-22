package com.expenseos.util;

import org.json.JSONArray;

import java.util.List;

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
     * Legacy entry — still implemented for compatibility, but the chat now
     * goes through {@link #askBlocking(AiRequest, Callback)} so the failover
     * manager can retry the same turn on other keys/models.
     */
    void ask(String userMessage, String imagePath, JSONArray conversationHistory, Callback cb);

    /**
     * Blocking single attempt (one provider+model+key). Call from a background
     * thread only. Throws {@link AiException} with a machine-readable kind on
     * failure so the failover manager can decide retry vs. move-on.
     *
     * @return the final assistant answer text (after any tool rounds).
     */
    default String askBlocking(AiRequest request, Callback cb) {
        throw new UnsupportedOperationException("askBlocking not implemented");
    }

    /**
     * Releases any resources held by this client instance. Called after every
     * failover attempt; safe to call more than once.
     */
    default void close() {
    }

    /**
     * Non-null only if a chart was rendered during the most recent ask() call.
     */
    String getLastChartPath();

    List<String> getLastChartPaths();

    /**
     * Non-null only if generate_image was called (via Grok) during the most recent ask() call.
     */
    String getLastImagePath();
}
