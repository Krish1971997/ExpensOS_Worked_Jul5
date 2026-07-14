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
    private static final int DB_VERSION = 6;

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
        db.execSQL("""
                CREATE TABLE IF NOT EXISTS categories (
                    id      INTEGER PRIMARY KEY AUTOINCREMENT,
                    name    TEXT    NOT NULL,
                    type    TEXT    NOT NULL CHECK(type IN ('INCOME','EXPENSE')),
                    book_id INTEGER REFERENCES cash_books(id),
                    synced  INTEGER DEFAULT 0,
                    UNIQUE(name, type, book_id)
                )""");

        // ── sub_categories ──────────────────────────────────
        db.execSQL("""
                CREATE TABLE IF NOT EXISTS sub_categories (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    name        TEXT    NOT NULL,
                    category_id INTEGER NOT NULL REFERENCES categories(id),
                    synced      INTEGER DEFAULT 0
                )""");

        // ── cash_books ───────────────────────────────────────
        db.execSQL("""
                CREATE TABLE IF NOT EXISTS cash_books (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    name        TEXT    NOT NULL,
                    description TEXT,
                    created_at  TEXT    DEFAULT (datetime('now')),
                    is_active   INTEGER DEFAULT 1,
                    synced      INTEGER DEFAULT 0
                )""");

        // ── column_definitions ──────────────────────────────
        db.execSQL("""
                CREATE TABLE IF NOT EXISTS column_definitions (
                    id       INTEGER PRIMARY KEY AUTOINCREMENT,
                    col_name TEXT NOT NULL,
                    col_key  TEXT NOT NULL,
                    type     TEXT NOT NULL,
                    synced   INTEGER DEFAULT 0,
                    UNIQUE(col_key, type)
                )""");

        // ── transactions ────────────────────────────────────
        db.execSQL("""
                CREATE TABLE IF NOT EXISTS transactions (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    type         TEXT    NOT NULL CHECK(type IN ('INCOME','EXPENSE')),
                    txn_datetime TEXT    NOT NULL,
                    amount       REAL    NOT NULL,
                    category_id  INTEGER REFERENCES categories(id),
                    sub_categories_id   INTEGER REFERENCES sub_categories(id),
                    note         TEXT,
                    book_id      INTEGER REFERENCES cash_books(id),
                    synced       INTEGER DEFAULT 0
                )""");

        // ── transaction_custom_values ───────────────────────
        db.execSQL("""
                CREATE TABLE IF NOT EXISTS transaction_custom_values (
                    id             INTEGER PRIMARY KEY AUTOINCREMENT,
                    transaction_id INTEGER NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
                    col_def_id     INTEGER NOT NULL REFERENCES column_definitions(id),
                    value          TEXT,
                    UNIQUE(transaction_id, col_def_id)
                )""");

        // ── deleted_records (tombstone) — for sync deletes ──
        // When a transaction is deleted locally, we record it here
        // so SyncManager can propagate the delete to Neon on next sync.
        db.execSQL("""
                CREATE TABLE IF NOT EXISTS deleted_records (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    table_name TEXT NOT NULL,
                    record_id  INTEGER NOT NULL,
                    deleted_at TEXT DEFAULT (datetime('now')),
                    synced     INTEGER DEFAULT 0,
                    UNIQUE(table_name, record_id)
                )""");

        // ── app_config (mirrors web.xml + Neon app_config) ──
        db.execSQL("""
                CREATE TABLE IF NOT EXISTS app_config (
                    key        TEXT PRIMARY KEY,
                    value      TEXT,
                    updated_at TEXT DEFAULT (datetime('now'))
                )""");

        // ── Seed default common categories ──────────────────
        String[] incomes = {"Salary", "Freelance", "Gift", "Other"};
        String[] expenses = {"Food", "Transport", "Merchandise",
                "Healthcare", "Entertainment", "Education", "Other"};
        for (String c : incomes)
            db.execSQL("INSERT OR IGNORE INTO categories(name,type,book_id) VALUES('" + c + "','INCOME',NULL)");
        for (String c : expenses)
            db.execSQL("INSERT OR IGNORE INTO categories(name,type,book_id) VALUES('" + c + "','EXPENSE',NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Future migrations go here
    }
}
