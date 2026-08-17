package com.expenseos.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.expenseos.util.ConsoleLogger;

public class LocalDB extends SQLiteOpenHelper {

    private static final String DB_NAME = "expenseos.db";
    private static final int DB_VERSION = 33; // bumped: added events, reminders, tasks
    // bumped: added keyword_mappings (auto-suggest category/sub-category from description)
    private static LocalDB instance;
    private final ConsoleLogger log = ConsoleLogger.get();
    // Every table that has a manually-assigned "id" column now gets a row
    // here so its next id can be reserved before insert. Keep this list in
    // sync with the id-bearing tables created below.
    private static final String[] ID_TABLES = {
            "cash_books", "categories", "sub_categories", "column_definitions",
            "transactions", "transaction_custom_values", "deleted_records",
            "transaction_audit_log", "transaction_receipts", "schedulers",
            "scheduler_log", "budgets", "budget_categories", "payment_types",
            "keyword_mappings", "events", "reminders", "event_reminders", "tasks", "task_events", "task_alarms"
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
                "payment_type      TEXT NOT NULL DEFAULT 'UPI'," +
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
                "created_at      TEXT DEFAULT (datetime('now'))," +
                "updated_at     TEXT NOT NULL DEFAULT (datetime('now')))");

        // budgets — mirrors public.budgets (Postgres)
        db.execSQL("CREATE TABLE IF NOT EXISTS budgets (" +
                "id             INTEGER PRIMARY KEY," +
                "book_id        INTEGER NOT NULL," +
                "year           INTEGER NOT NULL," +
                "month          INTEGER NOT NULL," +
                "overall_limit  REAL NOT NULL DEFAULT 0," +
                "created_at     TEXT NOT NULL DEFAULT (datetime('now'))," +
                "updated_at     TEXT NOT NULL DEFAULT (datetime('now'))," +
                "UNIQUE(book_id, year, month))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_budgets_book_ym ON budgets(book_id, year, month)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_budgets_updated ON budgets(updated_at)");

        // budget_categories — mirrors public.budget_categories (Postgres)
        db.execSQL("CREATE TABLE IF NOT EXISTS budget_categories (" +
                "id          INTEGER PRIMARY KEY," +
                "budget_id   INTEGER NOT NULL REFERENCES budgets(id) ON DELETE CASCADE," +
                "category_id INTEGER NOT NULL," +
                "cat_limit   REAL NOT NULL DEFAULT 0," +
                "alert_pct   INTEGER NOT NULL DEFAULT 80," +
                "created_at  TEXT DEFAULT (datetime('now'))," +
                "updated_at  TEXT DEFAULT (datetime('now'))," +
                "UNIQUE(budget_id, category_id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_budcat_budget ON budget_categories(budget_id)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_budget_categories_updated ON budget_categories(updated_at)");

        // payment_types — UPI, Cash, credit/debit cards etc. (universal, not income/expense-specific)
        db.execSQL("CREATE TABLE IF NOT EXISTS payment_types (" +
                "id         INTEGER PRIMARY KEY," +
                "name       TEXT NOT NULL UNIQUE," +
                "is_default INTEGER NOT NULL DEFAULT 0," +
                "created_at TEXT DEFAULT (datetime('now'))," +
                "updated_at TEXT DEFAULT (datetime('now'))," +
                "synced     INTEGER DEFAULT 0)");
        seedDefaultPaymentTypes(db);
        db.execSQL("UPDATE payment_types SET is_default=1 WHERE name='UPI'"); // sensible out-of-box default

        db.execSQL("CREATE TABLE IF NOT EXISTS passbook_entries (" +
                "sms_id           INTEGER PRIMARY KEY," +
                "type             TEXT NOT NULL," +
                "amount           TEXT NOT NULL," +
                "sender           TEXT," +
                "raw_body         TEXT," +
                "remark           TEXT," +          // <-- new
                "timestamp_millis INTEGER NOT NULL," +
                "copied           INTEGER DEFAULT 0)");

        // keyword_mappings — Description keyword -> Category/Sub-category,
        // used to auto-pick a category while typing the Remark/Note.
        db.execSQL("CREATE TABLE IF NOT EXISTS keyword_mappings (" +
                "id              INTEGER PRIMARY KEY," +
                "keyword         TEXT NOT NULL," +
                "type            TEXT NOT NULL," +           // INCOME | EXPENSE
                "category_id     INTEGER NOT NULL," +
                "sub_category_id INTEGER," +
                "book_id         INTEGER," +                 // NULL = common
                "created_at      TEXT DEFAULT (datetime('now'))," +
                "updated_at      TEXT DEFAULT (datetime('now'))," +
                "synced          INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_keyword_mappings_keyword ON keyword_mappings(keyword)");

        // events — the "before/after N days" trigger definitions
        db.execSQL("CREATE TABLE IF NOT EXISTS events (" +
                "id               INTEGER PRIMARY KEY," +
                "name             TEXT NOT NULL," +
                "offset_direction TEXT NOT NULL DEFAULT 'BEFORE' CHECK(offset_direction IN ('BEFORE','AFTER'))," +
                "offset_days      INTEGER NOT NULL DEFAULT 0," +
                "header           TEXT," +
                "created_at       TEXT DEFAULT (datetime('now'))," +
                "updated_at       TEXT DEFAULT (datetime('now'))," +
                "synced           INTEGER DEFAULT 0)");

        // reminders — reusable named reminder templates (e.g. "1 day before 7pm")
// reminders — standalone, reusable, named: name + offset (days/weeks before) + time
        db.execSQL("CREATE TABLE IF NOT EXISTS reminders (" +
                "id           INTEGER PRIMARY KEY," +
                "name         TEXT NOT NULL," +
                "offset_value INTEGER NOT NULL DEFAULT 0," +
                "offset_unit  TEXT NOT NULL DEFAULT 'DAY' CHECK(offset_unit IN ('DAY','WEEK'))," +
                "time_hour    INTEGER NOT NULL," +
                "time_minute  INTEGER NOT NULL," +
                "created_at   TEXT DEFAULT (datetime('now'))," +
                "updated_at   TEXT DEFAULT (datetime('now')))");

        // event_reminders — just links an event to one reminder for NOTIFICATION and one for ALARM
        db.execSQL("CREATE TABLE IF NOT EXISTS event_reminders (" +
                "id          INTEGER PRIMARY KEY," +
                "event_id    INTEGER NOT NULL REFERENCES events(id) ON DELETE CASCADE," +
                "reminder_id INTEGER NOT NULL REFERENCES reminders(id)," +
                "type        TEXT NOT NULL CHECK(type IN ('NOTIFICATION','ALARM'))," +
                "UNIQUE(event_id, type))");

        // tasks — Phase 2 la use aagum, table ippove create pannitrom
        db.execSQL("CREATE TABLE IF NOT EXISTS tasks (" +
                "id               INTEGER PRIMARY KEY," +
                "name             TEXT NOT NULL," +
                "task_datetime    TEXT NOT NULL," +
                "description      TEXT," +
                "color            TEXT," +
                "google_event_id  TEXT," +
                "created_at       TEXT DEFAULT (datetime('now'))," +
                "updated_at       TEXT DEFAULT (datetime('now'))," +
                "synced           INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE IF NOT EXISTS task_events (" +
                "id       INTEGER PRIMARY KEY," +
                "task_id  INTEGER NOT NULL REFERENCES tasks(id) ON DELETE CASCADE," +
                "event_id INTEGER NOT NULL REFERENCES events(id)," +
                "UNIQUE(task_id,event_id))");

        // task_alarms — tracks which AlarmManager request_code maps to which
        // task+event_reminder, so edit/delete can cancel the exact pending alarm
        db.execSQL("CREATE TABLE IF NOT EXISTS task_alarms (" +
                "id                 INTEGER PRIMARY KEY," +
                "task_id            INTEGER NOT NULL REFERENCES tasks(id) ON DELETE CASCADE," +
                "event_reminder_id  INTEGER NOT NULL REFERENCES event_reminders(id) ON DELETE CASCADE," +
                "request_code       INTEGER NOT NULL UNIQUE," +
                "trigger_at         TEXT NOT NULL," +
                "type               TEXT NOT NULL)");

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

        // v17 → v18: add budgets, budget_categories for existing installs
        if (oldV < 19) {

            try {
                db.execSQL("ALTER TABLE backup_history ADD COLUMN updated_at TEXT DEFAULT (datetime('now'))");
            } catch (Exception ignored) {
            }

            db.execSQL("CREATE TABLE IF NOT EXISTS budgets (" +
                    "id             INTEGER PRIMARY KEY," +
                    "book_id        INTEGER NOT NULL," +
                    "year           INTEGER NOT NULL," +
                    "month          INTEGER NOT NULL," +
                    "overall_limit  REAL NOT NULL DEFAULT 0," +
                    "created_at     TEXT NOT NULL DEFAULT (datetime('now'))," +
                    "updated_at     TEXT NOT NULL DEFAULT (datetime('now'))," +
                    "UNIQUE(book_id, year, month))");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_budgets_book_ym ON budgets(book_id, year, month)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_budgets_updated ON budgets(updated_at)");

            db.execSQL("CREATE TABLE IF NOT EXISTS budget_categories (" +
                    "id          INTEGER PRIMARY KEY," +
                    "budget_id   INTEGER NOT NULL REFERENCES budgets(id) ON DELETE CASCADE," +
                    "category_id INTEGER NOT NULL," +
                    "cat_limit   REAL NOT NULL DEFAULT 0," +
                    "alert_pct   INTEGER NOT NULL DEFAULT 80," +
                    "created_at  TEXT DEFAULT (datetime('now'))," +
                    "updated_at  TEXT DEFAULT (datetime('now'))," +
                    "UNIQUE(budget_id, category_id))");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_budcat_budget ON budget_categories(budget_id)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_budget_categories_updated ON budget_categories(updated_at)");

            // Seed next_id rows for the two new tables (safe to re-run for
            // existing tables too — it just recomputes MAX(id)+1, which is
            // already correct for anything with rows in it).
            initSequences(db);
        }

        if (oldV < 22) {
            // v18 → v19: defensive repair — some upgrade paths left cash_books
// (and potentially other tables) missing columns that onCreate() always
// includes for fresh installs. ALTER TABLE ADD COLUMN is safe to retry:
// it throws (caught + ignored) if the column already exists, so this
// runs harmlessly on every already-healthy device too.
            try {
                db.execSQL("ALTER TABLE cash_books ADD COLUMN updated_at TEXT DEFAULT (datetime('now'))");
            } catch (Exception ignored) {
            }
            try {
                db.execSQL("ALTER TABLE cash_books ADD COLUMN created_at TEXT DEFAULT (datetime('now'))");
            } catch (Exception ignored) {
            }
            try {
                db.execSQL("ALTER TABLE cash_books ADD COLUMN is_active INTEGER DEFAULT 1");
            } catch (Exception ignored) {
            }
            try {
                db.execSQL("ALTER TABLE cash_books ADD COLUMN synced INTEGER DEFAULT 0");
            } catch (Exception ignored) {
            }

            // Same defensive pattern for categories/sub_categories/transactions —
            // covers any device that hit the same partial-migration gap.
            try {
                db.execSQL("ALTER TABLE categories ADD COLUMN created_at TEXT DEFAULT (datetime('now'))");
            } catch (Exception ignored) {
            }
            try {
                db.execSQL("ALTER TABLE categories ADD COLUMN updated_at TEXT DEFAULT (datetime('now'))");
            } catch (Exception ignored) {
            }
            try {
                db.execSQL("ALTER TABLE sub_categories ADD COLUMN created_at TEXT DEFAULT (datetime('now'))");
            } catch (Exception ignored) {
            }
            try {
                db.execSQL("ALTER TABLE sub_categories ADD COLUMN updated_at TEXT DEFAULT (datetime('now'))");
            } catch (Exception ignored) {
            }
        }

//        if (oldV < 28) {
//            for (String table : ID_TABLES) {
//                // 1. created_at காலம் சேர்த்தல்
//                try {
//                    db.execSQL("ALTER TABLE " + table + " ADD COLUMN created_at TEXT DEFAULT ''");
//                } catch (Exception e) {
//                    log.error("DB_UPGRADE " + table + " --> column created_at already exists or error: " + e.getMessage());
//                }
//
//                // 2. updated_at காலம் சேர்த்தல்
//                try {
//                    db.execSQL("ALTER TABLE " + table + " ADD COLUMN updated_at TEXT DEFAULT ''");
//                } catch (Exception e) {
//                    log.error("DB_UPGRADE " + table + " --> column updated_at already exists or error: " + e.getMessage());
//                }
//            }
//        }

        if (oldV < 27) {
            db.execSQL("CREATE TABLE IF NOT EXISTS passbook_entries (" +
                    "sms_id           INTEGER PRIMARY KEY," +
                    "type             TEXT NOT NULL," +
                    "amount           TEXT NOT NULL," +
                    "sender           TEXT," +
                    "raw_body         TEXT," +
                    "remark           TEXT," +          // <-- new
                    "timestamp_millis INTEGER NOT NULL," +
                    "copied           INTEGER DEFAULT 0)");
        }

        if (oldV < 29) {
            db.execSQL("CREATE TABLE IF NOT EXISTS payment_types (" +
                    "id         INTEGER PRIMARY KEY," +
                    "name       TEXT NOT NULL UNIQUE," +
                    "created_at TEXT DEFAULT (datetime('now'))," +
                    "updated_at TEXT DEFAULT (datetime('now'))," +
                    "synced     INTEGER DEFAULT 0)");
            seedDefaultPaymentTypes(db);

            try {
                db.execSQL("ALTER TABLE transactions ADD COLUMN payment_type TEXT NOT NULL DEFAULT 'UPI'");
            } catch (Exception ignored) {
            }

            // Existing rows created before this column existed — explicit default,
            // in case the ALTER's DEFAULT clause doesn't backfill on this device.
            db.execSQL("UPDATE transactions SET payment_type='UPI' WHERE payment_type IS NULL OR payment_type=''");

            initSequences(db);
        }

        if (oldV < 30) {
            try {
                db.execSQL("ALTER TABLE payment_types ADD COLUMN is_default INTEGER NOT NULL DEFAULT 0");
                db.execSQL("UPDATE payment_types SET is_default=1 WHERE name='UPI'");
            } catch (Exception ignored) {
            }
        }

        if (oldV < 31) {
            db.execSQL("CREATE TABLE IF NOT EXISTS keyword_mappings (" +
                    "id              INTEGER PRIMARY KEY," +
                    "keyword         TEXT NOT NULL," +
                    "type            TEXT NOT NULL," +
                    "category_id     INTEGER NOT NULL," +
                    "sub_category_id INTEGER," +
                    "book_id         INTEGER," +
                    "created_at      TEXT DEFAULT (datetime('now'))," +
                    "updated_at      TEXT DEFAULT (datetime('now'))," +
                    "synced          INTEGER DEFAULT 0)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_keyword_mappings_keyword ON keyword_mappings(keyword)");
            initSequences(db);
        }

        if (oldV < 32) {
            db.execSQL("CREATE TABLE IF NOT EXISTS events (" +
                    "id INTEGER PRIMARY KEY, name TEXT NOT NULL," +
                    "offset_direction TEXT NOT NULL DEFAULT 'BEFORE' CHECK(offset_direction IN ('BEFORE','AFTER'))," +
                    "offset_days INTEGER NOT NULL DEFAULT 0, header TEXT," +
                    "created_at TEXT DEFAULT (datetime('now')), updated_at TEXT DEFAULT (datetime('now'))," +
                    "synced INTEGER DEFAULT 0)");
            db.execSQL("CREATE TABLE IF NOT EXISTS reminders (" +
                    "id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE," +
                    "created_at TEXT DEFAULT (datetime('now')), updated_at TEXT DEFAULT (datetime('now')))");
            db.execSQL("CREATE TABLE IF NOT EXISTS event_reminders (" +
                    "id INTEGER PRIMARY KEY, event_id INTEGER NOT NULL REFERENCES events(id) ON DELETE CASCADE," +
                    "reminder_id INTEGER NOT NULL REFERENCES reminders(id)," +
                    "type TEXT NOT NULL CHECK(type IN ('NOTIFICATION','ALARM'))," +
                    "offset_direction TEXT NOT NULL DEFAULT 'BEFORE' CHECK(offset_direction IN ('BEFORE','AFTER'))," +
                    "offset_days INTEGER NOT NULL DEFAULT 0, time_hour INTEGER NOT NULL, time_minute INTEGER NOT NULL," +
                    "created_at TEXT DEFAULT (datetime('now')), updated_at TEXT DEFAULT (datetime('now')))");
            db.execSQL("CREATE TABLE IF NOT EXISTS tasks (" +
                    "id INTEGER PRIMARY KEY, name TEXT NOT NULL, task_datetime TEXT NOT NULL," +
                    "description TEXT, color TEXT, google_event_id TEXT," +
                    "created_at TEXT DEFAULT (datetime('now')), updated_at TEXT DEFAULT (datetime('now'))," +
                    "synced INTEGER DEFAULT 0)");
            db.execSQL("CREATE TABLE IF NOT EXISTS task_events (" +
                    "id INTEGER PRIMARY KEY, task_id INTEGER NOT NULL REFERENCES tasks(id) ON DELETE CASCADE," +
                    "event_id INTEGER NOT NULL REFERENCES events(id), UNIQUE(task_id,event_id))");
            db.execSQL("CREATE TABLE IF NOT EXISTS task_alarms (" +
                    "id INTEGER PRIMARY KEY, task_id INTEGER NOT NULL REFERENCES tasks(id) ON DELETE CASCADE," +
                    "event_reminder_id INTEGER NOT NULL REFERENCES event_reminders(id) ON DELETE CASCADE," +
                    "request_code INTEGER NOT NULL UNIQUE, trigger_at TEXT NOT NULL, type TEXT NOT NULL)");
            initSequences(db);
        }

        if (oldV < 33) {

            db.execSQL("DROP TABLE reminders");
            db.execSQL("DROP TABLE event_reminders");

            // reminders — standalone, reusable, named: name + offset (days/weeks before) + time
            db.execSQL("CREATE TABLE IF NOT EXISTS reminders (" +
                    "id           INTEGER PRIMARY KEY," +
                    "name         TEXT NOT NULL," +
                    "offset_value INTEGER NOT NULL DEFAULT 0," +
                    "offset_unit  TEXT NOT NULL DEFAULT 'DAY' CHECK(offset_unit IN ('DAY','WEEK'))," +
                    "time_hour    INTEGER NOT NULL," +
                    "time_minute  INTEGER NOT NULL," +
                    "created_at   TEXT DEFAULT (datetime('now'))," +
                    "updated_at   TEXT DEFAULT (datetime('now')))");

            // event_reminders — just links an event to one reminder for NOTIFICATION and one for ALARM
            db.execSQL("CREATE TABLE IF NOT EXISTS event_reminders (" +
                    "id          INTEGER PRIMARY KEY," +
                    "event_id    INTEGER NOT NULL REFERENCES events(id) ON DELETE CASCADE," +
                    "reminder_id INTEGER NOT NULL REFERENCES reminders(id)," +
                    "type        TEXT NOT NULL CHECK(type IN ('NOTIFICATION','ALARM'))," +
                    "UNIQUE(event_id, type))");
        }

    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        db.execSQL("PRAGMA foreign_keys = ON;");
        repairSchema(db);   // <-- runs on EVERY app launch, regardless of version tracking
    }

    /**
     * Defensive column repair — runs unconditionally on every DB open (not
     * gated by onUpgrade's oldV check), because a device can end up with
     * PRAGMA user_version already bumped past the migration that was supposed
     * to add these columns (e.g. a broken/partial earlier run), which makes
     * onUpgrade's "if (oldV < N)" block permanently skip on that device.
     * ALTER TABLE ADD COLUMN is safe to call repeatedly — it just throws
     * (caught + ignored) once the column already exists.
     */
    private void repairSchema(SQLiteDatabase db) {
        String[][] repairs = {
                {"cash_books", "updated_at", "TEXT DEFAULT (datetime('now'))"},
                {"cash_books", "created_at", "TEXT DEFAULT (datetime('now'))"},
                {"cash_books", "is_active", "INTEGER DEFAULT 1"},
                {"cash_books", "synced", "INTEGER DEFAULT 0"},
                {"categories", "created_at", "TEXT DEFAULT (datetime('now'))"},
                {"categories", "updated_at", "TEXT DEFAULT (datetime('now'))"},
                {"sub_categories", "created_at", "TEXT DEFAULT (datetime('now'))"},
                {"sub_categories", "updated_at", "TEXT DEFAULT (datetime('now'))"},
                {"column_definitions", "created_at", "TEXT DEFAULT (datetime('now'))"},
                {"column_definitions", "updated_at", "TEXT DEFAULT (datetime('now'))"},
                {"transactions", "created_at", "TEXT DEFAULT (datetime('now'))"},
                {"transactions", "updated_at", "TEXT DEFAULT (datetime('now'))"},
                {"transactions", "payment_type", "TEXT NOT NULL DEFAULT 'UPI'"}
        };

        for (String[] r : repairs) {
            String tableName = r[0];
            String columnName = r[1];
            String columnDef = r[2];

            // Column ஏற்கனவே உள்ளதா என்று செக் செய்கிறோம்
            if (!isColumnExists(db, tableName, columnName)) {
                try {
                    db.execSQL("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDef);
                } catch (Exception e) {
                    log.error("repairSchema failed for " + tableName + "." + columnName + ": " + e.getMessage());
                }
            }
        }

        // Ensure payment_types table + seed rows exist even on devices whose
        // onUpgrade() version-gate got skipped for some reason (same defensive
        // pattern as the column repairs above).
        if (!tableExists(db, "payment_types")) {
            db.execSQL("CREATE TABLE IF NOT EXISTS payment_types (" +
                    "id         INTEGER PRIMARY KEY," +
                    "name       TEXT NOT NULL UNIQUE," +
                    "created_at TEXT DEFAULT (datetime('now'))," +
                    "updated_at TEXT DEFAULT (datetime('now'))," +
                    "synced     INTEGER DEFAULT 0)");
        }
        seedDefaultPaymentTypes(db);
        db.execSQL("UPDATE transactions SET payment_type='UPI' WHERE payment_type IS NULL OR payment_type=''");
    }

    // Helper method: Column இருக்கிறதா இல்லையா என பார்க்க
    private boolean isColumnExists(SQLiteDatabase db, String tableName, String columnName) {
        android.database.Cursor cursor = null;
        try {
            cursor = db.rawQuery("PRAGMA table_info(" + tableName + ")", null);
            if (cursor != null) {
                int nameIndex = cursor.getColumnIndex("name");
                while (cursor.moveToNext()) {
                    if (nameIndex != -1 && columnName.equalsIgnoreCase(cursor.getString(nameIndex))) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Check column exists failed: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        return false;
    }


    /**
     * Sets each table's next_id to MAX(id)+1 (or 1 if the table is empty).
     */
    /**
     * Sets each table's next_id to MAX(id)+1 (or 1 if the table is empty).
     * Skips any table that doesn't exist yet — ID_TABLES is one flat list
     * shared by every version's migration block, but a table added in a
     * later version block (e.g. "budgets") genuinely doesn't exist yet
     * when an *earlier* version block's initSequences() call runs during
     * the same onUpgrade() pass on an old device. Without this check,
     * that earlier call crashes with "no such table".
     */
    private void initSequences(SQLiteDatabase db) {
        for (String table : ID_TABLES) {
            if (!tableExists(db, table)) continue;
            db.execSQL("INSERT OR REPLACE INTO id_sequences(table_name, next_id) " +
                    "VALUES('" + table + "', (SELECT COALESCE(MAX(id),0)+1 FROM " + table + "))");
        }
    }

    private boolean tableExists(SQLiteDatabase db, String tableName) {
        try (Cursor c = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                new String[]{tableName})) {
            return c.moveToFirst();
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

    /**
     * Recomputes next_id = MAX(id)+1 for the given tables. Call this after any
     * bulk cloud-fetch/replace that inserts rows with server-assigned ids, so
     * subsequent local getNextId() calls never collide with them.
     */
    public void resyncSequences(String... tables) {
        SQLiteDatabase wdb = getWritableDatabase();
        for (String table : tables) {
            if (!tableExists(wdb, table)) continue;
            wdb.execSQL("INSERT OR REPLACE INTO id_sequences(table_name, next_id) " +
                    "VALUES('" + table + "', (SELECT COALESCE(MAX(id),0)+1 FROM " + table + "))");
        }
    }

    private void seedDefaultPaymentTypes(SQLiteDatabase db) {
        String[] defaults = {"UPI", "Cash", "SBI Credit Card", "Axis Credit Card", "Debit Card"};
        for (String name : defaults) {
            db.execSQL("INSERT OR IGNORE INTO payment_types(name) VALUES(?)", new Object[]{name});
        }
    }
}