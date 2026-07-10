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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Creates local ZIP backups (JSON-based) and restores them.
 * Mirrors the web app's backup/restore functionality.
 */
public class BackupManager {

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
    public void createBackup(Context ctx, String description, BackupCallback cb) {
        executor.execute(() -> {
            log.info("Creating local backup...");
            try {
                SQLiteDatabase db = LocalDatabase.get(ctx).getReadableDatabase();

                // Build JSON for each table
                JSONObject backup = new JSONObject();
                backup.put("version", 1);
                backup.put("created_at", new SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
                backup.put("description", description != null ? description : "");
                backup.put("transactions", tableToJson(db, "transactions"));
                backup.put("categories", tableToJson(db, "categories"));
                backup.put("sub_categories", tableToJson(db, "sub_categories"));
                backup.put("cash_books", tableToJson(db, "cash_books"));

                // Write JSON to temp file
                File cacheDir = ctx.getCacheDir();
                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                        .format(new Date());
                String fileName = "backup_" + ts + ".zip";

                File jsonFile = new File(cacheDir, "backup_data.json");
                try (FileWriter fw = new FileWriter(jsonFile)) {
                    fw.write(backup.toString(2));
                }

                // Zip it
                File downloadsDir = getBackupDir(ctx);
                File zipFile = new File(downloadsDir, fileName);
                zipFile(jsonFile, zipFile);
                jsonFile.delete();

                // Log to backup_history
                long size = zipFile.length();
                logBackupHistory(ctx, fileName, zipFile.getAbsolutePath(), size,
                        "MANUAL", "SUCCESS", description,
                        countRows(db, "transactions", "type='INCOME'"),
                        countRows(db, "transactions", "type='EXPENSE'"));

                log.success("Backup created: " + fileName + " (" + size / 1024 + " KB)");
                mainHandler.post(() -> cb.onComplete(true, "Backup saved: " + fileName, zipFile));

            } catch (Exception e) {
                log.error("Backup failed: " + e.getMessage());
                mainHandler.post(() -> cb.onComplete(false, e.getMessage(), null));
            }
        });
    }

    // ── RESTORE BACKUP ────────────────────────────────────────────────────────
    public void restoreBackup(Context ctx, File zipFile, BackupCallback cb) {
        executor.execute(() -> {
            log.info("Restoring backup: " + zipFile.getName());
            try {
                // Unzip
                File cacheDir = ctx.getCacheDir();
                File jsonFile = new File(cacheDir, "restore_data.json");
                unzipFile(zipFile, jsonFile);

                // Parse JSON
                byte[] bytes = new byte[(int) jsonFile.length()];
                try (FileInputStream fis = new FileInputStream(jsonFile)) {
                    fis.read(bytes);
                }
                JSONObject backup = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                jsonFile.delete();

                SQLiteDatabase db = LocalDatabase.get(ctx).getWritableDatabase();
                db.beginTransaction();
                try {
                    restoreTable(db, "transactions", backup.optJSONArray("transactions"));
                    restoreTable(db, "categories", backup.optJSONArray("categories"));
                    restoreTable(db, "sub_categories", backup.optJSONArray("sub_categories"));
                    restoreTable(db, "cash_books", backup.optJSONArray("cash_books"));
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }

                log.success("Restore complete from: " + zipFile.getName());
                mainHandler.post(() -> cb.onComplete(true, "Restore successful!", null));

            } catch (Exception e) {
                log.error("Restore failed: " + e.getMessage());
                mainHandler.post(() -> cb.onComplete(false, e.getMessage(), null));
            }
        });
    }

    // ── LIST LOCAL BACKUPS ────────────────────────────────────────────────────
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
            e.status = c.getString(c.getColumnIndexOrThrow("status"));
            e.description = c.getString(c.getColumnIndexOrThrow("description"));
            e.incomeCount = c.getInt(c.getColumnIndexOrThrow("income_count"));
            e.expenseCount = c.getInt(c.getColumnIndexOrThrow("expense_count"));
            e.createdAt = c.getString(c.getColumnIndexOrThrow("created_at"));
            list.add(e);
        }
        c.close();
        return list;
    }

    public static class BackupEntry {
        public int id;
        public String fileName, filePath, backupType, status, description, createdAt;
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

    private void zipFile(File source, File dest) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(dest));
             FileInputStream fis = new FileInputStream(source)) {
            zos.putNextEntry(new ZipEntry("backup_data.json"));
            byte[] buf = new byte[4096];
            int len;
            while ((len = fis.read(buf)) > 0) zos.write(buf, 0, len);
            zos.closeEntry();
        }
    }

    private void unzipFile(File zipFile, File destFile) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().endsWith(".json")) {
                    try (FileOutputStream fos = new FileOutputStream(destFile)) {
                        byte[] buf = new byte[4096];
                        int len;
                        while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                    }
                    break;
                }
            }
        }
    }

    private File getBackupDir(Context ctx) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS), "ExpenseOS/Backups");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void logBackupHistory(Context ctx, String fileName, String path, long size,
                                  String type, String status, String desc,
                                  int income, int expense) {
        ContentValues cv = new ContentValues();
        cv.put("file_name", fileName);
        cv.put("file_path", path);
        cv.put("file_size_bytes", size);
        cv.put("backup_type", type);
        cv.put("status", status);
        cv.put("description", desc != null ? desc : "");
        cv.put("income_count", income);
        cv.put("expense_count", expense);
        cv.put("created_at", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()).format(new Date()));
        LocalDatabase.get(ctx).getWritableDatabase()
                .insert("backup_history", null, cv);
    }

    private int countRows(SQLiteDatabase db, String table, String where) {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + table + " WHERE " + where, null);
        int count = 0;
        if (c.moveToFirst()) count = c.getInt(0);
        c.close();
        return count;
    }
}
