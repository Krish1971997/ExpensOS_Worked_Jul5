package com.expenseos.sync;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.expenseos.dao.LocalDatabase;
import com.expenseos.util.ConsoleLogger;
import com.expenseos.util.DBConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Central sync engine — mirrors the web app's NeonSyncService.sync():
 * push/pull EVERY table (not just transactions), filtered by updated_at
 * when a fromDate is supplied (used by the scheduler's incremental sync),
 * or unfiltered (full sync) when fromDate is null (manual "Sync" button).
 * <p>
 * IMPORTANT: confirm the Postgres enum type name below matches your DB
 * (`SELECT typname FROM pg_type WHERE typcategory='E';`). NeonSyncService.java
 * casts as `txn_type` — that's what ENUM_CAST is set to. If your DB actually
 * uses `transaction_type`, change the constant below (one place).
 */
public class SyncManager {

    private static final String ENUM_CAST = "txn_type"; // <-- confirm against your DB, see note above

    public interface SyncCallback {
        default void onProgress(String message) {
        }

        void onComplete(boolean success, String summary);
    }

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static SyncManager instance;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final ConsoleLogger log = ConsoleLogger.get();

    public static SyncManager get() {
        if (instance == null) instance = new SyncManager();
        return instance;
    }

    // ════════════════════════════════════════════════════════════════════
    // PUSH (local → cloud)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Full push — every row in every table, no date filter. Used by the manual Sync button.
     */
    public void syncToCloud(Context ctx, SyncCallback cb) {
        syncToCloud(ctx, null, cb);
    }

    /**
     * Incremental push — only rows with updated_at >= fromDate. Used by the scheduler.
     */
    public void syncToCloud(Context ctx, LocalDateTime fromDate, SyncCallback cb) {
        executor.execute(() -> {
            log.info("═══ PUSH TO CLOUD STARTED " + (fromDate != null ? "(since " + fromDate + ")" : "(full)") + " ═══");
            DBConnection.getInstance().configureFromAppConfig(ctx);
            SQLiteDatabase local = LocalDatabase.get(ctx).getWritableDatabase();
            String fromStr = fromDate != null ? fromDate.format(TS_FMT) : null;

            int totalRows = 0;
            try (Connection remote = DBConnection.getInstance().getConnection()) {
                remote.setAutoCommit(false);
                try {
                    // Parent tables first (FK order), then children.
                    log.info("Starting cash_books push...");
                    totalRows += pushCashBooks(local, remote, fromStr);

                    log.info("Starting categories push...");
                    totalRows += pushCategories(local, remote, fromStr);

                    log.info("Starting sub_categories push...");
                    totalRows += pushSubCategories(local, remote, fromStr);

                    log.info("Starting column_definitions push...");
                    totalRows += pushColumnDefinitions(local, remote, fromStr);

                    log.info("Starting transactions push...");
                    totalRows += pushTransactions(local, remote, fromStr);

                    log.info("Starting transaction_custom_values push...");
                    totalRows += pushTransactionCustomValues(local, remote, fromStr);

                    log.info("Starting transaction_audit_log push...");
                    totalRows += pushTransactionAuditLog(local, remote, fromStr);

                    log.info("Starting transaction_receipt push...");
                    totalRows += pushTransactionReceipts(local, remote, fromStr);

                    log.info("Starting backup_history push...");
                    totalRows += pushBackupHistory(local, remote, fromStr);

                    log.info("Starting budgets push...");
                    totalRows += pushBudgets(local, remote, fromStr);

                    log.info("Starting budget_categories push...");
                    totalRows += pushBudgetCategories(local, remote, fromStr);

                    log.info("Starting schedulers push...");
                    totalRows += pushSchedulers(local, remote, fromStr);

                    log.info("Starting scheduler_log push...");
                    totalRows += pushSchedulerLog(local, remote, fromStr);

                    remote.commit();
                    String summary = "Pushed " + totalRows + " rows across all tables";
                    log.success("═══ PUSH DONE — " + summary + " ═══");
                    post(cb, summary, true, summary);
                } catch (Exception ex) {
                    remote.rollback();
                    log.error("Push failed, rolled back: " + ex.getMessage());
                    post(cb, "Push failed", false, ex.getMessage());
                }
            } catch (Exception e) {
                log.error("Push connection failed: " + e.getMessage());
                post(cb, "Push failed", false, e.getMessage());
            }
        });
    }

    // ════════════════════════════════════════════════════════════════════
    // PULL (cloud → local)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Full pull — every row in every table, no date filter. Used by the manual Sync button.
     */
    public void fetchFromCloud(Context ctx, SyncCallback cb) {
        fetchFromCloud(ctx, null, cb);
    }

    /**
     * Incremental pull — only rows with updated_at >= fromDate. Used by the scheduler.
     */
    public void fetchFromCloud(Context ctx, LocalDateTime fromDate, SyncCallback cb) {
        executor.execute(() -> {
            log.info("═══ PULL FROM CLOUD STARTED " + (fromDate != null ? "(since " + fromDate + ")" : "(full)") + " ═══");
            DBConnection.getInstance().configureFromAppConfig(ctx);
            SQLiteDatabase local = LocalDatabase.get(ctx).getWritableDatabase();
            String fromStr = fromDate != null ? fromDate.format(TS_FMT) : null;

            log.info("Fetched date From : " + fromStr);
            int totalRows = 0;
            try (Connection remote = DBConnection.getInstance().getConnection()) {
                totalRows += pullCashBooks(remote, local, fromStr);
                totalRows += pullCategories(remote, local, fromStr);
                totalRows += pullSubCategories(remote, local, fromStr);
                totalRows += pullColumnDefinitions(remote, local, fromStr);
                totalRows += pullTransactions(remote, local, fromStr);
                totalRows += pullTransactionCustomValues(remote, local, fromStr);
                totalRows += pullTransactionAuditLog(remote, local, fromStr);
                totalRows += pullTransactionReceipts(remote, local, fromStr);
                totalRows += pullBackupHistory(remote, local, fromStr);
                totalRows += pullBudgets(remote, local, fromStr);
                totalRows += pullBudgetCategories(remote, local, fromStr);
                totalRows += pullSchedulers(remote, local, fromStr);
                totalRows += pullSchedulerLog(remote, local, fromStr);

                com.expenseos.db.LocalDB.getInstance(ctx).resyncSequences(
                        "cash_books", "categories", "sub_categories", "column_definitions",
                        "transactions", "transaction_custom_values", "transaction_audit_log",
                        "transaction_receipts", "backup_history", "budgets", "budget_categories",
                        "schedulers", "scheduler_log");

                String summary = "Pulled " + totalRows + " rows across all tables";
                log.success("═══ PULL DONE — " + summary + " ═══");
                post(cb, summary, true, summary);
            } catch (Exception e) {
                log.error("Pull failed: " + e.getMessage());

                try {
                    Cursor c = com.expenseos.db.LocalDB.getInstance(ctx).getWritableDatabase().rawQuery("PRAGMA foreign_key_check;", null);
                    if (c.moveToFirst()) {
                        do {
                            // Table Name, Row ID, Parent Table Name, Missing Index
                            String childTable = c.getString(0);
                            String rowId = c.getString(1);
                            String parentTable = c.getString(2);

                            log.error("FK_DEBUG --> ❌ FK Mismatch in Table: [" + childTable +
                                    "] | Row ID: [" + rowId +
                                    "] | Missing Parent Table: [" + parentTable + "]");
                        } while (c.moveToNext());
                    } else {
                        log.error("FK_DEBUG --> No FK violations found by PRAGMA check.");
                    }
                    c.close();
                } catch (Exception fkEx) {
                    log.error("FK_DEBUG --> Error running FK check: " + fkEx.getMessage());
                }
                post(cb, "Pull failed", false, e.getMessage());
            }
        });
    }

    // ── TEST CONNECTION ─────────────────────────────────────────────────
    public void testConnection(Context ctx, SyncCallback cb) {
        executor.execute(() -> {
            log.info("Testing connection to Neon PostgreSQL...");
            DBConnection.getInstance().configureFromAppConfig(ctx);
            String err = DBConnection.getInstance().testConnection();
            if (err == null) {
                log.success("Connection successful!");
                post(cb, "Connected", true, "Connection successful!");
            } else {
                log.error("Connection failed: " + err);
                post(cb, "Failed", false, err);
            }
        });
    }

    // ════════════════════════════════════════════════════════════════════
    // PUSH — per table (local SQLite → remote Postgres upsert)
    // ════════════════════════════════════════════════════════════════════

    private int pushCashBooks(SQLiteDatabase local, Connection remote, String fromStr) throws Exception {
        String sel = "SELECT id, name, description, created_at, updated_at, is_active FROM cash_books"
                + (fromStr != null ? " WHERE updated_at>=?" : "");

        // SQL Values Order:
        // 1: id
        // 2: name
        // 3: description
        // 4: created_at (timestamp)
        // 5: updated_at (timestamp)
        // 6: is_active  (boolean)
        String sql = "INSERT INTO cash_books (id, name, description, created_at, updated_at, is_active) "
                + "VALUES (?, ?, ?, ?::timestamp, ?::timestamp, ?) ON CONFLICT (id) DO UPDATE SET "
                + "name=EXCLUDED.name, "
                + "description=EXCLUDED.description, "
                + "created_at=EXCLUDED.created_at, "
                + "updated_at=EXCLUDED.updated_at, "
                + "is_active=EXCLUDED.is_active";

        try (Cursor c = rawQuery(local, sel, fromStr); PreparedStatement ps = remote.prepareStatement(sql)) {
            int n = 0;
            while (c.moveToNext()) {
                ps.setLong(1, c.getLong(0));                                 // 1: id
                ps.setString(2, c.getString(1));                               // 2: name
                ps.setString(3, c.getString(2));                               // 3: description
                ps.setString(4, c.getString(3));                               // 4: created_at -> ?::timestamp
                ps.setString(5, c.getString(4));                               // 5: updated_at -> ?::timestamp
                ps.setBoolean(6, c.isNull(5) || c.getInt(5) == 1);       // 6: is_active  -> boolean

                ps.executeUpdate();
                n++;
            }
            log.info("cash_books: pushed " + n);
            return n;
        }
    }

    private int pushCategories(SQLiteDatabase local, Connection remote, String fromStr) throws Exception {
        String sel = "SELECT id, name, type, created_at, updated_at FROM categories"
                + (fromStr != null ? " WHERE updated_at>=?" : "");

        String sql = "INSERT INTO categories (id, name, type, created_at, updated_at) "
                + "VALUES (?, ?, ?::txn_type, ?::timestamp, ?::timestamp) ON CONFLICT (id) DO UPDATE SET "
                + "name=EXCLUDED.name, "
                + "type=EXCLUDED.type, "
                + "created_at=EXCLUDED.created_at, "
                + "updated_at=EXCLUDED.updated_at";

        try (Cursor c = rawQuery(local, sel, fromStr); PreparedStatement ps = remote.prepareStatement(sql)) {
            int n = 0;
            while (c.moveToNext()) {
                ps.setLong(1, c.getLong(0));            // id
                ps.setString(2, c.getString(1));          // name
                ps.setString(3, c.getString(2));          // type

                // created_at handling
                String createdAt = c.getString(3);
                if (createdAt == null || createdAt.trim().isEmpty()) {
                    ps.setNull(4, java.sql.Types.TIMESTAMP);
                } else {
                    ps.setString(4, createdAt);
                }

                // updated_at handling
                String updatedAt = c.getString(4);
                if (updatedAt == null || updatedAt.trim().isEmpty()) {
                    ps.setNull(5, java.sql.Types.TIMESTAMP);
                } else {
                    ps.setString(5, updatedAt);
                }

                ps.executeUpdate();
                n++;
            }
            log.info("categories: pushed " + n);
            return n;
        }
    }

    private int pushSubCategories(SQLiteDatabase local, Connection remote, String fromStr) throws Exception {
        String sel = "SELECT id, category_id, name, created_at, updated_at FROM sub_categories"
                + (fromStr != null ? " WHERE updated_at>=?" : "");

        String sql = "INSERT INTO sub_categories (sub_categories_id, category_id, name, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?::timestamp, ?::timestamp) ON CONFLICT (sub_categories_id) DO UPDATE SET "
                + "category_id=EXCLUDED.category_id, "
                + "name=EXCLUDED.name, "
//                + "is_active=EXCLUDED.is_active, "
                + "created_at=EXCLUDED.created_at, "
                + "updated_at=EXCLUDED.updated_at";

        try (Cursor c = rawQuery(local, sel, fromStr); PreparedStatement ps = remote.prepareStatement(sql)) {
            int n = 0;
            while (c.moveToNext()) {
                ps.setLong(1, c.getLong(0));        // id
                ps.setLong(2, c.getLong(1));        // category_id
                ps.setString(3, c.getString(2));      // name
//                ps.setBoolean(4, c.getInt(3) == 1);   // is_active
                ps.setString(4, c.getString(3));      // created_at
                ps.setString(5, c.getString(4));      // updated_at

                ps.executeUpdate();
                n++;
            }
            log.info("sub_categories: pushed " + n);
            return n;
        }
    }

    private int pushColumnDefinitions(SQLiteDatabase local, Connection remote, String fromStr) throws Exception { //cash_book_id
        String sel = "SELECT id, col_name, col_key, type, created_at, updated_at FROM column_definitions"
                + (fromStr != null ? " WHERE updated_at>=?" : "");

        String sql = "INSERT INTO column_definitions (id, col_name, col_key, type, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?::txn_type, ?::timestamp, ?::timestamp) ON CONFLICT (id) DO UPDATE SET "
//                + "cash_book_id=EXCLUDED.cash_book_id, "
                + "col_name=EXCLUDED.col_name, "
                + "col_key=EXCLUDED.col_key, "
                + "type=EXCLUDED.type, "
                + "created_at=EXCLUDED.created_at, "
                + "updated_at=EXCLUDED.updated_at";

        try (Cursor c = rawQuery(local, sel, fromStr); PreparedStatement ps = remote.prepareStatement(sql)) {
            int n = 0;
            while (c.moveToNext()) {
                ps.setLong(1, c.getLong(0));        // id
//                ps.setLong(2, c.getLong(1));        // cash_book_id
                ps.setString(2, c.getString(1));      // column_name
                ps.setString(3, c.getString(2));      // column_type
                ps.setString(4, c.getString(3));      // type
//                ps.setBoolean(5, c.getInt(4) == 1);   // is_required
//                ps.setInt(6, c.getInt(5));          // sort_order
                ps.setString(5, c.getString(4));      // created_at
                ps.setString(6, c.getString(5));      // updated_at

                ps.executeUpdate();
                n++;
            }
            log.info("column_definitions: pushed " + n);
            return n;
        }
    }

    private int pushTransactions(SQLiteDatabase local, Connection remote, String fromStr) throws Exception {
        String sel = "SELECT id, book_id, category_id, sub_categories_id, amount, type, txn_datetime, note, created_at, updated_at FROM transactions"
                + (fromStr != null ? " WHERE updated_at>=?" : "");

        String sql = "INSERT INTO transactions (id, book_id, category_id, sub_categories_id, amount, type, txn_datetime, note, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?::txn_type, ?::timestamp, ?, ?::timestamp, ?::timestamp) ON CONFLICT (id) DO UPDATE SET "
                + "book_id=EXCLUDED.book_id, "
                + "category_id=EXCLUDED.category_id, "
                + "sub_categories_id=EXCLUDED.sub_categories_id, "
                + "amount=EXCLUDED.amount, "
                + "type=EXCLUDED.type, "
                + "txn_datetime=EXCLUDED.txn_datetime, "
                + "note=EXCLUDED.note, "
                + "created_at=EXCLUDED.created_at, "
                + "updated_at=EXCLUDED.updated_at";

        // Speed Optimization: Turn off AutoCommit for batch insertion
        boolean originalAutoCommit = remote.getAutoCommit();
        remote.setAutoCommit(false);

        try (Cursor c = rawQuery(local, sel, fromStr); PreparedStatement ps = remote.prepareStatement(sql)) {
            int n = 0;
            int batchSize = 0;

            while (c.moveToNext()) {
                ps.setLong(1, c.getLong(0));        // 1: id

                // Nullable book_id
                if (c.isNull(1)) ps.setNull(2, java.sql.Types.BIGINT);
                else ps.setLong(2, c.getLong(1));   // 2: book_id

                // Nullable category_id
                if (c.isNull(2)) ps.setNull(3, java.sql.Types.BIGINT);
                else ps.setLong(3, c.getLong(2));   // 3: category_id

                // Nullable sub_categories_id
                if (c.isNull(3)) ps.setNull(4, java.sql.Types.BIGINT);
                else ps.setLong(4, c.getLong(3));   // 4: sub_categories_id

                ps.setDouble(5, c.getDouble(4));    // 5: amount
                ps.setString(6, c.getString(5));      // 6: type
                ps.setString(7, c.getString(6));      // 7: txn_datetime
                ps.setString(8, c.getString(7));      // 8: note
                ps.setString(9, c.getString(8));      // 9: created_at
                ps.setString(10, c.getString(9));     // 10: updated_at

                // Individual update-க்கு பதிலாக Batch-ல் சேர்க்கிறோம்
                ps.addBatch();
                n++;
                batchSize++;

                // 200 records சேர்ந்தவுடன் ஒருமுறை Network-ல் அனுப்புகிறோம்
                if (batchSize % 200 == 0) {
                    ps.executeBatch();
                    remote.commit();
                    batchSize = 0;
                }
            }

            // எஞ்சிய records இருந்தால் push செய்தல்
            if (batchSize > 0) {
                ps.executeBatch();
                remote.commit();
            }

            log.info("transactions: pushed " + n);

            // Mark the rows we just pushed as synced, otherwise the local
            // `synced` flag never changes and the UI keeps showing the
            // amber "unsynced" dot even after a successful push.
            if (fromStr != null)
                local.execSQL("UPDATE transactions SET synced=1 WHERE updated_at>=?", new Object[]{fromStr});
            else local.execSQL("UPDATE transactions SET synced=1");

            return n;
        } catch (Exception e) {
            remote.rollback();
            throw e;
        } finally {
            remote.setAutoCommit(originalAutoCommit);
        }
    }

    private int pushTransactionCustomValues(SQLiteDatabase local, Connection remote, String fromStr) throws Exception {
        String sel = "SELECT id, transaction_id, col_def_id, value, created_at, updated_at FROM transaction_custom_values"
                + (fromStr != null ? " WHERE updated_at>=?" : "");

        String sql = "INSERT INTO transaction_custom_values (id, transaction_id, col_def_id, value, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?::timestamp, ?::timestamp) ON CONFLICT (id) DO UPDATE SET "
                + "transaction_id=EXCLUDED.transaction_id, "
                + "col_def_id=EXCLUDED.col_def_id, "
                + "value=EXCLUDED.value, "
                + "created_at=EXCLUDED.created_at, "
                + "updated_at=EXCLUDED.updated_at";

        try (Cursor c = rawQuery(local, sel, fromStr); PreparedStatement ps = remote.prepareStatement(sql)) {
            int n = 0;
            while (c.moveToNext()) {
                ps.setLong(1, c.getLong(0));        // id
                ps.setLong(2, c.getLong(1));        // transaction_id
                ps.setLong(3, c.getLong(2));        // column_def_id
                ps.setString(4, c.getString(3));      // value_text
                ps.setString(5, c.getString(4));      // created_at
                ps.setString(6, c.getString(5));      // updated_at

                ps.executeUpdate();
                n++;
            }
            log.info("transaction_custom_values: pushed " + n);
            return n;
        }
    }

    private int pushTransactionAuditLog(SQLiteDatabase local, Connection remote, String fromStr) throws Exception {
        String sel = "SELECT id, transaction_id, action, changed_by, changed_at, field_name, old_value, "
                + "new_value, note, created_at, updated_at FROM transaction_audit_log"
                + (fromStr != null ? " WHERE updated_at>=?" : "");

        String sql = "INSERT INTO transaction_audit_log (id, transaction_id, action, changed_by, changed_at, "
                + "field_name, old_value, new_value, note, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?::timestamp, ?, ?, ?, ?, ?::timestamp, ?::timestamp) "
                + "ON CONFLICT (id) DO NOTHING";

        boolean originalAutoCommit = remote.getAutoCommit();
        remote.setAutoCommit(false);

        try (Cursor c = rawQuery(local, sel, fromStr); PreparedStatement ps = remote.prepareStatement(sql)) {
            int n = 0;
            int batchSize = 0;

            while (c.moveToNext()) {
                ps.setLong(1, c.getLong(0));
                ps.setLong(2, c.getLong(1));
                ps.setString(3, c.getString(2));
                ps.setString(4, c.getString(3));
                ps.setString(5, c.getString(4)); // changed_at -> ?::timestamp
                ps.setString(6, c.getString(5));
                ps.setString(7, c.getString(6));
                ps.setString(8, c.getString(7));
                ps.setString(9, c.getString(8));
                ps.setString(10, c.getString(9)); // created_at -> ?::timestamp
                ps.setString(11, c.getString(10)); // updated_at -> ?::timestamp

                ps.addBatch();
                n++;
                batchSize++;

                if (batchSize % 200 == 0) {
                    ps.executeBatch();
                    remote.commit();
                    batchSize = 0;
                }
            }

            if (batchSize > 0) {
                ps.executeBatch();
                remote.commit();
            }

            log.info("transaction_audit_log: pushed " + n);
            return n;
        } catch (Exception e) {
            remote.rollback();
            throw e;
        } finally {
            remote.setAutoCommit(originalAutoCommit);
        }
    }

    private int pushTransactionReceipts(SQLiteDatabase local, Connection remote, String fromStr) throws Exception {
        String sel = "SELECT id, transaction_id, file_name, file_type, file_data, file_size, uploaded_at, "
                + "created_at, updated_at FROM transaction_receipts" + (fromStr != null ? " WHERE updated_at>=?" : "");

        String sql = "INSERT INTO transaction_receipts (id, transaction_id, file_name, file_type, file_data, "
                + "file_size, uploaded_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?::timestamp, ?::timestamp, ?::timestamp) "
                + "ON CONFLICT (id) DO UPDATE SET "
                + "transaction_id=EXCLUDED.transaction_id, file_name=EXCLUDED.file_name, "
                + "file_type=EXCLUDED.file_type, file_data=EXCLUDED.file_data, file_size=EXCLUDED.file_size, "
                + "uploaded_at=EXCLUDED.uploaded_at, created_at=EXCLUDED.created_at, updated_at=EXCLUDED.updated_at";

        boolean originalAutoCommit = remote.getAutoCommit();
        remote.setAutoCommit(false);

        try (Cursor c = rawQuery(local, sel, fromStr); PreparedStatement ps = remote.prepareStatement(sql)) {
            int n = 0;
            int batchSize = 0;

            while (c.moveToNext()) {
                ps.setLong(1, c.getLong(0));
                ps.setLong(2, c.getLong(1));
                ps.setString(3, c.getString(2));
                ps.setString(4, c.getString(3));

                byte[] blob = c.getBlob(4);
                if (blob == null) ps.setNull(5, Types.BINARY);
                else ps.setBytes(5, blob);

                if (c.isNull(5)) ps.setNull(6, Types.INTEGER);
                else ps.setLong(6, c.getLong(5));

                ps.setString(7, c.getString(6)); // uploaded_at -> ?::timestamp
                ps.setString(8, c.getString(7)); // created_at -> ?::timestamp
                ps.setString(9, c.getString(8)); // updated_at -> ?::timestamp

                ps.addBatch();
                n++;
                batchSize++;

                // Image/File Bytes இருப்பதால் 50 Records-க்கு ஒருமுறை Push செய்கிறோம்
                if (batchSize % 50 == 0) {
                    ps.executeBatch();
                    remote.commit();
                    batchSize = 0;
                }
            }

            if (batchSize > 0) {
                ps.executeBatch();
                remote.commit();
            }

            log.info("transaction_receipts: pushed " + n);
            return n;
        } catch (Exception e) {
            remote.rollback();
            throw e;
        } finally {
            remote.setAutoCommit(originalAutoCommit);
        }
    }

    private int pushBackupHistory(SQLiteDatabase local, Connection remote, String fromStr) throws Exception {
        String sel = "SELECT id, file_name, file_path, file_size_bytes, backup_type, status, description, "
                + "income_count, expense_count, created_at, backup_mode, external_id, updated_at FROM backup_history"
                + (fromStr != null ? " WHERE updated_at>=?" : "");

        String sql = "INSERT INTO backup_history (id, file_name, file_path, file_size_bytes, backup_type, status, "
                + "description, error_message, income_count, expense_count, created_at, completed_at, backupmode, "
                + "external_id, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?::timestamp, NULL, ?, ?, ?::timestamp) "
                + "ON CONFLICT (id) DO UPDATE SET "
                + "file_name=EXCLUDED.file_name, file_path=EXCLUDED.file_path, file_size_bytes=EXCLUDED.file_size_bytes, "
                + "backup_type=EXCLUDED.backup_type, status=EXCLUDED.status, description=EXCLUDED.description, "
                + "income_count=EXCLUDED.income_count, expense_count=EXCLUDED.expense_count, created_at=EXCLUDED.created_at, "
                + "backupmode=EXCLUDED.backupmode, external_id=EXCLUDED.external_id, updated_at=EXCLUDED.updated_at";

        boolean originalAutoCommit = remote.getAutoCommit();
        remote.setAutoCommit(false);

        try (Cursor c = rawQuery(local, sel, fromStr); PreparedStatement ps = remote.prepareStatement(sql)) {
            int n = 0;
            int batchSize = 0;

            while (c.moveToNext()) {
                ps.setLong(1, c.getLong(0));
                ps.setString(2, c.getString(1));
                ps.setString(3, c.getString(2));
                ps.setLong(4, c.getLong(3));
                ps.setString(5, c.getString(4));
                ps.setString(6, c.getString(5));
                ps.setString(7, c.getString(6));
                ps.setInt(8, c.getInt(7));        // income_count
                ps.setInt(9, c.getInt(8));        // expense_count
                ps.setString(10, c.getString(9)); // created_at -> ?::timestamp
                ps.setString(11, c.getString(10)); // backup_mode
                ps.setString(12, c.getString(11)); // external_id
                ps.setString(13, c.getString(12)); // updated_at -> ?::timestamp

                ps.addBatch();
                n++;
                batchSize++;

                if (batchSize % 200 == 0) {
                    ps.executeBatch();
                    remote.commit();
                    batchSize = 0;
                }
            }

            if (batchSize > 0) {
                ps.executeBatch();
                remote.commit();
            }

            log.info("backup_history: pushed " + n);
            return n;
        } catch (Exception e) {
            remote.rollback();
            throw e;
        } finally {
            remote.setAutoCommit(originalAutoCommit);
        }
    }

    private int pushBudgets(SQLiteDatabase local, Connection remote, String fromStr) throws Exception {
        String sel = "SELECT id, book_id, year, month, overall_limit, created_at, updated_at FROM budgets"
                + (fromStr != null ? " WHERE updated_at>=?" : "");

        // 💡 timestamp casting (?::timestamp) சேர்க்கப்பட்டுள்ளது
        String sql = "INSERT INTO budgets (id, book_id, year, month, overall_limit, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?::timestamp, ?::timestamp) "
                + "ON CONFLICT (id) DO UPDATE SET book_id=EXCLUDED.book_id, year=EXCLUDED.year, "
                + "month=EXCLUDED.month, overall_limit=EXCLUDED.overall_limit, created_at=EXCLUDED.created_at, updated_at=EXCLUDED.updated_at";

        boolean originalAutoCommit = remote.getAutoCommit();
        remote.setAutoCommit(false);

        try (Cursor c = rawQuery(local, sel, fromStr); PreparedStatement ps = remote.prepareStatement(sql)) {
            int n = 0;
            int batchSize = 0;

            while (c.moveToNext()) {
                ps.setLong(1, c.getLong(0));
                ps.setLong(2, c.getLong(1));
                ps.setInt(3, c.getInt(2));
                ps.setInt(4, c.getInt(3));
                ps.setDouble(5, c.getDouble(4));
                ps.setString(6, c.getString(5)); // created_at -> ?::timestamp
                ps.setString(7, c.getString(6)); // updated_at -> ?::timestamp

                ps.addBatch();
                n++;
                batchSize++;

                if (batchSize % 200 == 0) {
                    ps.executeBatch();
                    remote.commit();
                    batchSize = 0;
                }
            }

            if (batchSize > 0) {
                ps.executeBatch();
                remote.commit();
            }

            log.info("budgets: pushed " + n);
            return n;
        } catch (Exception e) {
            remote.rollback();
            throw e;
        } finally {
            remote.setAutoCommit(originalAutoCommit);
        }
    }

    private int pushBudgetCategories(SQLiteDatabase local, Connection remote, String fromStr) throws Exception {
        String sel = "SELECT id, budget_id, category_id, cat_limit, alert_pct, created_at, updated_at FROM budget_categories"
                + (fromStr != null ? " WHERE updated_at>=?" : "");

        // 💡 timestamp casting (?::timestamp) சேர்க்கப்பட்டுள்ளது
        String sql = "INSERT INTO budget_categories (id, budget_id, category_id, cat_limit, alert_pct, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?::timestamp, ?::timestamp) "
                + "ON CONFLICT (id) DO UPDATE SET budget_id=EXCLUDED.budget_id, "
                + "category_id=EXCLUDED.category_id, cat_limit=EXCLUDED.cat_limit, alert_pct=EXCLUDED.alert_pct, "
                + "created_at=EXCLUDED.created_at, updated_at=EXCLUDED.updated_at";

        boolean originalAutoCommit = remote.getAutoCommit();
        remote.setAutoCommit(false);

        try (Cursor c = rawQuery(local, sel, fromStr); PreparedStatement ps = remote.prepareStatement(sql)) {
            int n = 0;
            int batchSize = 0;

            while (c.moveToNext()) {
                ps.setLong(1, c.getLong(0));
                ps.setLong(2, c.getLong(1));
                ps.setLong(3, c.getLong(2));
                ps.setDouble(4, c.getDouble(3));
                ps.setInt(5, c.getInt(4));
                ps.setString(6, c.getString(5)); // created_at -> ?::timestamp
                ps.setString(7, c.getString(6)); // updated_at -> ?::timestamp

                ps.addBatch();
                n++;
                batchSize++;

                if (batchSize % 200 == 0) {
                    ps.executeBatch();
                    remote.commit();
                    batchSize = 0;
                }
            }

            if (batchSize > 0) {
                ps.executeBatch();
                remote.commit();
            }

            log.info("budget_categories: pushed " + n);
            return n;
        } catch (Exception e) {
            remote.rollback();
            throw e;
        } finally {
            remote.setAutoCommit(originalAutoCommit);
        }
    }

    private int pushSchedulers(SQLiteDatabase local, Connection remote, String fromStr) throws Exception {
        String sel = "SELECT id, name, display_name, enabled, repeat_type, repeat_days, run_hour, run_minute, "
                + "last_run_at, last_run_status, last_run_msg, next_run_at, created_at, updated_at FROM schedulers"
                + (fromStr != null ? " WHERE updated_at>=?" : "");

        // 💡 timestamp casting (?::timestamp) சேர்க்கப்பட்டுள்ளது
        String sql = "INSERT INTO schedulers (id, name, display_name, enabled, repeat_type, repeat_days, run_hour, "
                + "run_minute, last_run_at, last_run_status, last_run_msg, next_run_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::timestamp, ?, ?, ?::timestamp, ?::timestamp, ?::timestamp) "
                + "ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, "
                + "display_name=EXCLUDED.display_name, enabled=EXCLUDED.enabled, repeat_type=EXCLUDED.repeat_type, "
                + "repeat_days=EXCLUDED.repeat_days, run_hour=EXCLUDED.run_hour, run_minute=EXCLUDED.run_minute, "
                + "last_run_at=EXCLUDED.last_run_at, last_run_status=EXCLUDED.last_run_status, "
                + "last_run_msg=EXCLUDED.last_run_msg, next_run_at=EXCLUDED.next_run_at, created_at=EXCLUDED.created_at, "
                + "updated_at=EXCLUDED.updated_at";

        boolean originalAutoCommit = remote.getAutoCommit();
        remote.setAutoCommit(false);

        try (Cursor c = rawQuery(local, sel, fromStr); PreparedStatement ps = remote.prepareStatement(sql)) {
            int n = 0;
            int batchSize = 0;

            while (c.moveToNext()) {
                ps.setLong(1, c.getLong(0));
                ps.setString(2, c.getString(1));
                ps.setString(3, c.getString(2));
                ps.setBoolean(4, c.getInt(3) == 1);
                ps.setString(5, c.getString(4));
                ps.setString(6, c.getString(5));
                ps.setInt(7, c.getInt(6));
                ps.setInt(8, c.getInt(7));
                ps.setString(9, c.getString(8));   // last_run_at -> ?::timestamp
                ps.setString(10, c.getString(9));  // last_run_status
                ps.setString(11, c.getString(10)); // last_run_msg
                ps.setString(12, c.getString(11)); // next_run_at -> ?::timestamp
                ps.setString(13, c.getString(12)); // created_at -> ?::timestamp
                ps.setString(14, c.getString(13)); // updated_at -> ?::timestamp

                ps.addBatch();
                n++;
                batchSize++;

                if (batchSize % 200 == 0) {
                    ps.executeBatch();
                    remote.commit();
                    batchSize = 0;
                }
            }

            if (batchSize > 0) {
                ps.executeBatch();
                remote.commit();
            }

            log.info("schedulers: pushed " + n);
            return n;
        } catch (Exception e) {
            remote.rollback();
            throw e;
        } finally {
            remote.setAutoCommit(originalAutoCommit);
        }
    }

    private int pushSchedulerLog(SQLiteDatabase local, Connection remote, String fromStr) throws Exception {
        String sel = "SELECT id, scheduler_id, started_at, finished_at, status, message, rows_synced, created_at, "
                + "updated_at FROM scheduler_log" + (fromStr != null ? " WHERE updated_at>=?" : "");

        String sql = "INSERT INTO scheduler_log (id, scheduler_id, started_at, finished_at, status, message, "
                + "rows_synced, created_at, updated_at) "
                + "VALUES (?, ?, ?::timestamp, ?::timestamp, ?, ?, ?, ?::timestamp, ?::timestamp) "
                + "ON CONFLICT (id) DO UPDATE SET "
                + "scheduler_id=EXCLUDED.scheduler_id, started_at=EXCLUDED.started_at, finished_at=EXCLUDED.finished_at, "
                + "status=EXCLUDED.status, message=EXCLUDED.message, rows_synced=EXCLUDED.rows_synced, "
                + "created_at=EXCLUDED.created_at, updated_at=EXCLUDED.updated_at";

        boolean originalAutoCommit = remote.getAutoCommit();
        remote.setAutoCommit(false);

        try (Cursor c = rawQuery(local, sel, fromStr); PreparedStatement ps = remote.prepareStatement(sql)) {
            int n = 0;
            int batchSize = 0;

            while (c.moveToNext()) {
                ps.setLong(1, c.getLong(0));
                ps.setLong(2, c.getLong(1));
                ps.setString(3, c.getString(2)); // started_at -> ?::timestamp
                ps.setString(4, c.getString(3)); // finished_at -> ?::timestamp
                ps.setString(5, c.getString(4));
                ps.setString(6, c.getString(5));
                ps.setInt(7, c.getInt(6));
                ps.setString(8, c.getString(7)); // created_at -> ?::timestamp
                ps.setString(9, c.getString(8)); // updated_at -> ?::timestamp

                ps.addBatch();
                n++;
                batchSize++;

                if (batchSize % 200 == 0) {
                    ps.executeBatch();
                    remote.commit();
                    batchSize = 0;
                }
            }

            if (batchSize > 0) {
                ps.executeBatch();
                remote.commit();
            }

            log.info("scheduler_log: pushed " + n);
            return n;
        } catch (Exception e) {
            remote.rollback();
            throw e;
        } finally {
            remote.setAutoCommit(originalAutoCommit);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // PULL — per table (remote Postgres → local SQLite replace-into)
    // ════════════════════════════════════════════════════════════════════

    private int pullCashBooks(Connection remote, SQLiteDatabase local, String fromStr) throws Exception {
        String sql = "SELECT id, name, description, created_at, is_active, updated_at FROM cash_books"
                + (fromStr != null ? " WHERE updated_at>=?" : "");
        int n = 0;
        try (PreparedStatement ps = prep(remote, sql, fromStr); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ContentValues cv = new ContentValues();
                cv.put("id", rs.getInt("id"));
                cv.put("name", rs.getString("name"));
                cv.put("description", rs.getString("description"));
                cv.put("created_at", strTs(rs, "created_at"));
                cv.put("is_active", rs.getBoolean("is_active") ? 1 : 0);
                cv.put("updated_at", strTs(rs, "updated_at"));
                local.insertWithOnConflict("cash_books", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                n++;
            }
        }
        log.info("cash_books: pulled " + n);
        return n;
    }

    private int pullCategories(Connection remote, SQLiteDatabase local, String fromStr) throws Exception {
        String sql = "SELECT id, name, type, created_at, updated_at, book_id FROM categories"
                + (fromStr != null ? " WHERE updated_at>=?" : "");
        int n = 0;
        try (PreparedStatement ps = prep(remote, sql, fromStr); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ContentValues cv = new ContentValues();
                cv.put("id", rs.getInt("id"));
                cv.put("name", rs.getString("name"));
                cv.put("type", rs.getString("type"));
                cv.put("created_at", strTs(rs, "created_at"));
                cv.put("updated_at", strTs(rs, "updated_at"));
                int bookId = rs.getInt("book_id");
                if (rs.wasNull()) cv.putNull("book_id");
                else cv.put("book_id", bookId);
                local.insertWithOnConflict("categories", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                n++;
            }
        }
        log.info("categories: pulled " + n);
        return n;
    }

    private int pullSubCategories(Connection remote, SQLiteDatabase local, String fromStr) throws Exception {
        String sql = "SELECT sub_categories_id, name, category_id, created_at, updated_at FROM sub_categories"
                + (fromStr != null ? " WHERE updated_at>=?" : "");
        int n = 0;
        try (PreparedStatement ps = prep(remote, sql, fromStr); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ContentValues cv = new ContentValues();
                cv.put("id", rs.getInt("sub_categories_id"));
                cv.put("name", rs.getString("name"));
                cv.put("category_id", rs.getInt("category_id"));
                cv.put("created_at", strTs(rs, "created_at"));
                cv.put("updated_at", strTs(rs, "updated_at"));
                local.insertWithOnConflict("sub_categories", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                n++;
            }
        }
        log.info("sub_categories: pulled " + n);
        return n;
    }

    private int pullColumnDefinitions(Connection remote, SQLiteDatabase local, String fromStr) throws Exception {
        String sql = "SELECT id, col_name, col_key, type, created_at, updated_at FROM column_definitions"
                + (fromStr != null ? " WHERE updated_at>=?" : "");
        int n = 0;
        try (PreparedStatement ps = prep(remote, sql, fromStr); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ContentValues cv = new ContentValues();
                cv.put("id", rs.getInt("id"));
                cv.put("col_name", rs.getString("col_name"));
                cv.put("col_key", rs.getString("col_key"));
                cv.put("type", rs.getString("type"));
                cv.put("created_at", strTs(rs, "created_at"));
                cv.put("updated_at", strTs(rs, "updated_at"));
                local.insertWithOnConflict("column_definitions", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                n++;
            }
        }
        log.info("column_definitions: pulled " + n);
        return n;
    }

    private int pullTransactions(Connection remote, SQLiteDatabase local, String fromStr) throws Exception {
        String sql = "SELECT id, type, txn_datetime, amount, category_id, note, created_at, updated_at, "
                + "sub_categories_id, book_id FROM transactions" + (fromStr != null ? " WHERE updated_at>=? Order by id" : " Order by id");
        int n = 0;
        log.info("Transactions : fromStr ==>> " + fromStr);
        try (PreparedStatement ps = prep(remote, sql, fromStr); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ContentValues cv = new ContentValues();
                cv.put("id", rs.getInt("id"));
                cv.put("type", rs.getString("type"));
                cv.put("txn_datetime", strTs(rs, "txn_datetime"));
                cv.put("amount", rs.getBigDecimal("amount").toPlainString());
                cv.put("category_id", rs.getInt("category_id"));
                cv.put("note", rs.getString("note"));
                cv.put("created_at", strTs(rs, "created_at"));
                cv.put("updated_at", strTs(rs, "updated_at"));
                int subCat = rs.getInt("sub_categories_id");
                if (rs.wasNull()) cv.putNull("sub_categories_id");
                else cv.put("sub_categories_id", subCat);
                cv.put("book_id", rs.getInt("book_id"));
                cv.put("synced", 1);
//                local.insertWithOnConflict("transactions", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                try {
                    // Normal Insert/Replace
                    local.insertWithOnConflict("transactions", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                } catch (Exception e) {
                    // 🚨 பிழை தரும் குறிப்பிட்ட Record-ன் விவரங்களை Log-ல் அச்சிடும்:
                    log.error("FAILED_ROW --> ==========================================");
                    log.error("FAILED_ROW --> ❌ Foreign Key Failed for Transaction ID: " + cv.get("id"));
                    log.error("FAILED_ROW -->    -> cash_book_id : " + cv.get("cash_book_id"));
                    log.error("FAILED_ROW -->    -> category_id  : " + cv.get("category_id"));
                    log.error("FAILED_ROW -->    -> amount       : " + cv.get("amount"));
                    log.error("FAILED_ROW --> ==========================================");

                    // பிழையைக் காட்டிய பிறகு மேலதிக தகவலுக்கு இந்த Exception-ஐ throw செய்யலாம்
                    throw e;
                }
//                log.info("transaction id : " + rs.getInt("id"));
                n++;
            }
        } catch (Exception e) {
            log.error("Transaction insert error : " + e.getMessage());
        }
        log.info("transactions: pulled " + n);
        return n;
    }

    private int pullTransactionCustomValues(Connection remote, SQLiteDatabase local, String fromStr) throws Exception {
        String sql = "SELECT id, transaction_id, col_def_id, value, created_at, updated_at FROM transaction_custom_values"
                + (fromStr != null ? " WHERE updated_at>=?" : "");
        int n = 0;
        try (PreparedStatement ps = prep(remote, sql, fromStr); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ContentValues cv = new ContentValues();
                cv.put("id", rs.getInt("id"));
                cv.put("transaction_id", rs.getInt("transaction_id"));
                cv.put("col_def_id", rs.getInt("col_def_id"));
                cv.put("value", rs.getString("value"));
                cv.put("created_at", strTs(rs, "created_at"));
                cv.put("updated_at", strTs(rs, "updated_at"));
                local.insertWithOnConflict("transaction_custom_values", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                n++;
            }
        }
        log.info("transaction_custom_values: pulled " + n);
        return n;
    }

    private int pullTransactionAuditLog(Connection remote, SQLiteDatabase local, String fromStr) throws Exception {
        String sql = "SELECT id, transaction_id, action, changed_by, changed_at, field_name, old_value, new_value, "
                + "note, created_at, updated_at FROM transaction_audit_log" + (fromStr != null ? " WHERE updated_at>=?" : "");
        int n = 0;
        try (PreparedStatement ps = prep(remote, sql, fromStr); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ContentValues cv = new ContentValues();
                cv.put("id", rs.getInt("id"));
                cv.put("transaction_id", rs.getInt("transaction_id"));
                cv.put("action", rs.getString("action"));
                cv.put("changed_by", rs.getString("changed_by"));
                cv.put("changed_at", strTs(rs, "changed_at"));
                cv.put("field_name", rs.getString("field_name"));
                cv.put("old_value", rs.getString("old_value"));
                cv.put("new_value", rs.getString("new_value"));
                cv.put("note", rs.getString("note"));
                cv.put("created_at", strTs(rs, "created_at"));
                cv.put("updated_at", strTs(rs, "updated_at"));
                local.insertWithOnConflict("transaction_audit_log", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                n++;
            }
        }
        log.info("transaction_audit_log: pulled " + n);
        return n;
    }

    private int pullTransactionReceipts(Connection remote, SQLiteDatabase local, String fromStr) throws Exception {
        String sql = "SELECT id, transaction_id, file_name, file_type, file_data, file_size, uploaded_at, "
                + "created_at, updated_at FROM transaction_receipts" + (fromStr != null ? " WHERE updated_at>=?" : "");
        int n = 0;
        try (PreparedStatement ps = prep(remote, sql, fromStr); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ContentValues cv = new ContentValues();
                cv.put("id", rs.getInt("id"));
                cv.put("transaction_id", rs.getInt("transaction_id"));
                cv.put("file_name", rs.getString("file_name"));
                cv.put("file_type", rs.getString("file_type"));
                cv.put("file_data", rs.getBytes("file_data"));
                cv.put("file_size", rs.getLong("file_size"));
                cv.put("uploaded_at", strTs(rs, "uploaded_at"));
                cv.put("created_at", strTs(rs, "created_at"));
                cv.put("updated_at", strTs(rs, "updated_at"));
                local.insertWithOnConflict("transaction_receipts", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                n++;
            }
        }
        log.info("transaction_receipts: pulled " + n);
        return n;
    }

    private int pullBackupHistory(Connection remote, SQLiteDatabase local, String fromStr) throws Exception {
        String sql = "SELECT id, file_name, file_path, file_size_bytes, backup_type, status, description, "
                + "income_count, expense_count, created_at, backupmode, external_id, updated_at FROM backup_history"
                + (fromStr != null ? " WHERE updated_at>=?" : "");
        int n = 0;
        try (PreparedStatement ps = prep(remote, sql, fromStr); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ContentValues cv = new ContentValues();
                cv.put("id", rs.getInt("id"));
                cv.put("file_name", rs.getString("file_name"));
                cv.put("file_path", rs.getString("file_path"));
                cv.put("file_size_bytes", rs.getLong("file_size_bytes"));
                cv.put("backup_type", rs.getString("backup_type"));
                cv.put("status", rs.getString("status"));
                cv.put("description", rs.getString("description"));
                cv.put("income_count", rs.getInt("income_count"));
                cv.put("expense_count", rs.getInt("expense_count"));
                cv.put("created_at", strTs(rs, "created_at"));
                cv.put("backup_mode", rs.getString("backupmode"));
                cv.put("external_id", rs.getString("external_id"));
                cv.put("updated_at", strTs(rs, "updated_at"));
                local.insertWithOnConflict("backup_history", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                n++;
            }
        }
        log.info("backup_history: pulled " + n);
        return n;
    }

    private int pullBudgets(Connection remote, SQLiteDatabase local, String fromStr) throws Exception {
        String sql = "SELECT id, book_id, year, month, overall_limit, created_at, updated_at FROM budgets"
                + (fromStr != null ? " WHERE updated_at>=?" : "");
        int n = 0;
        try (PreparedStatement ps = prep(remote, sql, fromStr); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ContentValues cv = new ContentValues();
                cv.put("id", rs.getInt("id"));
                cv.put("book_id", rs.getInt("book_id"));
                cv.put("year", rs.getInt("year"));
                cv.put("month", rs.getInt("month"));
                cv.put("overall_limit", rs.getDouble("overall_limit"));
                cv.put("created_at", strTs(rs, "created_at"));
                cv.put("updated_at", strTs(rs, "updated_at"));
                local.insertWithOnConflict("budgets", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                n++;
            }
        }
        log.info("budgets: pulled " + n);
        return n;
    }

    private int pullBudgetCategories(Connection remote, SQLiteDatabase local, String fromStr) throws Exception {
        String sql = "SELECT id, budget_id, category_id, cat_limit, alert_pct, created_at, updated_at FROM budget_categories"
                + (fromStr != null ? " WHERE updated_at>=?" : "");
        int n = 0;
        try (PreparedStatement ps = prep(remote, sql, fromStr); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ContentValues cv = new ContentValues();
                cv.put("id", rs.getInt("id"));
                cv.put("budget_id", rs.getInt("budget_id"));
                cv.put("category_id", rs.getInt("category_id"));
                cv.put("cat_limit", rs.getDouble("cat_limit"));
                cv.put("alert_pct", rs.getInt("alert_pct"));
                cv.put("created_at", strTs(rs, "created_at"));
                cv.put("updated_at", strTs(rs, "updated_at"));
                local.insertWithOnConflict("budget_categories", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                n++;
            }
        }
        log.info("budget_categories: pulled " + n);
        return n;
    }

    private int pullSchedulers(Connection remote, SQLiteDatabase local, String fromStr) throws Exception {
        String sql = "SELECT id, name, display_name, enabled, repeat_type, repeat_days, run_hour, run_minute, "
                + "last_run_at, last_run_status, last_run_msg, next_run_at, created_at, updated_at FROM schedulers"
                + (fromStr != null ? " WHERE updated_at>=?" : "");
        int n = 0;
        try (PreparedStatement ps = prep(remote, sql, fromStr); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ContentValues cv = new ContentValues();
                cv.put("id", rs.getInt("id"));
                cv.put("name", rs.getString("name"));
                cv.put("display_name", rs.getString("display_name"));
                cv.put("enabled", rs.getBoolean("enabled") ? 1 : 0);
                cv.put("repeat_type", rs.getString("repeat_type"));
                cv.put("repeat_days", rs.getString("repeat_days"));
                cv.put("run_hour", rs.getInt("run_hour"));
                cv.put("run_minute", rs.getInt("run_minute"));
                cv.put("last_run_at", strTs(rs, "last_run_at"));
                cv.put("last_run_status", rs.getString("last_run_status"));
                cv.put("last_run_msg", rs.getString("last_run_msg"));
                cv.put("next_run_at", strTs(rs, "next_run_at"));
                cv.put("created_at", strTs(rs, "created_at"));
                cv.put("updated_at", strTs(rs, "updated_at"));
                local.insertWithOnConflict("schedulers", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                n++;
            }
        }
        log.info("schedulers: pulled " + n);
        return n;
    }

    private int pullSchedulerLog(Connection remote, SQLiteDatabase local, String fromStr) throws Exception {
        String sql = "SELECT id, scheduler_id, started_at, finished_at, status, message, rows_synced, created_at, "
                + "updated_at FROM scheduler_log" + (fromStr != null ? " WHERE updated_at>=?" : "");
        int n = 0;
        try (PreparedStatement ps = prep(remote, sql, fromStr); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ContentValues cv = new ContentValues();
                cv.put("id", rs.getInt("id"));
                cv.put("scheduler_id", rs.getInt("scheduler_id"));
                cv.put("started_at", strTs(rs, "started_at"));
                cv.put("finished_at", strTs(rs, "finished_at"));
                cv.put("status", rs.getString("status"));
                cv.put("message", rs.getString("message"));
                cv.put("rows_synced", rs.getInt("rows_synced"));
                cv.put("created_at", strTs(rs, "created_at"));
                cv.put("updated_at", strTs(rs, "updated_at"));
                local.insertWithOnConflict("scheduler_log", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                n++;
            }
        }
        log.info("scheduler_log: pulled " + n);
        return n;
    }

    // ════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════

    private Cursor rawQuery(SQLiteDatabase db, String sql, String fromStr) {
        return fromStr != null ? db.rawQuery(sql, new String[]{fromStr}) : db.rawQuery(sql, null);
    }

    private PreparedStatement prep(Connection conn, String sql, String fromStr) throws Exception {
        PreparedStatement ps = conn.prepareStatement(sql);
        if (fromStr != null) ps.setTimestamp(1, java.sql.Timestamp.valueOf(fromStr));
        return ps;
    }

    /**
     * Postgres timestamp → "yyyy-MM-dd HH:mm:ss" string, null-safe.
     */
    private String strTs(ResultSet rs, String col) throws Exception {
        java.sql.Timestamp ts = rs.getTimestamp(col);
        if (ts == null) return null;
        String s = ts.toString();
        return s.length() > 19 ? s.substring(0, 19) : s;
    }

    private void post(SyncCallback cb, String progress, boolean ok, String summary) {
        if (cb == null) return;
        mainHandler.post(() -> {
            cb.onProgress(progress);
            cb.onComplete(ok, summary);
        });
    }

    /**
     * Restores everything from cloud WITHOUT requiring an active book first —
     * used from MainActivity (book list) on a fresh install / after uninstall.
     * Simply a full (no fromDate) pull.
     */
    public void restoreAllFromCloud(Context ctx, SyncCallback cb) {
        fetchFromCloud(ctx, null, cb);
    }
}