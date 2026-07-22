package com.expenseos.sync;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import com.expenseos.dao.LocalDatabase;
import com.expenseos.util.ConsoleLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Creates ZIP backups (JSON + receipt BLOBs), saves LOCAL or uploads to Zoho
 * WorkDrive (CLOUD), and restores from either source.
 */
public class BackupManager {

    public enum BackupMode { LOCAL, CLOUD }

    public interface BackupCallback {
        void onComplete(boolean success, String message, File file);
    }

    private static BackupManager instance;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ConsoleLogger log = ConsoleLogger.get();

    public static BackupManager get() {
        if (instance == null) instance = new BackupManager();
        return instance;
    }

    // ── CREATE BACKUP ─────────────────────────────────────────────────────────
    public void createBackup(Context ctx, String description, BackupMode mode, BackupCallback cb) {
        executor.execute(() -> {
            log.info("Creating backup (mode=" + mode + ")...");
            try {
                SQLiteDatabase db = LocalDatabase.get(ctx).getReadableDatabase();

                JSONObject backup = new JSONObject();
                backup.put("version", 2);
                backup.put("created_at", new SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
                backup.put("description", description != null ? description : "");
                backup.put("transactions", tableToJson(db, "transactions"));
                backup.put("categories", tableToJson(db, "categories"));
                backup.put("sub_categories", tableToJson(db, "sub_categories"));
                backup.put("cash_books", tableToJson(db, "cash_books"));
                backup.put("transaction_receipts", receiptsMetaToJson(db)); // metadata only, no BLOB

                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                String fileName = "backup_" + ts + ".zip";
                File tempZip = new File(ctx.getCacheDir(), fileName);

                try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempZip))) {
                    zos.putNextEntry(new ZipEntry("backup_data.json"));
                    zos.write(backup.toString(2).getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                    writeReceiptsToZip(db, zos);
                }

                long size = tempZip.length();
                int incomeCount = countRows(db, "transactions", "type='INCOME'");
                int expenseCount = countRows(db, "transactions", "type='EXPENSE'");

                String finalPath;
                String externalId = null;

                if (mode == BackupMode.CLOUD) {
                    WorkDriveApiService wds = new WorkDriveApiService(ctx);
                    externalId = wds.uploadFile(tempZip);
                    if (externalId == null || externalId.isEmpty())
                        throw new Exception("Cloud upload failed — no resource id returned");
                    tempZip.delete(); // no local copy kept for CLOUD backups
                    finalPath = "";
                } else {
                    File destFile = new File(getBackupDir(ctx), fileName);
                    copyFile(tempZip, destFile);
                    tempZip.delete();
                    finalPath = destFile.getAbsolutePath();
                }

                logBackupHistory(ctx, fileName, finalPath, size, "MANUAL", mode.name(),
                        "SUCCESS", description, incomeCount, expenseCount, externalId);

                log.success("Backup created (" + mode + "): " + fileName);
                File resultFile = mode == BackupMode.LOCAL ? new File(finalPath) : null;
                String msg = mode == BackupMode.CLOUD ? "Uploaded to cloud: " + fileName : "Backup saved: " + fileName;
                mainHandler.post(() -> cb.onComplete(true, msg, resultFile));

            } catch (Exception e) {
                log.error("Backup failed: " + e.getMessage());
                mainHandler.post(() -> cb.onComplete(false, e.getMessage(), null));
            }
        });
    }

    // ── RESTORE — from a backup_history entry (LOCAL file or CLOUD download) ──
    public void restoreBackup(Context ctx, BackupEntry entry, BackupCallback cb) {
        executor.execute(() -> {
            log.info("Restoring backup #" + entry.id + " (" + entry.backupMode + ")");
            try {
                File zipFile;
                if ("CLOUD".equals(entry.backupMode)) {
                    if (entry.externalId == null || entry.externalId.isEmpty())
                        throw new Exception("No cloud reference stored for this backup");
                    WorkDriveApiService wds = new WorkDriveApiService(ctx);
                    byte[] bytes = wds.downloadFile(entry.externalId);
                    zipFile = new File(ctx.getCacheDir(), "restore_cloud_" + entry.id + ".zip");
                    try (FileOutputStream fos = new FileOutputStream(zipFile)) { fos.write(bytes); }
                } else {
                    zipFile = new File(entry.filePath);
                    if (!zipFile.exists()) throw new Exception("Local backup file missing: " + entry.filePath);
                }
                doRestore(ctx, zipFile);
                log.success("Restore complete: backup #" + entry.id);
                mainHandler.post(() -> cb.onComplete(true, "Restore successful!", null));
            } catch (Exception e) {
                log.error("Restore failed: " + e.getMessage());
                mainHandler.post(() -> cb.onComplete(false, e.getMessage(), null));
            }
        });
    }

    // ── RESTORE — from a manually picked ZIP file (↑ Restore ZIP button) ──────
    public void restoreBackup(Context ctx, File zipFile, BackupCallback cb) {
        executor.execute(() -> {
            log.info("Restoring backup from picked file: " + zipFile.getName());
            try {
                doRestore(ctx, zipFile);
                mainHandler.post(() -> cb.onComplete(true, "Restore successful!", null));
            } catch (Exception e) {
                log.error("Restore failed: " + e.getMessage());
                mainHandler.post(() -> cb.onComplete(false, e.getMessage(), null));
            }
        });
    }

    private void doRestore(Context ctx, File zipFile) throws Exception {
        SQLiteDatabase db = LocalDatabase.get(ctx).getWritableDatabase();
        JSONObject backup = null;
        Map<String, byte[]> receiptBytesByEntry = new HashMap<>();

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int len;
                while ((len = zis.read(buf)) > 0) bos.write(buf, 0, len);

                if (entry.getName().equals("backup_data.json")) {
                    backup = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
                } else if (entry.getName().startsWith("Receipts/")) {
                    receiptBytesByEntry.put(entry.getName().substring("Receipts/".length()), bos.toByteArray());
                }
            }
        }
        if (backup == null) throw new Exception("Invalid backup ZIP — backup_data.json missing");

        // FK checks OFF — must be set BEFORE beginTransaction(), SQLite doesn't
        // allow toggling this pragma mid-transaction.
        db.execSQL("PRAGMA foreign_keys = OFF");
        db.beginTransaction();
        try {
            // Parents first, then children — but with FK off this order no longer
            // strictly matters; keeping it logical anyway for clarity.
            restoreTable(db, "cash_books", backup.optJSONArray("cash_books"));
            restoreTable(db, "categories", backup.optJSONArray("categories"));
            restoreTable(db, "sub_categories", backup.optJSONArray("sub_categories"));
            restoreTable(db, "transactions", backup.optJSONArray("transactions"));
            restoreTable(db, "transaction_receipts", backup.optJSONArray("transaction_receipts"));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            db.execSQL("PRAGMA foreign_keys = ON");   // restore FK enforcement for normal app use
        }

        // second pass — fill in receipt BLOBs from the zip's Receipts/ entries
        for (Map.Entry<String, byte[]> e : receiptBytesByEntry.entrySet()) {
            String entryName = e.getKey(); // "{id}_{file_name}"
            int us = entryName.indexOf('_');
            if (us <= 0) continue;
            try {
                int receiptId = Integer.parseInt(entryName.substring(0, us));
                ContentValues cv = new ContentValues();
                cv.put("file_data", e.getValue());
                db.update("transaction_receipts", cv, "id=?", new String[]{String.valueOf(receiptId)});
            } catch (NumberFormatException ignored) { }
        }
    }

    // ── LIST LOCAL BACKUP HISTORY (both LOCAL + CLOUD entries recorded here) ──
    public List<BackupEntry> listBackups(Context ctx) {
        List<BackupEntry> list = new ArrayList<>();
        Cursor c = LocalDatabase.get(ctx).getReadableDatabase()
                .rawQuery("SELECT * FROM backup_history ORDER BY id DESC", null);
        while (c.moveToNext()) {
            BackupEntry e = new BackupEntry();
            e.id = c.getInt(c.getColumnIndexOrThrow("id"));
            e.fileName = c.getString(c.getColumnIndexOrThrow("file_name"));
            e.filePath = c.getString(c.getColumnIndexOrThrow("file_path"));
            e.fileSizeBytes = c.getLong(c.getColumnIndexOrThrow("file_size_bytes"));
            e.backupType = c.getString(c.getColumnIndexOrThrow("backup_type"));
            e.backupMode = c.getString(c.getColumnIndexOrThrow("backup_mode"));
            e.status = c.getString(c.getColumnIndexOrThrow("status"));
            e.description = c.getString(c.getColumnIndexOrThrow("description"));
            e.incomeCount = c.getInt(c.getColumnIndexOrThrow("income_count"));
            e.expenseCount = c.getInt(c.getColumnIndexOrThrow("expense_count"));
            e.createdAt = c.getString(c.getColumnIndexOrThrow("created_at"));
            int extIdx = c.getColumnIndexOrThrow("external_id");
            e.externalId = c.isNull(extIdx) ? null : c.getString(extIdx);
            list.add(e);
        }
        c.close();
        return list;
    }

    public static class BackupEntry {
        public int id;
        public String fileName, filePath, backupType, backupMode, status, description, createdAt, externalId;
        public long fileSizeBytes;
        public int incomeCount, expenseCount;

        public String getSizeFormatted() {
            if (fileSizeBytes < 1024) return fileSizeBytes + " B";
            return (fileSizeBytes / 1024) + " KB";
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private JSONArray tableToJson(SQLiteDatabase db, String table) throws Exception {
        JSONArray arr = new JSONArray();
        try (Cursor c = db.rawQuery("SELECT * FROM " + table, null)) {
            String[] cols = c.getColumnNames();
            while (c.moveToNext()) {
                JSONObject row = new JSONObject();
                for (String col : cols) {
                    int idx = c.getColumnIndex(col);
                    switch (c.getType(idx)) {
                        case Cursor.FIELD_TYPE_INTEGER -> row.put(col, c.getLong(idx));
                        case Cursor.FIELD_TYPE_FLOAT -> row.put(col, c.getDouble(idx));
                        case Cursor.FIELD_TYPE_NULL -> row.put(col, JSONObject.NULL);
                        default -> row.put(col, c.getString(idx));
                    }
                }
                arr.put(row);
            }
        }
        return arr;
    }

    /** Same as transaction_receipts row but WITHOUT the file_data BLOB (kept as separate zip entries). */
    private JSONArray receiptsMetaToJson(SQLiteDatabase db) throws Exception {
        JSONArray arr = new JSONArray();
        try (Cursor c = db.rawQuery(
                "SELECT id, transaction_id, file_name, file_type, file_size, uploaded_at FROM transaction_receipts", null)) {
            while (c.moveToNext()) {
                JSONObject row = new JSONObject();
                row.put("id", c.getInt(0));
                row.put("transaction_id", c.getInt(1));
                row.put("file_name", c.getString(2));
                row.put("file_type", c.isNull(3) ? JSONObject.NULL : c.getString(3));
                row.put("file_size", c.isNull(4) ? JSONObject.NULL : c.getLong(4));
                row.put("uploaded_at", c.isNull(5) ? JSONObject.NULL : c.getString(5));
                arr.put(row);
            }
        }
        return arr;
    }

    private void writeReceiptsToZip(SQLiteDatabase db, ZipOutputStream zos) throws Exception {
        try (Cursor c = db.rawQuery(
                "SELECT id, file_name, file_data FROM transaction_receipts WHERE file_data IS NOT NULL", null)) {
            while (c.moveToNext()) {
                int id = c.getInt(0);
                String fname = c.getString(1);
                byte[] data = c.getBlob(2);
                if (data == null || data.length == 0) continue;
                zos.putNextEntry(new ZipEntry("Receipts/" + id + "_" + fname));
                zos.write(data);
                zos.closeEntry();
            }
        }
    }

    private void restoreTable(SQLiteDatabase db, String table, JSONArray arr) throws Exception {
        if (arr == null) return;
        db.delete(table, null, null);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject row = arr.getJSONObject(i);
            ContentValues cv = new ContentValues();
            JSONArray names = row.names();
            if (names != null) {
                for (int j = 0; j < names.length(); j++) {
                    String key = names.getString(j);
                    Object val = row.get(key);
                    if (val == JSONObject.NULL) cv.putNull(key);
                    else if (val instanceof Integer) cv.put(key, (Integer) val);
                    else if (val instanceof Long) cv.put(key, (Long) val);
                    else if (val instanceof Double) cv.put(key, (Double) val);
                    else cv.put(key, val.toString());
                }
            }
            db.insertWithOnConflict(table, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    private void copyFile(File src, File dst) throws IOException {
        try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        }
    }

    private File getBackupDir(Context ctx) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS), "ExpenseOS/Backups");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void logBackupHistory(Context ctx, String fileName, String path, long size,
                                  String type, String mode, String status, String desc,
                                  int income, int expense, String externalId) {
        ContentValues cv = new ContentValues();
        cv.put("file_name", fileName);
        cv.put("file_path", path);
        cv.put("file_size_bytes", size);
        cv.put("backup_type", type);
        cv.put("backup_mode", mode);
        cv.put("status", status);
        cv.put("description", desc != null ? desc : "");
        cv.put("income_count", income);
        cv.put("expense_count", expense);
        if (externalId != null) cv.put("external_id", externalId);
        cv.put("created_at", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
        LocalDatabase.get(ctx).getWritableDatabase().insert("backup_history", null, cv);
    }

    private int countRows(SQLiteDatabase db, String table, String where) {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + table + " WHERE " + where, null);
        int count = 0;
        if (c.moveToFirst()) count = c.getInt(0);
        c.close();
        return count;
    }

    public void deleteBackup(Context ctx, BackupEntry entry, BackupCallback cb) {
        executor.execute(() -> {
            try {
                if ("CLOUD".equals(entry.backupMode) && entry.externalId != null && !entry.externalId.isEmpty()) {
                    new WorkDriveApiService(ctx).deleteFile(entry.externalId);
                } else if (entry.filePath != null && !entry.filePath.isEmpty()) {
                    new File(entry.filePath).delete();
                }
                LocalDatabase.get(ctx).getWritableDatabase()
                        .delete("backup_history", "id=?", new String[]{String.valueOf(entry.id)});
                mainHandler.post(() -> cb.onComplete(true, "Backup deleted", null));
            } catch (Exception e) {
                mainHandler.post(() -> cb.onComplete(false, e.getMessage(), null));
            }
        });
    }

    public void createBackupScheduled(Context ctx) {
        createBackup(ctx, "Scheduled daily backup", BackupMode.LOCAL, (ok, msg, file) -> { /* silent, no UI */ });
    }

    public void downloadForShare(Context ctx, BackupEntry entry, BackupCallback cb) {
        executor.execute(() -> {
            try {
                File file;
                if ("CLOUD".equals(entry.backupMode)) {
                    if (entry.externalId == null || entry.externalId.isEmpty())
                        throw new Exception("No cloud reference for this backup");
                    byte[] bytes = new WorkDriveApiService(ctx).downloadFile(entry.externalId);
                    file = new File(ctx.getCacheDir(), entry.fileName);
                    try (FileOutputStream fos = new FileOutputStream(file)) { fos.write(bytes); }
                } else {
                    file = new File(entry.filePath);
                    if (!file.exists()) throw new Exception("Local file missing: " + entry.filePath);
                }
                mainHandler.post(() -> cb.onComplete(true, "Ready", file));
            } catch (Exception e) {
                mainHandler.post(() -> cb.onComplete(false, e.getMessage(), null));
            }
        });
    }
}