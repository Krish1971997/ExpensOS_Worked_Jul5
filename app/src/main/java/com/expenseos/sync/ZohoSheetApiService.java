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
                    extra = " | Sheet la irukkura worksheets: " + (names.length() > 300 ? names.substring(0, 300) + "…" : names);
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
            p.put("render_option", "formatted");   // cell la display aagura text apdiye ("October 2026", "8.50 AM")
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
                return " — Refresh token ku ZohoSheet.dataAPI.READ + UPDATE scope illa, new refresh token generate pannunga";
            case 2862:
            case 2898:
                return " — Zoho Sheet resource id thappu (file Zoho Sheet ah irukkanum)";
            case 2863:
                return " — 'Import' / 'Reference' worksheet name check pannunga";
            case 2990:
            case 2884:
                return " — row 1 la header names check pannunga";
            case 2950:
                return " — API throttle, 5 min kazhichu try pannunga";
            default:
                return "";
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}