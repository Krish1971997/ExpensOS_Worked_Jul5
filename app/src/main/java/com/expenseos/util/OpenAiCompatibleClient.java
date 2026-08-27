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

    private static final String SYSTEM_PROMPT =
            "You are the in-app data assistant for ExpenseOS, a personal expense-tracking app. " +
                    "You can ONLY answer questions about this app's own data (transactions, categories, " +
                    "budgets, cash books, backups, schedulers, etc.) using the provided tools. " +
                    "You must NEVER attempt to modify data — you only have read tools available. " +
                    "Always start by calling list_tables, then describe_table on relevant tables before " +
                    "writing a query — never guess column names. If the user asks to visualize or chart " +
                    "something, call render_chart with labels/values AFTER querying the data. " +
                    "Keep answers concise and grounded only in query results.";

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
    public void ask(String userMessage, String imagePath, JSONArray priorMessages, Callback cb) {
        dispatcher.resetChart();
        if (apiKey == null || apiKey.isBlank()) {
            cb.onError(providerLabel() + " API key not configured — set it in Config first.");
            return;
        }
        try {
            JSONArray messages = priorMessages != null ? priorMessages : new JSONArray();
            if (messages.length() == 0) messages.put(msg("system", SYSTEM_PROMPT));
            messages.put(imagePath != null ? userMsgWithImage(userMessage, imagePath) : msg("user", userMessage));

            for (int round = 0; round < 6; round++) {
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

                        String result = dispatcher.dispatch(fnName, args);

                        JSONObject toolResultMsg = new JSONObject();
                        toolResultMsg.put("role", "tool");
                        toolResultMsg.put("tool_call_id", callId);
                        toolResultMsg.put("content", result);
                        messages.put(toolResultMsg);
                    }
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
        conn.setReadTimeout(30000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        InputStream is = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
        String responseBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        if (status < 200 || status >= 300) {
            throw new RuntimeException(providerLabel() + " error (" + status + "): " + responseBody);
        }
        return new JSONObject(responseBody);
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