package com.expenseos.util;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Neutral conversation-history helpers.
 *
 * Neutral format (what ChatActivity keeps and providers receive):
 *   [ { "role": "user"|"assistant", "text": "…" }, … ]
 *
 * Each client converts this into its own wire format so the shared history
 * never contains provider-specific tool traffic or duplicated system prompts.
 */
public final class AiHistory {

    private AiHistory() {
    }

    /** Drops blank entries, leading assistant turns and consecutive same-role turns (merging their text). */
    public static JSONArray sanitize(JSONArray neutral) {
        JSONArray out = new JSONArray();
        if (neutral == null) return out;
        String lastRole = null;
        for (int i = 0; i < neutral.length(); i++) {
            try {
                JSONObject e = neutral.getJSONObject(i);
                String role = e.optString("role", "");
                String text = e.optString("text", "");
                if ((!role.equals("user") && !role.equals("assistant")) || text == null || text.isBlank()) continue;
                if (out.length() == 0 && role.equals("assistant")) continue; // must start with user
                if (role.equals(lastRole)) {
                    // merge consecutive same-role entries into the previous one
                    JSONObject prev = out.getJSONObject(out.length() - 1);
                    prev.put("text", prev.optString("text") + "\n" + text);
                    continue;
                }
                out.put(new JSONObject().put("role", role).put("text", text));
                lastRole = role;
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** Gemini "contents" format: roles user/model with text parts. */
    public static JSONArray toGemini(JSONArray neutral) throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; i < neutral.length(); i++) {
            JSONObject e = neutral.getJSONObject(i);
            String role = e.getString("role");
            JSONObject content = new JSONObject();
            content.put("role", role.equals("assistant") ? "model" : "user");
            content.put("parts", new JSONArray().put(new JSONObject().put("text", e.getString("text"))));
            out.put(content);
        }
        return out;
    }

    /** OpenAI chat-completions "messages" format (no system message — added per-request by the client). */
    public static JSONArray toOpenAi(JSONArray neutral) throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; i < neutral.length(); i++) {
            JSONObject e = neutral.getJSONObject(i);
            out.put(new JSONObject().put("role", e.getString("role")).put("content", e.getString("text")));
        }
        return out;
    }

    /** Claude "messages" format: content blocks. */
    public static JSONArray toClaude(JSONArray neutral) throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; i < neutral.length(); i++) {
            JSONObject e = neutral.getJSONObject(i);
            JSONObject m = new JSONObject();
            m.put("role", e.getString("role"));
            m.put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", e.getString("text"))));
            out.put(m);
        }
        return out;
    }
}
