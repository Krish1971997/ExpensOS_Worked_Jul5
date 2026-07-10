package com.expenseos.dao;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.expenseos.model.Transaction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TransactionDao {

    private final SQLiteDatabase db;

    public TransactionDao(Context ctx) {
        db = LocalDatabase.get(ctx).getWritableDatabase();
    }

    public long insert(Transaction t) {
        ContentValues cv = toContentValues(t);
        cv.put("synced", 0);
        cv.put("sync_action", "INSERT");
        return db.insert("transactions", null, cv);
    }

    public int update(Transaction t) {
        ContentValues cv = toContentValues(t);
        cv.put("synced", 0);
        cv.put("sync_action", "UPDATE");
        return db.update("transactions", cv, "id=?",
                new String[]{String.valueOf(t.getId())});
    }

    public int delete(int localId) {
        ContentValues cv = new ContentValues();
        cv.put("is_deleted", 1);
        cv.put("synced", 0);
        cv.put("sync_action", "DELETE");
        return db.update("transactions", cv, "id=?",
                new String[]{String.valueOf(localId)});
    }

    public List<Transaction> getAllForBook(int bookId) {
        List<Transaction> list = new ArrayList<>();
        Cursor c = db.rawQuery(
                "SELECT t.*, c.name as cat_name, sc.name as subcat_name " +
                        "FROM transactions t " +
                        "LEFT JOIN categories c ON t.category_id = c.id " +
                        "LEFT JOIN sub_categories sc ON t.sub_categories_id = sc.sub_categories_id " +
                        "WHERE t.book_id=? AND t.is_deleted=0 " +
                        "ORDER BY t.txn_datetime DESC",
                new String[]{String.valueOf(bookId)});
        while (c.moveToNext()) list.add(fromCursor(c));
        c.close();
        return list;
    }

    public List<Transaction> getPendingSync() {
        List<Transaction> list = new ArrayList<>();
        Cursor c = db.rawQuery(
                "SELECT * FROM transactions WHERE synced=0",
                null);
        while (c.moveToNext()) list.add(fromCursor(c));
        c.close();
        return list;
    }

    public void markSynced(int localId, int remoteId) {
        ContentValues cv = new ContentValues();
        cv.put("synced", 1);
        cv.put("remote_id", remoteId);
        db.update("transactions", cv, "id=?",
                new String[]{String.valueOf(localId)});
    }

    /**
     * Replace all local transactions for a book with data fetched from remote
     */
    public void replaceAllFromRemote(int bookId, List<Transaction> remote) {
        db.beginTransaction();
        try {
            db.delete("transactions", "book_id=? AND synced=1",
                    new String[]{String.valueOf(bookId)});
            for (Transaction t : remote) {
                ContentValues cv = toContentValues(t);
                cv.put("remote_id", t.getId());
                cv.put("synced", 1);
                cv.put("sync_action", "NONE");
                db.insertWithOnConflict("transactions", null, cv,
                        SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private ContentValues toContentValues(Transaction t) {
        ContentValues cv = new ContentValues();
        cv.put("type", t.getType() != null ? t.getType().name() : "INCOME");
        cv.put("txn_datetime", t.getTxnDatetime());
        cv.put("amount", t.getAmount() != null ? t.getAmount().doubleValue() : 0.0);
        cv.put("category_id", t.getCategoryId());
        cv.put("sub_categories_id", t.getSubCategoryId());
        cv.put("note", t.getNote());
        cv.put("book_id", t.getBookId());
        cv.put("is_deleted", 0);
        return cv;
    }

    public List<Transaction> findAll(String typeFilter, int page, int pageSize, int bookId) {

        List<Transaction> list = new ArrayList<>();

        StringBuilder sql = new StringBuilder(
                "SELECT t.*, c.name AS cat_name, sc.name AS subcat_name " +
                        "FROM transactions t " +
                        "LEFT JOIN categories c ON t.category_id = c.id " +
                        "LEFT JOIN sub_categories sc ON t.sub_categories_id = sc.sub_categories_id " +
                        "WHERE t.is_deleted=0 "
        );

        List<String> args = new ArrayList<>();

        if (typeFilter != null && !typeFilter.isEmpty()) {
            sql.append("AND t.type=? ");
            args.add(typeFilter);
        }

        if (bookId > 0) {
            sql.append("AND t.book_id=? ");
            args.add(String.valueOf(bookId));
        }

        sql.append("ORDER BY t.txn_datetime DESC ");
        sql.append("LIMIT ? OFFSET ?");

        args.add(String.valueOf(pageSize));
        args.add(String.valueOf((page - 1) * pageSize));

        Cursor c = db.rawQuery(sql.toString(), args.toArray(new String[0]));

        while (c.moveToNext()) {
            list.add(fromCursor(c));
        }

        c.close();

        return list;
    }

    public int count(String typeFilter, int bookId) {

        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM transactions WHERE is_deleted=0 "
        );

        List<String> args = new ArrayList<>();

        if (typeFilter != null && !typeFilter.isEmpty()) {
            sql.append("AND type=? ");
            args.add(typeFilter);
        }

        if (bookId > 0) {
            sql.append("AND book_id=? ");
            args.add(String.valueOf(bookId));
        }

        Cursor c = db.rawQuery(sql.toString(), args.toArray(new String[0]));

        int count = 0;

        if (c.moveToFirst()) {
            count = c.getInt(0);
        }

        c.close();

        return count;
    }

    public double sumByType(String type, int bookId) {

        StringBuilder sql = new StringBuilder(
                "SELECT IFNULL(SUM(amount),0) " +
                        "FROM transactions " +
                        "WHERE type=? AND is_deleted=0 "
        );

        List<String> args = new ArrayList<>();
        args.add(type);

        if (bookId > 0) {
            sql.append("AND book_id=? ");
            args.add(String.valueOf(bookId));
        }

        Cursor c = db.rawQuery(sql.toString(), args.toArray(new String[0]));

        double total = 0;

        if (c.moveToFirst()) {
            total = c.getDouble(0);
        }

        c.close();

        return total;
    }

    public List<Transaction> findRecent(int limit) {

        List<Transaction> list = new ArrayList<>();

        Cursor c = db.rawQuery(
                "SELECT t.*, c.name AS cat_name, sc.name AS subcat_name " +
                        "FROM transactions t " +
                        "LEFT JOIN categories c ON t.category_id=c.id " +
                        "LEFT JOIN sub_categories sc ON t.sub_categories_id=sc.sub_categories_id " +
                        "WHERE t.is_deleted=0 " +
                        "ORDER BY t.txn_datetime DESC " +
                        "LIMIT ?",
                new String[]{String.valueOf(limit)}
        );

        while (c.moveToNext()) {
            list.add(fromCursor(c));
        }

        c.close();

        return list;
    }

    public List<String[]> monthlyTrend(int months, int bookId) {
        List<String[]> rows = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT strftime('%Y-%m', txn_datetime) AS month, " +
                        "SUM(CASE WHEN type='INCOME' THEN amount ELSE 0 END) AS income, " +
                        "SUM(CASE WHEN type='EXPENSE' THEN amount ELSE 0 END) AS expense " +
                        "FROM transactions WHERE is_deleted=0 "
        );
        List<String> args = new ArrayList<>();
        if (bookId > 0) {
            sql.append("AND book_id=? ");
            args.add(String.valueOf(bookId));
        }
        sql.append("GROUP BY strftime('%Y-%m', txn_datetime) ORDER BY month DESC LIMIT ?");
        args.add(String.valueOf(months));

        Cursor c = db.rawQuery(sql.toString(), args.toArray(new String[0]));
        while (c.moveToNext()) {
            rows.add(new String[]{
                    c.getString(0),
                    String.valueOf(c.getDouble(1)),
                    String.valueOf(c.getDouble(2))
            });
        }
        c.close();
        Collections.reverse(rows);
        return rows;
    }

    public List<String[]> expenseByCategory(int bookId) {
        List<String[]> rows = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT c.name, IFNULL(SUM(t.amount),0) FROM transactions t " +
                        "JOIN categories c ON t.category_id=c.id " +
                        "WHERE t.type='EXPENSE' AND t.is_deleted=0 "
        );
        List<String> args = new ArrayList<>();
        if (bookId > 0) {
            sql.append("AND t.book_id=? ");
            args.add(String.valueOf(bookId));
        }
        sql.append("GROUP BY c.name ORDER BY SUM(t.amount) DESC");

        Cursor c = db.rawQuery(sql.toString(), args.toArray(new String[0]));
        while (c.moveToNext()) {
            rows.add(new String[]{ c.getString(0), String.valueOf(c.getDouble(1)) });
        }
        c.close();
        return rows;
    }

    public List<String[]> incomeByCategory(int bookId) {
        List<String[]> rows = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT c.name, IFNULL(SUM(t.amount),0) FROM transactions t " +
                        "JOIN categories c ON t.category_id=c.id " +
                        "WHERE t.type='INCOME' AND t.is_deleted=0 "
        );
        List<String> args = new ArrayList<>();
        if (bookId > 0) {
            sql.append("AND t.book_id=? ");
            args.add(String.valueOf(bookId));
        }
        sql.append("GROUP BY c.name ORDER BY SUM(t.amount) DESC");

        Cursor c = db.rawQuery(sql.toString(), args.toArray(new String[0]));
        while (c.moveToNext()) {
            rows.add(new String[]{ c.getString(0), String.valueOf(c.getDouble(1)) });
        }
        c.close();
        return rows;
    }

    private Transaction fromCursor(Cursor c) {
        Transaction t = new Transaction();
        t.setId(c.getInt(c.getColumnIndexOrThrow("id")));
        String typeStr = c.getString(c.getColumnIndexOrThrow("type"));
        t.setType("EXPENSE".equals(typeStr) ? Transaction.Type.EXPENSE : Transaction.Type.INCOME);
        t.setTxnDatetime(c.getString(c.getColumnIndexOrThrow("txn_datetime")));
        t.setAmount(BigDecimal.valueOf(c.getDouble(c.getColumnIndexOrThrow("amount"))));
        t.setCategoryId(c.getInt(c.getColumnIndexOrThrow("category_id")));
        t.setSubCategoryId(c.getInt(c.getColumnIndexOrThrow("sub_categories_id")));
        t.setNote(c.getString(c.getColumnIndexOrThrow("note")));
        t.setBookId(c.getInt(c.getColumnIndexOrThrow("book_id")));
        t.setSynced(c.getInt(c.getColumnIndexOrThrow("synced")) == 1);
        int catIdx = c.getColumnIndex("cat_name");
        if (catIdx >= 0) t.setCategoryName(c.getString(catIdx));
        int subIdx = c.getColumnIndex("subcat_name");
        if (subIdx >= 0) t.setSubCategoryName(c.getString(subIdx));
        return t;
    }
}
