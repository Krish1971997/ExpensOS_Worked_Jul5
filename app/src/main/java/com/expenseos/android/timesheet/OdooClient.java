package com.expenseos.android.timesheet;

import android.content.Context;
import android.os.Build;

import com.expenseos.util.AppConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * Odoo JSON-RPC client. Blocking calls: always run from a background thread.
 */
public final class OdooClient {

    /**
     * Dropdown item (id 0 = placeholder / none).
     */
    public record Item(int id, String name, String stage) {
        public Item(int id, String name, String stage) {
            this.id = id;
            this.name = name;
            this.stage = stage == null ? "" : stage;
        }

        @Override
        public String toString() {
            return stage.isEmpty() ? name : name + "  [" + stage + "]";
        }
    }

    private static AppConfig cfg;
    private static String authKey = "";
    private static Integer uid;

    private static final int BREAK_MINUTES = 30;
    private static final String DEFAULT_TZ = "Asia/Kolkata";
    private static final Set<String> ACTIVE_STAGES =
            new HashSet<>(Arrays.asList("new", "ra", "da", "qa", "devops", "cs"));
    private static final String DONE_STAGE = "done";

    private OdooClient() {
    }

    // ---------------------------------------------------------------
    // Connection
    // ---------------------------------------------------------------

    /**
     * Read the Odoo settings from AppConfig (Configuration screen) and authenticate.
     */
    public static synchronized void init(Context ctx) throws Exception {
        cfg = AppConfig.get(ctx.getApplicationContext());
        if (prop("odoo.url").isEmpty() || prop("odoo.db").isEmpty()
                || prop("odoo.user").isEmpty() || prop("odoo.apikey").isEmpty()) {
            throw new Exception("Odoo is not configured. Fill the Odoo fields in Configuration.");
        }
        // Re-login automatically when the settings were changed in Configuration
        String key = prop("odoo.url") + "|" + prop("odoo.db") + "|" + prop("odoo.user") + "|" + prop("odoo.apikey");
        if (uid != null && key.equals(authKey)) return;
        uid = null;

        JSONArray a = new JSONArray().put(prop("odoo.db")).put(prop("odoo.user"))
                .put(prop("odoo.apikey")).put(new JSONObject());
        Object r = rpc("common", "authenticate", a);
        if (!(r instanceof Integer)) {
            throw new Exception("Odoo authentication failed: check DB, login and API key");
        }
        uid = (Integer) r;
        authKey = key;
    }

    private static String prop(String k) {
        switch (k) {
            case "odoo.url":
                return cfg.getOdooUrl();
            case "odoo.db":
                return cfg.getOdooDb();
            case "odoo.user":
                return cfg.getOdooUser();
            default:
                return cfg.getOdooApiKey();
        }
    }

    private static void ensure() throws Exception {
        if (uid == null) throw new Exception("Odoo is not connected");
    }

    private static Object rpc(String service, String method, JSONArray args) throws Exception {
        JSONObject params = new JSONObject().put("service", service).put("method", method).put("args", args);
        JSONObject body = new JSONObject().put("jsonrpc", "2.0").put("method", "call")
                .put("params", params).put("id", 1);

        String base = prop("odoo.url");
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);

        HttpURLConnection c = (HttpURLConnection) new URL(base + "/jsonrpc").openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = readAll(is);
        c.disconnect();

        JSONObject resp;
        try {
            resp = new JSONObject(text);
        } catch (JSONException e) {
            throw new Exception("Unexpected response from Odoo (HTTP " + code + ")");
        }
        if (resp.has("error")) {
            JSONObject err = resp.optJSONObject("error");
            String msg = "Odoo error";
            if (err != null) {
                JSONObject data = err.optJSONObject("data");
                msg = data != null && data.optString("message").length() > 0
                        ? data.optString("message") : err.optString("message", msg);
            }
            if (msg.length() > 500) msg = msg.substring(0, 500) + "...";
            throw new Exception(msg);
        }
        return resp.opt("result");
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return bos.toString(StandardCharsets.UTF_8);
        }
        return "";
    }

    private static Object call(String model, String method, JSONArray args, JSONObject kw) throws Exception {
        ensure();
        JSONArray a = new JSONArray().put(prop("odoo.db")).put((int) uid).put(prop("odoo.apikey"))
                .put(model).put(method).put(args).put(kw == null ? new JSONObject() : kw);
        return rpc("object", "execute_kw", a);
    }

    private static JSONArray searchRead(String model, JSONArray domain, JSONObject opts) throws Exception {
        Object r = call(model, "search_read", new JSONArray().put(domain), opts);
        return r instanceof JSONArray ? (JSONArray) r : new JSONArray();
    }

    // ---------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------

    private static JSONArray cond(String f, String op, Object v) throws JSONException {
        return new JSONArray().put(f).put(op).put(v);
    }

    private static JSONArray fields(String... f) {
        JSONArray a = new JSONArray();
        for (String s : f) a.put(s);
        return a;
    }

    /**
     * Name of a many2one value ([id, name] or false).
     */
    private static String m2oName(Object v) {
        return (v instanceof JSONArray && ((JSONArray) v).length() > 1) ? ((JSONArray) v).optString(1) : "";
    }

    /**
     * Id of a many2one value, 0 when empty.
     */
    private static int m2oId(Object v) {
        return (v instanceof JSONArray && ((JSONArray) v).length() > 0) ? ((JSONArray) v).optInt(0) : 0;
    }

    private static String str(JSONObject m, String key) {
        Object o = m.opt(key);
        return o instanceof String ? (String) o : "";
    }

    private static SimpleDateFormat odooFmt() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f;
    }

    private static String fmtIn(String pattern, Date d, TimeZone z) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
        f.setTimeZone(z);
        return f.format(d);
    }

    // ---------------------------------------------------------------
    // Dropdown data
    // ---------------------------------------------------------------

    public static List<Item> listProjects() throws Exception {
        JSONObject o = new JSONObject().put("fields", fields("id", "name")).put("order", "name asc");
        JSONArray rows = searchRead("project.project",
                new JSONArray().put(cond("allow_timesheets", "=", true)), o);
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject m = rows.getJSONObject(i);
            out.add(new Item(m.getInt("id"), str(m, "name"), ""));
        }
        return out;
    }

    public static List<Item> listTasks(int projectId, boolean includeDone) throws Exception {
        JSONObject o = new JSONObject().put("fields", fields("id", "name", "stage_id"))
                .put("order", "stage_id asc, name asc");
        JSONArray rows = searchRead("project.task",
                new JSONArray().put(cond("project_id", "=", projectId)), o);
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject m = rows.getJSONObject(i);
            String stage = m2oName(m.opt("stage_id"));
            String key = stage.trim().toLowerCase(Locale.ROOT);
            boolean show = ACTIVE_STAGES.contains(key) || (includeDone && DONE_STAGE.equals(key));
            if (!show) continue;
            out.add(new Item(m.getInt("id"), str(m, "name"), stage));
        }
        return out;
    }

    public static List<Item> listActivities() throws Exception {
        JSONObject o = new JSONObject().put("fields", fields("id", "name")).put("order", "name asc");
        JSONArray rows = searchRead("mail.activity.type", new JSONArray(), o);
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject m = rows.getJSONObject(i);
            out.add(new Item(m.getInt("id"), str(m, "name"), ""));
        }
        return out;
    }

    // ---------------------------------------------------------------
    // Create / update
    // ---------------------------------------------------------------

    /**
     * Sets date_time / date_time_end (UTC). Keeps the old time of day when there is one, else now.
     */
    private static void putDateTimes(JSONObject vals, String date, double hours, String oldStart) throws Exception {
        TimeZone zone = userZone();
        Calendar t = Calendar.getInstance(zone);   // now (in the user's zone)
        if (oldStart != null && oldStart.length() >= 19) {
            Date old = odooFmt().parse(oldStart.substring(0, 19));
            t.setTime(old);
        }
        String[] p = date.trim().split("-");
        Calendar c = Calendar.getInstance(zone);
        c.clear();
        c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]),
                t.get(Calendar.HOUR_OF_DAY), t.get(Calendar.MINUTE), t.get(Calendar.SECOND));
        Date start = c.getTime();
        Date end = new Date(start.getTime() + Math.round(hours * 3600) * 1000L);
        SimpleDateFormat f = odooFmt();
        vals.put("date_time", f.format(start));
        vals.put("date_time_end", f.format(end));
    }

    private static String checkDate(String date) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setLenient(false);
        f.parse(date.trim());
        return date.trim();
    }

    public static Object createTimesheet(int projectId, int taskId, Integer activityId,
                                         String date, double hours, String description) throws Exception {
        ensure();
        checkDate(date);
        JSONObject vals = new JSONObject();
        vals.put("name", description);
        vals.put("date", date);
        vals.put("unit_amount", hours);
        putDateTimes(vals, date, hours, null);
        vals.put("project_id", projectId);
        vals.put("task_id", taskId);
        if (activityId != null) vals.put("activity_id", (int) activityId);
        return call("account.analytic.line", "create", new JSONArray().put(vals), null);
    }

    public static void updateTimesheet(int id, String date, int taskId, Integer activityId,
                                       double hours, String description) throws Exception {
        ensure();
        JSONObject opts = new JSONObject().put("fields", fields("id", "date_time")).put("limit", 1);
        JSONArray own = searchRead("account.analytic.line", new JSONArray()
                .put(cond("id", "=", id)).put(cond("user_id", "=", (int) uid)), opts);
        if (own.length() == 0)
            throw new Exception("Timesheet not found or it does not belong to you");

        JSONObject vals = new JSONObject();
        vals.put("name", description);
        vals.put("unit_amount", hours);
        vals.put("task_id", taskId);
        vals.put("activity_id", activityId == null ? (Object) Boolean.FALSE : (Object) activityId);

        if (date != null && !date.trim().isEmpty()) {
            vals.put("date", checkDate(date));
            putDateTimes(vals, date, hours, str(own.getJSONObject(0), "date_time"));
        }
        call("account.analytic.line", "write",
                new JSONArray().put(new JSONArray().put(id)).put(vals), null);
    }

    // ---------------------------------------------------------------
    // Day summary: timesheets + attendance
    // ---------------------------------------------------------------

    public static JSONObject dayInfo(String date) throws Exception {
        ensure();
        checkDate(date);
        TimeZone zone = userZone();
        JSONArray sheets = timesheetsOf(date);
        double total = 0;
        for (int i = 0; i < sheets.length(); i++)
            total += sheets.getJSONObject(i).optDouble("hours", 0);

        JSONObject out = new JSONObject();
        out.put("date", date);
        out.put("timesheets", sheets);
        out.put("totalHours", Math.round(total * 100.0) / 100.0);
        out.put("attendance", attendanceOf(date, zone));
        return out;
    }

    private static JSONArray timesheetsOf(String date) throws Exception {
        JSONObject opts = new JSONObject()
                .put("fields", fields("id", "date", "name", "unit_amount", "project_id", "task_id", "activity_id"))
                .put("order", "id asc");
        JSONArray rows = searchRead("account.analytic.line", new JSONArray()
                .put(cond("date", "=", date)).put(cond("user_id", "=", (int) uid)), opts);

        JSONArray out = new JSONArray();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject m = rows.getJSONObject(i);
            JSONObject it = new JSONObject();
            it.put("id", m.getInt("id"));
            it.put("date", str(m, "date"));
            it.put("project", m2oName(m.opt("project_id")));
            it.put("projectId", m2oId(m.opt("project_id")));
            it.put("task", m2oName(m.opt("task_id")));
            it.put("taskId", m2oId(m.opt("task_id")));
            it.put("activity", m2oName(m.opt("activity_id")));
            it.put("activityId", m2oId(m.opt("activity_id")));
            it.put("description", str(m, "name"));
            it.put("hours", m.optDouble("unit_amount", 0));
            out.put(it);
        }
        return out;
    }

    private static TimeZone userZone() {
        try {
            JSONObject kw = new JSONObject().put("fields", fields("tz"));
            Object r = call("res.users", "read", new JSONArray().put(new JSONArray().put((int) uid)), kw);
            if (r instanceof JSONArray && ((JSONArray) r).length() > 0) {
                String tz = str(((JSONArray) r).getJSONObject(0), "tz").trim();
                if (!tz.isEmpty()) return TimeZone.getTimeZone(tz);
            }
        } catch (Exception e) {
            // fall through to default
        }
        return TimeZone.getTimeZone(DEFAULT_TZ);
    }

    private static Integer employeeId() throws Exception {
        JSONObject opts = new JSONObject().put("fields", fields("id")).put("limit", 1);
        JSONArray r = searchRead("hr.employee", new JSONArray().put(cond("user_id", "=", (int) uid)), opts);
        return r.length() == 0 ? null : r.getJSONObject(0).getInt("id");
    }

    private static Long toMillis(Object v) {
        if (v instanceof String && ((String) v).length() >= 19) {
            try {
                return odooFmt().parse(((String) v).substring(0, 19)).getTime();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static JSONObject attendanceOf(String date, TimeZone zone) {
        JSONObject a = new JSONObject();
        try {
            a.put("breakMinutes", BREAK_MINUTES);
            Integer emp = employeeId();
            if (emp == null) {
                a.put("found", false).put("note", "No employee is linked to this Odoo user");
                return a;
            }
            String[] p = date.split("-");
            Calendar c = Calendar.getInstance(zone);
            c.clear();
            c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]), 0, 0, 0);
            Date dayStart = c.getTime();
            c.add(Calendar.DAY_OF_MONTH, 1);
            Date dayEnd = c.getTime();
            SimpleDateFormat utc = odooFmt();

            JSONObject opts = new JSONObject().put("fields", fields("check_in", "check_out"))
                    .put("order", "check_in asc");
            JSONArray rows = searchRead("hr.attendance", new JSONArray()
                    .put(cond("employee_id", "=", (int) emp))
                    .put(cond("check_in", ">=", utc.format(dayStart)))
                    .put(cond("check_in", "<", utc.format(dayEnd))), opts);

            if (rows.length() == 0) {
                a.put("found", false);
                return a;
            }

            long now = System.currentTimeMillis();
            boolean isToday = date.equals(fmtIn("yyyy-MM-dd", new Date(now), zone));
            long grossSec = 0;
            boolean open = false, incomplete = false;
            Long firstIn = null, lastOut = null;

            for (int i = 0; i < rows.length(); i++) {
                JSONObject m = rows.getJSONObject(i);
                Long in = toMillis(m.opt("check_in"));
                Long out = toMillis(m.opt("check_out"));
                if (in == null) continue;
                if (firstIn == null || in < firstIn) firstIn = in;

                if (out == null) {
                    if (isToday) {
                        out = now;
                        open = true;
                    } else {
                        incomplete = true;
                        continue;
                    }
                } else if (lastOut == null || out > lastOut) {
                    lastOut = out;
                }
                grossSec += Math.max(0, (out - in) / 1000);
            }

            long grossMin = grossSec / 60;
            long netMin = Math.max(0, grossMin - BREAK_MINUTES);

            a.put("found", true);
            a.put("firstIn", firstIn == null ? "" : fmtIn("HH:mm", new Date(firstIn), zone));
            a.put("lastOut", lastOut == null ? "" : fmtIn("HH:mm", new Date(lastOut), zone));
            a.put("open", open);
            a.put("incomplete", incomplete);
            a.put("nowTime", fmtIn("HH:mm", new Date(now), zone));
            a.put("grossMinutes", grossMin);
            a.put("netMinutes", netMin);
        } catch (Exception e) {
            try {
                a.put("found", false);
                String msg = String.valueOf(e.getMessage());
                a.put("note", "Attendance not available: " + (msg.length() > 120 ? msg.substring(0, 120) + "..." : msg));
            } catch (JSONException ignored) {
            }
        }
        return a;
    }
}