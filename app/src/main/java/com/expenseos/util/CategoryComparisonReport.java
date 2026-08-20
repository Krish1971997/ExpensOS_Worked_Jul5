package com.expenseos.util;

import android.content.Context;

import com.expenseos.dao.TransactionDao;
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

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class CategoryComparisonReport {

    public enum Trend {RED, YELLOW, GREEN, NEUTRAL}

    // Increases smaller than this (in rupees) get flagged YELLOW instead of
    // RED — a small uptick isn't worth alarming the user about.
    private static final BigDecimal SMALL_INCREASE_THRESHOLD = BigDecimal.valueOf(500);

    public static class RowData {
        public String category;
        public List<BigDecimal> monthlyAmounts; // aligned with Result.months
        public BigDecimal lastMonthAmt;
        public BigDecimal prevMonthAmt;
        public BigDecimal diff;
        public double pctChange; // signed; +ve = increase
        public Trend trend;
    }

    public static class Result {
        public int bookId;
        public List<YearMonth> months; // oldest -> newest
        public List<RowData> rows;     // sorted by pctChange DESC
    }

    /**
     * @param monthsCount    2–12
     * @param includeCurrent true = last N months ending THIS month; false = last N months ending LAST month
     */
    public static Result build(Context ctx, int bookId, int monthsCount, boolean includeCurrent) {
        monthsCount = Math.max(2, Math.min(12, monthsCount));

        YearMonth anchor = YearMonth.from(includeCurrent ? LocalDate.now() : LocalDate.now().minusMonths(1));
        List<YearMonth> months = new ArrayList<>();
        for (int i = monthsCount - 1; i >= 0; i--) months.add(anchor.minusMonths(i));

        TransactionDao dao = new TransactionDao(ctx);

        // category -> (month -> total)
        Map<String, Map<YearMonth, BigDecimal>> byCategory = new LinkedHashMap<>();
        for (YearMonth ym : months) {
            List<Map<String, Object>> rows = dao.categoryBreakdownByMonth("EXPENSE", ym.getYear(), ym.getMonthValue(), bookId);
            for (Map<String, Object> r : rows) {
                String cat = (String) r.get("category");
                BigDecimal total = (BigDecimal) r.get("total");
                if (total == null) total = BigDecimal.ZERO;
                byCategory.computeIfAbsent(cat, k -> new LinkedHashMap<>()).put(ym, total);
            }
        }

        YearMonth lastMonth = months.get(months.size() - 1);
        YearMonth prevMonth = months.get(months.size() - 2);

        List<RowData> rows = new ArrayList<>();
        for (Map.Entry<String, Map<YearMonth, BigDecimal>> e : byCategory.entrySet()) {
            Map<YearMonth, BigDecimal> perMonth = e.getValue();
            RowData row = new RowData();
            row.category = e.getKey();
            row.monthlyAmounts = new ArrayList<>();
            for (YearMonth ym : months)
                row.monthlyAmounts.add(perMonth.getOrDefault(ym, BigDecimal.ZERO));

            row.lastMonthAmt = perMonth.getOrDefault(lastMonth, BigDecimal.ZERO);
            row.prevMonthAmt = perMonth.getOrDefault(prevMonth, BigDecimal.ZERO);
            row.diff = row.lastMonthAmt.subtract(row.prevMonthAmt);

            if (row.prevMonthAmt.signum() == 0) {
                row.pctChange = row.lastMonthAmt.signum() == 0 ? 0.0 : 100.0;
            } else {
                row.pctChange = row.diff.divide(row.prevMonthAmt, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).doubleValue();
            }

            if (row.diff.signum() > 0) {
                row.trend = row.diff.abs().compareTo(SMALL_INCREASE_THRESHOLD) < 0 ? Trend.YELLOW : Trend.RED;
            } else if (row.diff.signum() < 0) {
                row.trend = Trend.GREEN;
            } else {
                row.trend = Trend.NEUTRAL;
            }

            rows.add(row);
        }

        rows.sort((a, b) -> Double.compare(b.pctChange, a.pctChange));

        Result result = new Result();
        result.bookId = bookId;
        result.months = months;
        result.rows = rows;
        return result;
    }

    // ── PDF export (iText 5, matches ReportGenerator's style) ──────
    // Backward-compat overload — no cashbook name / custom title.
    public static void writePdf(Result result, OutputStream out) throws Exception {
        writePdf(result, null, null, out);
    }

    /**
     * @param cashbookName printed under the title as "Cashbook: <name>" —
     *                     pass null/blank to omit.
     * @param customTitle  overrides "Monthly Category Report" — pass
     *                     null/blank to use the default title.
     */
    public static void writePdf(Result result, String cashbookName, String customTitle, OutputStream out) throws Exception {
        Document doc = new Document(PageSize.A4.rotate(), 24, 24, 32, 40); // extra bottom margin for the footer
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        writer.setPageEvent(new FooterEvent());
        doc.open();

        Font titleFont = new Font(Font.FontFamily.HELVETICA, 16, Font.BOLD);
        Font metaFont = new Font(Font.FontFamily.HELVETICA, 9, Font.NORMAL, BaseColor.GRAY);
        Font headFont = new Font(Font.FontFamily.HELVETICA, 9, Font.BOLD, BaseColor.WHITE);
        Font cellFont = new Font(Font.FontFamily.HELVETICA, 9, Font.NORMAL);
        Font cellFontWhite = new Font(Font.FontFamily.HELVETICA, 9, Font.BOLD, BaseColor.WHITE);

        DateTimeFormatter monthFmt = DateTimeFormatter.ofPattern("MMM yyyy");

        String titleText = (customTitle != null && !customTitle.trim().isEmpty()) ? customTitle.trim() : "Monthly Category Report";
        Paragraph title = new Paragraph(titleText, titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(4);
        doc.add(title);

        Paragraph subtitle = new Paragraph(monthFmt.format(result.months.get(0)) + " – " + monthFmt.format(result.months.get(result.months.size() - 1)), new Font(Font.FontFamily.HELVETICA, 10, Font.NORMAL, BaseColor.GRAY));
        subtitle.setAlignment(Element.ALIGN_CENTER);
        subtitle.setSpacingAfter(4);
        doc.add(subtitle);

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

        float[] widths = new float[result.months.size() + 3];
        widths[0] = 2.4f;
        for (int i = 1; i <= result.months.size(); i++) widths[i] = 1f;
        widths[widths.length - 2] = 1f;
        widths[widths.length - 1] = 1.1f;

        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);

        addHeaderCell(table, "Category", headFont);
        for (YearMonth ym : result.months) addHeaderCell(table, monthFmt.format(ym), headFont);
        addHeaderCell(table, "Diff", headFont);
        addHeaderCell(table, "% Change", headFont);

        for (RowData row : result.rows) {
            addCell(table, row.category, cellFont, BaseColor.WHITE);
            for (BigDecimal amt : row.monthlyAmounts)
                addCell(table, amt.toPlainString(), cellFont, BaseColor.WHITE);

            BaseColor trendColor = trendColor(row.trend);
            Font trendFont = row.trend == Trend.NEUTRAL ? cellFont : cellFontWhite;
            addCell(table, (row.diff.signum() >= 0 ? "+" : "") + row.diff.toPlainString(), trendFont, trendColor);
            addCell(table, String.format(Locale.US, "%+.1f%%", row.pctChange), trendFont, trendColor);
        }
        doc.add(table);
        doc.close();
    }

    // Same "Page X of Y" + "Powered by ExpenseOS" footer as ReportGenerator.
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

            String pageLabel = "Page " + writer.getPageNumber() + " of ";
            float pageLabelWidth = baseFont.getWidthPoint(pageLabel, FONT_SIZE);
            cb.beginText();
            cb.setFontAndSize(baseFont, FONT_SIZE);
            cb.setTextMatrix(document.right() - pageLabelWidth - 30, y);
            cb.showText(pageLabel);
            cb.endText();
            cb.addTemplate(totalPagesTemplate, document.right() - 30, y);

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

    private static BaseColor trendColor(Trend t) {
        return switch (t) {
            case RED -> new BaseColor(220, 38, 38);
            case YELLOW -> new BaseColor(217, 119, 6);
            case GREEN -> new BaseColor(22, 163, 74);
            case NEUTRAL -> BaseColor.LIGHT_GRAY;
        };
    }

    private static void addHeaderCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Paragraph(text, font));
        cell.setBackgroundColor(new BaseColor(37, 99, 235));
        cell.setPadding(6);
        table.addCell(cell);
    }

    private static void addCell(PdfPTable table, String text, Font font, BaseColor bg) {
        PdfPCell cell = new PdfPCell(new Paragraph(text, font));
        cell.setBackgroundColor(bg);
        cell.setPadding(5);
        table.addCell(cell);
    }

    // ── Excel export (Apache POI, true .xlsx) ───────────────────────
    public static void writeXlsx(Result result, OutputStream out) throws Exception {
        writeXlsx(result, null, out);
    }

    public static void writeXlsx(Result result, String cashbookName, OutputStream out) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Category Report");
            DateTimeFormatter monthFmt = DateTimeFormatter.ofPattern("MMM yyyy");

            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            org.apache.poi.ss.usermodel.Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);

            CellStyle redStyle = coloredStyle(wb, IndexedColors.RED.getIndex());
            CellStyle yellowStyle = coloredStyle(wb, IndexedColors.LIGHT_ORANGE.getIndex());
            CellStyle greenStyle = coloredStyle(wb, IndexedColors.GREEN.getIndex());
            CellStyle neutralStyle = wb.createCellStyle();

            int rowIdx = 0;
            if (cashbookName != null && !cashbookName.trim().isEmpty()) {
                sheet.createRow(rowIdx++).createCell(0).setCellValue("Cashbook: " + cashbookName.trim());
            }
            String generatedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a"));
            sheet.createRow(rowIdx++).createCell(0).setCellValue("Generated: " + generatedAt);
            rowIdx++; // blank spacer row before the header

            Row header = sheet.createRow(rowIdx++);
            int col = 0;
            setHeader(header, col++, "Category", headerStyle);
            for (YearMonth ym : result.months)
                setHeader(header, col++, monthFmt.format(ym), headerStyle);
            setHeader(header, col++, "Diff", headerStyle);
            setHeader(header, col, "% Change", headerStyle);

            int r = rowIdx;
            for (RowData row : result.rows) {
                Row xr = sheet.createRow(r++);
                int c = 0;
                xr.createCell(c++).setCellValue(row.category);
                for (BigDecimal amt : row.monthlyAmounts)
                    xr.createCell(c++).setCellValue(amt.doubleValue());

                CellStyle style = switch (row.trend) {
                    case RED -> redStyle;
                    case YELLOW -> yellowStyle;
                    case GREEN -> greenStyle;
                    case NEUTRAL -> neutralStyle;
                };

                Cell diffCell = xr.createCell(c++);
                diffCell.setCellValue(row.diff.doubleValue());
                diffCell.setCellStyle(style);

                Cell pctCell = xr.createCell(c);
                pctCell.setCellValue(row.pctChange / 100.0);
                pctCell.setCellStyle(style);
            }

            for (int i = 0; i <= result.months.size() + 2; i++) sheet.autoSizeColumn(i);
            wb.write(out);
        }
    }

    private static CellStyle coloredStyle(XSSFWorkbook wb, short colorIndex) {
        CellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(colorIndex);
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private static void setHeader(Row row, int col, String text, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(text);
        cell.setCellStyle(style);
    }

    // ── Email body (inline-styled HTML table — works across mail clients) ──
    public static String buildHtmlEmail(Result result) {
        return buildHtmlEmail(result, null);
    }

    public static String buildHtmlEmail(Result result, String cashbookName) {
        DateTimeFormatter monthFmt = DateTimeFormatter.ofPattern("MMM yyyy");
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body style='font-family:Arial,sans-serif;'>");
        sb.append("<h2>Monthly Category Report</h2>");
        if (cashbookName != null && !cashbookName.trim().isEmpty()) {
            sb.append("<p style='color:#666;margin:0;'>Cashbook: ").append(cashbookName.trim()).append("</p>");
        }
        sb.append("<p style='color:#666;'>")
                .append(monthFmt.format(result.months.get(0))).append(" – ")
                .append(monthFmt.format(result.months.get(result.months.size() - 1)))
                .append("</p>");
        sb.append("<table style='border-collapse:collapse;width:100%;font-size:13px;'>");
        sb.append("<tr style='background:#2563EB;color:#fff;'>");
        sb.append("<th style='padding:6px;text-align:left;'>Category</th>");
        for (YearMonth ym : result.months)
            sb.append("<th style='padding:6px;text-align:right;'>").append(monthFmt.format(ym)).append("</th>");
        sb.append("<th style='padding:6px;text-align:right;'>Diff</th>");
        sb.append("<th style='padding:6px;text-align:right;'>% Change</th>");
        sb.append("</tr>");

        for (RowData row : result.rows) {
            String hex = switch (row.trend) {
                case RED -> "#DC2626";
                case YELLOW -> "#D97706";
                case GREEN -> "#16A34A";
                case NEUTRAL -> "#9CA3AF";
            };
            sb.append("<tr>");
            sb.append("<td style='padding:6px;border-bottom:1px solid #eee;'>").append(row.category).append("</td>");
            for (BigDecimal amt : row.monthlyAmounts)
                sb.append("<td style='padding:6px;text-align:right;border-bottom:1px solid #eee;'>₹").append(amt.toPlainString()).append("</td>");
            sb.append("<td style='padding:6px;text-align:right;border-bottom:1px solid #eee;color:#fff;background:").append(hex).append(";'>").append(row.diff.signum() >= 0 ? "+" : "").append(row.diff.toPlainString()).append("</td>");
            sb.append("<td style='padding:6px;text-align:right;border-bottom:1px solid #eee;color:#fff;background:").append(hex).append(";'>").append(String.format(Locale.US, "%+.1f%%", row.pctChange)).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</table></body></html>");
        return sb.toString();
    }
}
