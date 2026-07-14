package com.expenseos.dao;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.expenseos.db.LocalDB;
import com.expenseos.model.Receipt;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Android/SQLite port of the web ReceiptDAO.
 * file_data is stored as a BLOB column (transaction_receipts.file_data).
 */
public class ReceiptDao {

    private static final String TAG = "ReceiptDao";
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SQLiteDatabase db;
    private final AuditLogDao auditDao;

    public ReceiptDao(Context ctx) {
        db = LocalDB.getInstance(ctx).getWritableDatabase();
        auditDao = new AuditLogDao(ctx);
    }

    public void insert(Receipt r) {
        ContentValues cv = new ContentValues();
        cv.put("transaction_id", r.getTransactionId());
        cv.put("file_name", r.getFileName());
        cv.put("file_type", r.getFileType());
        cv.put("file_data", r.getFileData());
        cv.put("file_size", r.getFileSize());
        cv.put("uploaded_at", LocalDateTime.now().format(TS_FMT));
        db.insert("transaction_receipts", null, cv);
        Log.i(TAG, "File uploading...");
        auditDao.logReceiptUpload(r.getTransactionId(), "user", r.getFileName());
        Log.i(TAG, "File uploading completed");
    }

    public void uploadReceipt(Receipt r) {
        ContentValues cv = new ContentValues();
        cv.put("file_data", r.getFileData());
        cv.put("updated_at", LocalDateTime.now().format(TS_FMT));
        db.update("transaction_receipts", cv, "id = ?", new String[]{String.valueOf(r.getId())});
        Log.i(TAG, "File uploading completed");
    }

    public List<Receipt> findByTransactionId(int txnId) {
        String sql = "SELECT id, transaction_id, file_name, file_type, file_data, file_size, uploaded_at "
                + "FROM transaction_receipts WHERE transaction_id = ? ORDER BY uploaded_at DESC";
        List<Receipt> list = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(txnId)})) {
            while (c.moveToNext())
                list.add(mapRow(c));
        }
        return list;
    }

    /** Metadata only (no file_data) for listing */
    public List<Receipt> findMetaByTransactionId(int txnId) {
        String sql = "SELECT id, transaction_id, file_name, file_type, file_size, uploaded_at "
                + "FROM transaction_receipts WHERE transaction_id = ? ORDER BY uploaded_at DESC";
        List<Receipt> list = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(txnId)})) {
            while (c.moveToNext()) {
                Receipt r = new Receipt();
                r.setId(c.getInt(c.getColumnIndexOrThrow("id")));
                r.setTransactionId(c.getInt(c.getColumnIndexOrThrow("transaction_id")));
                r.setFileName(c.getString(c.getColumnIndexOrThrow("file_name")));
                r.setFileType(c.getString(c.getColumnIndexOrThrow("file_type")));
                r.setFileSize(c.getInt(c.getColumnIndexOrThrow("file_size")));
                String ts = c.getString(c.getColumnIndexOrThrow("uploaded_at"));
                if (ts != null)
                    r.setUploadedAt(LocalDateTime.parse(ts, TS_FMT));
                list.add(r);
            }
        }
        return list;
    }

    public Receipt findById(int id) {
        String sql = "SELECT id, transaction_id, file_name, file_type, file_data, file_size, uploaded_at "
                + "FROM transaction_receipts WHERE id = ?";
        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? mapRow(c) : null;
        }
    }

    public void delete(int id, String fileName) {
        auditDao.logReceiptDelete(id, "user", fileName);
        db.delete("transaction_receipts", "id = ?", new String[]{String.valueOf(id)});
    }

    private Receipt mapRow(Cursor c) {
        Receipt r = new Receipt();
        r.setId(c.getInt(c.getColumnIndexOrThrow("id")));
        r.setTransactionId(c.getInt(c.getColumnIndexOrThrow("transaction_id")));
        r.setFileName(c.getString(c.getColumnIndexOrThrow("file_name")));
        r.setFileType(c.getString(c.getColumnIndexOrThrow("file_type")));
        r.setFileData(c.getBlob(c.getColumnIndexOrThrow("file_data")));
        r.setFileSize(c.getInt(c.getColumnIndexOrThrow("file_size")));
        String ts = c.getString(c.getColumnIndexOrThrow("uploaded_at"));
        if (ts != null)
            r.setUploadedAt(LocalDateTime.parse(ts, TS_FMT));
        return r;
    }
}
