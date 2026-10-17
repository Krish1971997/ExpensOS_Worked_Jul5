package com.expenseos.excel;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.SAXParserFactory;

public final class ExcelImportReader {

    private ExcelImportReader() {
    }

    private static final class Cell {
        String text;
        boolean numeric;
    }

    public static List<ImportRow> read(byte[] xlsx) throws Exception {
        Map<String, byte[]> files = unzip(xlsx);
        List<String> shared = files.containsKey("xl/sharedStrings.xml")
                ? parseShared(files.get("xl/sharedStrings.xml")) : new ArrayList<>();
        String sheetPath = findSheetPath(files);
        TreeMap<Integer, Map<Integer, Cell>> grid = parseSheet(files.get(sheetPath), shared);
        if (grid.isEmpty()) return new ArrayList<>();

        int headerRowNo = grid.firstKey();
        Map<String, Integer> col = new HashMap<>();
        for (Map.Entry<Integer, Cell> e : grid.get(headerRowNo).entrySet())
            col.put(norm(e.getValue().text), e.getKey());
        String[] need = {"cashbook", "type", "date", "time", "amount", "paymenttype", "category"};
        for (String n : need)
            if (!col.containsKey(n))
                throw new IllegalStateException("Header \"" + n + "\" not found in row " + headerRowNo
                        + " — click 'Update Excel' to regenerate the template");

        List<ImportRow> out = new ArrayList<>();
        for (Map.Entry<Integer, Map<Integer, Cell>> re : grid.entrySet()) {
            if (re.getKey() <= headerRowNo) continue;
            Map<Integer, Cell> cells = re.getValue();
            ImportRow r = new ImportRow();
            r.sheetRow = re.getKey();
            r.cashbook = text(cells, col.get("cashbook"));
            r.type = text(cells, col.get("type"));
            r.paymentType = text(cells, col.get("paymenttype"));
            r.category = text(cells, col.get("category"));
            r.subCategory = text(cells, col.get("subcategory"));
            r.description = text(cells, col.get("description"));
            r.status = text(cells, col.get("status"));
            r.error = text(cells, col.get("error"));
            parseDate(cells.get(col.get("date")), r);
            parseTime(cells.get(col.get("time")), r);
            parseAmount(cells.get(col.get("amount")), r);
            boolean blank = r.cashbook.isEmpty() && r.type.isEmpty() && r.paymentType.isEmpty()
                    && r.category.isEmpty() && r.subCategory.isEmpty() && r.description.isEmpty()
                    && r.dateRaw.isEmpty() && r.timeRaw.isEmpty() && r.amountRaw.isEmpty();
            if (!blank) out.add(r);
        }
        return out;
    }

    /**
     * One Zoho Sheet record (header name -> value, plus "row_index") -> ImportRow, or null for a blank row.
     * Works whether the sheet returns formatted text ("16/10/2026", "01:45 PM") or raw serial numbers.
     */
    public static ImportRow fromRecord(Map<String, String> rec) {
        Map<String, String> m = new HashMap<>();
        for (Map.Entry<String, String> e : rec.entrySet())
            m.put(norm(e.getKey()), e.getValue() == null ? "" : e.getValue().trim());

        ImportRow r = new ImportRow();
        try {
            r.sheetRow = (int) Double.parseDouble(m.getOrDefault("rowindex", "0"));
        } catch (Exception e) {
            r.sheetRow = 0;
        }
        r.cashbook = m.getOrDefault("cashbook", "");
        r.type = m.getOrDefault("type", "");
        r.paymentType = m.getOrDefault("paymenttype", "");
        r.category = m.getOrDefault("category", "");
        r.subCategory = m.getOrDefault("subcategory", "");
        r.description = m.getOrDefault("description", "");
        r.status = m.getOrDefault("status", "");
        r.error = m.getOrDefault("error", "");
        parseDate(asCell(m.get("date")), r);
        String timeText = m.get("time");
        if (timeText != null && timeText.matches("([1-9]|1\\d|2[0-3])\\.[0-5]\\d"))
            timeText = timeText.replace('.', ':');   // "13.45" -> "13:45"
        parseTime(asCell(timeText), r);
        parseAmount(asCell(m.get("amount")), r);
        boolean blank = r.cashbook.isEmpty() && r.type.isEmpty() && r.paymentType.isEmpty()
                && r.category.isEmpty() && r.subCategory.isEmpty() && r.description.isEmpty()
                && r.dateRaw.isEmpty() && r.timeRaw.isEmpty() && r.amountRaw.isEmpty();
        return blank ? null : r;
    }

    private static Cell asCell(String s) {
        Cell c = new Cell();
        c.text = s == null ? "" : s;
        c.numeric = c.text.matches("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");
        return c;
    }

    private static final DateTimeFormatter[] DATE_FMTS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd"), DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"), DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("d-M-yyyy"), DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH), DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH), DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH), DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)};
    private static final DateTimeFormatter[] TIME_FMTS = {
            DateTimeFormatter.ofPattern("HH:mm"), DateTimeFormatter.ofPattern("H:mm"),
            DateTimeFormatter.ofPattern("HH:mm:ss"), DateTimeFormatter.ofPattern("H:mm:ss"),
            DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH), DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.ENGLISH), DateTimeFormatter.ofPattern("h:mm:ss a", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("h.mm a", Locale.ENGLISH), DateTimeFormatter.ofPattern("hh.mm a", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("h.mm.ss a", Locale.ENGLISH)};

    private static void parseDate(Cell c, ImportRow r) {
        if (c == null || c.text == null || c.text.trim().isEmpty()) return;
        String s = c.text.trim();
        r.dateRaw = s;
        if (c.numeric) {
            try {
                double d = Double.parseDouble(s);
                if (d >= 1) {
                    r.date = LocalDate.of(1899, 12, 30).plusDays((long) Math.floor(d));
                    r.dateRaw = r.date.toString();
                }
            } catch (NumberFormatException ignored) {
            }
            return;
        }
        for (DateTimeFormatter f : DATE_FMTS) {
            try {
                r.date = LocalDate.parse(s, f);
                return;
            } catch (Exception ignored) {
            }
        }
    }

    private static void parseTime(Cell c, ImportRow r) {
        if (c == null || c.text == null || c.text.trim().isEmpty()) return;
        String s = c.text.trim();
        r.timeRaw = s;
        if (c.numeric) {
            try {
                double d = Double.parseDouble(s);
                double frac = d - Math.floor(d);
                int sec = (int) Math.round(frac * 86400.0) % 86400;
                r.time = LocalTime.ofSecondOfDay(sec);
                r.timeRaw = r.time.toString();
            } catch (NumberFormatException ignored) {
            }
            return;
        }
        String up = s.toUpperCase(Locale.ENGLISH).replaceAll("\\s+", " ");
        up = up.replaceAll("(?<=\\d)(AM|PM)$", " $1");
        for (DateTimeFormatter f : TIME_FMTS) {
            try {
                r.time = LocalTime.parse(up, f);
                return;
            } catch (Exception ignored) {
            }
        }
    }

    private static void parseAmount(Cell c, ImportRow r) {
        if (c == null || c.text == null || c.text.trim().isEmpty()) return;
        String s = c.text.trim();
        r.amountRaw = s;
        try {
            String clean = c.numeric ? s : s.replace(",", "").replace("₹", "").trim();
            r.amount = new BigDecimal(clean).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception ignored) {
        }
    }

    private static String text(Map<Integer, Cell> cells, Integer idx) {
        if (idx == null) return "";
        Cell c = cells.get(idx);
        return c == null || c.text == null ? "" : c.text.trim();
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    private static Map<String, byte[]> unzip(byte[] data) throws Exception {
        Map<String, byte[]> m = new HashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = zin.read(buf)) > 0) bos.write(buf, 0, n);
                m.put(e.getName(), bos.toByteArray());
            }
        }
        if (m.isEmpty()) throw new IllegalStateException("Downloaded file is not a valid .xlsx");
        return m;
    }

    private static void sax(byte[] xml, DefaultHandler h) throws Exception {
        SAXParserFactory f = SAXParserFactory.newInstance();
        f.setNamespaceAware(false);
        f.newSAXParser().parse(new InputSource(new ByteArrayInputStream(xml)), h);
    }

    private static String attr(Attributes a, String name) {
        String v = a.getValue(name);
        if (v != null) return v;
        for (int i = 0; i < a.getLength(); i++)
            if (a.getQName(i).endsWith(":" + name)) return a.getValue(i);
        return null;
    }

    private static String findSheetPath(Map<String, byte[]> files) throws Exception {
        byte[] wb = files.get("xl/workbook.xml"), rels = files.get("xl/_rels/workbook.xml.rels");
        if (wb == null || rels == null)
            throw new IllegalStateException("Invalid workbook structure");
        final List<String[]> sheets = new ArrayList<>();
        sax(wb, new DefaultHandler() {
            @Override
            public void startElement(String u, String l, String q, Attributes a) {
                if (q.equals("sheet") || q.endsWith(":sheet"))
                    sheets.add(new String[]{attr(a, "name"), attr(a, "id")});
            }
        });
        final Map<String, String> targets = new HashMap<>();
        sax(rels, new DefaultHandler() {
            @Override
            public void startElement(String u, String l, String q, Attributes a) {
                if (q.equals("Relationship")) targets.put(a.getValue("Id"), a.getValue("Target"));
            }
        });
        String rid = null;
        for (String[] s : sheets) if ("Import".equalsIgnoreCase(s[0])) rid = s[1];
        if (rid == null && !sheets.isEmpty()) rid = sheets.get(0)[1];
        String t = rid == null ? null : targets.get(rid);
        if (t == null) throw new IllegalStateException("Import sheet not found");
        return t.startsWith("/") ? t.substring(1) : "xl/" + t;
    }

    private static List<String> parseShared(byte[] xml) throws Exception {
        final List<String> out = new ArrayList<>();
        sax(xml, new DefaultHandler() {
            StringBuilder cur;
            boolean inT;
            int skip;

            @Override
            public void startElement(String u, String l, String q, Attributes a) {
                if (q.equals("si")) cur = new StringBuilder();
                else if (q.equals("rPh")) skip++;
                else if (q.equals("t") && skip == 0) inT = true;
            }

            @Override
            public void characters(char[] ch, int s, int n) {
                if (inT && cur != null) cur.append(ch, s, n);
            }

            @Override
            public void endElement(String u, String l, String q) {
                if (q.equals("t")) inT = false;
                else if (q.equals("rPh")) skip--;
                else if (q.equals("si")) {
                    out.add(cur.toString());
                    cur = null;
                }
            }
        });
        return out;
    }

    private static TreeMap<Integer, Map<Integer, Cell>> parseSheet(byte[] xml, final List<String> shared) throws Exception {
        final TreeMap<Integer, Map<Integer, Cell>> grid = new TreeMap<>();
        sax(xml, new DefaultHandler() {
            int rowNo, nextRow = 1, colIdx;
            String type;
            final StringBuilder v = new StringBuilder();
            final StringBuilder is = new StringBuilder();
            boolean inV, inIs, inT;

            @Override
            public void startElement(String u, String l, String q, Attributes a) {
                switch (q) {
                    case "row": {
                        String r = a.getValue("r");
                        rowNo = r != null ? Integer.parseInt(r) : nextRow;
                        nextRow = rowNo + 1;
                        break;
                    }
                    case "c": {
                        String ref = a.getValue("r");
                        colIdx = ref != null ? colIndex(ref) : colIdx + 1;
                        type = a.getValue("t");
                        v.setLength(0);
                        is.setLength(0);
                        break;
                    }
                    case "v":
                        inV = true;
                        break;
                    case "is":
                        inIs = true;
                        break;
                    case "t":
                        if (inIs) inT = true;
                        break;
                    default:
                        break;
                }
            }

            @Override
            public void characters(char[] ch, int s, int n) {
                if (inV) v.append(ch, s, n);
                else if (inT) is.append(ch, s, n);
            }

            @Override
            public void endElement(String u, String l, String q) {
                switch (q) {
                    case "v":
                        inV = false;
                        break;
                    case "t":
                        inT = false;
                        break;
                    case "is":
                        inIs = false;
                        break;
                    case "c": {
                        Cell c = new Cell();
                        if ("s".equals(type)) {
                            try {
                                c.text = shared.get(Integer.parseInt(v.toString().trim()));
                            } catch (Exception e) {
                                c.text = "";
                            }
                        } else if ("inlineStr".equals(type)) c.text = is.toString();
                        else if ("str".equals(type) || "b".equals(type) || "e".equals(type))
                            c.text = v.toString();
                        else {
                            c.text = v.toString();
                            c.numeric = true;
                        }
                        if (c.text != null && !c.text.trim().isEmpty())
                            grid.computeIfAbsent(rowNo, k -> new TreeMap<>()).put(colIdx, c);
                        break;
                    }
                    default:
                        break;
                }
            }
        });
        return grid;
    }

    private static int colIndex(String ref) {
        int n = 0;
        for (int i = 0; i < ref.length(); i++) {
            char ch = ref.charAt(i);
            if (ch < 'A' || ch > 'Z') break;
            n = n * 26 + (ch - 'A' + 1);
        }
        return n - 1;
    }
}