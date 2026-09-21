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

    public AiRequest(String userMessage, String imagePath, JSONArray history, String userDisplayText) {
        this.userMessage = userMessage;
        this.imagePath = imagePath;
        this.history = history;
        this.userDisplayText = userDisplayText;
    }
}
