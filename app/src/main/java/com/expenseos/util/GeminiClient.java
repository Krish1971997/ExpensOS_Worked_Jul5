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
import java.util.Locale;

/**
 * Google Gemini {@code generateContent} client (function calling).
 * <p>
 * One instance = one concrete candidate (provider → model → key), exactly like
 * {@link ClaudeClient} and {@link OpenAiCompatibleClient}, so the failover
 * manager can retry the same user turn on another key or model safely.
 * Per-turn chart/image state is fresh ({@link ToolDispatcher#resetChart()}).
 * <p>
 * The API key travels in the {@code x-goog-api-key} header — never in a URL —
 * so it can never leak into a log, an error message or the history.
 */
public class GeminiClient implements AiProvider {

    private static final String ENDPOINT_BASE =
            "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final int MAX_ROUNDS = 12;

    /** Last model that actually answered — handy for UI/debugging. */
    private static volatile String ACTIVE_MODEL = null;

    private final ToolDispatcher dispatcher;
    private final String apiKey;
    private final String model;

    /** Legacy single-provider construction (Config screen / AiClientFactory.create(ctx)). */
    public GeminiClient(Context ctx) {
        this(ctx, new AiCandidate(
                AppConfig.PROVIDER_GEMINI,
                AppConfig.get(ctx).getAiModel(AppConfig.PROVIDER_GEMINI),
                AppConfig.get(ctx).getAiKey(AppConfig.PROVIDER_GEMINI),
                "", 0));
    }

    /** Failover construction — one concrete (provider → model → key) candidate. */
    public GeminiClient(Context ctx, AiCandidate cand) {
        this.apiKey = cand != null ? cand.apiKey : null;
        this.model = cand != null ? cand.model : null;
        this.dispatcher = new ToolDispatcher(ctx);
    }

    public static String getActiveModel() {
        return ACTIVE_MODEL;
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

    /** Legacy entry — kept for compatibility; the chat goes through askBlocking(). */
    @Override
    public void ask(String userMessage, String imagePath, JSONArray priorMessages, Callback cb) {
        try {
            JSONArray neutral = AiHistory.sanitize(priorMessages);
            askBlocking(new AiRequest(userMessage, imagePath, neutral, userMessage), cb);
        } catch (AiException e) {
            cb.onError(e.getMessage());
        } catch (Throwable t) {
            // Defence in depth: any non-AiException must still reach the UI as an
            // error, never escape into a silently dead background thread.
            cb.onError("Gemini failed: " + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? " — " + t.getMessage() : ""));
        }
    }

    @Override
    public String askBlocking(AiRequest request, Callback cb) {
        dispatcher.resetChart();
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiException(AiException.Kind.AUTH,
                    "Gemini API key is not configured — add it in Config.");
        }
        if (model == null || model.isBlank()) {
            throw new AiException(AiException.Kind.MODEL_UNAVAILABLE,
                    "Gemini: no model selected — pick one in Config.");
        }
        try {
            JSONArray contents = toGeminiContents(AiHistory.sanitize(request.history));
            contents.put(request.imagePath != null
                    ? userContentWithImage(request.userMessage, request.imagePath)
                    : userContent(request.userMessage));

            cb.onProgress("Thinking…");
            for (int round = 0; round < MAX_ROUNDS; round++) {
                JSONObject response = call(contents);

                JSONArray candidates = response.optJSONArray("candidates");
                if (candidates == null || candidates.length() == 0) {
                    JSONObject feedback = response.optJSONObject("promptFeedback");
                    String block = feedback != null ? feedback.optString("blockReason", "") : "";
                    throw new AiException(AiException.Kind.BAD_REQUEST,
                            "Gemini declined that request"
                                    + (block.isEmpty() ? " — try rephrasing." : " (" + block + ")."));
                }

                JSONObject candidate = candidates.getJSONObject(0);
                String finish = candidate.optString("finishReason", "");
                JSONObject content = candidate.optJSONObject("content");
                JSONArray parts = content != null ? content.optJSONArray("parts") : null;

                if (parts == null || parts.length() == 0) {
                    if ("MAX_TOKENS".equals(finish))
                        throw new AiException(AiException.Kind.BAD_REQUEST,
                                "That answer was cut off — try asking something shorter.");
                    if ("SAFETY".equals(finish) || "PROHIBITED_CONTENT".equals(finish)
                            || "RECITATION".equals(finish))
                        throw new AiException(AiException.Kind.BAD_REQUEST,
                                "Gemini declined that request (" + finish + ") — try rephrasing.");
                    throw new AiException(AiException.Kind.UNKNOWN,
                            "Gemini returned an empty response"
                                    + (finish.isEmpty() ? "." : " (" + finish + ")."));
                }

                // Did the model ask to run tools?
                boolean hasToolCall = false;
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject p = parts.optJSONObject(i);
                    if (p != null && p.has("functionCall")) {
                        hasToolCall = true;
                        break;
                    }
                }

                if (hasToolCall) {
                    // Echo the model's tool-call turn back before the results.
                    JSONObject modelTurn = new JSONObject();
                    modelTurn.put("role", "model");
                    modelTurn.put("parts", parts);
                    contents.put(modelTurn);

                    JSONArray fnResponses = new JSONArray();
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject p = parts.optJSONObject(i);
                        if (p == null) continue;
                        JSONObject fnCall = p.optJSONObject("functionCall");
                        if (fnCall == null) continue;

                        String fnName = fnCall.getString("name");
                        JSONObject args = fnCall.optJSONObject("args");
                        if (args == null) args = new JSONObject();

                        cb.onProgress(progressLabel(fnName, args));
                        String result;
                        try {
                            result = dispatcher.dispatch(fnName, args);
                        } catch (Exception e) {
                            result = "ERROR: " + (e.getMessage() != null ? e.getMessage() : e.toString());
                        }

                        JSONObject fr = new JSONObject();
                        fr.put("name", fnName);
                        fr.put("response", new JSONObject().put("result", result));
                        fnResponses.put(new JSONObject().put("functionResponse", fr));
                    }

                    JSONObject toolTurn = new JSONObject();
                    toolTurn.put("role", "user");
                    toolTurn.put("parts", fnResponses);
                    contents.put(toolTurn);

                    cb.onProgress("Thinking…");
                    continue;
                }

                // Final answer — join every text part.
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject p = parts.optJSONObject(i);
                    if (p != null) sb.append(p.optString("text", ""));
                }
                String answer = sb.toString().trim();
                ACTIVE_MODEL = model;
                return answer.isEmpty() ? "I couldn't find an answer." : answer;
            }
            throw new AiException(AiException.Kind.UNKNOWN,
                    "Assistant took too many steps — try rephrasing your question.");
        } catch (AiException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw AiErrorClassifier.fromNetwork("Gemini", e, apiKey);
        } catch (Exception e) {
            throw AiErrorClassifier.fromUnexpected("Gemini", e, apiKey);
        }
    }

    private String progressLabel(String toolName, JSONObject args) {
        return switch (toolName) {
            case "list_tables" -> "Checking tables…";
            case "describe_table" ->
                    "Reading \"" + args.optString("table_name", "table") + "\" structure…";
            case "run_query" -> "Querying your data…";
            case "render_chart" -> "Drawing chart…";
            case "render_pdf" -> "Building PDF…";
            case "generate_image" -> "Generating image…";
            default -> "Working on \"" + toolName + "\"…";
        };
    }

    /** Neutral history (from AiHistory) → Gemini {@code contents} (user / model). */
    private JSONArray toGeminiContents(JSONArray neutral) throws Exception {
        JSONArray out = new JSONArray();
        if (neutral == null) return out;

        for (int i = 0; i < neutral.length(); i++) {
            JSONObject item = neutral.optJSONObject(i);
            if (item == null) continue;

            String role = item.optString("role", "user").toLowerCase(Locale.ROOT);
            boolean isModel = role.contains("model") || role.contains("assistant")
                    || role.contains("bot") || role.contains("ai");

            JSONArray parts = item.optJSONArray("parts");
            if (parts == null || parts.length() == 0) {
                String text = item.optString("content", item.optString("text", ""));
                if (text == null || text.isEmpty()) continue;
                parts = new JSONArray().put(new JSONObject().put("text", text));
            }

            String geminiRole = isModel ? "model" : "user";
            // Merge consecutive same-role turns — Gemini rejects non-alternating history.
            if (out.length() > 0) {
                JSONObject prev = out.optJSONObject(out.length() - 1);
                if (prev != null && geminiRole.equals(prev.optString("role"))) {
                    JSONArray prevParts = prev.optJSONArray("parts");
                    for (int k = 0; k < parts.length(); k++) prevParts.put(parts.get(k));
                    continue;
                }
            }

            JSONObject c = new JSONObject();
            c.put("role", geminiRole);
            c.put("parts", parts);
            out.put(c);
        }

        // History must begin with a user turn.
        while (out.length() > 0 && !"user".equals(out.getJSONObject(0).optString("role"))) {
            out.remove(0);
        }
        return out;
    }

    private JSONObject userContent(String text) throws Exception {
        JSONObject content = new JSONObject();
        content.put("role", "user");
        content.put("parts", new JSONArray().put(new JSONObject().put("text", text)));
        return content;
    }

    private JSONObject userContentWithImage(String text, String imagePath) throws Exception {
        byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(imagePath));
        String b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
        String mime = imagePath.toLowerCase(Locale.ROOT).endsWith(".png") ? "image/png" : "image/jpeg";

        JSONArray parts = new JSONArray();
        parts.put(new JSONObject().put("text", text));
        JSONObject inlineData = new JSONObject();
        inlineData.put("mime_type", mime);
        inlineData.put("data", b64);
        parts.put(new JSONObject().put("inline_data", inlineData));

        JSONObject content = new JSONObject();
        content.put("role", "user");
        content.put("parts", parts);
        return content;
    }

    private JSONObject call(JSONArray contents) throws Exception {
        JSONObject body = new JSONObject();
        JSONObject systemInstruction = new JSONObject();
        systemInstruction.put("parts",
                new JSONArray().put(new JSONObject().put("text", AiPrompts.systemPrompt())));
        body.put("systemInstruction", systemInstruction);
        body.put("contents", contents);
        body.put("tools", new JSONArray().put(
                new JSONObject().put("functionDeclarations", functionDeclarations())));

        URL url = new URL(ENDPOINT_BASE + model + ":generateContent");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-goog-api-key", apiKey); // key in a header, never in the URL
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);

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
        String responseBody = is != null ? new String(is.readAllBytes(), StandardCharsets.UTF_8) : "";

        if (status < 200 || status >= 300) {
            throw AiErrorClassifier.fromHttp("Gemini", status, responseBody, apiKey);
        }
        return new JSONObject(responseBody);
    }

    private JSONArray functionDeclarations() throws Exception {
        JSONArray declarations = new JSONArray();

        declarations.put(tool("list_tables", "List the database tables available to query.",
                new JSONObject().put("type", "OBJECT").put("properties", new JSONObject())));

        JSONObject describeProps = new JSONObject();
        describeProps.put("table_name", new JSONObject().put("type", "STRING"));
        declarations.put(tool("describe_table", "Get the column names and types for a table.",
                new JSONObject().put("type", "OBJECT").put("properties", describeProps)));

        JSONObject queryProps = new JSONObject();
        queryProps.put("sql", new JSONObject().put("type", "STRING")
                .put("description", "A single read-only SELECT statement."));
        declarations.put(tool("run_query", "Run a read-only SELECT query against the app database.",
                new JSONObject().put("type", "OBJECT").put("properties", queryProps)));

        JSONObject chartProps = new JSONObject();
        chartProps.put("title", new JSONObject().put("type", "STRING"));
        chartProps.put("chart_type", new JSONObject().put("type", "STRING")
                .put("description", "bar (default) or pie"));
        chartProps.put("labels", new JSONObject().put("type", "ARRAY")
                .put("items", new JSONObject().put("type", "STRING")));
        chartProps.put("values", new JSONObject().put("type", "ARRAY")
                .put("items", new JSONObject().put("type", "NUMBER")));
        declarations.put(tool("render_chart",
                "Render a bar or pie chart from labels/values, shown to the user as an image.",
                new JSONObject().put("type", "OBJECT").put("properties", chartProps)));

        JSONObject pdfProps = new JSONObject();
        pdfProps.put("title", new JSONObject().put("type", "STRING"));
        pdfProps.put("rows", new JSONObject().put("type", "ARRAY")
                .put("items", new JSONObject().put("type", "STRING")));
        declarations.put(tool("render_pdf",
                "Generate a PDF file from rows of \"date|amount|note\". The user gets a download link.",
                new JSONObject().put("type", "OBJECT").put("properties", pdfProps)));

        JSONObject imageProps = new JSONObject();
        imageProps.put("prompt", new JSONObject().put("type", "STRING")
                .put("description", "Description of the illustrative image to generate. "
                        + "Use render_chart instead for real data/stats."));
        declarations.put(tool("generate_image",
                "Generate an AI illustrative image from a text prompt (via Grok/xAI). "
                        + "Requires a Grok API key configured in Config.",
                new JSONObject().put("type", "OBJECT").put("properties", imageProps)));

        return declarations;
    }

    private JSONObject tool(String name, String description, JSONObject parameters) throws Exception {
        JSONObject fn = new JSONObject();
        fn.put("name", name);
        fn.put("description", description);
        fn.put("parameters", parameters);
        return fn;
    }
}
