package com.expenseos.db;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bidirectional sync: SQLite ↔ Neon PostgreSQL
 * <p>
 * PUSH: rows where synced=0 → insert/upsert into Postgres
 * PULL: all rows from Postgres → upsert into SQLite, mark synced=1
 * <p>
 * Call SyncManager.sync(context, callback) from UI thread.
 */
public class SyncManager {

    private static final String TAG = "SyncManager";
    private static final ExecutorService exec = Executors.newSingleThreadExecutor();

    public interface SyncCallback {
        void onSuccess(String msg);

        void onError(String err);
    }

    // ── Entry point (runs on background thread) ──────────────────
    public static void sync(Context ctx, SyncCallback cb) {
        exec.execute(() -> {
            try {
                SharedPreferences prefs = ctx.getSharedPreferences("expenseos_prefs", Context.MODE_PRIVATE);
                String url = prefs.getString("db_url", "");
                String user = prefs.getString("db_user", "");
                String pass = prefs.getString("db_pass", "");

                if (url.isEmpty()) {
                    cb.onError("DB URL not configured. Go to Settings.");
                    return;
                }

                Class.forName("org.postgresql.Driver");
                try (Connection pg = DriverManager.getConnection(url, user, pass)) {
                    pg.setAutoCommit(false);
                    SQLiteDatabase lite = LocalDB.getInstance(ctx).getWritableDatabase();

                    syncCashBooks(lite, pg);
                    syncCategories(lite, pg);
                    syncSubCategories(lite, pg);
                    syncTransactions(lite, pg);

                    pg.commit();
                    lite.execSQL("UPDATE cash_books    SET synced=1");
                    lite.execSQL("UPDATE categories    SET synced=1");
                    lite.execSQL("UPDATE sub_categories SET synced=1");
                    lite.execSQL("UPDATE transactions   SET synced=1");

                    cb.onSuccess("Sync complete ✓");
                }
            } catch (Exception e) {
                Log.e(TAG, "Sync error", e);
                cb.onError(e.getMessage());
            }
        });
    }

    // ── CASH BOOKS ───────────────────────────────────────────────
    private static void syncCashBooks(SQLiteDatabase lite, Connection pg) throws Exception {
        // PUSH unsynced local books → Postgres
        Cursor c = lite.rawQuery("SELECT id,name,description,created_at FROM cash_books WHERE synced=0", null);
        PreparedStatement ps = pg.prepareStatement(
                "INSERT INTO cash_books(name,description,created_at) VALUES(?,?,?) " +
                        "ON CONFLICT(name) DO NOTHING RETURNING id");
        while (c.moveToNext()) {
            ps.setString(1, c.getString(1));
            ps.setString(2, c.getString(2));
            ps.setString(3, c.getString(3));
            ps.execute();
        }
        c.close();
        ps.close();

        // PULL all Postgres books → SQLite upsert
        Statement st = pg.createStatement();
        ResultSet rs = st.executeQuery("SELECT id,name,description,created_at,is_active FROM cash_books");
        while (rs.next()) {
            ContentValues cv = new ContentValues();
            cv.put("id", rs.getInt("id"));
            cv.put("name", rs.getString("name"));
            cv.put("description", rs.getString("description"));
            cv.put("created_at", rs.getString("created_at"));
            cv.put("is_active", rs.getBoolean("is_active") ? 1 : 0);
            cv.put("synced", 1);
            lite.insertWithOnConflict("cash_books", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        }
        rs.close();
        st.close();
    }

    // ── CATEGORIES ───────────────────────────────────────────────
    private static void syncCategories(SQLiteDatabase lite, Connection pg) throws Exception {
        // PUSH
        Cursor c = lite.rawQuery("SELECT name,type FROM categories WHERE synced=0", null);
        PreparedStatement ps = pg.prepareStatement(
                "INSERT INTO categories(name,type) VALUES(?,?::txn_type) ON CONFLICT DO NOTHING");
        while (c.moveToNext()) {
            ps.setString(1, c.getString(0));
            ps.setString(2, c.getString(1));
            ps.execute();
        }
        c.close();
        ps.close();

        // PULL
        Statement st = pg.createStatement();
        ResultSet rs = st.executeQuery("SELECT id,name,type FROM categories ORDER BY id");
        while (rs.next()) {
            ContentValues cv = new ContentValues();
            cv.put("id", rs.getInt("id"));
            cv.put("name", rs.getString("name"));
            cv.put("type", rs.getString("type"));
            cv.put("synced", 1);
            lite.insertWithOnConflict("categories", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        }
        rs.close();
        st.close();
    }

    // ── SUB CATEGORIES ───────────────────────────────────────────
    private static void syncSubCategories(SQLiteDatabase lite, Connection pg) throws Exception {
        // PUSH
        Cursor c = lite.rawQuery("SELECT name,category_id FROM sub_categories WHERE synced=0", null);
        PreparedStatement ps = pg.prepareStatement(
                "INSERT INTO sub_categories(name,category_id) VALUES(?,?) ON CONFLICT DO NOTHING");
        while (c.moveToNext()) {
            ps.setString(1, c.getString(0));
            ps.setInt(2, c.getInt(1));
            ps.execute();
        }
        c.close();
        ps.close();

        // PULL
        Statement st = pg.createStatement();
        ResultSet rs = st.executeQuery("SELECT sub_categories_id AS id, name, category_id FROM sub_categories");
        while (rs.next()) {
            ContentValues cv = new ContentValues();
            cv.put("id", rs.getInt("id"));
            cv.put("name", rs.getString("name"));
            cv.put("category_id", rs.getInt("category_id"));
            cv.put("synced", 1);
            lite.insertWithOnConflict("sub_categories", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        }
        rs.close();
        st.close();
    }

    // ── TRANSACTIONS ─────────────────────────────────────────────
    private static void syncTransactions(SQLiteDatabase lite, Connection pg) throws Exception {
        // PUSH unsynced local transactions → Postgres
        Cursor c = lite.rawQuery(
                "SELECT id,type,txn_datetime,amount,category_id,sub_cat_id,note,book_id " +
                        "FROM transactions WHERE synced=0", null);
        PreparedStatement ps = pg.prepareStatement(
                "INSERT INTO transactions(type,txn_datetime,amount,category_id," +
                        "sub_categories_id,note,book_id) VALUES(?::txn_type,?,?,?,?,?,?) RETURNING id");
        while (c.moveToNext()) {
            ps.setString(1, c.getString(1));
            ps.setTimestamp(2, Timestamp.valueOf(c.getString(2)));
            ps.setBigDecimal(3, new java.math.BigDecimal(c.getString(3)));
            ps.setInt(4, c.getInt(4));
            if (c.isNull(5)) ps.setNull(5, Types.INTEGER);
            else ps.setInt(5, c.getInt(5));
            ps.setString(6, c.getString(6));
            ps.setInt(7, c.getInt(7));
            ps.execute();
        }
        c.close();
        ps.close();

        // PULL all Postgres transactions → SQLite upsert
        Statement st = pg.createStatement();
        ResultSet rs = st.executeQuery(
                "SELECT id,type,txn_datetime,amount,category_id," +
                        "sub_categories_id,note,book_id FROM transactions ORDER BY id");
        while (rs.next()) {
            ContentValues cv = new ContentValues();
            cv.put("id", rs.getLong("id"));
            cv.put("type", rs.getString("type"));
            cv.put("txn_datetime", rs.getTimestamp("txn_datetime").toString().substring(0, 19));
            cv.put("amount", rs.getBigDecimal("amount").toPlainString());
            cv.put("category_id", rs.getInt("category_id"));
            int subCat = rs.getInt("sub_categories_id");
            if (!rs.wasNull()) cv.put("sub_cat_id", subCat);
            cv.put("note", rs.getString("note"));
            cv.put("book_id", rs.getInt("book_id"));
            cv.put("synced", 1);
            lite.insertWithOnConflict("transactions", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        }
        rs.close();
        st.close();
    }
}