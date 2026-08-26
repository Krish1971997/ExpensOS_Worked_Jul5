package com.expenseos.util;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Minimal OpenAI Chat Completions client with function-calling, scoped to
 * SafeQueryTools only. Runs synchronously — callers must be off the main
 * thread. No dependency added: plain HttpURLConnection + org.json (already
 * used elsewhere in this codebase, e.g. ReceiptDao/RecycleBinDao).
 */
public class OpenAiClient {

    private static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";

    private static final String SYSTEM_PROMPT =
            "You are the in-app data assistant for ExpenseOS, a personal expense-tracking app. " +
                    "You can ONLY answer questions about this app's own data (transactions, categories, " +
                    "budgets, cash books, backups, schedulers, etc.) using the provided tools. " +
                    "You must NEVER attempt to modify data — you only have read tools available; there is " +
                    "no way for you to insert, update, or delete anything, so don't claim to. " +
                    "If the user asks about anything unrelated to this app's data (general knowledge, other " +
                    "apps, personal advice, coding help, etc.), politely decline and say you can only help " +
                    "with questions about their ExpenseOS data. " +
                    "Always start by calling list_tables, then describe_table on whichever tables look " +
                    "relevant, before writing a query — never guess column names. " +
                    "Keep answers concise and grounded only in what the query results actually show.";

    private final SafeQueryTools tools;
    private final String apiKey;
    private final String model;

    public OpenAiClient(Context ctx) {
        AppConfig cfg = AppConfig.get(ctx);
        this.apiKey = cfg.getOpenAiApiKey();
        this.model = cfg.getOpenAiModel();
        this.tools = new SafeQueryTools(ctx);
    }

    public interface Callback {
        void onResult(String answer);

        void onError(String message);
    }

    /**
     * Blocking — call from a background thread only.
     */
    public void ask(String userMessage, JSONArray priorMessages, Callback cb) {
        if (apiKey == null || apiKey.isBlank()) {
            cb.onError("OpenAI API key not configured — set it in Config first.");
            return;
        }
        try {
            JSONArray messages = priorMessages != null ? priorMessages : new JSONArray();
            if (messages.length() == 0) {
                messages.put(msg("system", SYSTEM_PROMPT));
            }
            messages.put(msg("user", userMessage));

            // Tool-calling loop — bounded so a confused model can't spin forever.
            for (int round = 0; round < 6; round++) {
                JSONObject response = callChatCompletions(messages);
                JSONObject choice = response.getJSONArray("choices").getJSONObject(0);
                JSONObject message = choice.getJSONObject("message");

                if (message.has("tool_calls")) {
                    messages.put(message); // assistant's tool-call request
                    JSONArray toolCalls = message.getJSONArray("tool_calls");
                    for (int i = 0; i < toolCalls.length(); i++) {
                        JSONObject call = toolCalls.getJSONObject(i);
                        String callId = call.getString("id");
                        JSONObject fn = call.getJSONObject("function");
                        String fnName = fn.getString("name");
                        JSONObject args = new JSONObject(fn.optString("arguments", "{}"));

                        String result = switch (fnName) {
                            case "list_tables" -> tools.listTables();
                            case "describe_table" ->
                                    tools.describeTable(args.optString("table_name"));
                            case "run_query" -> tools.runQuery(args.optString("sql"));
                            default -> "{\"error\":\"unknown tool\"}";
                        };

                        JSONObject toolResultMsg = new JSONObject();
                        toolResultMsg.put("role", "tool");
                        toolResultMsg.put("tool_call_id", callId);
                        toolResultMsg.put("content", result);
                        messages.put(toolResultMsg);
                    }
                    continue; // ask the model again with tool results appended
                }

                // No more tool calls — final answer.
                String answer = message.optString("content", "").trim();
                cb.onResult(answer.isEmpty() ? "I couldn't find an answer." : answer);
                return;
            }
            cb.onError("Assistant took too many steps — try rephrasing your question.");
        } catch (Exception e) {
            cb.onError(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private JSONObject msg(String role, String content) throws Exception {
        JSONObject m = new JSONObject();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private JSONObject callChatCompletions(JSONArray messages) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", messages);
        body.put("tools", toolDefinitions());
        body.put("tool_choice", "auto");
        body.put("temperature", 0.2);

        URL url = new URL(ENDPOINT);
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
        java.io.InputStream is = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
        String responseBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        if (status < 200 || status >= 300) {
            throw new RuntimeException("OpenAI error (" + status + "): " + responseBody);
        }
        return new JSONObject(responseBody);
    }

    private JSONArray toolDefinitions() throws Exception {
        JSONArray tools = new JSONArray();

        tools.put(toolDef("list_tables", "List the database tables available to query.",
                new JSONObject()));

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