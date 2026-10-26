package com.expenseos.util;

import org.json.JSONArray;

/**
 * A single chat turn to send: the effective user message, optional image path,
 * a bounded neutral-format history snapshot, and the user-facing display text
 * (for rebuilding conversation context after a successful turn).
 */
public class AiRequest {
    public final String userMessage;
    public final String imagePath;
    public final JSONArray history;
    public final String userDisplayText;

    /**
     * Absolute wall-clock deadline for this whole turn (set by AiFailoverManager). 0 = no deadline.
     */
    public volatile long deadlineMs = 0;

    public AiRequest(String userMessage, String imagePath, JSONArray history, String userDisplayText) {
        this.userMessage = userMessage;
        this.imagePath = imagePath;
        this.history = history;
        this.userDisplayText = userDisplayText;
    }

    /**
     * Milliseconds left in this turn's budget (Long.MAX_VALUE if no deadline).
     */
    public long remainingMs() {
        return deadlineMs <= 0 ? Long.MAX_VALUE : deadlineMs - System.currentTimeMillis();
    }

    /**
     * Read timeout capped to the remaining budget. Never 0, because 0 means "infinite" in HttpURLConnection.
     */
    public int readTimeoutMs(int capMs) {
        long rem = remainingMs();
        return (int) Math.max(1000, Math.min(capMs, rem));
    }
}
