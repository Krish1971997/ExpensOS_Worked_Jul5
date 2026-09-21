package com.expenseos.util;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Builds a simple month/expense PDF from a JSON payload the AI fills in
 * via "render_pdf" tool calls. Uses android.graphics.pdf.PdfDocument —
 * NOT iText — so this is independent of the bigger report pipeline and
 * safe to call from a background thread mid-conversation.
 */
public final class PdfReportGenerator {

    private PdfReportGenerator() {
    }

    public static String generate(Context ctx, String title, JSONArray rows) {
        try {
            // Page size 595x842 = A4 at 72 DPI baseline (we scale drawings to it)
            int pageW = 595, pageH = 842;
            PdfDocument doc = new PdfDocument();
            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(pageW, pageH, 1).create();
            PdfDocument.Page page = doc.startPage(info);
            android.graphics.Canvas c = page.getCanvas();

            Paint paintTitle = new Paint(Paint.ANTI_ALIAS_FLAG);
            paintTitle.setColor(Color.parseColor("#111827"));
            paintTitle.setTextSize(20);
            paintTitle.setFakeBoldText(true);

            Paint meta = new Paint(Paint.ANTI_ALIAS_FLAG);
            meta.setColor(Color.parseColor("#6B7280"));
            meta.setTextSize(10);

            Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
            head.setColor(Color.parseColor("#FFFFFF"));
            head.setTextSize(12);
            head.setFakeBoldText(true);

            Paint row = new Paint(Paint.ANTI_ALIAS_FLAG);
            row.setColor(Color.parseColor("#111827"));
            row.setTextSize(11);

            Paint altFill = new Paint();
            altFill.setColor(Color.parseColor("#F9FAFB"));

            // 🟢 FIXED: Now checking the String parameter 'title' and passing 'paintTitle' as the Paint arg
            c.drawText(title == null || title.isEmpty() ? "ExpenseOS Report" : title, 36, 60, paintTitle);
            String now = new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.UK).format(new Date());
            c.drawText("Generated: " + now, 36, 80, meta);
            c.drawLine(36, 90, pageW - 36, 90, meta);

            // Column widths (Date / Amount / Note) for a clean 3-column PDF
            int x0 = 36;
            int xDate = x0;
            int xAmount = x0 + 160;
            int xNote = x0 + 320;
            int rowH = 22;

            c.drawRect(x0, 110, pageW - 36, 110 + rowH, head);
            head.setColor(Color.parseColor("#111827"));
            c.drawText("Date", xDate + 6, 110 + 16, head);
            c.drawText("Amount", xAmount + 6, 110 + 16, head);
            c.drawText("Note", xNote + 6, 110 + 16, head);
            head.setColor(Color.parseColor("#FFFFFF")); // restore for later use

            int y = 110 + rowH;
            int maxRowsFirstPage = 28;
            int total = rows == null ? 0 : rows.length();
            int idx = 0;
            while (idx < total) {
                if (y > pageH - 60) break; // overflow handled below if needed
                if (idx % 2 == 1) c.drawRect(x0, y, pageW - 36, y + rowH, altFill);
                String r = rows.optString(idx, "");
                String[] parts = r.split("\\|", -1);
                String date = parts.length > 0 ? parts[0] : "";
                String amt = parts.length > 1 ? parts[1] : "";
                String note = parts.length > 2 ? parts[2] : "";
                c.drawText(date, xDate + 6, y + 16, row);
                c.drawText(amt, xAmount + 6, y + 16, row);
                c.drawText(note, xNote + 6, y + 16, row);
                y += rowH;
                idx++;
            }
            // If more rows overflow, render extra page
            if (idx < total) {
                doc.finishPage(page);
                PdfDocument.PageInfo info2 = new PdfDocument.PageInfo.Builder(pageW, pageH, doc.getPages().size() + 1).create();
                PdfDocument.Page page2 = doc.startPage(info2);
                android.graphics.Canvas c2 = page2.getCanvas();
                int y2 = 60;
                // 🟢 FIXED: Updated here as well
                c2.drawText(title + " (cont.)", 36, y2, paintTitle);
                y2 += 24;
                while (idx < total) {
                    if (y2 > pageH - 60) break;
                    String r = rows.optString(idx, "");
                    String[] parts = r.split("\\|", -1);
                    c2.drawText(parts.length > 0 ? parts[0] : "", xDate + 6, y2 + 16, row);
                    c2.drawText(parts.length > 1 ? parts[1] : "", xAmount + 6, y2 + 16, row);
                    c2.drawText(parts.length > 2 ? parts[2] : "", xNote + 6, y2 + 16, row);
                    y2 += rowH;
                    idx++;
                }
                doc.finishPage(page2);
            } else {
                doc.finishPage(page);
            }

            File dir = new File(ctx.getCacheDir(), "ai_reports");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "report_" + System.currentTimeMillis() + ".pdf");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                doc.writeTo(fos);
            }
            doc.close();
            return out.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Use PdfRenderer to also paint a preview PNG so chat bubble shows the cover.
     */
    public static String renderCoverPng(Context ctx, String pdfPath) {
        if (pdfPath == null) return null;
        File f = new File(pdfPath);
        if (!f.exists()) return null;
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(pfd)) {
            if (renderer.getPageCount() == 0) return null;
            PdfRenderer.Page page = renderer.openPage(0);
            BitmapHolder b = new BitmapHolder();
            b.width = page.getWidth();
            b.height = page.getHeight();
            page.close();

            File dir = new File(ctx.getCacheDir(), "ai_pdf_pages");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "report_cover_" + System.currentTimeMillis() + ".png");
            // re-open a second time to actually render (close-then-open is fine)
            try (ParcelFileDescriptor pfd2 = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                 PdfRenderer r2 = new PdfRenderer(pfd2)) {
                PdfRenderer.Page p2 = r2.openPage(0);
                int targetW = Math.min(900, p2.getWidth() * 2);
                int targetH = (int) (p2.getHeight() * ((double) targetW / p2.getWidth()));
                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(targetW, targetH, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas c = new android.graphics.Canvas(bmp);
                c.drawColor(android.graphics.Color.WHITE);
                p2.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                p2.close();
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, fos);
                }
                return out.getAbsolutePath();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static class BitmapHolder {
        int width, height;
    }
}
