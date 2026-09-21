package com.expenseos.util;

/**
 * Classifies provider HTTP/network failures into {@link AiException} kinds and
 * produces user-safe messages. Never includes the API key or raw payload in a
 * message — bodies are scrubbed and truncated first.
 */
public final class AiErrorClassifier {

    private AiErrorClassifier() {
    }

    public static AiException fromHttp(String providerLabel, int status, String body, String apiKey) {
        String detail = extractDetail(body);
        String lower = (detail + " " + safe(body)).toLowerCase(java.util.Locale.ROOT);

        if (status == 401 || status == 403 || lower.contains("api_key_invalid")
                || lower.contains("unauthorized") || lower.contains("invalid api key")
                || lower.contains("permission") && status == 403) {
            return new AiException(AiException.Kind.AUTH,
                    providerLabel + ": API key rejected (invalid or expired) — update it in Config.", status);
        }
        if (status == 404 || lower.contains("not found") || lower.contains("not supported")
                || lower.contains("does not exist") || lower.contains("unsupported_model")
                || lower.contains("deprecated")) {
            return new AiException(AiException.Kind.MODEL_UNAVAILABLE,
                    providerLabel + ": model unavailable or not found for this key.", status);
        }
        if (status == 429 || lower.contains("quota") || lower.contains("resource_exhausted")
                || lower.contains("rate limit") || lower.contains("rate_limit")
                || lower.contains("insufficient") || lower.contains("billing")
                || lower.contains("credit") || lower.contains("exceeded")
                || lower.contains("overloaded")) {
            return new AiException(AiException.Kind.RATE_LIMIT,
                    providerLabel + ": rate/quota limit reached on this key.", status);
        }
        if (status >= 500) {
            return new AiException(AiException.Kind.SERVER,
                    providerLabel + ": temporary provider error (" + status + ").", status);
        }
        String shown = detail.isEmpty() ? safe(body) : detail;
        return new AiException(AiException.Kind.BAD_REQUEST,
                providerLabel + " error (" + status + "): " + truncate(scrub(shown, apiKey)), status);
    }

    public static AiException fromNetwork(String providerLabel, Exception e, String apiKey) {
        boolean timeout = e instanceof java.net.SocketTimeoutException;
        String why = timeout ? "request timed out" : "network error";
        return new AiException(AiException.Kind.NETWORK,
                providerLabel + ": " + why + " — check your internet connection.", 0);
    }

    public static AiException fromUnexpected(String providerLabel, Exception e, String apiKey) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return new AiException(AiException.Kind.UNKNOWN,
                providerLabel + ": unexpected error — " + truncate(scrub(msg, apiKey)));
    }

    /** Removes the key itself and common "key=…" URL fragments from any text. */
    public static String scrub(String text, String apiKey) {
        if (text == null) return "";
        String out = text;
        if (apiKey != null && !apiKey.isBlank()) {
            out = out.replace(apiKey, "•••");
        }
        return out.replaceAll("(?i)(key|api[_-]?key|token)\\s*[=:]\\s*[A-Za-z0-9._\\-]{8,}", "$1=•••");
    }

    private static String extractDetail(String body) {
        try {
            org.json.JSONObject root = new org.json.JSONObject(body);
            org.json.JSONObject err = root.optJSONObject("error");
            if (err != null) {
                String m = err.optString("message", "");
                if (!m.isEmpty()) return m;
            }
            String m = root.optString("message", "");
            return m;
        } catch (Exception e) {
            return "";
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }
}
