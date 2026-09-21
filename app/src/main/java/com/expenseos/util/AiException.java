package com.expenseos.util;

/**
 * Failure raised by any AI client, carrying a machine-readable error kind so
 * the failover manager can decide between local retry, key cooldown and
 * moving to the next key/model. Messages are always user-safe: they never
 * contain API keys or raw provider payloads.
 */
public class AiException extends RuntimeException {
    public enum Kind {
        RATE_LIMIT,        // 429 / quota / credit / rate limit → cool down key, move on
        AUTH,              // 401/403 / invalid or expired key → long cooldown, move on
        MODEL_UNAVAILABLE, // 404 / model not found → cool down model, move on
        SERVER,            // 5xx → bounded local retry, then move on
        NETWORK,           // timeout / IO → bounded local retry, then move on
        BAD_REQUEST,       // 400-class, likely prompt/content issue
        UNKNOWN
    }

    public final Kind kind;
    public final int httpStatus;

    public AiException(Kind kind, String message) {
        this(kind, message, 0);
    }

    public AiException(Kind kind, String message, int httpStatus) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }
}
