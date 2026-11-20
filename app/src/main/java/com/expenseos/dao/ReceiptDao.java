package com.expenseos.dao;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.expenseos.cloud.FileCrypto;
import com.expenseos.cloud.SupabaseStorage;
import com.expenseos.db.LocalDB;
import com.expenseos.model.Receipt;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Android/SQLite port of the web ReceiptDAO.
 *
 * <p>Attachment bytes live in Supabase Storage now (encrypted with {@link FileCrypto}); the local
 * row only keeps metadata plus {@code storage_path}. The file_data BLOB column stays for rows that
 * have not been migrated yet, so old data keeps working until the migration runs.
 */
public class ReceiptDao {

    private static final String TAG = "ReceiptDao";
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Context ctx;
    private final LocalDB helper;
    private final SQLiteDatabase db;
    private final AuditLogDao auditDao;

    public ReceiptDao(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        helper = LocalDB.getInstance(ctx);
        db = helper.getWritableDatabase();
        auditDao = new AuditLogDao(ctx);
    }

    /**
     * The only place that uploads: always encrypts first, so Supabase never sees plaintext.
     */
    private void putEncrypted(String path, byte[] plain) throws IOException {
        SupabaseStorage.upload(ctx, path, "application/octet-stream", FileCrypto.encrypt(ctx, plain, path));
    }

    /** Uploads to the cloud first; the row is only saved once that succeeded. */
    public void insert(Receipt r) throws IOException {
        long id = helper.getNextId("transaction_receipts");
        String path = SupabaseStorage.pathFor(r.getTransactionId(), id);
        putEncrypted(path, r.getFileData()); // encrypt, then upload; the row is saved only after this succeeds

        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("transaction_id", r.getTransactionId());
        cv.put("file_name", r.getFileName());
        cv.put("file_type", r.getFileType());
        cv.put("file_size", r.getFileSize());
        cv.put("storage_path", path);
        cv.put("uploaded_at", LocalDateTime.now().format(TS_FMT));
        db.insert("transaction_receipts", null, cv);
        Log.i(TAG, "File uploaded to cloud: " + path);
        auditDao.logReceiptUpload(r.getTransactionId(), "user", r.getFileName());
    }

    /** Overwrites the bytes of an existing attachment in the cloud. */
    public void uploadReceipt(Receipt r) throws IOException {
        String path = r.getStoragePath() != null ? r.getStoragePath()
                : SupabaseStorage.pathFor(r.getTransactionId(), r.getId());
        putEncrypted(path, r.getFileData());
        ContentValues cv = new ContentValues();
        cv.put("storage_path", path);
        cv.putNull("file_data");
        cv.put("file_size", r.getFileData().length);
        cv.put("updated_at", LocalDateTime.now().format(TS_FMT));
        db.update("transaction_receipts", cv, "id = ?", new String[]{String.valueOf(r.getId())});
        new File(ctx.getCacheDir(), "receipt_cache/" + r.getId()).delete();
    }

    /** Metadata only (no bytes) — call loadFileData() when the bytes are actually needed. */
    public List<Receipt> findByTransactionId(int txnId) {
        return findMetaByTransactionId(txnId);
    }

    /**
     * Metadata only (no file_data) for listing
     */
    public List<Receipt> findMetaByTransactionId(int txnId) {
        String sql = "SELECT id, transaction_id, file_name, file_type, file_size, uploaded_at, storage_path "
                + "FROM transaction_receipts WHERE transaction_id = ? ORDER BY uploaded_at DESC";
        List<Receipt> list = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(txnId)})) {
            while (c.moveToNext())
                list.add(mapMeta(c));
        }
        return list;
    }

    public Receipt findMetaById(int id) {
        String sql = "SELECT id, transaction_id, file_name, file_type, file_size, uploaded_at, storage_path "
                + "FROM transaction_receipts WHERE id = ?";
        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? mapMeta(c) : null;
        }
    }

    /**
     * Metadata + bytes. Uses the network, so call it from a background thread only.
     * fileData stays null on failure.
     */
    public Receipt findById(int id) {
        Receipt r = findMetaById(id);
        if (r == null) return null;
        try {
            r.setFileData(loadFileData(r));
        } catch (IOException e) {
            Log.w(TAG, "loadFileData failed: " + e.getMessage());
        }
        return r;
    }

    /**
     * Cache -> Supabase. NETWORK. Throws NotSignedInException if login is needed.
     * The cache file holds ciphertext exactly as downloaded.
     */
    public byte[] loadFileData(Receipt r) throws IOException {
        if (r.getStoragePath() == null) return migrateOne(r); // old un-migrated row
        File f = new File(ctx.getCacheDir(), "receipt_cache/" + r.getId());
        byte[] enc;
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) {
                enc = SupabaseStorage.readAll(in);
            }
        } else {
            enc = SupabaseStorage.download(ctx, r.getStoragePath());
            f.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(enc); // the cache holds ciphertext only
            }
            trimCache();
        }
        try {
            return FileCrypto.decrypt(ctx, enc, r.getStoragePath());
        } catch (IOException e) {
            f.delete(); // drop a possibly corrupted cache entry
            throw e;
        }
    }

    /** Keeps the ciphertext cache under 100 MB (LRU by lastModified). */
    private void trimCache() {
        File[] files = new File(ctx.getCacheDir(), "receipt_cache").listFiles();
        if (files == null) return;
        final long MAX = 100L * 1024 * 1024;
        long total = 0;
        for (File x : files) total += x.length();
        if (total <= MAX) return;
        java.util.Arrays.sort(files, java.util.Comparator.comparingLong(File::lastModified));
        for (File x : files) {
            if (total <= MAX * 8 / 10) break;
            total -= x.length();
            x.delete();
        }
    }

    // ── one-time migration: local BLOB -> Supabase ────────────

    public int migrateLocalBlobsToCloud() {
        List<Receipt> todo = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT id, transaction_id, file_type FROM transaction_receipts "
                + "WHERE storage_path IS NULL AND file_data IS NOT NULL", null)) {
            while (c.moveToNext()) {
                Receipt r = new Receipt();
                r.setId(c.getInt(0));
                r.setTransactionId(c.getInt(1));
                r.setFileType(c.getString(2));
                todo.add(r);
            }
        }
        int n = 0;
        for (Receipt r : todo) {
            try {
                migrateOne(r);
                n++;
            } catch (IOException e) {
                Log.w(TAG, "migrate " + r.getId() + " failed: " + e.getMessage());
            }
        }
        return n;
    }

    /** How many rows still hold local bytes only (progress reporting). */
    public int countPendingLocalBlobs() {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM transaction_receipts "
                + "WHERE storage_path IS NULL AND file_data IS NOT NULL", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    private byte[] migrateOne(Receipt r) throws IOException {
        byte[] data = null;
        try (Cursor c = rawQueryBigBlobWindow("SELECT file_data FROM transaction_receipts WHERE id=?",
                new String[]{String.valueOf(r.getId())})) {
            if (c.moveToFirst()) data = c.getBlob(0);
        }
        if (data == null) throw new IOException("Attachment data missing locally");
        String path = SupabaseStorage.pathFor(r.getTransactionId(), r.getId());
        putEncrypted(path, data);
        ContentValues cv = new ContentValues();
        cv.put("storage_path", path);
        cv.putNull("file_data");
        cv.put("updated_at", LocalDateTime.now().format(TS_FMT)); // so the next cloud sync pushes the new storage_path
        db.update("transaction_receipts", cv, "id = ?", new String[]{String.valueOf(r.getId())});
        r.setStoragePath(path);
        return data;
    }

    /**
     * Regular Cursor.rawQuery() uses a default ~2MB CursorWindow per row —
     * a single large receipt (photo/PDF stored as BLOB) can exceed that and
     * throw SQLiteBlobTooBigException. Installing a bigger CursorWindow
     * BEFORE the cursor's first access (getCount/moveToFirst) makes it use
     * our window instead of the default one. Only needed for queries that
     * select file_data — metadata-only queries never hit this.
     */
    private Cursor rawQueryBigBlobWindow(String sql, String[] args) {
        Cursor c = db.rawQuery(sql, args);
        if (c instanceof android.database.AbstractWindowedCursor) {
            ((android.database.AbstractWindowedCursor) c).setWindow(
                    new android.database.CursorWindow("receipt_blob_window", 50L * 1024 * 1024)); // 50MB
        }
        return c;
    }

    public void delete(int id, String fileName) {
        // Soft delete: the recycle-bin snapshot keeps the cloud path so restore can re-link it.
        try (Cursor c = rawQueryBigBlobWindow(
                "SELECT r.transaction_id, r.file_name, r.file_type, r.file_data, r.file_size, t.book_id, r.storage_path " +
                        "FROM transaction_receipts r JOIN transactions t ON t.id = r.transaction_id WHERE r.id=?",
                new String[]{String.valueOf(id)})) {
            if (c.moveToFirst()) {
                Integer receiptBookId = c.isNull(5) ? null : c.getInt(5);
                org.json.JSONObject row = new org.json.JSONObject();
                try {
                    row.put("transaction_id", c.getInt(0));
                    row.put("file_name", c.getString(1));
                    row.put("file_type", c.getString(2));
                    byte[] blob = c.getBlob(3);
                    row.put("file_data", blob != null
                            ? android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP)
                            : org.json.JSONObject.NULL);
                    row.put("file_size", c.getInt(4));
                    row.put("storage_path", c.isNull(6) ? org.json.JSONObject.NULL : c.getString(6));
                } catch (org.json.JSONException ignored) {
                }
                new RecycleBinDao(ctx).put("transaction_receipts", id, receiptBookId, row);
            }
        }
        auditDao.logReceiptDelete(id, "user", fileName);
        db.delete("transaction_receipts", "id = ?", new String[]{String.valueOf(id)});
        new File(ctx.getCacheDir(), "receipt_cache/" + id).delete();
    }

    // Overload for callers that already have the transaction id handy —
    // same behavior; the row lookup by id alone already gives us
    // everything the recycle-bin snapshot needs.
    public void delete(int id, int transactionId, String fileName) {
        delete(id, fileName);
    }

    private Receipt mapMeta(Cursor c) {
        Receipt r = new Receipt();
        r.setId(c.getInt(c.getColumnIndexOrThrow("id")));
        r.setTransactionId(c.getInt(c.getColumnIndexOrThrow("transaction_id")));
        r.setFileName(c.getString(c.getColumnIndexOrThrow("file_name")));
        r.setFileType(c.getString(c.getColumnIndexOrThrow("file_type")));
        r.setFileSize(c.getInt(c.getColumnIndexOrThrow("file_size")));
        r.setStoragePath(c.getString(c.getColumnIndexOrThrow("storage_path")));
        String ts = c.getString(c.getColumnIndexOrThrow("uploaded_at"));
        if (ts != null)
            r.setUploadedAt(LocalDateTime.parse(ts, TS_FMT));
        return r;
    }
}
