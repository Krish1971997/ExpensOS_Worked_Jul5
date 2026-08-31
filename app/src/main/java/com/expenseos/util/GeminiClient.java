package com.expenseos.util;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class GeminiClient implements AiProvider {
    private static final String GEMINI_ENDPOINT_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

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

    @Override
    public String getLastChartPath() {
        return dispatcher.getLastChartPath();
    }

    @Override
    public String getLastImagePath() {
        return dispatcher.getLastImagePath();
    }

    @Override
    public void ask(String userMessage, String imagePath, JSONArray conversationHistory, Callback cb) {
        dispatcher.resetChart();
        if (apiKey == null || apiKey.isBlank()) {
            cb.onError("Gemini API key is not configured in Config.");
            return;
        }

        try {
            JSONArray contents = conversationHistory != null ? conversationHistory : new JSONArray();
            contents.put(imagePath != null ? createContentWithImage(userMessage, imagePath) : createContent("user", userMessage));

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
                cb.onResult(textResponse.isEmpty() ? "No answer received." : textResponse);
                return;
            }
            cb.onError("Assistant reached maximum tool calling steps.");
        } catch (Exception e) {
            cb.onError(e.getMessage() != null ? e.getMessage() : e.toString());
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
        JSONObject body = new JSONObject();

        // System Instruction
        JSONObject sysInstruction = new JSONObject();
        sysInstruction.put("parts", new JSONArray().put(new JSONObject().put("text", buildSystemPrompt())));
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
        chartProps.put("labels", new JSONObject().put("type", "ARRAY").put("items", new JSONObject().put("type", "STRING")));
        chartProps.put("values", new JSONObject().put("type", "ARRAY").put("items", new JSONObject().put("type", "NUMBER")));
        functionDeclarations.put(createToolDeclaration("render_chart", "Render a bar chart from labels and values, shown to the user as an image", chartProps));

        JSONObject imageProps = new JSONObject();
        imageProps.put("prompt", new JSONObject().put("type", "STRING"));
        functionDeclarations.put(createToolDeclaration("generate_image",
                "Generate an AI illustrative image from a text prompt (via Grok/xAI). Only for explicit picture/illustration requests — use render_chart for real data/stats instead.",
                imageProps));

        JSONArray toolsArray = new JSONArray();

        toolsArray.put(new JSONObject().put("functionDeclarations", functionDeclarations));
        body.put("tools", toolsArray);

        URL url = new URL(GEMINI_ENDPOINT_BASE + model + ":generateContent?key=" + apiKey);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000); // model "thinking" + tool round trips can run long

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        } catch (java.net.SocketTimeoutException e) {
            throw new RuntimeException("Gemini: request timed out — try a shorter/simpler question.");
        }

        int status;
        try {
            status = conn.getResponseCode();
        } catch (java.net.SocketTimeoutException e) {
            throw new RuntimeException("Gemini: response timed out (model took too long) — try again.");
        }
        InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
        String responseStr = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        if (status < 200 || status >= 300) {
            throw new RuntimeException(friendlyError(status, responseStr));
        }
        return new JSONObject(responseStr);
    }

    private String friendlyError(int status, String responseBody) {
        try {
            JSONObject err = new JSONObject(responseBody).optJSONObject("error");
            String apiStatus = err != null ? err.optString("status", "") : "";
            String message = err != null ? err.optString("message", "") : "";

            if (status == 400 && "API_KEY_INVALID".equals(apiStatus))
                return "Gemini: invalid API key — check it in Config.";
            if (status == 404 || "NOT_FOUND".equals(apiStatus))
                return "Gemini: model \"" + model + "\" not found — try \"gemini-2.0-flash\" in Config.";
            if (status == 429 || "RESOURCE_EXHAUSTED".equals(apiStatus))
                return "Gemini: quota limit reached on this API key — check your plan & billing.";
            if (!message.isEmpty()) return "Gemini error (" + status + "): " + message;
        } catch (Exception ignored) {
        }
        return "Gemini error (" + status + "): " + responseBody;
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