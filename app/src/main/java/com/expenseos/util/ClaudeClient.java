package com.expenseos.util;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Anthropic Claude Messages API client (tool use). One instance = one concrete
 * candidate (provider → model → key). Per-turn chart/image state is fresh.
 */
public class ClaudeClient implements AiProvider {

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int MAX_TOOL_RESULT_CHARS = 4000; // same fix as GeminiClient

    private final ToolDispatcher dispatcher;
    private final String apiKey;
    private final String model;

    public ClaudeClient(Context ctx, AiCandidate cand) {
        this.apiKey = cand.apiKey();
        this.model = cand.model();
        this.dispatcher = new ToolDispatcher(ctx);
    }

    @Override
    public String getLastChartPath() {
        return dispatcher.getLastChartPath();
    }

    @Override
    public List<String> getLastChartPaths() {
        return dispatcher.getLastChartPaths();
    }

    @Override
    public String getLastImagePath() {
        return dispatcher.getLastImagePath();
    }

    @Override
    public void ask(String userMessage, String imagePath, JSONArray priorMessages, Callback cb) {
        try {
            JSONArray neutral = AiHistory.sanitize(priorMessages);
            askBlocking(new AiRequest(userMessage, imagePath, neutral, userMessage), cb);
        } catch (AiException e) {
            cb.onError(e.getMessage());
        }
    }

    @Override
    public String askBlocking(AiRequest request, Callback cb) {
        dispatcher.resetChart();
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiException(AiException.Kind.AUTH, "Claude API key is not configured — add it in Config.");
        }
        try {
            JSONArray neutral = AiHistory.sanitize(request.history);
            JSONArray messages = AiHistory.toClaude(neutral);
            messages.put(request.imagePath != null ? userMsgWithImage(request.userMessage, request.imagePath) : userMsg(request.userMessage));

            cb.onProgress("Thinking…");
            for (int round = 0; round < 12; round++) {
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

                        cb.onProgress(progressLabel(fnName, args));
                        String result = capToolResult(dispatcher.dispatch(fnName, args));

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
                    cb.onProgress("Thinking…");
                    continue;
                }

                // Final answer — collect all text blocks.
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < content.length(); i++) {
                    JSONObject block = content.getJSONObject(i);
                    if ("text".equals(block.optString("type")))
                        sb.append(block.optString("text", ""));
                }
                String answer = sb.toString().trim();
                return answer.isEmpty() ? "I couldn't find an answer." : answer;
            }
            throw new AiException(AiException.Kind.UNKNOWN, "Assistant took too many steps — try rephrasing your question.");
        } catch (AiException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw AiErrorClassifier.fromNetwork("Claude", e instanceof Exception ? (Exception) e : new Exception(e), apiKey);
        } catch (Exception e) {
            throw AiErrorClassifier.fromUnexpected("Claude", e, apiKey);
        }
    }

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

    private String capToolResult(String result) {
        if (result == null) return "";
        if (result.length() <= MAX_TOOL_RESULT_CHARS) return result;
        return result.substring(0, MAX_TOOL_RESULT_CHARS)
                + "\n…(truncated — " + (result.length() - MAX_TOOL_RESULT_CHARS)
                + " more chars; ask a narrower question or add a LIMIT to the SQL)";
    }

    private JSONObject userMsg(String text) throws Exception {
        JSONObject m = new JSONObject();
        m.put("role", "user");
        m.put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", text)));
        return m;
    }

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
        body.put("system", AiPrompts.systemPrompt());
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
        conn.setReadTimeout(60000); // model "thinking" + tool round trips can run long

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        } catch (java.net.SocketTimeoutException e) {
            throw new java.io.IOException("timeout");
        }

        int status;
        try {
            status = conn.getResponseCode();
        } catch (java.net.SocketTimeoutException e) {
            throw new java.io.IOException("timeout");
        }
        InputStream is = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
        String responseBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        if (status < 200 || status >= 300)
            throw AiErrorClassifier.fromHttp("Claude", status, responseBody, apiKey);
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
        chartProps.put("chart_type", new JSONObject().put("type", "string").put("description", "bar (default) or pie"));
        chartProps.put("labels", new JSONObject().put("type", "array").put("items", new JSONObject().put("type", "string")));
        chartProps.put("values", new JSONObject().put("type", "array").put("items", new JSONObject().put("type", "number")));
        chartSchema.put("properties", chartProps);
        chartSchema.put("required", new JSONArray().put("labels").put("values"));
        tools.put(tool("render_chart", "Render a bar or pie chart from labels/values, shown to the user as an image.", chartSchema));

        // PDF tool schema
        JSONObject pdfSchema = new JSONObject();
        pdfSchema.put("type", "object");
        JSONObject pdfProps = new JSONObject();
        pdfProps.put("title", new JSONObject().put("type", "string"));
        pdfProps.put("rows", new JSONObject().put("type", "array").put("items", new JSONObject().put("type", "string")));
        pdfSchema.put("properties", pdfProps);
        pdfSchema.put("required", new JSONArray().put("title").put("rows"));
        tools.put(tool("render_pdf", "Generate a PDF file from rows of \"date|amount|note\". The user gets a download link.", pdfSchema));

        JSONObject imageSchema = new JSONObject();
        imageSchema.put("type", "object");
        imageSchema.put("properties", new JSONObject().put("prompt",
                new JSONObject().put("type", "string").put("description",
                        "Description of the illustrative image to generate. Use render_chart instead for real data/stats.")));
        imageSchema.put("required", new JSONArray().put("prompt"));
        tools.put(tool("generate_image", "Generate an AI illustrative image from a text prompt (via Grok/xAI). Requires a Grok API key configured in Config.", imageSchema));

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
