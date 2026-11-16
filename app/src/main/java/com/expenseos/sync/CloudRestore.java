package com.expenseos.sync;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.expenseos.dao.LocalDatabase;
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
    private static final int PAGE_SIZE_BLOBS = 25; // receipts carry base64 file data

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ConsoleLogger LOG = ConsoleLogger.get();

    private CloudRestore() {
    }

    public static void restoreAll(Context ctx, SyncManager.SyncCallback cb) {
        final Context app = ctx.getApplicationContext();
        EXECUTOR.execute(() -> {
            LOG.info("=== RESTORE VIA CATALYST STARTED ===");
            SQLiteDatabase local = LocalDatabase.get(app).getWritableDatabase();
            int total = 0;
            local.beginTransaction();
            try {
                for (String t : REQUIRED_TABLES) {
                    progress(cb, "Restoring " + t + "...");
                    total += pullTable(app, local, t);
                }
                for (String t : OPTIONAL_TABLES) {
                    progress(cb, "Restoring " + t + "...");
                    try {
                        total += pullTable(app, local, t);
                    } catch (Exception e) {
                        LOG.warn(t + " skipped/failed: " + e.getMessage());
                    }
                }
                local.setTransactionSuccessful();
            } catch (Exception e) {
                LOG.error("Restore failed, rolled back: " + e.getMessage());
                local.endTransaction();
                done(cb, false, e.getMessage());
                return;
            }
            local.endTransaction();

            // Keep local autoincrement sequences ahead of the restored ids
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

            String summary = "Restored " + total + " rows across all tables";
            LOG.success("=== RESTORE DONE - " + summary + " ===");
            done(cb, true, summary);
        });
    }

    /**
     * Pages through "SELECT * FROM table" on the server and upserts into the local table.
     */
    private static int pullTable(Context app, SQLiteDatabase local, String table) throws Exception {
        Set<String> localCols = localColumns(local, table);
        if (localCols.isEmpty()) {
            LOG.warn(table + ": no local table, skipped");
            return 0;
        }
        boolean hasSynced = localCols.contains("synced");
        int pageSize = table.equals("transaction_receipts") ? PAGE_SIZE_BLOBS : PAGE_SIZE;

        int n = 0;
        int offset = 0;
        boolean more = true;
        while (more) {
            // The server only allows this exact "SELECT ... FROM <table>" shape
            JSONObject body = new JSONObject()
                    .put("sql", "SELECT * FROM " + table)
                    .put("limit", pageSize)
                    .put("offset", offset);
            JSONObject res = CatalystClient.post(app, "/query", body);

            JSONArray cols = res.getJSONArray("cols");
            JSONArray types = res.getJSONArray("types");
            JSONArray rows = res.getJSONArray("rows");

            for (int r = 0; r < rows.length(); r++) {
                JSONArray row = rows.getJSONArray(r);
                ContentValues cv = new ContentValues();
                for (int c = 0; c < cols.length(); c++) {
                    String col = cols.getString(c);
                    if (!localCols.contains(col)) continue; // cloud-only column
                    put(cv, col, types.getString(c), row.get(c));
                }
                if (hasSynced && !cv.containsKey("synced")) cv.put("synced", 1);
                local.insertWithOnConflict(table, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                n++;
            }
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
}