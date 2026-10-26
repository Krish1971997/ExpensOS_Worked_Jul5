package com.expenseos.sync;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Zoho Sheet Data API v2 (tabular records). Needs ZohoSheet.dataAPI.READ + UPDATE on the refresh token.
 */
public class ZohoSheetApiService {

    private static final String BASE = "https://sheet.zoho.com/api/v2/";
    private static final int PAGE = 1000;
    private final ZohoTokenService token;

    public ZohoSheetApiService(Context ctx) {
        token = new ZohoTokenService(ctx);
    }

    public JSONObject call(String resourceId, String method, Map<String, String> params) throws Exception {
        StringBuilder body = new StringBuilder("method=").append(enc(method));
        for (Map.Entry<String, String> e : params.entrySet())
            body.append('&').append(enc(e.getKey())).append('=').append(enc(e.getValue()));

        HttpURLConnection conn = (HttpURLConnection) new URL(BASE + resourceId).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("Authorization", "Zoho-oauthtoken " + token.getAccessToken());
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        InputStream is = status < 400 ? conn.getInputStream() : conn.getErrorStream();
        String resp = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
        JSONObject json;
        try {
            json = new JSONObject(resp);
        } catch (JSONException e) {
            throw new IOException("Zoho Sheet [HTTP " + status + "] non-JSON response: " + resp);
        }
        boolean failed = "failure".equalsIgnoreCase(json.optString("status")) || json.has("error_code");
        if (failed || status >= 400) {
            int code = json.optInt("error_code", 0);
            String extra = "";
            if (code == 2863 && !"worksheet.list".equals(method)) {
                try {   // diagnostic only: show which worksheets really exist
                    String names = call(resourceId, "worksheet.list", new LinkedHashMap<>()).toString();
                    extra = " | Worksheets in this sheet: " + (names.length() > 300 ? names.substring(0, 300) + "…" : names);
                } catch (Exception ignored) {
                }
            }
            throw new IOException("Zoho Sheet " + method + " failed [" + code + "]: "
                    + json.optString("error_message", resp) + hint(code) + extra);
        }
        return json;
    }

    /**
     * All records of a worksheet (row 1 = header). Each map has the header names as keys + "row_index".
     */
    public List<Map<String, String>> fetchRecords(String rid, String worksheet) throws Exception {
        List<Map<String, String>> all = new ArrayList<>();
        int start = 1;
        while (true) {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("worksheet_name", worksheet);
            p.put("render_option", "formatted");   // cell text exactly as displayed ("October 2026", "8.50 AM")
            p.put("records_start_index", String.valueOf(start));
            p.put("count", String.valueOf(PAGE));
            JSONArray recs = call(rid, "worksheet.records.fetch", p).optJSONArray("records");
            if (recs == null || recs.length() == 0) break;
            for (int i = 0; i < recs.length(); i++) {
                JSONObject o = recs.getJSONObject(i);
                Map<String, String> m = new LinkedHashMap<>();
                Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    Object v = o.opt(k);
                    m.put(k, v == null || v == JSONObject.NULL ? "" : String.valueOf(v));
                }
                all.add(m);
            }
            if (recs.length() < PAGE) break;
            start += PAGE;
        }
        return all;
    }

    /**
     * Appends rows at the bottom; each JSON object's keys must match the header names.
     */
    public void addRecords(String rid, String worksheet, JSONArray rows) throws Exception {
        for (int i = 0; i < rows.length(); i += 200) {
            JSONArray chunk = new JSONArray();
            for (int j = i; j < Math.min(i + 200, rows.length()); j++) chunk.put(rows.get(j));
            Map<String, String> p = new LinkedHashMap<>();
            p.put("worksheet_name", worksheet);
            p.put("json_data", chunk.toString());
            call(rid, "worksheet.records.add", p);
        }
    }

    /**
     * Writes CSV text starting at (row, column), 1-based. Overwrites existing cells; blank CSV cells blank them too.
     * Safe to retry (idempotent).
     */
    public void setCsv(String rid, String worksheet, int row, int column, String csv) throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("worksheet_name", worksheet);
        p.put("row", String.valueOf(row));
        p.put("column", String.valueOf(column));
        p.put("ignore_empty", "false");
        p.put("data", csv);
        call(rid, "worksheet.csvdata.set", p);
    }

    /**
     * Clears only the CONTENT of a range (format stays), 1-based and inclusive. Safe to retry.
     */
    public void clearRange(String rid, String worksheet, int startRow, int startCol, int endRow, int endCol) throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("worksheet_name", worksheet);
        p.put("start_row", String.valueOf(startRow));
        p.put("start_column", String.valueOf(startCol));
        p.put("end_row", String.valueOf(endRow));
        p.put("end_column", String.valueOf(endCol));
        call(rid, "range.content.clear", p);
    }

    // ── Dropdowns / validation (Zoho Sheet API: worksheet.picklist.* / worksheet.datavalidation.set) ──

    private static JSONArray rangeArray(String worksheet, String range) throws Exception {
        return new JSONArray().put(new JSONObject().put("worksheet_name", worksheet).put("range", range));
    }

    /**
     * Removes picklists from the given A1 ranges (all on one worksheet) in ONE call.
     */
    public void removePicklists(String rid, String worksheet, List<String> ranges) throws Exception {
        JSONArray arr = new JSONArray();
        for (String r : ranges)
            arr.put(new JSONObject().put("worksheet_name", worksheet).put("range", r));
        Map<String, String> p = new LinkedHashMap<>();
        p.put("ranges", arr.toString());
        call(rid, "worksheet.picklist.remove", p);
    }

    /**
     * Picklist whose items come from a source range (follows later edits of the source cells).
     */
    public void setPicklistFromRange(String rid, String worksheet, String targetRange,
                                     String srcWorksheet, String srcRange) throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("ranges", rangeArray(worksheet, targetRange).toString());
        p.put("source_ranges", rangeArray(srcWorksheet, srcRange).toString());
        p.put("ascending_sort", "false");
        p.put("display_as_chip", "false");
        p.put("show_dropdown_icon", "true");
        p.put("multi_select", "false");
        call(rid, "worksheet.picklist.set", p);
    }

    /**
     * Picklist with fixed items: each object {value, font_color?, fill_color?}.
     */
    public void setPicklistItems(String rid, String worksheet, String targetRange, JSONArray items) throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("ranges", rangeArray(worksheet, targetRange).toString());
        p.put("picklist_items", items.toString());
        p.put("display_as_chip", "false");
        p.put("show_dropdown_icon", "true");
        p.put("multi_select", "false");
        call(rid, "worksheet.picklist.set", p);
    }

    /**
     * criteria e.g. {"type":"decimal","operator":"greater_than","values":["0"]}. Invalid entry is blocked (STOP).
     */
    public void setDataValidation(String rid, String worksheet, String range, JSONObject criteria,
                                  String hint, String errorMessage) throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("worksheet_name", worksheet);
        p.put("ranges", new JSONArray().put(new JSONObject().put("range", range)).toString());
        p.put("dv_criteria_json", criteria.toString());
        p.put("ignore_blanks", "true");
        if (hint != null) p.put("hint", hint);
        p.put("error_alert", new JSONObject().put("show_alert", true).put("type", "STOP")
                .put("message", errorMessage).toString());
        call(rid, "worksheet.datavalidation.set", p);
    }

    /**
     * Rewrites the table body IN PLACE (no row is ever deleted, so dropdown ranges never shrink):
     * writes rows from firstRow, then clears whatever is left of the old data (up to lastOldRow).
     * Every String[] must have exactly `cols` items so stale values in trailing columns get blanked.
     */
    public void rewriteRows(String rid, String worksheet, int firstRow, int cols,
                            List<String[]> rows, int lastOldRow) throws Exception {
        if (!rows.isEmpty()) setCsv(rid, worksheet, firstRow, 1, toCsv(rows));
        int firstSurplus = firstRow + rows.size();
        if (lastOldRow >= firstSurplus)
            clearRange(rid, worksheet, firstSurplus, 1, lastOldRow, cols);
    }

    static String toCsv(List<String[]> rows) {
        StringBuilder sb = new StringBuilder();
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) sb.append(',');
                String v = row[i] == null ? "" : row[i];
                if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0)
                    v = '"' + v.replace("\"", "\"\"") + '"';
                sb.append(v);
            }
            sb.append('\n');
        }
        if (sb.length() > 0) sb.setLength(sb.length() - 1); // no trailing blank row
        return sb.toString();
    }

    /**
     * Deletes whole rows by row index (highest first, so earlier indexes never shift).
     */
    public void deleteRows(String rid, String worksheet, List<Integer> rowIndexes) throws Exception {
        if (rowIndexes.isEmpty()) return;
        List<Integer> sorted = new ArrayList<>(rowIndexes);
        sorted.sort(Collections.reverseOrder());
        for (int i = 0; i < sorted.size(); i += 500) {
            JSONArray arr = new JSONArray();
            for (int j = i; j < Math.min(i + 500, sorted.size()); j++)
                arr.put(sorted.get(j).intValue());
            Map<String, String> p = new LinkedHashMap<>();
            p.put("worksheet_name", worksheet);
            p.put("row_array", arr.toString());
            p.put("delete_rows", "true");
            call(rid, "worksheet.records.delete", p);
        }
    }

    private static String hint(int code) {
        switch (code) {
            case 2401:
            case 2403:
                return " — the refresh token lacks the ZohoSheet.dataAPI.READ + UPDATE scopes; generate a new refresh token";
            case 2862:
            case 2898:
                return " — wrong Zoho Sheet resource id (the file must be a Zoho Sheet)";
            case 2863:
                return " — check the 'Import' / 'Reference' worksheet names";
            case 2990:
            case 2884:
                return " — check the header names in row 1";
            case 2950:
                return " — API throttled, try again after 5 minutes";
            default:
                return "";
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}