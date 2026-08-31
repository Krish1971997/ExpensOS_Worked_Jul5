package com.expenseos.util;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * OpenAI's Chat Completions tool-calling format — shared by OpenAI itself
 * and xAI's Grok (Grok's API is OpenAI-compatible), so both just supply a
 * different endpoint/key/model to this same request/response logic.
 */
public abstract class OpenAiCompatibleClient implements AiProvider {

    private static String buildSystemPrompt() {
        String today = java.time.LocalDate.now().toString(); // yyyy-MM-dd
        return
                "You are the in-app data assistant for ExpenseOS, a personal expense-tracking app. " +
                        "Today's date is " + today + " — use this directly for \"today\"/\"yesterday\"/\"this month\" " +
                        "style questions instead of spending a step figuring out the date. " +
                        "You can ONLY answer questions about this app's own data (transactions, categories, " +
                        "budgets, cash books, backups, schedulers, etc.) using the provided tools. " +
                        "You must NEVER attempt to modify data — you only have read tools available. " +
                        "Always start by calling list_tables, then describe_table on relevant tables before " +
                        "writing a query — never guess column names. If the user asks to visualize or chart " +
                        "something, call render_chart with labels/values AFTER querying the data. " +
                        "Always reply in the same language and style the user wrote in — including Tanglish " +
                        "(Tamil written in English letters), plain English, or Tamil script; match their " +
                        "language rather than defaulting to English. " +
                        "Keep answers concise and grounded only in query results.";
    }

    private final ToolDispatcher dispatcher;
    protected final String apiKey;
    protected final String model;
    protected final String endpoint;

    protected OpenAiCompatibleClient(Context ctx, String provider, String endpoint) {
        AppConfig cfg = AppConfig.get(ctx);
        this.apiKey = cfg.getAiKey(provider);
        this.model = cfg.getAiModel(provider);
        this.endpoint = endpoint;
        this.dispatcher = new ToolDispatcher(ctx);
    }

    @Override
    public String getLastChartPath() {
        return dispatcher.getLastChartPath();
    }

    @Override
    public String getLastImagePath() {
        return dispatcher.getLastImagePath();
    }

    @Override
    public void ask(String userMessage, String imagePath, JSONArray priorMessages, Callback cb) {
        dispatcher.resetChart();
        if (apiKey == null || apiKey.isBlank()) {
            cb.onError(providerLabel() + " API key not configured — set it in Config first.");
            return;
        }
        try {
            JSONArray messages = priorMessages != null ? priorMessages : new JSONArray();
            if (messages.length() == 0) messages.put(msg("system", buildSystemPrompt()));
            messages.put(imagePath != null ? userMsgWithImage(userMessage, imagePath) : msg("user", userMessage));

            cb.onProgress("Thinking…");
            for (int round = 0; round < 12; round++) {
                JSONObject response = call(messages);
                JSONObject choice = response.getJSONArray("choices").getJSONObject(0);
                JSONObject message = choice.getJSONObject("message");

                if (message.has("tool_calls") && !message.isNull("tool_calls")) {
                    messages.put(message);
                    JSONArray toolCalls = message.getJSONArray("tool_calls");
                    for (int i = 0; i < toolCalls.length(); i++) {
                        JSONObject callObj = toolCalls.getJSONObject(i);
                        String callId = callObj.getString("id");
                        JSONObject fn = callObj.getJSONObject("function");
                        String fnName = fn.getString("name");
                        JSONObject args = new JSONObject(fn.optString("arguments", "{}"));

                        cb.onProgress(progressLabel(fnName, args));
                        String result = dispatcher.dispatch(fnName, args);

                        JSONObject toolResultMsg = new JSONObject();
                        toolResultMsg.put("role", "tool");
                        toolResultMsg.put("tool_call_id", callId);
                        toolResultMsg.put("content", result);
                        messages.put(toolResultMsg);
                    }
                    cb.onProgress("Thinking…");
                    continue;
                }

                String answer = message.optString("content", "").trim();
                cb.onResult(answer.isEmpty() ? "I couldn't find an answer." : answer);
                return;
            }
            cb.onError("Assistant took too many steps — try rephrasing your question.");
        } catch (Exception e) {
            cb.onError(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    protected abstract String providerLabel();

    // Friendly progress labels for the common tools — falls back to the raw name for anything else.
    private String progressLabel(String toolName, JSONObject args) {
        return switch (toolName) {
            case "list_tables" -> "Checking tables…";
            case "describe_table" ->
                    "Reading \"" + args.optString("table_name", "table") + "\" structure…";
            case "run_query" -> "Querying your data…";
            case "render_chart" -> "Drawing chart…";
            case "generate_image" -> "Generating image…";
            default -> "Working on \"" + toolName + "\"…";
        };
    }

    private JSONObject msg(String role, String content) throws Exception {
        JSONObject m = new JSONObject();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    // OpenAI/Grok vision format: content is an array of {type:text} and
    // {type:image_url} parts instead of a plain string.
    private JSONObject userMsgWithImage(String text, String imagePath) throws Exception {
        byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(imagePath));
        String b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
        String mime = imagePath.toLowerCase(java.util.Locale.ROOT).endsWith(".png") ? "image/png" : "image/jpeg";

        JSONArray parts = new JSONArray();
        parts.put(new JSONObject().put("type", "text").put("text", text));
        JSONObject imagePart = new JSONObject();
        imagePart.put("type", "image_url");
        imagePart.put("image_url", new JSONObject().put("url", "data:" + mime + ";base64," + b64));
        parts.put(imagePart);

        JSONObject m = new JSONObject();
        m.put("role", "user");
        m.put("content", parts);
        return m;
    }

    private JSONObject call(JSONArray messages) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", messages);
        body.put("tools", toolDefinitions());
        body.put("tool_choice", "auto");
        body.put("temperature", 0.2);

        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000); // model "thinking" + tool round trips can run long

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        } catch (java.net.SocketTimeoutException e) {
            throw new RuntimeException(providerLabel() + ": request timed out — try a shorter/simpler question.");
        }

        int status;
        try {
            status = conn.getResponseCode();
        } catch (java.net.SocketTimeoutException e) {
            throw new RuntimeException(providerLabel() + ": response timed out (model took too long) — try again.");
        }
        InputStream is = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
        String responseBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        if (status < 200 || status >= 300) {
            throw new RuntimeException(friendlyError(providerLabel(), status, responseBody));
        }
        return new JSONObject(responseBody);
    }

    // Turns the raw error JSON into one readable line instead of dumping
    // the whole payload — the common cases (bad key, quota, bad model) all
    // have a stable "error.code"/"error.type" field to key off.
    private String friendlyError(String provider, int status, String responseBody) {
        try {
            JSONObject err = new JSONObject(responseBody).optJSONObject("error");
            String code = err != null ? err.optString("code", err.optString("type", "")) : "";
            String message = err != null ? err.optString("message", "") : "";

            if (status == 401) return provider + ": invalid API key — check it in Config.";
            if (status == 404)
                return provider + ": model \"" + model + "\" not found — check the model name in Config.";
            if (status == 429 && code.contains("quota")) {
                return provider + ": quota/billing limit reached on this API key's account — check your plan & billing.";
            }
            if (status == 429) return provider + ": rate limited — try again in a moment.";
            if (!message.isEmpty()) return provider + " error (" + status + "): " + message;
        } catch (Exception ignored) {
        }
        return provider + " error (" + status + "): " + responseBody;
    }

    private JSONArray toolDefinitions() throws Exception {
        JSONArray tools = new JSONArray();
        tools.put(toolDef("list_tables", "List the database tables available to query.", new JSONObject()));

        JSONObject describeParams = new JSONObject();
        describeParams.put("type", "object");
        JSONObject describeProps = new JSONObject();
        describeProps.put("table_name", new JSONObject().put("type", "string"));
        describeParams.put("properties", describeProps);
        describeParams.put("required", new JSONArray().put("table_name"));
        tools.put(toolDef("describe_table", "Get the column names and types for a table.", describeParams));

        JSONObject queryParams = new JSONObject();
        queryParams.put("type", "object");
        JSONObject queryProps = new JSONObject();
        queryProps.put("sql", new JSONObject().put("type", "string")
                .put("description", "A single read-only SELECT statement. No semicolons, no writes."));
        queryParams.put("properties", queryProps);
        queryParams.put("required", new JSONArray().put("sql"));
        tools.put(toolDef("run_query", "Run a read-only SELECT query against the app database.", queryParams));

        JSONObject chartParams = new JSONObject();
        chartParams.put("type", "object");
        JSONObject chartProps = new JSONObject();
        chartProps.put("title", new JSONObject().put("type", "string"));
        chartProps.put("labels", new JSONObject().put("type", "array").put("items", new JSONObject().put("type", "string")));
        chartProps.put("values", new JSONObject().put("type", "array").put("items", new JSONObject().put("type", "number")));
        chartParams.put("properties", chartProps);
        chartParams.put("required", new JSONArray().put("labels").put("values"));
        tools.put(toolDef("render_chart", "Render a bar chart from labels/values and show it to the user as an image.", chartParams));

        JSONObject imageParams = new JSONObject();
        imageParams.put("type", "object");
        JSONObject imageProps = new JSONObject();
        imageProps.put("prompt", new JSONObject().put("type", "string")
                .put("description", "Description of the illustrative image to generate. Only use this when the user explicitly asks for a picture/illustration/image to be drawn — for showing real numbers/stats from their data, use render_chart instead, not this."));
        imageParams.put("properties", imageProps);
        imageParams.put("required", new JSONArray().put("prompt"));
        tools.put(toolDef("generate_image", "Generate an AI illustrative image from a text prompt (via Grok/xAI). Requires a Grok API key configured in Config.", imageParams));

        return tools;
    }

    private JSONObject toolDef(String name, String description, JSONObject parameters) throws Exception {
        JSONObject fn = new JSONObject();
        fn.put("name", name);
        fn.put("description", description);
        fn.put("parameters", parameters);
        JSONObject wrapper = new JSONObject();
        wrapper.put("type", "function");
        wrapper.put("function", fn);
        return wrapper;
    }
}