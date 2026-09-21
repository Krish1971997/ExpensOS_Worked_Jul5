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
 * Google Gemini chat client (function calling). One instance = one concrete
 * candidate (provider → model → key); per-turn state (charts/images) is fresh
 * so failover attempts never leak partial results between keys.
 */
public class GeminiClient implements AiProvider {

    private static final String[] FALLBACK_MODELS = {"gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash"};
    private static final String GEMINI_ENDPOINT_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final ToolDispatcher dispatcher;
    private final String apiKey;
    private final String model;

    public GeminiClient(Context ctx, AiCandidate cand) {
        this.apiKey = cand.apiKey;
        this.model = cand.model;
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
    public void ask(String userMessage, String imagePath, JSONArray conversationHistory, Callback cb) {
        // Legacy entry: wrap into a neutral request with the raw history as-is.
        try {
            JSONArray neutral = AiHistory.sanitize(conversationHistory);
            askBlocking(new AiRequest(userMessage, imagePath, neutral, userMessage), cb);
        } catch (AiException e) {
            cb.onError(e.getMessage());
        }
    }

    @Override
    public String askBlocking(AiRequest request, Callback cb) {
        dispatcher.resetChart();
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiException(AiException.Kind.AUTH, "Gemini: API key is not configured.");
        }

        try {
            JSONArray neutral = AiHistory.sanitize(request.history);
            JSONArray contents = AiHistory.toGemini(neutral);
            contents.put(request.imagePath != null
                    ? createContentWithImage(request.userMessage, request.imagePath)
                    : createContent("user", request.userMessage));

            cb.onProgress("Thinking…");
            for (int round = 0; round < 12; round++) {
                JSONObject response = callGeminiApi(contents);
                JSONObject candidate = response.getJSONArray("candidates").getJSONObject(0);
                JSONObject content = candidate.getJSONObject("content");
                JSONArray parts = content.getJSONArray("parts");

                JSONObject firstPart = parts.getJSONObject(0);

                // Handle Function Call from Gemini
                if (firstPart.has("functionCall")) {
                    contents.put(content); // Add model response to history

                    JSONObject fnCall = firstPart.getJSONObject("functionCall");
                    String fnName = fnCall.getString("name");
                    JSONObject args = fnCall.optJSONObject("args");
                    if (args == null) args = new JSONObject();

                    cb.onProgress(progressLabel(fnName, args));
                    String toolResult = dispatcher.dispatch(fnName, args);

                    // Send Tool response back to Gemini
                    JSONObject responsePart = new JSONObject();
                    JSONObject functionResponse = new JSONObject();
                    functionResponse.put("name", fnName);
                    functionResponse.put("response", new JSONObject().put("result", toolResult));
                    responsePart.put("functionResponse", functionResponse);

                    JSONObject toolResponseContent = new JSONObject();
                    toolResponseContent.put("role", "user");
                    toolResponseContent.put("parts", new JSONArray().put(responsePart));
                    contents.put(toolResponseContent);

                    cb.onProgress("Thinking…");
                    continue;
                }

                // Final Answer
                String textResponse = firstPart.optString("text", "").trim();
                return textResponse.isEmpty() ? "No answer received." : textResponse;
            }
            throw new AiException(AiException.Kind.UNKNOWN, "Assistant reached maximum tool calling steps.");
        } catch (AiException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw AiErrorClassifier.fromNetwork("Gemini", e instanceof Exception ? (Exception) e : new Exception(e), apiKey);
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

    // Gemini vision format: an inline_data part alongside the text part.
    private JSONObject createContentWithImage(String text, String imagePath) throws Exception {
        byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(imagePath));
        String b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
        String mime = imagePath.toLowerCase(java.util.Locale.ROOT).endsWith(".png") ? "image/png" : "image/jpeg";

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

    private JSONObject callGeminiApi(JSONArray contents) throws Exception {
        // Auto-heal a stale/renamed model id: retry the request against known-good models.
        try {
            return callGeminiApiOnce(contents, model);
        } catch (AiException first) {
            if (first.kind == AiException.Kind.MODEL_UNAVAILABLE) {
                for (String alt : FALLBACK_MODELS) {
                    if (alt.equals(model)) continue;
                    try {
                        return callGeminiApiOnce(contents, alt);
                    } catch (AiException ignored) {
                    }
                }
            }
            throw first;
        }
    }

    private JSONObject callGeminiApiOnce(JSONArray contents, String useModel) throws Exception {
        JSONObject body = new JSONObject();

        // System Instruction
        JSONObject sysInstruction = new JSONObject();
        sysInstruction.put("parts", new JSONArray().put(new JSONObject().put("text", AiPrompts.systemPrompt())));
        body.put("systemInstruction", sysInstruction);
        body.put("contents", contents);

        // Define Tools for Gemini
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
        chartProps.put("chart_type", new JSONObject().put("type", "STRING"));
        chartProps.put("labels", new JSONObject().put("type", "ARRAY").put("items", new JSONObject().put("type", "STRING")));
        chartProps.put("values", new JSONObject().put("type", "ARRAY").put("items", new JSONObject().put("type", "NUMBER")));
        functionDeclarations.put(createToolDeclaration("render_chart", "Render a bar OR pie chart from labels and values, shown to the user as an image. Call TWICE for \"day-wise and category-wise\" requests.", chartProps));

        JSONObject pdfProps = new JSONObject();
        pdfProps.put("title", new JSONObject().put("type", "STRING"));
        pdfProps.put("rows", new JSONObject().put("type", "ARRAY").put("items", new JSONObject().put("type", "STRING")));
        functionDeclarations.put(createToolDeclaration("render_pdf", "Generate a PDF file from rows of \"date|amount|note\" strings — for export / monthly report requests.", pdfProps));

        JSONObject imageProps = new JSONObject();
        imageProps.put("prompt", new JSONObject().put("type", "STRING"));
        functionDeclarations.put(createToolDeclaration("generate_image",
                "Generate an AI illustrative image from a text prompt (via Grok/xAI). Only for explicit picture/illustration requests — use render_chart for real data/stats instead.",
                imageProps));

        JSONArray toolsArray = new JSONArray();

        toolsArray.put(new JSONObject().put("functionDeclarations", functionDeclarations));
        body.put("tools", toolsArray);

        URL url = new URL(GEMINI_ENDPOINT_BASE + useModel + ":generateContent?key=" + apiKey);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
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
        InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
        String responseStr = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        if (status < 200 || status >= 300) {
            throw AiErrorClassifier.fromHttp("Gemini", status, responseStr, apiKey);
        }
        return new JSONObject(responseStr);
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
}
