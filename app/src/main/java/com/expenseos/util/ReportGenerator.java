package com.expenseos.util;

// imports — OLD

import com.expenseos.model.Transaction;
import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Document;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.pdf.BaseFont;
import com.itextpdf.text.pdf.PdfContentByte;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfPageEventHelper;
import com.itextpdf.text.pdf.PdfTemplate;
import com.itextpdf.text.pdf.PdfWriter;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReportGenerator {

    public static final String TYPE_ALL = "all";
    public static final String TYPE_DAYWISE = "daywise";
    public static final String TYPE_CATEGORYWISE = "categorywise";
    public static final String TYPE_SUBCATEGORYWISE = "subcategorywise";
    public static final String TYPE_PAYMENTTYPEWISE = "paymenttypewise";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("hh:mm a");

    // ── CSV ──────────────────────────────────────────────────
    public static void writeCsv(List<Transaction> txns, String reportType, OutputStream out) throws Exception {
        Writer w = new OutputStreamWriter(out);
        switch (reportType) {
            case TYPE_DAYWISE -> writeDaywiseCsv(txns, w);
            case TYPE_CATEGORYWISE -> writeCategorywiseCsv(txns, w);
            case TYPE_SUBCATEGORYWISE -> writeSubcategorywiseCsv(txns, w);
            case TYPE_PAYMENTTYPEWISE -> writePaymentTypewiseCsv(txns, w); // 👈 Added
            default -> writeAllEntriesCsv(txns, w);
        }
        w.flush();
    }

    private static void writeAllEntriesCsv(List<Transaction> txns, Writer w) throws Exception {
        w.write("Date,Time,Type,Category,Sub Category,Payment Type,Amount,Note\n");
        for (Transaction t : txns) {
            w.write(csvEscape(t.getDateTime() != null ? t.getDateTime().format(DATE_FMT) : "") + ",");
            w.write(csvEscape(t.getDateTime() != null ? t.getDateTime().format(TIME_FMT) : "") + ",");
            w.write(csvEscape(t.getType().name()) + ",");
            w.write(csvEscape(t.getCategoryName()) + ",");
            w.write(csvEscape(t.getSubCategoryName()) + ",");
            w.write(csvEscape(t.getPaymentTypeName()) + ",");
            w.write(csvEscape(t.getAmount() != null ? t.getAmount().toPlainString() : "0") + ",");
            w.write(csvEscape(t.getNote()) + "\n");
        }
    }

    private static void writeDaywiseCsv(List<Transaction> txns, Writer w) throws Exception {
        w.write("Date,Total In,Total Out,Balance\n");
        BigDecimal running = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal[]> e : dayTotals(txns).entrySet()) {
            BigDecimal in = e.getValue()[0], out = e.getValue()[1];
            running = running.add(in).subtract(out);
            w.write(csvEscape(e.getKey()) + "," + in.toPlainString() + "," + out.toPlainString() + "," + running.toPlainString() + "\n");
        }
    }

    private static void writeCategorywiseCsv(List<Transaction> txns, Writer w) throws Exception {
        w.write("Category,Total Income,Total Expense,Net\n");
        for (Map.Entry<String, BigDecimal[]> e : categoryTotals(txns).entrySet()) {
            BigDecimal in = e.getValue()[0], out = e.getValue()[1];
            w.write(csvEscape(e.getKey()) + "," + in.toPlainString() + "," + out.toPlainString() + "," + in.subtract(out).toPlainString() + "\n");
        }
    }

    private static void writeSubcategorywiseCsv(List<Transaction> txns, Writer w) throws Exception {
        w.write("Sub Category,Total Income,Total Expense,Net\n");
        for (Map.Entry<String, BigDecimal[]> e : subCategoryTotals(txns).entrySet()) {
            BigDecimal in = e.getValue()[0], out = e.getValue()[1];
            w.write(csvEscape(e.getKey()) + "," + in.toPlainString() + "," + out.toPlainString() + "," + in.subtract(out).toPlainString() + "\n");
        }
    }

    // 👈 Payment Type-wise CSV logic added
    private static void writePaymentTypewiseCsv(List<Transaction> txns, Writer w) throws Exception {
        w.write("Payment Type,Total Income,Total Expense,Net\n");
        for (Map.Entry<String, BigDecimal[]> e : paymentTypeTotals(txns).entrySet()) {
            BigDecimal in = e.getValue()[0], out = e.getValue()[1];
            w.write(csvEscape(e.getKey()) + "," + in.toPlainString() + "," + out.toPlainString() + "," + in.subtract(out).toPlainString() + "\n");
        }
    }

    private static String csvEscape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n"))
            return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    // ── PDF (iText 5) ────────────────────────────────────────
    // Backward-compat overload — no cashbook name / custom title.
    public static void writePdf(List<Transaction> txns, String reportType, OutputStream out) throws Exception {
        writePdf(txns, reportType, null, null, out);
    }

    /**
     * @param cashbookName printed under the title as "Cashbook: <name>" —
     *                     pass null/blank to omit.
     * @param customTitle  overrides the default report title (e.g. "August
     *                     2026 Expense Report") — pass null/blank to use the
     *                     default title for the report type.
     */
    public static void writePdf(List<Transaction> txns, String reportType, String cashbookName,
                                String customTitle, OutputStream out) throws Exception {
        Document doc = new Document(PageSize.A4, 24, 24, 32, 40); // extra bottom margin for the footer
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        writer.setPageEvent(new FooterEvent());
        doc.open();

        Font titleFont = new Font(Font.FontFamily.HELVETICA, 16, Font.BOLD);
        Font metaFont = new Font(Font.FontFamily.HELVETICA, 9, Font.NORMAL, BaseColor.GRAY);
        Font headFont = new Font(Font.FontFamily.HELVETICA, 10, Font.BOLD, BaseColor.WHITE);
        Font cellFont = new Font(Font.FontFamily.HELVETICA, 9, Font.NORMAL);

        String titleText = (customTitle != null && !customTitle.trim().isEmpty())
                ? customTitle.trim() : reportTitle(reportType);
        Paragraph title = new Paragraph(titleText, titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(4);
        doc.add(title);

        if (cashbookName != null && !cashbookName.trim().isEmpty()) {
            Paragraph cb = new Paragraph("Cashbook: " + cashbookName.trim(), metaFont);
            cb.setAlignment(Element.ALIGN_CENTER);
            cb.setSpacingAfter(2);
            doc.add(cb);
        }

        String generatedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a"));
        Paragraph gen = new Paragraph("Generated: " + generatedAt, metaFont);
        gen.setAlignment(Element.ALIGN_CENTER);
        gen.setSpacingAfter(16);
        doc.add(gen);

        switch (reportType) {
            case TYPE_DAYWISE -> addDaywiseTable(doc, txns, headFont, cellFont);
            case TYPE_CATEGORYWISE -> addCategorywiseTable(doc, txns, headFont, cellFont);
            case TYPE_SUBCATEGORYWISE -> addSubcategorywiseTable(doc, txns, headFont, cellFont);
            case TYPE_PAYMENTTYPEWISE ->
                    addPaymentTypewiseTable(doc, txns, headFont, cellFont); // 👈 Added
            default -> addAllEntriesTable(doc, txns, headFont, cellFont);
        }

        doc.close();
    }

    // Draws "Page X of Y" (bottom-right) and "Powered by ExpenseOS"
    // (bottom-center) on every page. The "of Y" part needs the total page
    // count, which iText only knows once the whole document is closed — so
    // a blank PdfTemplate placeholder is reserved on each page and filled
    // in with the real total at onCloseDocument(), the standard iText 5
    // trick for this.
    private static class FooterEvent extends PdfPageEventHelper {
        private PdfTemplate totalPagesTemplate;
        private BaseFont baseFont;
        private static final float FONT_SIZE = 8f;

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            try {
                baseFont = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
            } catch (Exception ignored) {
            }
            totalPagesTemplate = writer.getDirectContent().createTemplate(30, 16);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            if (baseFont == null) return;
            PdfContentByte cb = writer.getDirectContent();
            float y = document.bottom() - 20;
            cb.saveState();
            cb.setColorFill(BaseColor.GRAY);

            // "Page X of " + [template with total], right-aligned
            String pageLabel = "Page " + writer.getPageNumber() + " of ";
            float pageLabelWidth = baseFont.getWidthPoint(pageLabel, FONT_SIZE);
            cb.beginText();
            cb.setFontAndSize(baseFont, FONT_SIZE);
            cb.setTextMatrix(document.right() - pageLabelWidth - 30, y);
            cb.showText(pageLabel);
            cb.endText();
            cb.addTemplate(totalPagesTemplate, document.right() - 30, y);

            // "Powered by ExpenseOS", centered
            String powered = "Powered by ExpenseOS";
            float poweredWidth = baseFont.getWidthPoint(powered, FONT_SIZE);
            cb.beginText();
            cb.setFontAndSize(baseFont, FONT_SIZE);
            cb.setTextMatrix((document.left() + document.right() - poweredWidth) / 2, y);
            cb.showText(powered);
            cb.endText();

            cb.restoreState();
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            if (baseFont == null) return;
            totalPagesTemplate.beginText();
            totalPagesTemplate.setFontAndSize(baseFont, FONT_SIZE);
            totalPagesTemplate.setColorFill(BaseColor.GRAY);
            totalPagesTemplate.setTextMatrix(0, 0);
            totalPagesTemplate.showText(String.valueOf(writer.getPageNumber()));
            totalPagesTemplate.endText();
        }
    }

    private static String reportTitle(String type) {
        return switch (type) {
            case TYPE_DAYWISE -> "Day-wise Summary";
            case TYPE_CATEGORYWISE -> "Category-wise Summary";
            case TYPE_SUBCATEGORYWISE -> "Sub Category-wise Summary";
            case TYPE_PAYMENTTYPEWISE -> "Payment Type-wise Summary"; // 👈 Added
            default -> "All Entries Report";
        };
    }

    private static void addAllEntriesTable(Document doc, List<Transaction> txns, Font headFont, Font cellFont) throws Exception {
        PdfPTable table = new PdfPTable(new float[]{2f, 1.2f, 1.5f, 1.5f, 1.5f, 1.3f, 2f});
        table.setWidthPercentage(100);
        for (String h : new String[]{"Date", "Type", "Category", "Sub Cat", "Payment Type", "Amount", "Note"})
            addHeaderCell(table, h, headFont);

        for (Transaction t : txns) {
            String date = (t.getDateTime() != null ? t.getDateTime().format(DATE_FMT) + " " + t.getDateTime().format(TIME_FMT) : "");
            addCell(table, date, cellFont);
            addCell(table, t.getType().name(), cellFont);
            addCell(table, t.getCategoryName() != null ? t.getCategoryName() : "", cellFont);
            addCell(table, t.getSubCategoryName() != null ? t.getSubCategoryName() : "", cellFont);
            addCell(table, t.getPaymentTypeName() != null ? t.getPaymentTypeName() : "", cellFont);
            addCell(table, t.getAmount() != null ? t.getAmount().toPlainString() : "0", cellFont);
            addCell(table, t.getNote() != null ? t.getNote() : "", cellFont);
        }
        doc.add(table);
    }

    private static void addDaywiseTable(Document doc, List<Transaction> txns, Font headFont, Font cellFont) throws Exception {
        PdfPTable table = new PdfPTable(new float[]{2f, 1.5f, 1.5f, 1.5f});
        table.setWidthPercentage(100);
        for (String h : new String[]{"Date", "Total In", "Total Out", "Balance"})
            addHeaderCell(table, h, headFont);

        BigDecimal running = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal[]> e : dayTotals(txns).entrySet()) {
            BigDecimal in = e.getValue()[0], out = e.getValue()[1];
            running = running.add(in).subtract(out);
            addCell(table, e.getKey(), cellFont);
            addCell(table, in.toPlainString(), cellFont);
            addCell(table, out.toPlainString(), cellFont);
            addCell(table, running.toPlainString(), cellFont);
        }
        doc.add(table);
    }

    private static void addCategorywiseTable(Document doc, List<Transaction> txns, Font headFont, Font cellFont) throws Exception {
        PdfPTable table = new PdfPTable(new float[]{2.2f, 1.5f, 1.5f, 1.5f});
        table.setWidthPercentage(100);
        for (String h : new String[]{"Category", "Income", "Expense", "Net"})
            addHeaderCell(table, h, headFont);

        for (Map.Entry<String, BigDecimal[]> e : categoryTotals(txns).entrySet()) {
            BigDecimal in = e.getValue()[0], out = e.getValue()[1];
            addCell(table, e.getKey(), cellFont);
            addCell(table, in.toPlainString(), cellFont);
            addCell(table, out.toPlainString(), cellFont);
            addCell(table, in.subtract(out).toPlainString(), cellFont);
        }
        doc.add(table);
    }

    private static void addSubcategorywiseTable(Document doc, List<Transaction> txns, Font headFont, Font cellFont) throws Exception {
        PdfPTable table = new PdfPTable(new float[]{2.2f, 1.5f, 1.5f, 1.5f});
        table.setWidthPercentage(100);
        for (String h : new String[]{"Sub Category", "Income", "Expense", "Net"})
            addHeaderCell(table, h, headFont);

        for (Map.Entry<String, BigDecimal[]> e : subCategoryTotals(txns).entrySet()) {
            BigDecimal in = e.getValue()[0], out = e.getValue()[1];
            addCell(table, e.getKey(), cellFont);
            addCell(table, in.toPlainString(), cellFont);
            addCell(table, out.toPlainString(), cellFont);
            addCell(table, in.subtract(out).toPlainString(), cellFont);
        }
        doc.add(table);
    }

    // 👈 Payment Type-wise PDF Table logic added
    private static void addPaymentTypewiseTable(Document doc, List<Transaction> txns, Font headFont, Font cellFont) throws Exception {
        PdfPTable table = new PdfPTable(new float[]{2.2f, 1.5f, 1.5f, 1.5f});
        table.setWidthPercentage(100);
        for (String h : new String[]{"Payment Type", "Income", "Expense", "Net"})
            addHeaderCell(table, h, headFont);

        for (Map.Entry<String, BigDecimal[]> e : paymentTypeTotals(txns).entrySet()) {
            BigDecimal in = e.getValue()[0], out = e.getValue()[1];
            addCell(table, e.getKey(), cellFont);
            addCell(table, in.toPlainString(), cellFont);
            addCell(table, out.toPlainString(), cellFont);
            addCell(table, in.subtract(out).toPlainString(), cellFont);
        }
        doc.add(table);
    }

    private static void addHeaderCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Paragraph(text, font));
        cell.setBackgroundColor(new BaseColor(37, 99, 235));
        cell.setPadding(6);
        table.addCell(cell);
    }

    private static void addCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Paragraph(text, font));
        cell.setPadding(5);
        table.addCell(cell);
    }

    // ── Shared aggregation ─────────────────────────────────────
    private static Map<String, BigDecimal[]> dayTotals(List<Transaction> txns) {
        List<Transaction> sorted = new ArrayList<>(txns);
        sorted.sort((a, b) -> {
            if (a.getDateTime() == null || b.getDateTime() == null) return 0;
            return a.getDateTime().compareTo(b.getDateTime());
        });
        Map<String, BigDecimal[]> map = new LinkedHashMap<>();
        for (Transaction t : sorted) {
            String day = t.getDateTime() != null ? t.getDateTime().format(DATE_FMT) : "Unknown";
            map.putIfAbsent(day, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal[] tot = map.get(day);
            BigDecimal amt = t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO;
            if (t.getType() == Transaction.Type.INCOME) tot[0] = tot[0].add(amt);
            else tot[1] = tot[1].add(amt);
        }
        return map;
    }

    private static Map<String, BigDecimal[]> categoryTotals(List<Transaction> txns) {
        Map<String, BigDecimal[]> map = new LinkedHashMap<>();
        for (Transaction t : txns) {
            String cat = t.getCategoryName() != null ? t.getCategoryName() : "Uncategorized";
            map.putIfAbsent(cat, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal[] tot = map.get(cat);
            BigDecimal amt = t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO;
            if (t.getType() == Transaction.Type.INCOME) tot[0] = tot[0].add(amt);
            else tot[1] = tot[1].add(amt);
        }
        return map;
    }

    private static Map<String, BigDecimal[]> subCategoryTotals(List<Transaction> txns) {
        Map<String, BigDecimal[]> map = new LinkedHashMap<>();
        for (Transaction t : txns) {
            String sub = t.getSubCategoryName() != null && !t.getSubCategoryName().isEmpty()
                    ? t.getSubCategoryName() : "No Sub Category";
            map.putIfAbsent(sub, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal[] tot = map.get(sub);
            BigDecimal amt = t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO;
            if (t.getType() == Transaction.Type.INCOME) tot[0] = tot[0].add(amt);
            else tot[1] = tot[1].add(amt);
        }
        return map;
    }

    // 👈 Payment Type calculation method added
    private static Map<String, BigDecimal[]> paymentTypeTotals(List<Transaction> txns) {
        Map<String, BigDecimal[]> map = new LinkedHashMap<>();
        for (Transaction t : txns) {
            String pt = t.getPaymentTypeName() != null && !t.getPaymentTypeName().isEmpty()
                    ? t.getPaymentTypeName() : "Unspecified";
            map.putIfAbsent(pt, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal[] tot = map.get(pt);
            BigDecimal amt = t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO;
            if (t.getType() == Transaction.Type.INCOME) tot[0] = tot[0].add(amt);
            else tot[1] = tot[1].add(amt);
        }
        return map;
    }
}