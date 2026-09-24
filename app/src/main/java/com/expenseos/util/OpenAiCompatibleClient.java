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
 * OpenAI's Chat Completions tool-calling format — shared by OpenAI itself,
 * xAI's Grok and Genspark (all OpenAI-compatible). One instance = one concrete
 * candidate (provider → model → key). Per-turn chart/image state is fresh.
 */
public class OpenAiCompatibleClient implements AiProvider {

    public static final String OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions";
    private static final int MAX_TOOL_RESULT_CHARS = 4000; // same fix as GeminiClient — uncapped tool output re-sent every round

    private final ToolDispatcher dispatcher;
    private final String apiKey;
    private final String model;
    private final String endpoint;
    private final String label;

    public OpenAiCompatibleClient(Context ctx, AiCandidate cand) {
        this(ctx, cand, OPENAI_ENDPOINT);
    }

    public OpenAiCompatibleClient(Context ctx, AiCandidate cand, String endpoint) {
        this.apiKey = cand.apiKey();
        this.model = cand.model();
        this.endpoint = endpoint;
        this.label = cand.providerLabel();
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
        // Legacy entry: wrap into a neutral request with the raw history as-is.
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
            throw new AiException(AiException.Kind.AUTH, label + " API key is not configured — add it in Config.");
        }
        try {
            JSONArray neutral = AiHistory.sanitize(request.history);
            JSONArray messages = AiHistory.toOpenAi(neutral);
            if (messages.length() == 0 || !messages.optJSONObject(0).optString("role", "").equals("system")) {
                // single concise system prompt, added per-request — never stored in shared history
                messages.put(msg("system", AiPrompts.systemPrompt()));
            }
            messages.put(request.imagePath != null ? userMsgWithImage(request.userMessage, request.imagePath) : msg("user", request.userMessage));

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
                        String result = capToolResult(dispatcher.dispatch(fnName, args));

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
                return answer.isEmpty() ? "I couldn't find an answer." : answer;
            }
            throw new AiException(AiException.Kind.UNKNOWN, "Assistant took too many steps — try rephrasing your question.");
        } catch (AiException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw AiErrorClassifier.fromNetwork(label, e instanceof Exception ? (Exception) e : new Exception(e), apiKey);
        } catch (Exception e) {
            throw AiErrorClassifier.fromUnexpected(label, e, apiKey);
        }
    }

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

    private String capToolResult(String result) {
        if (result == null) return "";
        if (result.length() <= MAX_TOOL_RESULT_CHARS) return result;
        return result.substring(0, MAX_TOOL_RESULT_CHARS)
                + "\n…(truncated — " + (result.length() - MAX_TOOL_RESULT_CHARS)
                + " more chars; ask a narrower question or add a LIMIT to the SQL)";
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

        if (status < 200 || status >= 300) {
            throw AiErrorClassifier.fromHttp(label, status, responseBody, apiKey);
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
        chartProps.put("chart_type", new JSONObject().put("type", "string").put("description", "bar (default) or pie"));
        chartProps.put("labels", new JSONObject().put("type", "array").put("items", new JSONObject().put("type", "string")));
        chartProps.put("values", new JSONObject().put("type", "array").put("items", new JSONObject().put("type", "number")));
        chartParams.put("properties", chartProps);
        chartParams.put("required", new JSONArray().put("labels").put("values"));
        tools.put(toolDef("render_chart", "Render a bar OR pie chart from labels/values and show it to the user as an image. Call it TWICE if the user wants both day-wise AND category-wise in one turn.", chartParams));

        JSONObject pdfParams = new JSONObject();
        pdfParams.put("type", "object");
        JSONObject pdfProps = new JSONObject();
        pdfProps.put("title", new JSONObject().put("type", "string"));
        pdfProps.put("rows", new JSONObject().put("type", "array").put("items", new JSONObject().put("type", "string")).put("description", "Array of \"YYYY-MM-DD|amount|note\" strings."));
        pdfParams.put("properties", pdfProps);
        pdfParams.put("required", new JSONArray().put("title").put("rows"));
        tools.put(toolDef("render_pdf", "Generate a PDF file from rows of \"date|amount|note\". Use this when the user asks for a PDF export of expenses.", pdfParams));

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
