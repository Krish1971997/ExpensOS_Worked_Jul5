package com.expenseos.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Draws a simple labeled bar chart to a PNG in the app's cache dir, so the
 * assistant can answer "show me X as a chart" without any external
 * image-generation API. Called via the "render_chart" tool.
 */
public class ChartRenderer {

    public static String render(Context ctx, JSONObject args) {
        try {
            String title = args.optString("title", "Chart");
            JSONArray labels = args.getJSONArray("labels");
            JSONArray values = args.getJSONArray("values");
            int n = Math.min(labels.length(), values.length());
            if (n == 0) return errorJson("No data to chart");

            int width = 900, height = 600, padding = 80;
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            canvas.drawColor(Color.WHITE);

            Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            titlePaint.setColor(Color.BLACK);
            titlePaint.setTextSize(32);
            titlePaint.setFakeBoldText(true);
            canvas.drawText(title, padding, 48, titlePaint);

            double max = 1;
            for (int i = 0; i < n; i++) max = Math.max(max, values.getDouble(i));

            int chartTop = 80, chartBottom = height - 100;
            int chartHeight = chartBottom - chartTop;
            int barAreaWidth = width - padding * 2;
            int barWidth = barAreaWidth / n;

            Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            barPaint.setColor(Color.parseColor("#3B82F6"));
            Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            labelPaint.setColor(Color.DKGRAY);
            labelPaint.setTextSize(22);
            labelPaint.setTextAlign(Paint.Align.CENTER);
            Paint valuePaint = new Paint(labelPaint);
            valuePaint.setColor(Color.BLACK);

            for (int i = 0; i < n; i++) {
                double v = values.getDouble(i);
                int barHeight = (int) (chartHeight * (v / max));
                int left = padding + i * barWidth + barWidth / 8;
                int right = padding + (i + 1) * barWidth - barWidth / 8;
                int top = chartBottom - barHeight;
                canvas.drawRect(left, top, right, chartBottom, barPaint);

                int cx = (left + right) / 2;
                canvas.drawText(String.format("%.0f", v), cx, top - 10, valuePaint);
                canvas.drawText(truncate(labels.getString(i), 10), cx, chartBottom + 30, labelPaint);
            }

            File dir = new File(ctx.getCacheDir(), "ai_charts");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "chart_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }

            JSONObject result = new JSONObject();
            result.put("status", "chart rendered");
            result.put("chart_path", out.getAbsolutePath());
            return result.toString();
        } catch (Exception e) {
            return errorJson("Chart render failed: " + e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String errorJson(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("error", msg);
        } catch (Exception ignored) {
        }
        return o.toString();
    }
}