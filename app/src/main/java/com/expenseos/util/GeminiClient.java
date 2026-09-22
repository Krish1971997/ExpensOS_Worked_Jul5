package com.expenseos.util;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class GeminiClient implements AiProvider {

    // Known-good ids, tried in order. Kept inside the client so a stale/renamed
    // model id, an overloaded model (503) or an exhausted quota (429) can never
    // dead-end the chat: the client rotates and remembers what actually worked.
    private static final String[] MODEL_CHAIN = {
            "gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash", "gemini-1.5-flash"
    };
    private static final String GEMINI_ENDPOINT_BASE =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    // Session memory of models that reported "quota/credits exhausted" or
    // "not found", so later turns skip them automatically (auto-switch).
    private static final Set<String> UNAVAILABLE =
            Collections.synchronizedSet(new HashSet<String>());
    private static volatile String ACTIVE_MODEL = null;

    private static final int MAX_ROUNDS = 12;
    private static final int TRANSIENT_ATTEMPTS = 2;
    private static final int MAX_HISTORY = 20;

    private static String buildSystemPrompt() {
        String today = java.time.LocalDate.now().toString(); // yyyy-MM-dd
        return
                "You are the in-app data assistant for ExpenseOS, a personal expense-tracking app. " +
                        "Today's date is " + today + " — use this directly for \"today\"/\"yesterday\"/\"this month\" " +
                        "style questions instead of spending a step figuring out the date. " +
                        "You can ONLY answer questions about this app's own data (transactions, categories, " +
                        "budgets, cash books, backups, schedulers, etc.) using the provided tools. " +
                        "You must NEVER attempt to modify data — you only have read tools available. " +
                        "Always start by calling list_tables, then describe_table on relevant tables before writing a query. " +
                        "If the user asks to visualize or chart something, call render_chart with the labels/values " +
                        "AFTER you've queried the data. Always reply in the same language and style the user wrote " +
                        "in — including Tanglish (Tamil written in English letters), plain English, or Tamil script; " +
                        "match their language rather than defaulting to English. " +
                        "Prefer answering directly instead of running the same query twice. " +
                        "Keep answers concise and grounded only in query results.";
    }

    private final ToolDispatcher dispatcher;
    private final String apiKey;
    private final String model;

    public GeminiClient(Context ctx) {
        AppConfig cfg = AppConfig.get(ctx);
        this.apiKey = cfg.getAiKey(AppConfig.PROVIDER_GEMINI);
        this.model = cfg.getAiModel(AppConfig.PROVIDER_GEMINI);
        this.dispatcher = new ToolDispatcher(ctx);
    }

    /** The model that most recently answered — useful for showing which engine replied. */
    public static String getActiveModel() {
        return ACTIVE_MODEL;
    }

    @Override
    public String getLastChartPath() {
        return dispatcher.getLastChartPath();
    }

    @Override
    public String getLastImagePath() {
        return dispatcher.getLastImagePath();
    }

    // ── HTTP failure classification ──────────────────────────────────
    private static final class HttpFailure extends RuntimeException {
        final int status;
        final String apiStatus;

        HttpFailure(int status, String apiStatus, String message) {
            super(message);
            this.status = status;
            this.apiStatus = apiStatus == null ? "" : apiStatus;
        }

        boolean isAuth() {
            return status == 401 || status == 403
                    || "API_KEY_INVALID".equals(apiStatus) || "PERMISSION_DENIED".equals(apiStatus);
        }

        /** Credits / tokens / free-tier quota used up on THIS model → switch model. */
        boolean isExhausted() {
            return status == 429 || "RESOURCE_EXHAUSTED".equals(apiStatus)
                    || "QUOTA_EXCEEDED".equals(apiStatus);
        }

        /** Model overloaded / server side hiccup → retry, then switch model. */
        boolean isTransient() {
            return status == 500 || status == 502 || status == 503 || status == 504
                    || status == -1
                    || "UNAVAILABLE".equals(apiStatus) || "INTERNAL".equals(apiStatus)
                    || "DEADLINE_EXCEEDED".equals(apiStatus);
        }

        /** Bad/renamed model id → switch model. */
        boolean isModelBad() {
            return status == 404 || "NOT_FOUND".equals(apiStatus)
                    || "INVALID_ARGUMENT".equals(apiStatus);
        }

        /** Nothing can be fixed by rotating models (safety block, empty answer, …). */
        boolean isDefinitive() {
            return !isAuth() && !isExhausted() && !isTransient() && !isModelBad();
        }
    }

    @Override
    public void ask(String userMessage, String imagePath, JSONArray conversationHistory, Callback cb) {
        dispatcher.resetChart();
        if (apiKey == null || apiKey.isBlank()) {
            cb.onError("Gemini API key is not configured in Config.");
            return;
        }

        JSONArray contents;
        try {
            contents = normalizeHistory(conversationHistory);
            contents.put(imagePath != null
                    ? createContentWithImage(userMessage, imagePath)
                    : createContent("user", userMessage));
            contents = normalizeHistory(contents); // merges into a valid alternating shape
        } catch (Exception e) {
            cb.onError("Gemini: couldn't build the request — " + e);
            return;
        }

        Exception lastError = null;
        for (String useModel : modelChain()) {
            if (UNAVAILABLE.contains(useModel)) continue;
            try {
                runConversation(contents, useModel, cb);
                ACTIVE_MODEL = useModel;
                return;
            } catch (HttpFailure f) {
                lastError = f;
                if (f.isAuth() || f.isDefinitive()) {
                    cb.onError(f.getMessage());
                    return;
                }
                UNAVAILABLE.add(useModel);
                if (f.isExhausted()) {
                    cb.onProgress("Quota reached on " + useModel + " — switching model…");
                } else if (f.isTransient()) {
                    cb.onProgress(useModel + " is busy — trying another model…");
                } else {
                    cb.onProgress(useModel + " unavailable — trying another model…");
                }
            } catch (Throwable t) {
                lastError = (t instanceof Exception) ? (Exception) t : new RuntimeException(t);
                cb.onProgress("Network problem with " + useModel + " — trying another model…");
            }
        }

        String detail = lastError != null && lastError.getMessage() != null ? lastError.getMessage() : "";
        StringBuilder msg = new StringBuilder("Couldn't reach any Gemini model just now. ");
        if (!UNAVAILABLE.isEmpty()) msg.append("Already blocked this session: ").append(UNAVAILABLE).append(". ");
        if (!detail.isEmpty()) msg.append("Last error: ").append(detail).append(" ");
        msg.append("Wait a few seconds and tap Retry, or check the key/model in Config.");
        cb.onError(msg.toString());
    }

    private String[] modelChain() {
        LinkedHashSet<String> chain = new LinkedHashSet<>();
        if (ACTIVE_MODEL != null && !ACTIVE_MODEL.isBlank()) chain.add(ACTIVE_MODEL);
        if (model != null && !model.isBlank()) chain.add(model);
        for (String m : MODEL_CHAIN) chain.add(m);
        return chain.toArray(new String[0]);
    }

    // ── One full assistant turn against one model ────────────────────
    private void runConversation(JSONArray contents, String useModel, Callback cb) throws Exception {
        for (int round = 0; round < MAX_ROUNDS; round++) {
            JSONObject response = callWithRetries(contents, useModel, true);

            JSONArray candidates = response.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                JSONObject feedback = response.optJSONObject("promptFeedback");
                String why = feedback != null ? feedback.optString("blockReason", "") : "";
                throw new HttpFailure(200, "", "The model returned no answer"
                        + (why.isEmpty() ? "." : " (blocked: " + why + ")."));
            }
            JSONObject candidate = candidates.getJSONObject(0);
            String finish = candidate.optString("finishReason", "");
            JSONObject content = candidate.optJSONObject("content");
            JSONArray parts = content != null ? content.optJSONArray("parts") : null;

            if (parts == null || parts.length() == 0) {
                if ("SAFETY".equals(finish) || "PROHIBITED_CONTENT".equals(finish) || "RECITATION".equals(finish))
                    throw new HttpFailure(200, "", "The model declined that request (" + finish + "). Try rephrasing.");
                if ("MAX_TOKENS".equals(finish))
                    throw new HttpFailure(200, "", "That answer was cut off (token limit). Try a shorter question.");
                throw new HttpFailure(200, "", "The model returned an empty response"
                        + (finish.isEmpty() ? "." : " (" + finish + ")."));
            }

            JSONObject firstPart = parts.getJSONObject(0);

            if (firstPart.has("functionCall")) {
                contents.put(content);
                JSONObject fnCall = firstPart.getJSONObject("functionCall");
                String fnName = fnCall.getString("name");
                JSONObject args = fnCall.optJSONObject("args");
                if (args == null) args = new JSONObject();

                cb.onProgress(progressLabel(fnName, args));

                String toolResult;
                try {
                    toolResult = dispatcher.dispatch(fnName, args);
                } catch (Throwable t) {
                    toolResult = "ERROR: " + (t.getMessage() != null ? t.getMessage() : t.toString());
                }

                JSONObject functionResponse = new JSONObject();
                functionResponse.put("name", fnName);
                functionResponse.put("response", new JSONObject().put("result", toolResult));
                JSONObject toolResponseContent = new JSONObject();
                toolResponseContent.put("role", "user");
                toolResponseContent.put("parts", new JSONArray().put(
                        new JSONObject().put("functionResponse", functionResponse)));
                contents.put(toolResponseContent);

                cb.onProgress("Thinking…");
                continue;
            }

            String textResponse = firstPart.optString("text", "").trim();
            if (textResponse.isEmpty()) {
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject p = parts.optJSONObject(i);
                    String t = p != null ? p.optString("text", "") : "";
                    if (!t.trim().isEmpty()) {
                        textResponse = t.trim();
                        break;
                    }
                }
            }
            cb.onResult(textResponse.isEmpty() ? "I couldn't find anything to report for that." : textResponse);
            return;
        }

        // Ran out of tool rounds — ask once more with tools OFF for a final summary,
        // instead of dumping a "maximum steps" error on the user.
        try {
            JSONObject finalResp = callWithRetries(contents, useModel, false);
            JSONArray cands = finalResp.optJSONArray("candidates");
            if (cands != null && cands.length() > 0) {
                JSONObject c = cands.getJSONObject(0).optJSONObject("content");
                JSONArray ps = c != null ? c.optJSONArray("parts") : null;
                if (ps != null && ps.length() > 0) {
                    String t = ps.getJSONObject(0).optString("text", "").trim();
                    if (!t.isEmpty()) {
                        cb.onResult(t);
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        cb.onError("That question needed too many steps. Try asking something more specific.");
    }

    // ── HTTP with transient retry on the SAME model ──────────────────
    private JSONObject callWithRetries(JSONArray contents, String useModel, boolean withTools) {
        HttpFailure last = null;
        for (int attempt = 0; attempt < TRANSIENT_ATTEMPTS; attempt++) {
            try {
                return requestOnce(contents, useModel, withTools);
            } catch (HttpFailure f) {
                last = f;
                // Credentials broken, model missing or quota spent: rotating/retrying
                // the same model cannot help — let the caller switch models now.
                if (f.isAuth() || f.isExhausted() || f.isModelBad()) throw f;
                if (!f.isTransient()) throw f;
                if (attempt < TRANSIENT_ATTEMPTS - 1) sleepQuietly(500L);
            }
        }
        throw last != null ? last : new HttpFailure(-1, "", "Gemini: request failed.");
    }

    private JSONObject requestOnce(JSONArray contents, String useModel, boolean withTools) {
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            JSONObject sysInstruction = new JSONObject();
            sysInstruction.put("parts", new JSONArray().put(new JSONObject().put("text", buildSystemPrompt())));
            body.put("systemInstruction", sysInstruction);
            body.put("contents", contents);
            if (withTools) {
                body.put("tools", new JSONArray().put(
                        new JSONObject().put("functionDeclarations", functionDeclarations())));
            }

            URL url = new URL(GEMINI_ENDPOINT_BASE + useModel + ":generateContent?key=" + apiKey);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            String responseStr = is != null ? new String(is.readAllBytes(), StandardCharsets.UTF_8) : "";

            if (status < 200 || status >= 300) {
                String apiStatus = "";
                String message = "";
                try {
                    JSONObject err = new JSONObject(responseStr).optJSONObject("error");
                    if (err != null) {
                        apiStatus = err.optString("status", "");
                        message = err.optString("message", "");
                    }
                } catch (Exception ignored) {
                    message = responseStr.length() > 200 ? responseStr.substring(0, 200) : responseStr;
                }
                throw new HttpFailure(status, apiStatus, friendlyError(status, apiStatus, message));
            }
            return new JSONObject(responseStr);
        } catch (HttpFailure f) {
            throw f;
        } catch (java.net.SocketTimeoutException e) {
            throw new HttpFailure(-1, "", "Gemini: the model took too long to respond.");
        } catch (Exception e) {
            throw new HttpFailure(-1, "", "Gemini: network error — "
                    + (e.getMessage() != null ? e.getMessage() : e.toString()));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private JSONArray functionDeclarations() throws Exception {
        JSONArray functionDeclarations = new JSONArray();
        functionDeclarations.put(createToolDeclaration("list_tables", "List database tables", new JSONObject()));

        JSONObject descProps = new JSONObject();
        descProps.put("table_name", new JSONObject().put("type", "STRING"));
        functionDeclarations.put(createToolDeclaration("describe_table", "Describe table structure", descProps));

        JSONObject queryProps = new JSONObject();
        queryProps.put("sql", new JSONObject().put("type", "STRING"));
        functionDeclarations.put(createToolDeclaration("run_query", "Execute SELECT query", queryProps));

        JSONObject chartProps = new JSONObject();
        chartProps.put("title", new JSONObject().put("type", "STRING"));
        chartProps.put("labels", new JSONObject().put("type", "ARRAY").put("items", new JSONObject().put("type", "STRING")));
        chartProps.put("values", new JSONObject().put("type", "ARRAY").put("items", new JSONObject().put("type", "NUMBER")));
        functionDeclarations.put(createToolDeclaration("render_chart",
                "Render a bar chart from labels and values, shown to the user as an image", chartProps));

        JSONObject imageProps = new JSONObject();
        imageProps.put("prompt", new JSONObject().put("type", "STRING"));
        functionDeclarations.put(createToolDeclaration("generate_image",
                "Generate an AI illustrative image from a text prompt (via Grok/xAI). Only for explicit picture/illustration requests — use render_chart for real data/stats instead.",
                imageProps));
        return functionDeclarations;
    }

    // ── History hygiene: Gemini rejects non-alternating turns ────────
    private JSONArray normalizeHistory(JSONArray raw) throws Exception {
        JSONArray out = new JSONArray();
        if (raw == null) return out;

        List<JSONObject> items = new ArrayList<>();
        for (int i = 0; i < raw.length(); i++) {
            JSONObject o = raw.optJSONObject(i);
            if (o != null) items.add(o);
        }

        String lastRole = null;
        JSONArray lastParts = null;
        for (JSONObject o : items) {
            String role = o.optString("role", "user").toLowerCase(Locale.ROOT);
            boolean isModel = role.contains("model") || role.contains("assistant")
                    || role.contains("bot") || role.contains("ai");
            role = isModel ? "model" : "user";

            JSONArray parts = o.optJSONArray("parts");
            if (parts == null) {
                String t = o.optString("text", o.optString("content", ""));
                parts = new JSONArray().put(new JSONObject().put("text", t));
            }
            if (parts.length() == 0) continue;

            if (role.equals(lastRole) && lastParts != null) {
                for (int i = 0; i < parts.length(); i++) lastParts.put(parts.get(i)); // merge same-role turns
            } else {
                JSONObject c = new JSONObject();
                c.put("role", role);
                c.put("parts", parts);
                out.put(c);
                lastRole = role;
                lastParts = parts;
            }
        }

        while (out.length() > MAX_HISTORY) out.remove(0);
        while (out.length() > 0 && !"user".equals(out.getJSONObject(0).optString("role"))) out.remove(0);
        return out;
    }

    private String progressLabel(String toolName, JSONObject args) {
        return switch (toolName) {
            case "list_tables" -> "Checking tables…";
            case "describe_table" -> "Reading \"" + args.optString("table_name", "table") + "\" structure…";
            case "run_query" -> "Querying your data…";
            case "render_chart" -> "Drawing chart…";
            case "generate_image" -> "Generating image…";
            default -> "Working on \"" + toolName + "\"…";
        };
    }

    private JSONObject createContent(String role, String text) throws Exception {
        JSONObject content = new JSONObject();
        content.put("role", role);
        JSONArray parts = new JSONArray();
        parts.put(new JSONObject().put("text", text));
        content.put("parts", parts);
        return content;
    }

    private JSONObject createContentWithImage(String text, String imagePath) throws Exception {
        byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(imagePath));
        String b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
        String mime = imagePath.toLowerCase(Locale.ROOT).endsWith(".png") ? "image/png" : "image/jpeg";

        JSONObject content = new JSONObject();
        content.put("role", "user");
        JSONArray parts = new JSONArray();
        parts.put(new JSONObject().put("text", text));
        JSONObject inlineData = new JSONObject();
        inlineData.put("mime_type", mime);
        inlineData.put("data", b64);
        parts.put(new JSONObject().put("inline_data", inlineData));
        content.put("parts", parts);
        return content;
    }

    private String friendlyError(int status, String apiStatus, String message) {
        if (status == 400 && "API_KEY_INVALID".equals(apiStatus))
            return "Gemini: invalid API key — check it in Config.";
        if (status == 401 || status == 403 || "PERMISSION_DENIED".equals(apiStatus))
            return "Gemini: this API key isn't allowed for that model — check the key/billing in Config.";
        if (status == 429 || "RESOURCE_EXHAUSTED".equals(apiStatus))
            return "Gemini: quota/credits used up on this model — switching to the next one…";
        if (status == 404 || "NOT_FOUND".equals(apiStatus))
            return "Gemini: model \"" + model + "\" not found — trying another model…";
        if (status == 500 || status == 502 || status == 503 || status == 504 || "UNAVAILABLE".equals(apiStatus))
            return "Gemini is busy (" + status + ") — retrying…";
        if (message != null && !message.isEmpty()) return "Gemini error (" + status + "): " + message;
        return "Gemini error (" + status + ").";
    }

    private JSONObject createToolDeclaration(String name, String description, JSONObject properties) throws Exception {
        JSONObject fn = new JSONObject();
        fn.put("name", name);
        fn.put("description", description);
        if (properties.length() > 0) {
            JSONObject parameters = new JSONObject();
            parameters.put("type", "OBJECT");
            parameters.put("properties", properties);
            fn.put("parameters", parameters);
        }
        return fn;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
