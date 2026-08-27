package com.expenseos.util;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class ClaudeClient implements AiProvider {

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String SYSTEM_PROMPT =
            "You are the in-app data assistant for ExpenseOS, a personal expense-tracking app. " +
                    "You can ONLY answer questions about this app's own data using the provided tools. " +
                    "You must NEVER attempt to modify data — you only have read tools available. " +
                    "Always start by calling list_tables, then describe_table on relevant tables before " +
                    "writing a query. If asked to visualize/chart something, call render_chart after " +
                    "querying. Keep answers concise and grounded only in query results.";

    private final ToolDispatcher dispatcher;
    private final String apiKey;
    private final String model;

    public ClaudeClient(Context ctx) {
        AppConfig cfg = AppConfig.get(ctx);
        this.apiKey = cfg.getAiKey(AppConfig.PROVIDER_CLAUDE);
        this.model = cfg.getAiModel(AppConfig.PROVIDER_CLAUDE);
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
            cb.onError("Claude API key not configured — set it in Config first.");
            return;
        }
        try {
            JSONArray messages = priorMessages != null ? priorMessages : new JSONArray();
            messages.put(imagePath != null ? userMsgWithImage(userMessage, imagePath) : userMsg(userMessage));

            for (int round = 0; round < 6; round++) {
                JSONObject response = call(messages);
                JSONArray content = response.getJSONArray("content");
                String stopReason = response.optString("stop_reason", "");

                if ("tool_use".equals(stopReason)) {
                    // Echo the assistant's turn (including tool_use blocks) back into history.
                    JSONObject assistantMsg = new JSONObject();
                    assistantMsg.put("role", "assistant");
                    assistantMsg.put("content", content);
                    messages.put(assistantMsg);

                    JSONArray toolResults = new JSONArray();
                    for (int i = 0; i < content.length(); i++) {
                        JSONObject block = content.getJSONObject(i);
                        if (!"tool_use".equals(block.optString("type"))) continue;
                        String toolUseId = block.getString("id");
                        String fnName = block.getString("name");
                        JSONObject args = block.optJSONObject("input");
                        if (args == null) args = new JSONObject();

                        String result = dispatcher.dispatch(fnName, args);

                        JSONObject toolResultBlock = new JSONObject();
                        toolResultBlock.put("type", "tool_result");
                        toolResultBlock.put("tool_use_id", toolUseId);
                        toolResultBlock.put("content", result);
                        toolResults.put(toolResultBlock);
                    }

                    JSONObject userToolMsg = new JSONObject();
                    userToolMsg.put("role", "user");
                    userToolMsg.put("content", toolResults);
                    messages.put(userToolMsg);
                    continue;
                }

                StringBuilder text = new StringBuilder();
                for (int i = 0; i < content.length(); i++) {
                    JSONObject block = content.getJSONObject(i);
                    if ("text".equals(block.optString("type")))
                        text.append(block.optString("text"));
                }
                String answer = text.toString().trim();
                cb.onResult(answer.isEmpty() ? "I couldn't find an answer." : answer);
                return;
            }
            cb.onError("Assistant took too many steps — try rephrasing your question.");
        } catch (Exception e) {
            cb.onError(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private JSONObject userMsg(String text) throws Exception {
        JSONObject m = new JSONObject();
        m.put("role", "user");
        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "text").put("text", text));
        m.put("content", content);
        return m;
    }

    // Claude vision format: an image content block (base64 source) alongside the text block.
    private JSONObject userMsgWithImage(String text, String imagePath) throws Exception {
        byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(imagePath));
        String b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
        String mime = imagePath.toLowerCase(java.util.Locale.ROOT).endsWith(".png") ? "image/png" : "image/jpeg";

        JSONObject imageBlock = new JSONObject();
        imageBlock.put("type", "image");
        JSONObject source = new JSONObject();
        source.put("type", "base64");
        source.put("media_type", mime);
        source.put("data", b64);
        imageBlock.put("source", source);

        JSONArray content = new JSONArray();
        content.put(imageBlock);
        content.put(new JSONObject().put("type", "text").put("text", text));

        JSONObject m = new JSONObject();
        m.put("role", "user");
        m.put("content", content);
        return m;
    }

    private JSONObject call(JSONArray messages) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", 1024);
        body.put("system", SYSTEM_PROMPT);
        body.put("messages", messages);
        body.put("tools", toolDefinitions());

        URL url = new URL(ENDPOINT);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("x-api-key", apiKey);
        conn.setRequestProperty("anthropic-version", ANTHROPIC_VERSION);
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
        if (status < 200 || status >= 300)
            throw new RuntimeException("Claude error (" + status + "): " + responseBody);
        return new JSONObject(responseBody);
    }

    private JSONArray toolDefinitions() throws Exception {
        JSONArray tools = new JSONArray();
        tools.put(tool("list_tables", "List the database tables available to query.",
                new JSONObject().put("type", "object").put("properties", new JSONObject())));

        JSONObject describeSchema = new JSONObject();
        describeSchema.put("type", "object");
        describeSchema.put("properties", new JSONObject().put("table_name", new JSONObject().put("type", "string")));
        describeSchema.put("required", new JSONArray().put("table_name"));
        tools.put(tool("describe_table", "Get the column names and types for a table.", describeSchema));

        JSONObject querySchema = new JSONObject();
        querySchema.put("type", "object");
        querySchema.put("properties", new JSONObject().put("sql",
                new JSONObject().put("type", "string").put("description", "A single read-only SELECT statement.")));
        querySchema.put("required", new JSONArray().put("sql"));
        tools.put(tool("run_query", "Run a read-only SELECT query against the app database.", querySchema));

        JSONObject chartSchema = new JSONObject();
        chartSchema.put("type", "object");
        JSONObject chartProps = new JSONObject();
        chartProps.put("title", new JSONObject().put("type", "string"));
        chartProps.put("labels", new JSONObject().put("type", "array").put("items", new JSONObject().put("type", "string")));
        chartProps.put("values", new JSONObject().put("type", "array").put("items", new JSONObject().put("type", "number")));
        chartSchema.put("properties", chartProps);
        chartSchema.put("required", new JSONArray().put("labels").put("values"));
        tools.put(tool("render_chart", "Render a bar chart from labels/values, shown to the user as an image.", chartSchema));

        return tools;
    }

    private JSONObject tool(String name, String description, JSONObject inputSchema) throws Exception {
        JSONObject t = new JSONObject();
        t.put("name", name);
        t.put("description", description);
        t.put("input_schema", inputSchema);
        return t;
    }
}