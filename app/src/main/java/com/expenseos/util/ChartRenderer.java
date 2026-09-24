package com.expenseos.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders bar OR pie chart to PNG in app cache dir.
 * - Multi-color palette (every bar / slice gets a distinct color)
 * - Optional chart_type arg: "bar" (default) or "pie"
 * - drawBothWhenAsked: the AI prompt instructs it to call render_chart twice
 * (day-wise + category-wise) on requests like "day wise and category wise",
 * so dispatcher returns a list of paths instead of a single one.
 */
public class ChartRenderer {

    private static final String[] PALETTE = {
            "#3B82F6", "#EF4444", "#F59E0B", "#10B981", "#8B5CF6",
            "#EC4899", "#14B8A6", "#F97316", "#6366F1", "#06B6D4",
            "#84CC16", "#A855F7"
    };

    /**
     * Backward-compatible single-chart entry. Returns one path (JSON).
     */
    public static List<String> renderAndCollect(Context ctx, JSONObject args) {
        return renderInternal(ctx, args);
    }

    /**
     * Same path, exposed under the old call sites that expect a single String.
     */
    public static String render(Context ctx, JSONObject args) {
        List<String> paths = renderInternal(ctx, args);
        if (paths.isEmpty()) return "{\"error\":\"chart render produced no file\"}";
        return "{\"status\":\"chart rendered\",\"chart_path\":\"" + paths.get(0) + "\"}";
    }

    /**
     * Renders every requested chart and returns their paths in order.
     */
    public static List<String> renderInternal(Context ctx, JSONObject args) {
        List<String> out = new ArrayList<>();
        try {
            String title = args.optString("title", "Chart");
            String type = args.optString("chart_type", "bar").toLowerCase(Locale_);

            JSONArray labels = args.getJSONArray("labels");
            JSONArray values = args.getJSONArray("values");
            int n = Math.min(labels.length(), values.length());
            if (n == 0) return out;

            int width = 900, height = 600, padding = 80;

            if ("pie".equals(type)) {
                File f = renderPie(ctx, title, labels, values, n, width, height, padding);
                if (f != null) out.add(f.getAbsolutePath());
            } else {
                File f = renderBar(ctx, title, labels, values, n, width, height, padding);
                if (f != null) out.add(f.getAbsolutePath());
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static final java.util.Locale Locale_ = java.util.Locale.US;

    private static File renderBar(Context ctx, String title, JSONArray labels, JSONArray values,
                                  int n, int width, int height, int padding) throws Exception {
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.WHITE);

        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(Color.BLACK);
        titlePaint.setTextSize(30);
        titlePaint.setFakeBoldText(true);
        canvas.drawText(title, padding, 44, titlePaint);

        double max = 1;
        for (int i = 0; i < n; i++) max = Math.max(max, values.getDouble(i));
        // Round max up so the tallest bar never touches the top of the canvas
        max = niceCeil(max);

        int chartTop = 80, chartBottom = height - 100;
        int chartHeight = chartBottom - chartTop;
        int barAreaWidth = width - padding * 2;
        int barWidth = barAreaWidth / n;

        Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        valuePaint.setColor(Color.parseColor("#111827"));
        valuePaint.setTextSize(22);
        valuePaint.setFakeBoldText(true);
        valuePaint.setTextAlign(Paint.Align.CENTER);

        Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(Color.parseColor("#374151"));
        labelPaint.setTextSize(22);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        Paint bgBar = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgBar.setColor(Color.parseColor("#F3F4F6"));

        Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
        axis.setColor(Color.parseColor("#D1D5DB"));
        axis.setStrokeWidth(1f);

        // Baseline
        canvas.drawLine(padding, chartBottom, width - padding, chartBottom, axis);

        for (int i = 0; i < n; i++) {
            double v = values.getDouble(i);
            int barHeight = (int) (chartHeight * (v / max));
            int left = padding + i * barWidth + barWidth / 8;
            int right = padding + (i + 1) * barWidth - barWidth / 8;
            int top = chartBottom - barHeight;

            // Background track behind every bar — makes short bars still visible
            canvas.drawRect(left, chartTop, right, chartBottom, bgBar);

            Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            barPaint.setColor(Color.parseColor(paletteFor(i)));
            canvas.drawRect(left, top, right, chartBottom, barPaint);

            int cx = (left + right) / 2;
            canvas.drawText(String.format(java.util.Locale.ROOT, "%.0f", v), cx, Math.max(top - 10, chartTop + 22), valuePaint);
            canvas.drawText(truncate(labels.getString(i), 12), cx, chartBottom + 30, labelPaint);
        }

        return saveBitmap(ctx, bmp);
    }

    private static File renderPie(Context ctx, String title, JSONArray labels, JSONArray values,
                                  int n, int width, int height, int padding) throws Exception {
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.WHITE);

        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(Color.BLACK);
        titlePaint.setTextSize(30);
        titlePaint.setFakeBoldText(true);
        canvas.drawText(title, padding, 44, titlePaint);

        double total = 0;
        for (int i = 0; i < n; i++) total += values.getDouble(i);
        if (total <= 0) total = 1;

        int pieLeft = padding;
        int pieTop = 90;
        int pieSize = Math.min(width - padding * 2 - 280, height - pieTop - 60);
        if (pieSize < 100) pieSize = 300;
        RectF pieRect = new RectF(pieLeft, pieTop, pieLeft + pieSize, pieTop + pieSize);

        float startAngle = -90f;
        Paint slicePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint labelOn = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelOn.setColor(Color.WHITE);
        labelOn.setTextSize(20);
        labelOn.setFakeBoldText(true);
        labelOn.setTextAlign(Paint.Align.CENTER);

        Paint legendText = new Paint(Paint.ANTI_ALIAS_FLAG);
        legendText.setColor(Color.parseColor("#111827"));
        legendText.setTextSize(20);

        Paint legendDot = new Paint(Paint.ANTI_ALIAS_FLAG);

        for (int i = 0; i < n; i++) {
            double v = values.getDouble(i);
            float sweep = (float) (v / total * 360.0);
            slicePaint.setColor(Color.parseColor(paletteFor(i)));
            canvas.drawArc(pieRect, startAngle, sweep, true, slicePaint);

            // Slice label (percent) for slices >= 6%
            float mid = (float) Math.toRadians(startAngle + sweep / 2);
            if (sweep >= 6f) {
                float lx = (float) (pieRect.centerX() + (pieSize * 0.32f) * Math.cos(mid));
                float ly = (float) (pieRect.centerY() + (pieSize * 0.32f) * Math.sin(mid));
                canvas.drawText(
                        String.format(java.util.Locale.ROOT, "%.0f%%", v / total * 100),
                        lx, ly + 6, labelOn);
            }
            startAngle += sweep;
        }

        // Legend on right
        int legendX = (int) pieRect.right + 40;
        int legendY = pieTop + 10;
        for (int i = 0; i < n; i++) {
            legendDot.setColor(Color.parseColor(paletteFor(i)));
            canvas.drawCircle(legendX + 10, legendY + 12, 10, legendDot);
            double v = values.getDouble(i);
            String legend = truncate(labels.getString(i), 18)
                    + " — " + String.format(java.util.Locale.ROOT, "%.0f (%.0f%%)", v, v / total * 100);
            canvas.drawText(legend, legendX + 32, legendY + 18, legendText);
            legendY += 30;
        }
        return saveBitmap(ctx, bmp);
    }

    private static File saveBitmap(Context ctx, Bitmap bmp) throws Exception {
        // filesDir (not cacheDir) — chart paths are persisted in chat history
        // (ChatMessage.chartPath), so a "Clear cache" action must never delete
        // a file an old message still points to.
        File dir = new File(ctx.getFilesDir(), "ai_charts");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, "chart_" + System.currentTimeMillis() + ".png");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        return out;
    }

    private static String paletteFor(int idx) {
        return PALETTE[idx % PALETTE.length];
    }

    private static double niceCeil(double v) {
        if (v <= 0) return 1;
        double pow = Math.pow(10, Math.floor(Math.log10(v)));
        double norm = v / pow;
        double rounded;
        if (norm <= 1) rounded = 1;
        else if (norm <= 2) rounded = 2;
        else if (norm <= 5) rounded = 5;
        else rounded = 10;
        return rounded * pow;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}