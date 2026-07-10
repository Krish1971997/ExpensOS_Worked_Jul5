package com.expenseos.db;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * SQLite schema — mirrors the PostgreSQL tables.
 * Extra column: synced INTEGER DEFAULT 0  (0=pending, 1=synced)
 */
public class LocalDB extends SQLiteOpenHelper {

    private static final String DB_NAME = "expenseos.db";
    private static final int DB_VERSION = 1;

    private static LocalDB instance;

    public static synchronized LocalDB getInstance(Context ctx) {
        if (instance == null)
            instance = new LocalDB(ctx.getApplicationContext());
        return instance;
    }

    private LocalDB(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("PRAGMA foreign_keys = ON;");

        // cash_books
        db.execSQL("""
                CREATE TABLE IF NOT EXISTS cash_books (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    name        TEXT    NOT NULL,
                    description TEXT,
                    created_at  TEXT    DEFAULT (datetime('now')),
                    is_active   INTEGER DEFAULT 1,
                    synced      INTEGER DEFAULT 0
                )""");

        // categories
        db.execSQL("""
                CREATE TABLE IF NOT EXISTS categories (
                    id     INTEGER PRIMARY KEY AUTOINCREMENT,
                    name   TEXT NOT NULL,
                    type   TEXT NOT NULL CHECK(type IN ('INCOME','EXPENSE')),
                    synced INTEGER DEFAULT 0,
                    UNIQUE(name, type)
                )""");

        // sub_categories
        db.execSQL("""
                CREATE TABLE IF NOT EXISTS sub_categories (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    name        TEXT NOT NULL,
                    category_id INTEGER NOT NULL REFERENCES categories(id),
                    synced      INTEGER DEFAULT 0
                )""");

        // column_definitions
        db.execSQL("""
                CREATE TABLE IF NOT EXISTS column_definitions (
                    id       INTEGER PRIMARY KEY AUTOINCREMENT,
                    col_name TEXT NOT NULL,
                    col_key  TEXT NOT NULL,
                    type     TEXT NOT NULL,
                    synced   INTEGER DEFAULT 0,
                    UNIQUE(col_key, type)
                )""");

        // transactions
        db.execSQL("""
                CREATE TABLE IF NOT EXISTS transactions (
                    id             INTEGER PRIMARY KEY AUTOINCREMENT,
                    type           TEXT NOT NULL CHECK(type IN ('INCOME','EXPENSE')),
                    txn_datetime   TEXT NOT NULL,
                    amount         REAL NOT NULL,
                    category_id    INTEGER REFERENCES categories(id),
                    sub_cat_id     INTEGER REFERENCES sub_categories(id),
                    note           TEXT,
                    book_id        INTEGER REFERENCES cash_books(id),
                    synced         INTEGER DEFAULT 0
                )""");

        // transaction_custom_values
        db.execSQL("""
                CREATE TABLE IF NOT EXISTS txn_custom_values (
                    id             INTEGER PRIMARY KEY AUTOINCREMENT,
                    transaction_id INTEGER NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
                    col_def_id     INTEGER NOT NULL REFERENCES column_definitions(id),
                    value          TEXT,
                    UNIQUE(transaction_id, col_def_id)
                )""");

        // Seed default categories
        String[] incomes = {"Salary", "Freelance", "Business", "Investment", "Gift", "Other"};
        String[] expenses = {"Food", "Transport", "Shopping", "Rent", "Utilities",
                "Healthcare", "Entertainment", "Education", "Other"};
        for (String c : incomes)
            db.execSQL("INSERT OR IGNORE INTO categories(name,type) VALUES('" + c + "','INCOME')");
        for (String c : expenses)
            db.execSQL("INSERT OR IGNORE INTO categories(name,type) VALUES('" + c + "','EXPENSE')");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // Future migrations here
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        db.execSQL("PRAGMA foreign_keys = ON;");
    }
}