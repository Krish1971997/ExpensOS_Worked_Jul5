package com.expenseos.util;

import com.expenseos.model.Transaction;
import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Document;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the three implemented report types (All Entries / Day-wise /
 * Category-wise) as CSV or PDF, writing straight to an OutputStream so the
 * same code works whether the destination is a plain File or a MediaStore
 * Uri's stream (see DownloadsSaver).
 *
 * "Party-wise summary" is intentionally not implemented — there's no
 * "party"/member concept anywhere in this app's schema, matching how the
 * old app shows that option greyed out rather than functional.
 */
public class ReportGenerator {

    public static final String TYPE_ALL = "all";
    public static final String TYPE_DAYWISE = "daywise";
    public static final String TYPE_CATEGORYWISE = "categorywise";
    public static final String TYPE_SUBCATEGORYWISE = "subcategorywise";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("hh:mm a");

    // ── CSV ──────────────────────────────────────────────────
    public static void writeCsv(List<Transaction> txns, String reportType, OutputStream out) throws Exception {
        Writer w = new OutputStreamWriter(out);
        switch (reportType) {
            case TYPE_DAYWISE -> writeDaywiseCsv(txns, w);
            case TYPE_CATEGORYWISE -> writeCategorywiseCsv(txns, w);
            case TYPE_SUBCATEGORYWISE -> writeSubcategorywiseCsv(txns, w);
            default -> writeAllEntriesCsv(txns, w);
        }
        w.flush();
    }

    private static void writeAllEntriesCsv(List<Transaction> txns, Writer w) throws Exception {
        w.write("Date,Time,Type,Category,Sub Category,Amount,Note\n");
        for (Transaction t : txns) {
            w.write(csvEscape(t.getDateTime() != null ? t.getDateTime().format(DATE_FMT) : "") + ",");
            w.write(csvEscape(t.getDateTime() != null ? t.getDateTime().format(TIME_FMT) : "") + ",");
            w.write(csvEscape(t.getType().name()) + ",");
            w.write(csvEscape(t.getCategoryName()) + ",");
            w.write(csvEscape(t.getSubCategoryName()) + ",");
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

    private static String csvEscape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n"))
            return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    // ── PDF (iText 5) ────────────────────────────────────────
    public static void writePdf(List<Transaction> txns, String reportType, OutputStream out) throws Exception {
        Document doc = new Document(PageSize.A4, 24, 24, 32, 32);
        PdfWriter.getInstance(doc, out);
        doc.open();

        Font titleFont = new Font(Font.FontFamily.HELVETICA, 16, Font.BOLD);
        Font headFont = new Font(Font.FontFamily.HELVETICA, 10, Font.BOLD, BaseColor.WHITE);
        Font cellFont = new Font(Font.FontFamily.HELVETICA, 9, Font.NORMAL);

        Paragraph title = new Paragraph(reportTitle(reportType), titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(16);
        doc.add(title);

        switch (reportType) {
            case TYPE_DAYWISE -> addDaywiseTable(doc, txns, headFont, cellFont);
            case TYPE_CATEGORYWISE -> addCategorywiseTable(doc, txns, headFont, cellFont);
            case TYPE_SUBCATEGORYWISE -> addSubcategorywiseTable(doc, txns, headFont, cellFont);
            default -> addAllEntriesTable(doc, txns, headFont, cellFont);
        }

        doc.close();
    }

    private static String reportTitle(String type) {
        return switch (type) {
            case TYPE_DAYWISE -> "Day-wise Summary";
            case TYPE_CATEGORYWISE -> "Category-wise Summary";
            case TYPE_SUBCATEGORYWISE -> "Sub Category-wise Summary";
            default -> "All Entries Report";
        };
    }

    private static void addAllEntriesTable(Document doc, List<Transaction> txns, Font headFont, Font cellFont) throws Exception {
        PdfPTable table = new PdfPTable(new float[]{2f, 1.3f, 1.6f, 1.6f, 1.3f, 2.5f});
        table.setWidthPercentage(100);
        for (String h : new String[]{"Date", "Type", "Category", "Sub Category", "Amount", "Note"})
            addHeaderCell(table, h, headFont);

        for (Transaction t : txns) {
            String date = (t.getDateTime() != null ? t.getDateTime().format(DATE_FMT) + " " + t.getDateTime().format(TIME_FMT) : "");
            addCell(table, date, cellFont);
            addCell(table, t.getType().name(), cellFont);
            addCell(table, t.getCategoryName() != null ? t.getCategoryName() : "", cellFont);
            addCell(table, t.getSubCategoryName() != null ? t.getSubCategoryName() : "", cellFont);
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

    private static void addHeaderCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Paragraph(text, font));
        cell.setBackgroundColor(new BaseColor(37, 99, 235)); // matches @color/primary
        cell.setPadding(6);
        table.addCell(cell);
    }

    private static void addCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Paragraph(text, font));
        cell.setPadding(5);
        table.addCell(cell);
    }

    // ── Shared aggregation ─────────────────────────────────────
    // date-string -> [totalIn, totalOut], insertion order = chronological
    // since callers already fetch txns sorted by date.
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
}
