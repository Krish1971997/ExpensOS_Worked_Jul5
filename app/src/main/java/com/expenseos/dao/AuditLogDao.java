package com.expenseos.dao;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.expenseos.db.LocalDB;
import com.expenseos.model.AuditLog;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Android/SQLite port of the web AuditLogDAO.
 * Timestamps are stored as TEXT in "yyyy-MM-dd HH:mm:ss" format so they
 * sort correctly with plain string ORDER BY / comparisons.
 */
public class AuditLogDao {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LocalDB helper;
    private final SQLiteDatabase db;

    public AuditLogDao(Context ctx) {
        helper = LocalDB.getInstance(ctx);
        db = helper.getWritableDatabase();
    }

    /**
     * Log a CREATE event
     */
    public void logCreate(int transactionId, String changedBy) {
        insert(transactionId, "CREATE", changedBy, null, null, null, "Transaction created");
    }

    /**
     * Log one field change
     */
    public void logUpdate(int transactionId, String changedBy, String fieldName, String oldValue, String newValue) {
        insert(transactionId, "UPDATE", changedBy, fieldName, oldValue, newValue, null);
    }

    /**
     * Log DELETE
     */
    public void logDelete(int transactionId, String changedBy) {
        insert(transactionId, "DELETE", changedBy, null, null, null, "Transaction deleted");
    }

    /**
     * Log receipt upload
     */
    public void logReceiptUpload(int transactionId, String changedBy, String fileName) {
        insert(transactionId, "RECEIPT_ADD", changedBy, "receipt", null, fileName, "Receipt uploaded: " + fileName);
    }

    /**
     * Log receipt delete
     */
    public void logReceiptDelete(int transactionId, String changedBy, String fileName) {
        insert(transactionId, "RECEIPT_DEL", changedBy, "receipt", fileName, null, "Receipt deleted: " + fileName);
    }

    private void insert(int transactionId, String action, String changedBy, String fieldName, String oldValue,
                        String newValue, String note) {
        long id = helper.getNextId("transaction_audit_log");
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("transaction_id", transactionId);
        cv.put("action", action);
        cv.put("changed_by", changedBy != null ? changedBy : "user");
        cv.put("field_name", fieldName);
        cv.put("old_value", oldValue);
        cv.put("new_value", newValue);
        cv.put("note", note);
        cv.put("changed_at", LocalDateTime.now().format(TS_FMT));
        db.insert("transaction_audit_log", null, cv);
    }

    /**
     * All audit entries for a transaction
     */
    public List<AuditLog> findByTransactionId(int transactionId) {
        String sql = "SELECT id, transaction_id, action, changed_by, changed_at, "
                + "field_name, old_value, new_value, note "
                + "FROM transaction_audit_log WHERE transaction_id = ? ORDER BY changed_at ASC";
        List<AuditLog> list = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(transactionId)})) {
            while (c.moveToNext())
                list.add(mapRow(c));
        }
        return list;
    }

    /**
     * Book-scoped audit log with pagination
     */
    public List<AuditLog> findRecentByBook(int bookId, int page, int pageSize) {
        String sql = "SELECT a.id, a.transaction_id, a.action, a.changed_at, "
                + "a.field_name, a.old_value, a.new_value, a.note, "
                + "t.amount, c.name AS cat_name, "
                + "strftime('%d %b %Y %H:%M', t.txn_datetime) AS txn_date "
                + "FROM transaction_audit_log a "
                + "JOIN transactions t ON a.transaction_id = t.id "
                + "LEFT JOIN categories c ON t.category_id = c.id "
                + "WHERE t.book_id = ? "
                + "ORDER BY a.changed_at DESC LIMIT ? OFFSET ?";
        List<AuditLog> list = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, new String[]{
                String.valueOf(bookId), String.valueOf(pageSize), String.valueOf((page - 1) * pageSize)})) {
            while (c.moveToNext()) {
                AuditLog a = mapRow(c);
                int amountIdx = c.getColumnIndex("amount");
                String amountStr = amountIdx >= 0 ? c.getString(amountIdx) : null;
                a.setTxnAmount(amountStr != null ? new BigDecimal(amountStr) : null);
                a.setTxnCategoryName(c.getString(c.getColumnIndexOrThrow("cat_name")));
                a.setTxnDate(c.getString(c.getColumnIndexOrThrow("txn_date")));
                list.add(a);
            }
        }
        return list;
    }

    public int countByBook(int bookId) {
        String sql = "SELECT COUNT(*) FROM transaction_audit_log a "
                + "JOIN transactions t ON a.transaction_id = t.id WHERE t.book_id = ?";
        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(bookId)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /**
     * Recent audit entries across all transactions (for admin view)
     */
    public List<AuditLog> findRecent(int limit) {
        String sql = "SELECT id, transaction_id, action, changed_by, changed_at, "
                + "field_name, old_value, new_value, note "
                + "FROM transaction_audit_log ORDER BY changed_at DESC LIMIT ?";
        List<AuditLog> list = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(limit)})) {
            while (c.moveToNext())
                list.add(mapRow(c));
        }
        return list;
    }

    private AuditLog mapRow(Cursor c) {
        AuditLog a = new AuditLog();
        a.setId(c.getInt(c.getColumnIndexOrThrow("id")));
        a.setTransactionId(c.getInt(c.getColumnIndexOrThrow("transaction_id")));
        a.setAction(c.getString(c.getColumnIndexOrThrow("action")));
        // a.setChangedBy(...) intentionally left unset (mirrors the web DAO, which also left it commented out)
        String ts = c.getString(c.getColumnIndexOrThrow("changed_at"));
        if (ts != null)
            a.setChangedAt(LocalDateTime.parse(ts, TS_FMT));
        a.setFieldName(c.getString(c.getColumnIndexOrThrow("field_name")));
        a.setOldValue(c.getString(c.getColumnIndexOrThrow("old_value")));
        a.setNewValue(c.getString(c.getColumnIndexOrThrow("new_value")));
        a.setNote(c.getString(c.getColumnIndexOrThrow("note")));
        return a;
    }
}
