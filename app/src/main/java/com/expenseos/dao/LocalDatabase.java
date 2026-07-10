package com.expenseos.dao;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * Local SQLite database — mirrors the PostgreSQL schema.
 * Transactions are saved here first; sync pushes them to Neon.
 */
public class LocalDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "expenseos.db";
    private static final int DB_VERSION = 1;

    private static LocalDatabase instance;

    public static LocalDatabase get(Context ctx) {
        if (instance == null)
            instance = new LocalDatabase(ctx.getApplicationContext());
        return instance;
    }

    private LocalDatabase(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // ── categories ───────────────────────────────────────
        db.execSQL("CREATE TABLE IF NOT EXISTS categories (" +
                "id INTEGER PRIMARY KEY, " +
                "name TEXT NOT NULL, " +
                "type TEXT NOT NULL)");

        // ── sub_categories ───────────────────────────────────
        db.execSQL("CREATE TABLE IF NOT EXISTS sub_categories (" +
                "sub_categories_id INTEGER PRIMARY KEY, " +
                "name TEXT NOT NULL, " +
                "category_id INTEGER NOT NULL)");

        // ── cash_books ───────────────────────────────────────
        db.execSQL("CREATE TABLE IF NOT EXISTS cash_books (" +
                "id INTEGER PRIMARY KEY, " +
                "name TEXT NOT NULL, " +
                "description TEXT, " +
                "created_at TEXT)");

        // ── transactions (local copy + pending flag) ─────────
        db.execSQL("CREATE TABLE IF NOT EXISTS transactions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "remote_id INTEGER DEFAULT 0, " +         // 0 = not yet synced
                "type TEXT NOT NULL, " +
                "txn_datetime TEXT NOT NULL, " +
                "amount REAL NOT NULL, " +
                "category_id INTEGER, " +
                "sub_categories_id INTEGER, " +
                "note TEXT, " +
                "book_id INTEGER, " +
                "is_deleted INTEGER DEFAULT 0, " +        // soft-delete for sync
                "synced INTEGER DEFAULT 0, " +            // 0=pending, 1=synced
                "sync_action TEXT DEFAULT 'INSERT')");    // INSERT|UPDATE|DELETE

        // ── backup_history (local log) ───────────────────────
        db.execSQL("CREATE TABLE IF NOT EXISTS backup_history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "file_name TEXT, " +
                "file_path TEXT, " +
                "file_size_bytes INTEGER DEFAULT 0, " +
                "backup_type TEXT DEFAULT 'MANUAL', " +
                "status TEXT DEFAULT 'PENDING', " +
                "description TEXT, " +
                "income_count INTEGER DEFAULT 0, " +
                "expense_count INTEGER DEFAULT 0, " +
                "created_at TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Future migrations go here
    }
}
