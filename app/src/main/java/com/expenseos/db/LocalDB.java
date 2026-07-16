package com.expenseos.db;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class LocalDB extends SQLiteOpenHelper {

    private static final String DB_NAME = "expenseos.db";
    private static final int DB_VERSION = 9; // bumped: added transaction_receipts table
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
        db.execSQL("CREATE TABLE IF NOT EXISTS cash_books (" +
                "id          INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name        TEXT NOT NULL," +
                "description TEXT," +
                "created_at  TEXT DEFAULT (datetime('now'))," +
                "updated_at  TEXT DEFAULT (datetime('now'))," +
                "is_active   INTEGER DEFAULT 1," +
                "synced      INTEGER DEFAULT 0)");

        // categories
        db.execSQL("CREATE TABLE IF NOT EXISTS categories (" +
                "id      INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name    TEXT NOT NULL," +
                "type    TEXT NOT NULL CHECK(type IN ('INCOME','EXPENSE'))," +
                "book_id INTEGER REFERENCES cash_books(id)," +
                "synced  INTEGER DEFAULT 0," +
                "UNIQUE(name,type,book_id))");

        // sub_categories
        db.execSQL("CREATE TABLE IF NOT EXISTS sub_categories (" +
                "id          INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name        TEXT NOT NULL," +
                "category_id INTEGER NOT NULL REFERENCES categories(id)," +
                "synced      INTEGER DEFAULT 0)");

        // column_definitions
        db.execSQL("CREATE TABLE IF NOT EXISTS column_definitions (" +
                "id       INTEGER PRIMARY KEY AUTOINCREMENT," +
                "col_name TEXT NOT NULL," +
                "col_key  TEXT NOT NULL," +
                "type     TEXT NOT NULL," +
                "synced   INTEGER DEFAULT 0," +
                "UNIQUE(col_key,type))");

        // transactions — sub_categories_id column name
        db.execSQL("CREATE TABLE IF NOT EXISTS transactions (" +
                "id                INTEGER PRIMARY KEY AUTOINCREMENT," +
                "type              TEXT NOT NULL CHECK(type IN ('INCOME','EXPENSE'))," +
                "txn_datetime      TEXT NOT NULL," +
                "amount            REAL NOT NULL," +
                "category_id       INTEGER REFERENCES categories(id)," +
                "sub_categories_id INTEGER REFERENCES sub_categories(id)," +
                "note              TEXT," +
                "book_id           INTEGER REFERENCES cash_books(id)," +
                "synced            INTEGER DEFAULT 0)");

        // transaction_custom_values ← இது முக்கியம்!
        db.execSQL("CREATE TABLE IF NOT EXISTS transaction_custom_values (" +
                "id             INTEGER PRIMARY KEY AUTOINCREMENT," +
                "transaction_id INTEGER NOT NULL REFERENCES transactions(id) ON DELETE CASCADE," +
                "col_def_id     INTEGER NOT NULL REFERENCES column_definitions(id)," +
                "value          TEXT," +
                "UNIQUE(transaction_id,col_def_id))");

        // deleted_records (tombstone)
        db.execSQL("CREATE TABLE IF NOT EXISTS deleted_records (" +
                "id         INTEGER PRIMARY KEY AUTOINCREMENT," +
                "table_name TEXT NOT NULL," +
                "record_id  INTEGER NOT NULL," +
                "deleted_at TEXT DEFAULT (datetime('now'))," +
                "synced     INTEGER DEFAULT 0," +
                "UNIQUE(table_name,record_id))");

        // app_config
        db.execSQL("CREATE TABLE IF NOT EXISTS app_config (" +
                "key        TEXT PRIMARY KEY," +
                "value      TEXT," +
                "updated_at TEXT DEFAULT (datetime('now')))");

        // audit_log
        db.execSQL("CREATE TABLE IF NOT EXISTS transaction_audit_log (" +
                "id             INTEGER PRIMARY KEY AUTOINCREMENT," +
                "transaction_id INTEGER NOT NULL REFERENCES transactions(id) ON DELETE CASCADE," +
                "action         TEXT NOT NULL," +
                "changed_by     TEXT DEFAULT 'user'," +
                "changed_at     TEXT DEFAULT (datetime('now'))," +
                "field_name     TEXT," +
                "old_value      TEXT," +
                "new_value      TEXT," +
                "note           TEXT," +
                "created_at     TEXT DEFAULT (datetime('now'))," +
                "updated_at     TEXT DEFAULT (datetime('now'))" +
                ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_audit_changed ON transaction_audit_log(changed_at DESC);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_audit_txn_id ON transaction_audit_log(transaction_id ASC);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_transaction_audit_log_updated ON transaction_audit_log(updated_at ASC);");

        // transaction_receipts — mirrors the Postgres transaction_receipts table
        // (bytea -> BLOB, timestamp -> TEXT, ON DELETE CASCADE preserved)
        db.execSQL("CREATE TABLE IF NOT EXISTS transaction_receipts (" +
                "id             INTEGER PRIMARY KEY AUTOINCREMENT," +
                "transaction_id INTEGER NOT NULL REFERENCES transactions(id) ON DELETE CASCADE," +
                "file_name      TEXT NOT NULL," +
                "file_type      TEXT," +
                "file_data      BLOB," +
                "file_size      INTEGER," +
                "uploaded_at    TEXT DEFAULT (datetime('now'))," +
                "created_at     TEXT DEFAULT (datetime('now'))," +
                "updated_at     TEXT DEFAULT (datetime('now')))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_receipts_txn ON transaction_receipts(transaction_id)");

        // Seed default categories
        String[] incomes = {"Salary", "Freelance", "Gift", "Other"};
        String[] expenses = {"Food", "Transport", "Merchandise",
                "Health", "Entertainment", "Other","Snacks"};
        for (String c : incomes)
            db.execSQL("INSERT OR IGNORE INTO categories(name,type,book_id) VALUES('" + c + "','INCOME',NULL)");
        for (String c : expenses)
            db.execSQL("INSERT OR IGNORE INTO categories(name,type,book_id) VALUES('" + c + "','EXPENSE',NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // Fresh install-ஆ இருந்தா onCreate() call ஆகும் — migration வேண்டாம்
        // Existing install upgrade மட்டும்
        if (oldV < 5) {
            // transaction_custom_values missing-ஆ இருந்தா create பண்ணு
            db.execSQL("CREATE TABLE IF NOT EXISTS transaction_custom_values (" +
                    "id             INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "transaction_id INTEGER NOT NULL REFERENCES transactions(id) ON DELETE CASCADE," +
                    "col_def_id     INTEGER NOT NULL REFERENCES column_definitions(id)," +
                    "value          TEXT," +
                    "UNIQUE(transaction_id,col_def_id))");

            // updated_at missing-ஆ இருந்தா add பண்ணு
            try { db.execSQL("ALTER TABLE cash_books ADD COLUMN updated_at TEXT DEFAULT (datetime('now'))"); }
            catch (Exception ignored) {}

            // book_id missing-ஆ இருந்தா add பண்ணு
            try { db.execSQL("ALTER TABLE categories ADD COLUMN book_id INTEGER REFERENCES cash_books(id)"); }
            catch (Exception ignored) {}
        }

        // v5 → v6: devices whose "transactions" table was created before
        // sub_categories_id existed (stored version already at 5, so the
        // old `if (oldV < 5)` block above never ran for them).
        if (oldV < 6) {
            try { db.execSQL("ALTER TABLE transactions ADD COLUMN sub_categories_id INTEGER REFERENCES sub_categories(id)"); }
            catch (Exception ignored) {}
        }

        // v6 → v7: add transaction_receipts for existing installs
        if (oldV < 7) {
            db.execSQL("CREATE TABLE IF NOT EXISTS transaction_receipts (" +
                    "id             INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "transaction_id INTEGER NOT NULL REFERENCES transactions(id) ON DELETE CASCADE," +
                    "file_name      TEXT NOT NULL," +
                    "file_type      TEXT," +
                    "file_data      BLOB," +
                    "file_size      INTEGER," +
                    "uploaded_at    TEXT DEFAULT (datetime('now'))," +
                    "created_at     TEXT DEFAULT (datetime('now'))," +
                    "updated_at     TEXT DEFAULT (datetime('now')))");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_receipts_txn ON transaction_receipts(transaction_id)");
        }
            if (oldV < 9) {
                // Drop older simple audit log if it exists to avoid conflicts
                db.execSQL("DROP TABLE IF EXISTS audit_log;");

                // audit_log
                db.execSQL("CREATE TABLE IF NOT EXISTS transaction_audit_log (" +
                        "id             INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "transaction_id INTEGER NOT NULL REFERENCES transactions(id) ON DELETE CASCADE," +
                        "action         TEXT NOT NULL," +
                        "changed_by     TEXT DEFAULT 'user'," +
                        "changed_at     TEXT DEFAULT (datetime('now'))," +
                        "field_name     TEXT," +
                        "old_value      TEXT," +
                        "new_value      TEXT," +
                        "note           TEXT," +
                        "created_at     TEXT DEFAULT (datetime('now'))," +
                        "updated_at     TEXT DEFAULT (datetime('now'))" +
                        ")");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_audit_changed ON transaction_audit_log(changed_at DESC);");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_audit_txn_id ON transaction_audit_log(transaction_id ASC);");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_transaction_audit_log_updated ON transaction_audit_log(updated_at ASC);");
            }
        }

        @Override
        public void onOpen(SQLiteDatabase db) {
            super.onOpen(db);
            db.execSQL("PRAGMA foreign_keys = ON;");
        }
    }