package com.expenseos.util;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

public class ToolDispatcher {

    private final SafeQueryTools tools;
    private final Context ctx;
    private String lastChartPath; // set when render_chart runs; ChatActivity reads it after ask() returns
    private String lastImagePath; // set when generate_image runs

    public ToolDispatcher(Context ctx) {
        this.ctx = ctx;
        this.tools = new SafeQueryTools(ctx);
    }

    public String dispatch(String toolName, JSONObject args) throws JSONException {
        return switch (toolName) {
            case "list_tables" -> tools.listTables();
            case "describe_table" -> tools.describeTable(args.optString("table_name"));
            case "run_query" -> tools.runQuery(args.optString("sql"));
            case "render_chart" -> {
                String result = ChartRenderer.render(ctx, args);
                JSONObject parsed = new JSONObject(result);
                if (parsed.has("chart_path")) lastChartPath = parsed.optString("chart_path");
                yield result;
            }
            case "generate_image" -> {
                String result = GrokImageGenerator.generate(ctx, args.optString("prompt"));
                JSONObject parsed = new JSONObject(result);
                if (parsed.has("image_path")) lastImagePath = parsed.optString("image_path");
                yield result;
            }
            default -> "{\"error\":\"unknown tool\"}";
        };
    }

    /**
     * Non-null only if render_chart was called during the most recent ask().
     */
    public String getLastChartPath() {
        return lastChartPath;
    }

    /**
     * Non-null only if generate_image was called during the most recent ask().
     */
    public String getLastImagePath() {
        return lastImagePath;
    }

    public void resetChart() {
        lastChartPath = null;
        lastImagePath = null;
    }
}