package com.expenseos.sync;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.expenseos.dao.LocalDatabase;
import com.expenseos.util.AppConfig;
import com.expenseos.util.CatalystClient;
import com.expenseos.util.ConsoleLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full restore (cloud -> local SQLite) through the Catalyst function's /query endpoint.
 * The function reads DB_URL / DB_USER / DB_PASSWORD from its own environment variables,
 * so the phone never needs the database credentials.
 * <p>
 * Replaces the JDBC-based full pull used by SyncManager.restoreAllFromCloud().
 */
public final class CloudRestore {

    // Parent tables first (FK order) - same order as SyncManager.fetchFromCloud()
    private static final String[] REQUIRED_TABLES = {
            "cash_books", "categories", "sub_categories", "column_definitions",
            "transactions", "transaction_custom_values", "transaction_audit_log",
            "transaction_receipts", "backup_history", "budgets", "budget_categories",
            "schedulers", "scheduler_log", "payment_types", "keyword_mappings",
            "events", "reminders", "event_reminders", "tasks", "task_events",
            "task_alarms", "budget_allocation_template", "recycle_bin", "deleted_records"
    };

    // These may not exist in the cloud DB yet - a failure must not abort the restore
    private static final String[] OPTIONAL_TABLES = {
            "settlement_links", "passbook_entries", "ai_chat_messages", "import_log", "card_mappings"
    };

    private static final int PAGE_SIZE = 500;
    private static final int PAGE_SIZE_BLOBS = 3; // receipts carry base64 file data

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ConsoleLogger LOG = ConsoleLogger.get();

    private CloudRestore() {
    }

    public interface Listener {
        void onProgress(int percent, String message);   // main thread

        void onDone(boolean ok, String summary);        // main thread
    }

    /**
     * Old entry point (SyncManager.restoreAllFromCloud): the percentage is shown inside the message text.
     */
    public static void restoreAll(Context ctx, SyncManager.SyncCallback cb) {
        restoreAll(ctx, new Listener() {
            @Override
            public void onProgress(int percent, String message) {
                progress(cb, percent + "% - " + message);
            }

            @Override
            public void onDone(boolean ok, String summary) {
                done(cb, ok, summary);
            }
        });
    }

    /**
     * Number of rows of one cloud table (used for the progress bar and the restore prompt). Background thread.
     */
    public static long cloudRowCount(Context ctx, String table) throws Exception {
        JSONObject res = CatalystClient.post(ctx, "/query", new JSONObject()
                .put("sql", "SELECT COUNT(*) FROM " + table).put("limit", 1).put("offset", 0));
        JSONArray rows = res.getJSONArray("rows");
        return rows.length() == 0 ? 0 : rows.getJSONArray(0).getLong(0);
    }

    public static void restoreAll(Context ctx, Listener l) {
        final Context app = ctx.getApplicationContext();
        EXECUTOR.execute(() -> {
            LOG.info("=== RESTORE VIA CATALYST STARTED ===");
            LOG.info("CloudRestore build v3 (FK debug)");
            LOG.info("=== RESTORE VIA CATALYST STARTED ===");
            final int nReq = REQUIRED_TABLES.length;
            final String[] all = new String[nReq + OPTIONAL_TABLES.length];
            System.arraycopy(REQUIRED_TABLES, 0, all, 0, nReq);
            System.arraycopy(OPTIONAL_TABLES, 0, all, nReq, OPTIONAL_TABLES.length);

            // Phase 1 (0-10%): count the cloud rows so the bar shows real progress
            long total = 0;
            try {
                for (int i = 0; i < all.length; i++) {
                    post(l, i * 10 / all.length, "Checking cloud data (" + (i + 1) + "/" + all.length + ")...");
                    try {
                        long cnt = cloudRowCount(app, all[i]);
                        LOG.info("cloud count: " + all[i] + " = " + cnt);
                        total += cnt;
                    } catch (Exception e) {
                        if (i < nReq)
                            throw e;   // required table: a real problem (login / network / DB)
                        LOG.warn(all[i] + " count skipped: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                LOG.error("Restore failed while counting: " + e.getMessage());
                finish(l, false, e.getMessage());
                return;
            }
            final Tracker tr = new Tracker(l, total);

            // Phase 2 (10-100%): download
            SQLiteDatabase local = LocalDatabase.get(app).getWritableDatabase();
            int restored = 0;
            local.beginTransaction();
            try {
                // FK is checked only at the end, so one bad row doesn't stop the loading
                local.execSQL("PRAGMA defer_foreign_keys = ON");
                for (int i = 0; i < all.length; i++) {
                    if (i < nReq) {
                        restored += pullTable(app, local, all[i], tr);
                    } else {
                        try {
                            restored += pullTable(app, local, all[i], tr);
                        } catch (Exception e) {
                            LOG.warn(all[i] + " skipped/failed: " + e.getMessage());
                        }
                    }
                }
                // list ALL orphan rows before commit
                int bad = logFkViolations(local);
                if (bad > 0)
                    throw new Exception(bad + " foreign key violations (see FK_DEBUG lines above)");
                local.setTransactionSuccessful();
            } catch (Exception e) {
                LOG.error("Restore failed, rolled back: " + e.getMessage());
                local.endTransaction();
                finish(l, false, e.getMessage());
                return;
            }
            local.endTransaction();

            post(l, 99, "Finishing...");

            // Restore the Strict TLS setting from the cloud app_config table (optional: table or row may not exist)
            try {
                JSONObject cfgRes = CatalystClient.post(app, "/query", new JSONObject()
                        .put("sql", "SELECT value FROM app_config WHERE key = 'db.strict.tls'")
                        .put("limit", 1).put("offset", 0));
                JSONArray cfgRows = cfgRes.getJSONArray("rows");
                if (cfgRows.length() > 0)
                    AppConfig.get(app).setStrictTls("true".equalsIgnoreCase(cfgRows.getJSONArray(0).getString(0)));
            } catch (Exception e) {
                LOG.warn("strict_tls restore skipped: " + e.getMessage());
            }

            try {
                JSONObject cfgRes = CatalystClient.post(app, "/query", new JSONObject()
                        .put("sql", "SELECT key, value FROM app_config WHERE key IN ('supabase.url','supabase.bucket')")
                        .put("limit", 200).put("offset", 0));
                JSONArray cfgRows = cfgRes.getJSONArray("rows");
                String sbUrl = null, sbBucket = null;
                for (int i = 0; i < cfgRows.length(); i++) {
                    JSONArray row = cfgRows.getJSONArray(i);
                    String k = row.getString(0);
                    if ("supabase.url".equals(k)) sbUrl = row.getString(1);
                    else if ("supabase.bucket".equals(k)) sbBucket = row.getString(1);
                }
                if (sbUrl != null) AppConfig.get(app).setSupabasePublic(sbUrl, sbBucket);
            } catch (Exception e) {
                LOG.warn("supabase config restore skipped: " + e.getMessage());
            }

            try {
                com.expenseos.db.LocalDB.getInstance(app).resyncSequences(
                        "cash_books", "categories", "sub_categories", "column_definitions",
                        "transactions", "transaction_custom_values", "deleted_records",
                        "transaction_audit_log", "transaction_receipts", "schedulers",
                        "scheduler_log", "budgets", "budget_categories", "payment_types",
                        "keyword_mappings", "events", "reminders", "event_reminders", "tasks",
                        "task_events", "task_alarms", "recycle_bin", "budget_allocation_template",
                        "settlement_links", "passbook_entries", "ai_chat_messages");
            } catch (Exception e) {
                LOG.warn("resyncSequences failed: " + e.getMessage());
            }

            String summary = "Restored " + restored + " rows across all tables";
            LOG.success("=== RESTORE DONE - " + summary + " ===");
            post(l, 100, "Done");
            finish(l, true, summary);
        });
    }

    private static final class Tracker {
        private final Listener l;
        private final long total;
        private long done;

        Tracker(Listener l, long total) {
            this.l = l;
            this.total = total;
        }

        void add(int rows, String table) {
            done += rows;
            int pct = 10 + (int) (89L * Math.min(done, total) / Math.max(1L, total));
            post(l, pct, "Restoring " + table + " (" + done + " / " + total + " rows)");
        }
    }

    private static void post(Listener l, int percent, String msg) {
        if (l != null) MAIN.post(() -> l.onProgress(Math.max(0, Math.min(100, percent)), msg));
    }

    private static void finish(Listener l, boolean ok, String summary) {
        if (l != null) MAIN.post(() -> l.onDone(ok, summary));
    }

    /**
     * Pages through "SELECT * FROM table" on the server and upserts into the local table.
     */
    private static int pullTable(Context app, SQLiteDatabase local, String table, Tracker tr) throws Exception {
        Set<String> localCols = localColumns(local, table);
        if (localCols.isEmpty()) {
            LOG.warn(table + ": no local table, skipped");
            return 0;
        }
        boolean hasSynced = localCols.contains("synced");
        int pageSize = table.equals("transaction_receipts") ? PAGE_SIZE_BLOBS : PAGE_SIZE;

        // transactions only: ids that really exist (sub_categories is already restored at this point)
        Set<Long> validSubIds = table.equals("transactions") ? loadIds(local, "sub_categories") : null;

        int n = 0;
        int offset = 0;
        boolean more = true;
        boolean warnedNoId = false;
        tr.add(0, table);   // show the table name straight away
        while (more) {
            // The server only allows this exact "SELECT ... FROM <table>" shape
            JSONObject body = new JSONObject()
                    .put("sql", "SELECT * FROM " + table)
                    .put("limit", pageSize)
                    .put("offset", offset);

            JSONObject res = postWithRetry(app, "/query", body, table + " offset=" + offset);

            JSONArray cols = res.getJSONArray("cols");
            JSONArray types = res.getJSONArray("types");
            JSONArray rows = res.getJSONArray("rows");

            for (int r = 0; r < rows.length(); r++) {
                JSONArray row = rows.getJSONArray(r);
                ContentValues cv = new ContentValues();
                for (int c = 0; c < cols.length(); c++) {
                    String col = localName(table, cols.getString(c));
                    if (!localCols.contains(col)) continue; // cloud-only column
                    put(cv, col, types.getString(c), row.get(c));
                }
                if (localCols.contains("id") && !cv.containsKey("id") && !warnedNoId) {
                    LOG.warn("ID_DEBUG --> " + table + ": cloud rows have no 'id' column, "
                            + "local ids will be auto-assigned (column name mismatch?)");
                    warnedNoId = true;
                }
                if (hasSynced && !cv.containsKey("synced")) cv.put("synced", 1);

                // repair: transaction points to a sub-category that doesn't exist in the cloud
                if (validSubIds != null && cv.get("sub_categories_id") != null) {
                    long sid = cv.getAsLong("sub_categories_id");
                    if (!validSubIds.contains(sid)) {
                        LOG.warn("REPAIR --> transaction " + cv.get("id") + ": sub_category "
                                + sid + " not found, set to NULL");
                        cv.putNull("sub_categories_id");
                    }
                }

                try {
                    local.insertWithOnConflict(table, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                } catch (Exception e) {
                    String data = String.valueOf(cv);
                    if (data.length() > 400) data = data.substring(0, 400) + "...";
                    LOG.error("FAILED_ROW --> table=" + table + " row#" + (offset + r)
                            + " error=" + e.getMessage());
                    LOG.error("FAILED_ROW --> data: " + data);
                    logMissingParents(local, table, cv);
                    throw e;
                }
                n++;
            }
            tr.add(rows.length(), table);
            more = res.optBoolean("more", false);
            offset += pageSize;
        }
        LOG.info(table + ": restored " + n);
        return n;
    }

    /**
     * Converts a JSON cell to the type the local SQLite column expects.
     */
    private static void put(ContentValues cv, String col, String pgType, Object v) {
        if (v == null || v == JSONObject.NULL) {
            cv.putNull(col);
            return;
        }
        switch (pgType) {
            case "bytea":
                cv.put(col, Base64.decode((String) v, Base64.DEFAULT));
                break;
            case "bool":
                cv.put(col, ((Boolean) v) ? 1 : 0);
                break;
            case "int2":
            case "int4":
            case "int8":
            case "serial":
            case "bigserial":
                cv.put(col, ((Number) v).longValue());
                break;
            case "float4":
            case "float8":
                cv.put(col, ((Number) v).doubleValue());
                break;
            default: {
                String s = String.valueOf(v);
                // timestamps -> "yyyy-MM-dd HH:mm:ss" (same as SyncManager.strTs)
                if (pgType.startsWith("timestamp") && s.length() > 19) s = s.substring(0, 19);
                cv.put(col, s);
            }
        }
    }

    /**
     * Cloud column name -> local column name, where the two schemas differ.
     */
    private static String localName(String table, String cloudCol) {
        if (table.equals("sub_categories") && cloudCol.equals("sub_categories_id")) return "id";
        return cloudCol;
    }

    private static Set<String> localColumns(SQLiteDatabase db, String table) {
        Set<String> cols = new HashSet<>();
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            int nameIdx = c.getColumnIndexOrThrow("name");
            while (c.moveToNext()) cols.add(c.getString(nameIdx));
        }
        return cols;
    }

    private static void progress(SyncManager.SyncCallback cb, String msg) {
        if (cb != null) MAIN.post(() -> cb.onProgress(msg));
    }

    private static void done(SyncManager.SyncCallback cb, boolean ok, String summary) {
        if (cb != null) MAIN.post(() -> cb.onComplete(ok, summary));
    }

    /**
     * Runs PRAGMA foreign_key_check and logs every orphan row. Returns the violation count.
     */
    private static int logFkViolations(SQLiteDatabase db) {
        int n = 0;
        try (Cursor c = db.rawQuery("PRAGMA foreign_key_check", null)) {
            while (c.moveToNext()) {
                n++;
                if (n <= 50) {   // don't flood the console
                    LOG.error("FK_DEBUG --> child table=[" + c.getString(0)
                            + "] id=[" + c.getLong(1)
                            + "] missing parent table=[" + c.getString(2) + "]");
                }
            }
        }
        if (n > 50) LOG.error("FK_DEBUG --> ... and " + (n - 50) + " more");
        LOG.info("FK_DEBUG --> total violations: " + n);
        return n;
    }

    /**
     * For a failed row: checks each FK column of that row and says which parent id is missing.
     */
    private static void logMissingParents(SQLiteDatabase db, String table, ContentValues cv) {
        try (Cursor c = db.rawQuery("PRAGMA foreign_key_list(" + table + ")", null)) {
            int iTable = c.getColumnIndexOrThrow("table");
            int iFrom = c.getColumnIndexOrThrow("from");
            int iTo = c.getColumnIndexOrThrow("to");
            while (c.moveToNext()) {
                String parent = c.getString(iTable);
                String from = c.getString(iFrom);
                String to = c.isNull(iTo) ? "id" : c.getString(iTo);
                Object val = cv.get(from);
                if (val == null) {
                    LOG.info("FK_DEBUG --> " + table + "." + from + " = NULL (ok)");
                    continue;
                }
                long cnt = DatabaseUtils.longForQuery(db,
                        "SELECT COUNT(*) FROM " + parent + " WHERE " + to + "=?",
                        new String[]{String.valueOf(val)});
                LOG.info("FK_DEBUG --> " + table + "." + from + " = " + val + " -> "
                        + parent + "." + to + (cnt > 0 ? " exists" : "  ❌ MISSING"));
            }
        } catch (Exception e) {
            LOG.warn("FK_DEBUG --> parent check failed: " + e.getMessage());
        }
    }

    /**
     * Same as CatalystClient.post, but retries a failed page (network drop / timeout). Safe because SELECT is idempotent.
     */
    private static JSONObject postWithRetry(Context app, String path, JSONObject body, String label) throws Exception {
        final int MAX = 4;
        Exception last = null;
        for (int attempt = 1; attempt <= MAX; attempt++) {
            try {
                long t0 = System.currentTimeMillis();
                JSONObject res = CatalystClient.post(app, path, body);
                long ms = System.currentTimeMillis() - t0;
                if (ms > 5000) LOG.info("SLOW --> " + label + " took " + ms + " ms");
                return res;
            } catch (Exception e) {
                last = e;
                LOG.warn("RETRY --> " + label + " attempt " + attempt + "/" + MAX
                        + " failed: " + e.getMessage());
                if (attempt < MAX) Thread.sleep(2000L * attempt);   // 2s, 4s, 6s
            }
        }
        throw last;
    }

    /**
     * All ids currently in a local table.
     */
    private static Set<Long> loadIds(SQLiteDatabase db, String table) {
        Set<Long> ids = new HashSet<>();
        try (Cursor c = db.rawQuery("SELECT id FROM " + table, null)) {
            while (c.moveToNext()) ids.add(c.getLong(0));
        }
        return ids;
    }

}