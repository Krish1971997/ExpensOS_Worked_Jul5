package com.expenseos.util;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-tool router for the in-app AI assistant. Each provider (Gemini /
 * Claude / OpenAI / Grok / Genspark) forwards tool calls here.
 *
 * Tracks a *list* of chart paths so a single AI turn can render both a
 * day-wise bar AND a category-wise pie, and ChatActivity renders both.
 */
public class ToolDispatcher {

    private final SafeQueryTools tools;
    private final Context ctx;
    private final List<String> lastChartPaths = new ArrayList<>();
    private String lastImagePath; // generate_image only ever produces one
    private String lastPdfPath;   // render_pdf produces one PDF per call

    public ToolDispatcher(Context ctx) {
        this.ctx = ctx;
        this.tools = new SafeQueryTools(ctx);
    }

    public String dispatch(String toolName, JSONObject args) throws JSONException {
        switch (toolName) {
            case "list_tables":
                return tools.listTables();
            case "describe_table":
                return tools.describeTable(args.optString("table_name"));
            case "run_query":
                return tools.runQuery(args.optString("sql"));
            case "render_chart": {
                List<String> paths = ChartRenderer.renderInternal(ctx, args);
                lastChartPaths.addAll(paths);
                JSONObject out = new JSONObject();
                if (paths.isEmpty()) {
                    out.put("status", "render failed");
                } else {
                    out.put("status", "chart rendered");
                    out.put("chart_path", paths.get(0));
                    out.put("all_paths", new JSONArray(paths));
                }
                return out.toString();
            }
            case "generate_image": {
                String r = GrokImageGenerator.generate(ctx, args.optString("prompt"));
                JSONObject parsed = new JSONObject(r);
                if (parsed.has("image_path")) lastImagePath = parsed.optString("image_path");
                return r;
            }
            case "render_pdf": {
                String title = args.optString("title", "ExpenseOS Report");
                JSONArray rows = args.optJSONArray("rows");
                if (rows == null) rows = new JSONArray();
                String path = PdfReportGenerator.generate(ctx, title, rows);
                lastPdfPath = path;
                JSONObject out = new JSONObject();
                if (path == null) {
                    out.put("error", "PDF generation failed");
                } else {
                    out.put("status", "pdf rendered");
                    out.put("pdf_path", path);
                    // also produce a cover image so the chat bubble can preview the PDF
                    String cover = PdfReportGenerator.renderCoverPng(ctx, path);
                    if (cover != null) {
                        out.put("cover_path", cover);
                        lastChartPaths.add(cover);
                    }
                }
                return out.toString();
            }
            default:
                return "{\"error\":\"unknown tool\"}";
        }
    }

    /** All charts / covers produced during the most recent ask() call, in order. */
    public List<String> getLastChartPaths() {
        return new ArrayList<>(lastChartPaths);
    }

    /** Backwards-compat single chart path (first one). */
    public String getLastChartPath() {
        return lastChartPaths.isEmpty() ? null : lastChartPaths.get(0);
    }

    public String getLastImagePath() {
        return lastImagePath;
    }

    public String getLastPdfPath() {
        return lastPdfPath;
    }

    public void resetChart() {
        lastChartPaths.clear();
        lastImagePath = null;
        lastPdfPath = null;
    }
}
