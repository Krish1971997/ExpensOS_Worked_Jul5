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
    private static final String SYSTEM_PROMPT =
            "You are the in-app data assistant for ExpenseOS, a personal expense-tracking app. " +
                    "You can ONLY answer questions about this app's own data (transactions, categories, " +
                    "budgets, cash books, backups, schedulers, etc.) using the provided tools. " +
                    "You must NEVER attempt to modify data — you only have read tools available. " +
                    "Always start by calling list_tables, then describe_table on relevant tables before writing a query. " +
                    "If the user asks to visualize or chart something, call render_chart with the labels/values " +
                    "AFTER you've queried the data. Keep answers concise and grounded only in query results.";

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
    public void ask(String userMessage, String imagePath, JSONArray conversationHistory, Callback cb) {
        dispatcher.resetChart();
        if (apiKey == null || apiKey.isBlank()) {
            cb.onError("Gemini API key is not configured in Config.");
            return;
        }

        try {
            JSONArray contents = conversationHistory != null ? conversationHistory : new JSONArray();
            contents.put(imagePath != null ? createContentWithImage(userMessage, imagePath) : createContent("user", userMessage));

            for (int round = 0; round < 6; round++) {
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
        sysInstruction.put("parts", new JSONArray().put(new JSONObject().put("text", SYSTEM_PROMPT)));
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

        JSONArray toolsArray = new JSONArray();

        toolsArray.put(new JSONObject().put("functionDeclarations", functionDeclarations));
        body.put("tools", toolsArray);

        URL url = new URL(GEMINI_ENDPOINT_BASE + model + ":generateContent?key=" + apiKey);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
        String responseStr = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        if (status < 200 || status >= 300) {
            throw new RuntimeException("Gemini error (" + status + "): " + responseStr);
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