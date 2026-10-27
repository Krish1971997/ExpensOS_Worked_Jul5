package com.expenseos.excel;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ExcelImportTemplate {

    public static final String[] HEADERS = {"Cashbook", "Type", "Date", "Time", "Amount",
            "Payment Type", "Category", "Subcategory", "Description", "Status", "Error"};
    private static final int[] WIDTHS = {22, 11, 13, 9, 12, 18, 20, 20, 30, 12, 50};
    private static final int S_DEFAULT = 0, S_HEADER = 1, S_DATE = 2, S_TIME = 3, S_AMOUNT = 4;
    private static final int MIN_VALIDATION_ROWS = 1000;
    /**
     * true = Subcategory list follows the chosen Category (needs Excel/LibreOffice). false = all subcategories (works in every app).
     */
    private static final boolean DEPENDENT_SUBS = true;
    private static final int MATRIX_COL = 10; // K: one column per category that has subcategories
    private static final String SUB_FORMULA =
            "IFERROR(OFFSET(Reference!$K$2,0,MATCH($G2,Reference!$K$1:$ZZ$1,0)-1,"
                    + "COUNTA(OFFSET(Reference!$K$2,0,MATCH($G2,Reference!$K$1:$ZZ$1,0)-1,200,1)),1),Reference!$J$2)";

    private ExcelImportTemplate() {
    }

    public static byte[] build(ReferenceData ref, List<ImportRow> carryOver) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            put(zip, "[Content_Types].xml", CONTENT_TYPES);
            put(zip, "_rels/.rels", ROOT_RELS);
            put(zip, "xl/workbook.xml", WORKBOOK);
            put(zip, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS);
            put(zip, "xl/styles.xml", STYLES);
            put(zip, "xl/worksheets/sheet1.xml", importSheet(ref, carryOver));
            put(zip, "xl/worksheets/sheet2.xml", referenceSheet(ref));
        }
        return bos.toByteArray();
    }

    private static String importSheet(ReferenceData ref, List<ImportRow> rows) {
        int lastRow = Math.max(MIN_VALIDATION_ROWS, 1 + rows.size() + 500);
        StringBuilder sb = new StringBuilder(8192);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                .append("<sheetViews><sheetView tabSelected=\"1\" workbookViewId=\"0\">")
                .append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
                .append("</sheetView></sheetViews><cols>");
        int[] colStyle = {S_DEFAULT, S_DEFAULT, S_DATE, S_TIME, S_AMOUNT, S_DEFAULT, S_DEFAULT, S_DEFAULT, S_DEFAULT, S_DEFAULT, S_DEFAULT};
        for (int i = 0; i < HEADERS.length; i++)
            sb.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1)
                    .append("\" width=\"").append(WIDTHS[i]).append("\" style=\"").append(colStyle[i])
                    .append("\" customWidth=\"1\"/>");
        sb.append("</cols><sheetData><row r=\"1\">");
        for (int i = 0; i < HEADERS.length; i++) str(sb, i, 1, HEADERS[i], S_HEADER);
        sb.append("</row>");

        int r = 2;
        for (ImportRow row : rows) {
            sb.append("<row r=\"").append(r).append("\">");
            str(sb, 0, r, row.cashbook, S_DEFAULT);
            str(sb, 1, r, row.type, S_DEFAULT);
            if (row.date != null) num(sb, 2, r, serialOf(row.date), S_DATE);
            else str(sb, 2, r, row.dateRaw, S_DEFAULT);
            if (row.time != null) num(sb, 3, r, timeFraction(row.time), S_TIME);
            else str(sb, 3, r, row.timeRaw, S_DEFAULT);
            if (row.amount != null) num(sb, 4, r, row.amount.toPlainString(), S_AMOUNT);
            else str(sb, 4, r, row.amountRaw, S_DEFAULT);
            str(sb, 5, r, row.paymentType, S_DEFAULT);
            str(sb, 6, r, row.category, S_DEFAULT);
            str(sb, 7, r, row.subCategory, S_DEFAULT);
            str(sb, 8, r, row.description, S_DEFAULT);
            str(sb, 9, r, row.status, S_DEFAULT);
            str(sb, 10, r, row.error, S_DEFAULT);
            sb.append("</row>");
            r++;
        }
        sb.append("</sheetData><dataValidations count=\"8\">");
        list(sb, "A2:A" + lastRow, "Reference!$A$2:$A$" + Math.max(2, 1 + ref.cashbooks.size()), "Pick a cashbook from the dropdown");
        list(sb, "B2:B" + lastRow, "\"INCOME,EXPENSE\"", "Pick INCOME or EXPENSE");
        sb.append("<dataValidation type=\"date\" operator=\"between\" allowBlank=\"0\" showInputMessage=\"1\" showErrorMessage=\"1\" ")
                .append("errorTitle=\"Invalid date\" error=\"Enter a valid date (yyyy-mm-dd)\" sqref=\"C2:C").append(lastRow)
                .append("\"><formula1>36526</formula1><formula2>73415</formula2></dataValidation>");
        sb.append("<dataValidation type=\"time\" operator=\"between\" allowBlank=\"0\" showInputMessage=\"1\" showErrorMessage=\"1\" ")
                .append("errorTitle=\"Invalid time\" error=\"Enter a valid time (hh:mm)\" sqref=\"D2:D").append(lastRow)
                .append("\"><formula1>0</formula1><formula2>0.99998842592</formula2></dataValidation>");
        sb.append("<dataValidation type=\"decimal\" operator=\"greaterThan\" allowBlank=\"0\" showInputMessage=\"1\" showErrorMessage=\"1\" ")
                .append("errorTitle=\"Invalid amount\" error=\"Enter a number greater than 0\" sqref=\"E2:E").append(lastRow)
                .append("\"><formula1>0</formula1></dataValidation>");
        list(sb, "F2:F" + lastRow, "Reference!$B$2:$B$" + Math.max(2, 1 + ref.paymentTypes.size()), "Pick a payment type from the dropdown");
        list(sb, "G2:G" + lastRow, "Reference!$C$2:$C$" + Math.max(2, 1 + ref.categories.size()), "Pick a category from the dropdown");
        String subSource = DEPENDENT_SUBS ? SUB_FORMULA
                : "Reference!$D$2:$D$" + Math.max(2, 1 + ref.subCategories.size());
        sb.append("<dataValidation type=\"list\" allowBlank=\"1\" showInputMessage=\"1\" showErrorMessage=\"1\" ")
                .append("errorTitle=\"Invalid subcategory\" error=\"")
                .append(DEPENDENT_SUBS ? "Pick a subcategory that belongs to the chosen category"
                        : "Pick a subcategory from the dropdown")
                .append("\" sqref=\"H2:H").append(lastRow).append("\"><formula1>")
                .append(esc(subSource)).append("</formula1></dataValidation>");
        sb.append("</dataValidations></worksheet>");
        return sb.toString();
    }

    private static void list(StringBuilder sb, String sqref, String formula, String msg) {
        sb.append("<dataValidation type=\"list\" allowBlank=\"0\" showInputMessage=\"1\" showErrorMessage=\"1\" ")
                .append("errorTitle=\"Invalid value\" error=\"").append(esc(msg)).append("\" sqref=\"").append(sqref)
                .append("\"><formula1>").append(esc(formula)).append("</formula1></dataValidation>");
    }

    private static String referenceSheet(ReferenceData ref) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                .append("<cols><col min=\"1\" max=\"4\" width=\"22\" customWidth=\"1\"/>")
                .append("<col min=\"5\" max=\"5\" width=\"4\" customWidth=\"1\"/>")
                .append("<col min=\"6\" max=\"9\" width=\"24\" customWidth=\"1\"/>")
                .append("<col min=\"11\" max=\"60\" width=\"22\" customWidth=\"1\"/></cols><sheetData>");
        List<String> matrixCats = new java.util.ArrayList<>(ref.subsByCategory.keySet());
        int maxSubs = 0;
        for (List<String> l : ref.subsByCategory.values()) maxSubs = Math.max(maxSubs, l.size());
        String[] head = {"Cashbooks", "Payment Types", "Categories", "Subcategories", null,
                "Category", "Type", "Scope", "Its subcategories"};
        int rows = 1 + Math.max(maxSubs, Math.max(Math.max(ref.cashbooks.size(), ref.paymentTypes.size()),
                Math.max(Math.max(ref.categories.size(), ref.subCategories.size()), ref.categoryInfo.size())));
        for (int r = 1; r <= rows; r++) {
            sb.append("<row r=\"").append(r).append("\">");
            for (int c = 0; c < 9; c++) {
                String v = null;
                if (r == 1) v = head[c];
                else {
                    int i = r - 2;
                    switch (c) {
                        case 0:
                            v = at(ref.cashbooks, i);
                            break;
                        case 1:
                            v = at(ref.paymentTypes, i);
                            break;
                        case 2:
                            v = at(ref.categories, i);
                            break;
                        case 3:
                            v = at(ref.subCategories, i);
                            break;
                        case 5:
                        case 6:
                        case 7:
                        case 8:
                            v = i < ref.categoryInfo.size() ? ref.categoryInfo.get(i)[c - 5] : null;
                            break;
                        default:
                            break;
                    }
                }
                if (v != null && !v.isEmpty()) str(sb, c, r, v, r == 1 ? S_HEADER : S_DEFAULT);
            }
            for (int j = 0; j < matrixCats.size(); j++) {
                String cat = matrixCats.get(j);
                String v = r == 1 ? cat : at(ref.subsByCategory.get(cat), r - 2);
                if (v != null && !v.isEmpty())
                    str(sb, MATRIX_COL + j, r, v, r == 1 ? S_HEADER : S_DEFAULT);
            }
            sb.append("</row>");
        }
        sb.append("</sheetData></worksheet>");
        return sb.toString();
    }

    private static String at(List<String> l, int i) {
        return i >= 0 && i < l.size() ? l.get(i) : null;
    }

    private static void str(StringBuilder sb, int col, int row, String v, int style) {
        if (v == null || v.isEmpty()) return;
        sb.append("<c r=\"").append(colName(col)).append(row).append("\" s=\"").append(style)
                .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(esc(v)).append("</t></is></c>");
    }

    private static void num(StringBuilder sb, int col, int row, String v, int style) {
        sb.append("<c r=\"").append(colName(col)).append(row).append("\" s=\"").append(style)
                .append("\"><v>").append(v).append("</v></c>");
    }

    static String colName(int idx) {
        StringBuilder s = new StringBuilder();
        for (int n = idx; n >= 0; n = n / 26 - 1) s.insert(0, (char) ('A' + n % 26));
        return s.toString();
    }

    static String serialOf(LocalDate d) {
        return String.valueOf(ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), d));
    }

    static String timeFraction(LocalTime t) {
        return String.valueOf(t.toSecondOfDay() / 86400.0);
    }

    static String esc(String s) {
        StringBuilder o = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&':
                    o.append("&amp;");
                    break;
                case '<':
                    o.append("&lt;");
                    break;
                case '>':
                    o.append("&gt;");
                    break;
                case '"':
                    o.append("&quot;");
                    break;
                default:
                    if (ch == 9 || ch == 10 || ch == 13 || ch >= 32) o.append(ch);
            }
        }
        return o.toString();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static final String CONTENT_TYPES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
            + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
            + "<Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
            + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
            + "</Types>";

    private static final String ROOT_RELS = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
            + "</Relationships>";

    private static final String WORKBOOK = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
            + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
            + "<bookViews><workbookView activeTab=\"0\"/></bookViews><sheets>"
            + "<sheet name=\"Import\" sheetId=\"1\" r:id=\"rId1\"/>"
            + "<sheet name=\"Reference\" sheetId=\"2\" r:id=\"rId2\"/></sheets></workbook>";

    private static final String WORKBOOK_RELS = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
            + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/>"
            + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
            + "</Relationships>";

    private static final String STYLES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<numFmts count=\"2\"><numFmt numFmtId=\"164\" formatCode=\"yyyy\\-mm\\-dd\"/><numFmt numFmtId=\"165\" formatCode=\"hh:mm\"/></numFmts>"
            + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
            + "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font></fonts>"
            + "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF2E7D32\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"
            + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
            + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
            + "<cellXfs count=\"5\">"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
            + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
            + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
            + "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
            + "<xf numFmtId=\"2\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
            + "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>";
}