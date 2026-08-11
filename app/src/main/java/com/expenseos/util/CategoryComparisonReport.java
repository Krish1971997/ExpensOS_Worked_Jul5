package com.expenseos.util;

import android.content.Context;

import com.expenseos.dao.TransactionDao;
import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Document;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font.*;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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
     * @param monthsCount     2–12
     * @param includeCurrent  true = last N months ending THIS month; false = last N months ending LAST month
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
                row.pctChange = row.diff
                        .divide(row.prevMonthAmt, 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .doubleValue();
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
    public static void writePdf(Result result, OutputStream out) throws Exception {
        Document doc = new Document(PageSize.A4.rotate(), 24, 24, 32, 32); // landscape — room for month columns
        PdfWriter.getInstance(doc, out);
        doc.open();

        Font titleFont = new Font(Font.FontFamily.HELVETICA, 16, Font.BOLD);
        Font headFont = new Font(Font.FontFamily.HELVETICA, 9, Font.BOLD, BaseColor.WHITE);
        Font cellFont = new Font(Font.FontFamily.HELVETICA, 9, Font.NORMAL);
        Font cellFontWhite = new Font(Font.FontFamily.HELVETICA, 9, Font.BOLD, BaseColor.WHITE);

        DateTimeFormatter monthFmt = DateTimeFormatter.ofPattern("MMM yyyy");

        Paragraph title = new Paragraph("Monthly Category Report", titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(4);
        doc.add(title);

        Paragraph subtitle = new Paragraph(
                monthFmt.format(result.months.get(0)) + " – " + monthFmt.format(result.months.get(result.months.size() - 1)),
                new Font(Font.FontFamily.HELVETICA, 10, Font.NORMAL, BaseColor.GRAY));
        subtitle.setAlignment(Element.ALIGN_CENTER);
        subtitle.setSpacingAfter(16);
        doc.add(subtitle);

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

            Row header = sheet.createRow(0);
            int col = 0;
            setHeader(header, col++, "Category", headerStyle);
            for (YearMonth ym : result.months) setHeader(header, col++, monthFmt.format(ym), headerStyle);
            setHeader(header, col++, "Diff", headerStyle);
            setHeader(header, col, "% Change", headerStyle);

            int r = 1;
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
        DateTimeFormatter monthFmt = DateTimeFormatter.ofPattern("MMM yyyy");
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body style='font-family:Arial,sans-serif;'>");
        sb.append("<h2>Monthly Category Report</h2>");
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
                sb.append("<td style='padding:6px;text-align:right;border-bottom:1px solid #eee;'>₹")
                        .append(amt.toPlainString()).append("</td>");
            sb.append("<td style='padding:6px;text-align:right;border-bottom:1px solid #eee;color:#fff;background:")
                    .append(hex).append(";'>").append(row.diff.signum() >= 0 ? "+" : "").append(row.diff.toPlainString()).append("</td>");
            sb.append("<td style='padding:6px;text-align:right;border-bottom:1px solid #eee;color:#fff;background:")
                    .append(hex).append(";'>").append(String.format(Locale.US, "%+.1f%%", row.pctChange)).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</table></body></html>");
        return sb.toString();
    }
}
