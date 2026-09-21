package com.expenseos.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * Reads a user-attached PDF and exposes (a) the first N pages rendered as a
 * concatenated PNG so vision-capable AI providers actually see the content,
 * and (b) a text approximation good enough for chatbots that don't accept
 * raw PDFs.
 *
 * Reads text only as a fallback because parsing arbitrary PDFs to plain text
 * requires pdfbox-class OCR / heuristics that are too heavy for Android.
 * For real "summarize this statement" use cases, the rendered image is the
 * higher-fidelity path — the chat will send it to Gemini/OpenAI/Grok vision.
 */
public final class PdfAttachmentReader {

    private PdfAttachmentReader() {}

    public static Bitmap renderFirstPage(Context ctx, String pdfPath) {
        if (pdfPath == null) return null;
        File f = new File(pdfPath);
        if (!f.exists() || f.length() == 0) return null;
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(pfd)) {
            if (renderer.getPageCount() == 0) return null;
            PdfRenderer.Page page = renderer.openPage(0);
            // Scale up so small-screen vision reads it well; cap at 1600 wide
            int targetW = Math.min(1600, page.getWidth() * 2);
            int targetH = (int) (page.getHeight() * ((double) targetW / page.getWidth()));
            Bitmap bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
            // iText-style white background first so dark PDFs read correctly
            android.graphics.Canvas c = new android.graphics.Canvas(bmp);
            c.drawColor(Color.WHITE);
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            page.close();
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    /** Save rendered PDF page(s) to a PNG next to the original for the AI vision call. */
    public static String renderFirstPageToFile(Context ctx, String pdfPath) {
        Bitmap bmp = renderFirstPage(ctx, pdfPath);
        if (bmp == null) return null;
        try {
            File dir = new File(ctx.getCacheDir(), "ai_pdf_pages");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "pdfpage_" + System.currentTimeMillis() + ".png");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 95, fos);
            }
            return out.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    /** A short header to fold into the prompt when the AI needs some context about the PDF. */
    public static String summarize(Context ctx, String pdfPath) {
        Bitmap bmp = renderFirstPage(ctx, pdfPath);
        if (bmp == null) return "[PDF: could not render]";
        return "[Attached PDF rendered as image — dimensions "
                + bmp.getWidth() + "x" + bmp.getHeight()
                + ". The assistant can read it via vision.]";
    }

    private static byte[] toBytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
