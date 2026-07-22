package com.expenseos.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class LocalDB extends SQLiteOpenHelper {

    private static final String DB_NAME = "expenseos.db";
    private static final int DB_VERSION = 17; // bumped: fixed v11 migration that dropped created_at/updated_at from transactions
    private static LocalDB instance;
    // Every table that has a manually-assigned "id" column now gets a row
    // here so its next id can be reserved before insert. Keep this list in
    // sync with the id-bearing tables created below.
    private static final String[] ID_TABLES = {
            "cash_books", "categories", "sub_categories", "column_definitions",
            "transactions", "transaction_custom_values", "deleted_records",
            "transaction_audit_log", "transaction_receipts",
            "schedulers", "scheduler_log"
    };

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
                "id          INTEGER PRIMARY KEY," +
                "name        TEXT NOT NULL," +
                "description TEXT," +
                "created_at  TEXT DEFAULT (datetime('now'))," +
                "updated_at  TEXT DEFAULT (datetime('now'))," +
                "is_active   INTEGER DEFAULT 1," +
                "synced      INTEGER DEFAULT 0)");

        // categories
        db.execSQL("CREATE TABLE IF NOT EXISTS categories (" +
                "id      INTEGER PRIMARY KEY," +
                "name    TEXT NOT NULL," +
                "type    TEXT NOT NULL CHECK(type IN ('INCOME','EXPENSE'))," +
                "book_id INTEGER REFERENCES cash_books(id)," +
                "created_at  TEXT DEFAULT (datetime('now'))," +
                "updated_at  TEXT DEFAULT (datetime('now'))," +
                "synced  INTEGER DEFAULT 0," +
                "UNIQUE(name,type,book_id))");

        // sub_categories
        db.execSQL("CREATE TABLE IF NOT EXISTS sub_categories (" +
                "id          INTEGER PRIMARY KEY," +
                "name        TEXT NOT NULL," +
                "category_id INTEGER NOT NULL REFERENCES categories(id)," +
                "created_at  TEXT DEFAULT (datetime('now'))," +
                "updated_at  TEXT DEFAULT (datetime('now'))," +
                "synced      INTEGER DEFAULT 0)");

        // column_definitions
        db.execSQL("CREATE TABLE IF NOT EXISTS column_definitions (" +
                "id       INTEGER PRIMARY KEY," +
                "col_name TEXT NOT NULL," +
                "col_key  TEXT NOT NULL," +
                "type     TEXT NOT NULL," +
                "created_at  TEXT DEFAULT (datetime('now'))," +
                "updated_at  TEXT DEFAULT (datetime('now'))," +
                "synced   INTEGER DEFAULT 0," +
                "UNIQUE(col_key,type))");

        // transactions — sub_categories_id column name
        db.execSQL("CREATE TABLE IF NOT EXISTS transactions (" +
                "id                INTEGER PRIMARY KEY," +
                "type              TEXT NOT NULL CHECK(type IN ('INCOME','EXPENSE'))," +
                "txn_datetime      TEXT NOT NULL," +
                "amount            REAL NOT NULL," +
                "category_id       INTEGER REFERENCES categories(id)," +
                "sub_categories_id INTEGER REFERENCES sub_categories(id)," +
                "note              TEXT," +
                "book_id           INTEGER REFERENCES cash_books(id)," +
                "created_at  TEXT DEFAULT (datetime('now'))," +
                "updated_at  TEXT DEFAULT (datetime('now'))," +
                "synced            INTEGER DEFAULT 0)");

        // transaction_custom_values ← இது முக்கியம்!
        db.execSQL("CREATE TABLE IF NOT EXISTS transaction_custom_values (" +
                "id             INTEGER PRIMARY KEY," +
                "transaction_id INTEGER NOT NULL REFERENCES transactions(id) ON DELETE CASCADE," +
                "col_def_id     INTEGER NOT NULL REFERENCES column_definitions(id)," +
                "value          TEXT," +
                "created_at  TEXT DEFAULT (datetime('now'))," +
                "updated_at  TEXT DEFAULT (datetime('now'))," +
                "UNIQUE(transaction_id,col_def_id))");

        // deleted_records (tombstone)
        db.execSQL("CREATE TABLE IF NOT EXISTS deleted_records (" +
                "id         INTEGER PRIMARY KEY," +
                "table_name TEXT NOT NULL," +
                "record_id  INTEGER NOT NULL," +
                "deleted_at TEXT DEFAULT (datetime('now'))," +
                "synced     INTEGER DEFAULT 0," +
                "UNIQUE(table_name,record_id))");

        // app_config
        db.execSQL("CREATE TABLE IF NOT EXISTS app_config (" +
                "key        TEXT PRIMARY KEY," +
                "value      TEXT," +
                "created_at  TEXT DEFAULT (datetime('now'))," +
                "updated_at TEXT DEFAULT (datetime('now')))");

        // audit_log
        db.execSQL("CREATE TABLE IF NOT EXISTS transaction_audit_log (" +
                "id             INTEGER PRIMARY KEY," +
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
                "id             INTEGER PRIMARY KEY," +
                "transaction_id INTEGER NOT NULL REFERENCES transactions(id) ON DELETE CASCADE," +
                "file_name      TEXT NOT NULL," +
                "file_type      TEXT," +
                "file_data      BLOB," +
                "file_size      INTEGER," +
                "uploaded_at    TEXT DEFAULT (datetime('now'))," +
                "created_at     TEXT DEFAULT (datetime('now'))," +
                "updated_at     TEXT DEFAULT (datetime('now')))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_receipts_txn ON transaction_receipts(transaction_id)");

        // schedulers — mirrors public.schedulers (Postgres) on the server side
        db.execSQL("CREATE TABLE IF NOT EXISTS schedulers (" +
                "id              INTEGER PRIMARY KEY," +
                "name            TEXT NOT NULL UNIQUE," +
                "display_name    TEXT NOT NULL," +
                "enabled         INTEGER NOT NULL DEFAULT 1," +
                "repeat_type     TEXT NOT NULL DEFAULT 'DAILY'," +
                "repeat_days     TEXT," +
                "run_hour        INTEGER NOT NULL DEFAULT 0," +
                "run_minute      INTEGER NOT NULL DEFAULT 0," +
                "last_run_at     TEXT," +
                "last_run_status TEXT," +
                "last_run_msg    TEXT," +
                "next_run_at     TEXT," +
                "created_at      TEXT NOT NULL DEFAULT (datetime('now'))," +
                "updated_at      TEXT NOT NULL DEFAULT (datetime('now')))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_schedulers_updated ON schedulers(updated_at ASC)");

        SchedulerSeedData.insert(db);

        // scheduler_log — mirrors public.scheduler_log (Postgres) on the server side
        db.execSQL("CREATE TABLE IF NOT EXISTS scheduler_log (" +
                "id           INTEGER PRIMARY KEY," +
                "scheduler_id INTEGER NOT NULL REFERENCES schedulers(id)," +
                "started_at   TEXT NOT NULL DEFAULT (datetime('now'))," +
                "finished_at  TEXT," +
                "status       TEXT NOT NULL DEFAULT 'RUNNING'," +
                "message      TEXT," +
                "rows_synced  INTEGER DEFAULT 0," +
                "created_at   TEXT DEFAULT (datetime('now'))," +
                "updated_at   TEXT DEFAULT (datetime('now')))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sched_log_id ON scheduler_log(scheduler_id ASC, started_at DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_scheduler_log_updated ON scheduler_log(updated_at ASC)");

        db.execSQL("CREATE TABLE IF NOT EXISTS backup_history (" +
                "id              INTEGER PRIMARY KEY AUTOINCREMENT," +
                "file_name       TEXT NOT NULL," +
                "file_path       TEXT," +                    // local path; CLOUD-only entries can be empty after upload
                "file_size_bytes INTEGER DEFAULT 0," +
                "backup_type     TEXT DEFAULT 'MANUAL'," +
                "backup_mode     TEXT NOT NULL DEFAULT 'LOCAL'," +   // LOCAL | CLOUD
                "status          TEXT DEFAULT 'SUCCESS'," +
                "description     TEXT," +
                "income_count    INTEGER DEFAULT 0," +
                "expense_count   INTEGER DEFAULT 0," +
                "external_id     TEXT," +                     // Zoho WorkDrive resource_id, null for LOCAL
                "created_at      TEXT DEFAULT (datetime('now')))");

        // id_sequences — app-controlled "next id" per table, replacing
        // AUTOINCREMENT so that ids stay predictable/reservable and won't
        // clash when merging rows migrated in from another (server) DB.
        db.execSQL("CREATE TABLE IF NOT EXISTS id_sequences (" +
                "table_name TEXT PRIMARY KEY," +
                "next_id    INTEGER NOT NULL DEFAULT 1)");
        // Seed default categories
        String[] incomes = {"Salary", "Freelance", "Gift", "Other"};
        String[] expenses = {"Food", "Transport", "Merchandise",
                "Health", "Entertainment", "Other", "Snacks"};
        for (String c : incomes)
            db.execSQL("INSERT OR IGNORE INTO categories(name,type,book_id) VALUES('" + c + "','INCOME',NULL)");
        for (String c : expenses)
            db.execSQL("INSERT OR IGNORE INTO categories(name,type,book_id) VALUES('" + c + "','EXPENSE',NULL)");
        // Now that every table exists (and seed rows are in), initialise
        // each table's sequence to MAX(id)+1 so the next insert doesn't
        // collide with anything already present.
        initSequences(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // Fresh install-ஆ இருந்தா onCreate() call ஆகும் — migration வேண்டாம்
        // Existing install upgrade மட்டும்
        if (oldV < 5) {
            // transaction_custom_values missing-ஆ இருந்தா create பண்ணு
            db.execSQL("CREATE TABLE IF NOT EXISTS transaction_custom_values (" +
                    "id             INTEGER PRIMARY KEY," +
                    "transaction_id INTEGER NOT NULL REFERENCES transactions(id) ON DELETE CASCADE," +
                    "col_def_id     INTEGER NOT NULL REFERENCES column_definitions(id)," +
                    "value          TEXT," +
                    "UNIQUE(transaction_id,col_def_id))");

            // updated_at missing-ஆ இருந்தா add பண்ணு
            try {
                db.execSQL("ALTER TABLE cash_books ADD COLUMN updated_at TEXT DEFAULT (datetime('now'))");
            } catch (Exception ignored) {
            }

            // book_id missing-ஆ இருந்தா add பண்ணு
            try {
                db.execSQL("ALTER TABLE categories ADD COLUMN book_id INTEGER REFERENCES cash_books(id)");
            } catch (Exception ignored) {
            }
        }

        // v5 → v6: devices whose "transactions" table was created before
        // sub_categories_id existed (stored version already at 5, so the
        // old `if (oldV < 5)` block above never ran for them).
        if (oldV < 6) {
            try {
                db.execSQL("ALTER TABLE transactions ADD COLUMN sub_categories_id INTEGER REFERENCES sub_categories(id)");
            } catch (Exception ignored) {
            }
        }

        // v6 → v7: add transaction_receipts for existing installs
        if (oldV < 7) {
            db.execSQL("CREATE TABLE IF NOT EXISTS transaction_receipts (" +
                    "id             INTEGER PRIMARY KEY," +
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
                    "id             INTEGER PRIMARY KEY," +
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
        // v9 → v10: drop AUTOINCREMENT everywhere in favour of an explicit
        // id_sequences table. Existing "id INTEGER PRIMARY KEY AUTOINCREMENT"
        // columns keep working fine without being rebuilt (AUTOINCREMENT is
        // just extra bookkeeping in sqlite_sequence); we simply stop relying
        // on it and start reserving ids ourselves from here on.
        if (oldV < 11) {
            db.execSQL("DROP TABLE IF EXISTS transactions;");

            db.execSQL("CREATE TABLE IF NOT EXISTS transactions (" +
                    "id                INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "type              TEXT NOT NULL CHECK(type IN ('INCOME','EXPENSE'))," +
                    "txn_datetime      TEXT NOT NULL," +
                    "amount            REAL NOT NULL," +
                    "category_id       INTEGER REFERENCES categories(id)," +
                    "sub_categories_id INTEGER REFERENCES sub_categories(id)," +
                    "note              TEXT," +
                    "book_id           INTEGER REFERENCES cash_books(id)," +
                    "created_at        TEXT DEFAULT (datetime('now'))," +
                    "updated_at        TEXT DEFAULT (datetime('now'))," +
                    "synced            INTEGER DEFAULT 0)");

        }

        // v11 → v12: devices that already upgraded to the broken v11 schema
        // (transactions table recreated WITHOUT created_at/updated_at) need
        // those columns added back in place, without losing existing rows.
        if (oldV < 13) {
            try {
                db.execSQL("ALTER TABLE transactions ADD COLUMN created_at TEXT DEFAULT (datetime('now'))");
            } catch (Exception ignored) {
            }
            try {
                db.execSQL("ALTER TABLE transactions ADD COLUMN updated_at TEXT DEFAULT (datetime('now'))");
            } catch (Exception ignored) {
            }
        }

        if (oldV < 14) {
            db.execSQL("CREATE TABLE IF NOT EXISTS deleted_records (" +
                    "id         INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "table_name TEXT NOT NULL," +
                    "record_id  INTEGER NOT NULL," +
                    "deleted_at TEXT DEFAULT (datetime('now'))," +
                    "synced     INTEGER DEFAULT 0," +
                    "UNIQUE(table_name,record_id))");
        }

        if (oldV < 15) {
            db.execSQL("CREATE TABLE IF NOT EXISTS schedulers (" +
                    "id              INTEGER PRIMARY KEY," +
                    "name            TEXT NOT NULL UNIQUE," +
                    "display_name    TEXT NOT NULL," +
                    "enabled         INTEGER NOT NULL DEFAULT 1," +
                    "repeat_type     TEXT NOT NULL DEFAULT 'DAILY'," +
                    "repeat_days     TEXT," +
                    "run_hour        INTEGER NOT NULL DEFAULT 0," +
                    "run_minute      INTEGER NOT NULL DEFAULT 0," +
                    "last_run_at     TEXT," +
                    "last_run_status TEXT," +
                    "last_run_msg    TEXT," +
                    "next_run_at     TEXT," +
                    "created_at      TEXT NOT NULL DEFAULT (datetime('now'))," +
                    "updated_at      TEXT NOT NULL DEFAULT (datetime('now')))");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_schedulers_updated ON schedulers(updated_at ASC)");

            db.execSQL("CREATE TABLE IF NOT EXISTS scheduler_log (" +
                    "id           INTEGER PRIMARY KEY," +
                    "scheduler_id INTEGER NOT NULL REFERENCES schedulers(id)," +
                    "started_at   TEXT NOT NULL DEFAULT (datetime('now'))," +
                    "finished_at  TEXT," +
                    "status       TEXT NOT NULL DEFAULT 'RUNNING'," +
                    "message      TEXT," +
                    "rows_synced  INTEGER DEFAULT 0," +
                    "created_at   TEXT DEFAULT (datetime('now'))," +
                    "updated_at   TEXT DEFAULT (datetime('now')))");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_sched_log_id ON scheduler_log(scheduler_id ASC, started_at DESC)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_scheduler_log_updated ON scheduler_log(updated_at ASC)");

            db.execSQL("CREATE TABLE IF NOT EXISTS id_sequences (" +
                    "table_name TEXT PRIMARY KEY," +
                    "next_id    INTEGER NOT NULL DEFAULT 1)");
            initSequences(db);
        }

        if (oldV < 16) {
            SchedulerSeedData.insert(db);
        }

        if (oldV < 17) {
            if (oldV < 10) {
                db.execSQL("CREATE TABLE IF NOT EXISTS backup_history (" +
                        "id              INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "file_name       TEXT NOT NULL," +
                        "file_path       TEXT," +
                        "file_size_bytes INTEGER DEFAULT 0," +
                        "backup_type     TEXT DEFAULT 'MANUAL'," +
                        "backup_mode     TEXT NOT NULL DEFAULT 'LOCAL'," +
                        "status          TEXT DEFAULT 'SUCCESS'," +
                        "description     TEXT," +
                        "income_count    INTEGER DEFAULT 0," +
                        "expense_count   INTEGER DEFAULT 0," +
                        "external_id     TEXT," +
                        "created_at      TEXT DEFAULT (datetime('now')))");
            }
        }
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        db.execSQL("PRAGMA foreign_keys = ON;");
    }

    /**
     * Sets each table's next_id to MAX(id)+1 (or 1 if the table is empty).
     */
    private void initSequences(SQLiteDatabase db) {
        for (String table : ID_TABLES) {
            db.execSQL("INSERT OR REPLACE INTO id_sequences(table_name, next_id) " +
                    "VALUES('" + table + "', (SELECT COALESCE(MAX(id),0)+1 FROM " + table + "))");
        }
    }

    /**
     * Reserves and returns the next id for the given table, bumping the
     * counter in id_sequences. Callers must put the returned value into
     * their ContentValues as "id" before inserting.
     * <p>
     * DAOs should call this instead of relying on AUTOINCREMENT, e.g.:
     * long id = LocalDB.getInstance(ctx).getNextId("sub_categories");
     * cv.put("id", id);
     */
    public synchronized long getNextId(String tableName) {
        SQLiteDatabase wdb = getWritableDatabase();
        wdb.beginTransaction();
        try {
            long nextId;
            Cursor c = wdb.rawQuery(
                    "SELECT next_id FROM id_sequences WHERE table_name=?",
                    new String[]{tableName});
            if (c.moveToFirst()) {
                nextId = c.getLong(0);
                c.close();
                ContentValues cv = new ContentValues();
                cv.put("next_id", nextId + 1);
                wdb.update("id_sequences", cv, "table_name=?", new String[]{tableName});
            } else {
                c.close();
                nextId = 1;
                ContentValues cv = new ContentValues();
                cv.put("table_name", tableName);
                cv.put("next_id", 2);
                wdb.insertOrThrow("id_sequences", null, cv);
            }
            wdb.setTransactionSuccessful();
            return nextId;
        } finally {
            wdb.endTransaction();
        }
    }
}