package com.expenseos.sync;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.expenseos.dao.LocalDatabase;
import com.expenseos.dao.TransactionDao;
import com.expenseos.model.Transaction;
import com.expenseos.util.AppConfig;
import com.expenseos.util.ConsoleLogger;
import com.expenseos.util.DBConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Central sync engine.
 * All DB operations run on a single-thread executor (background).
 * UI callbacks are delivered on the main thread via Handler.
 */
public class SyncManager {

    public interface SyncCallback {
        default void onProgress(String message) {
        }

        void onComplete(boolean success, String summary);
    }


    // Android's core-library desugaring does NOT support the
    // java.sql.Timestamp <-> java.time bridge methods (toLocalDateTime(),
    // valueOf(LocalDateTime)) even on apps targeting old minSdk — they
    // compile on a plain JVM but fail on Android. Route through
    // string/epoch-millis conversions instead.
    private static final java.time.format.DateTimeFormatter TS_FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static java.time.LocalDateTime timestampToLocalDateTime(Timestamp ts) {
        if (ts == null) return null;
        String s = ts.toString(); // "yyyy-MM-dd HH:mm:ss.fffffffff"
        if (s.length() > 19) s = s.substring(0, 19);
        return java.time.LocalDateTime.parse(s, TS_FMT);
    }

    private static Timestamp localDateTimeToTimestamp(java.time.LocalDateTime dt) {
        if (dt == null) return null;
        long millis = dt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        return new Timestamp(millis);
    }

    private static SyncManager instance;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ConsoleLogger log = ConsoleLogger.get();

    public static SyncManager get() {
        if (instance == null) instance = new SyncManager();
        return instance;
    }

    // ── SYNC TO CLOUD ────────────────────────────────────────────────────────
    public void syncToCloud(Context ctx, SyncCallback cb) {
        executor.execute(() -> {
            log.info("═══ SYNC TO CLOUD STARTED ═══");
            int pushed = 0, errors = 0;

            DBConnection.getInstance().configureFromAppConfig(ctx);
            TransactionDao dao = new TransactionDao(ctx);

            List<Transaction> pending = dao.getPendingSync();
            log.info("Pending local records: " + pending.size());

            if (pending.isEmpty()) {
                log.success("Nothing to sync — all records up to date.");
                post(cb, "Nothing to sync", true, "All records already synced.");
                return;
            }

            try (Connection conn = DBConnection.getInstance().getConnection()) {
                conn.setAutoCommit(false);
                int bookId = AppConfig.get(ctx).getActiveBookId();

                for (Transaction t : pending) {
                    try {
                        String action = getSyncAction(ctx, t.getId());
                        switch (action) {
                            case "INSERT" -> {
                                int remoteId = insertRemote(conn, t, bookId);
                                dao.markSynced(t.getId(), remoteId);
                                log.success("Inserted TXN #" + remoteId + " → " +
                                        t.getType() + " ₹" + t.getAmount());
                                pushed++;
                            }
                            case "UPDATE" -> {
                                updateRemote(conn, t);
                                dao.markSynced(t.getId(), t.getId());
                                log.success("Updated TXN remote_id=" + t.getId());
                                pushed++;
                            }
                            case "DELETE" -> {
                                deleteRemote(conn, t);
                                dao.markSynced(t.getId(), t.getId());
                                log.warn("Deleted TXN remote_id=" + t.getId());
                                pushed++;
                            }
                            default -> log.warn("Unknown sync action: " + action);
                        }
                    } catch (Exception e) {
                        log.error("Failed to sync local id=" + t.getId() + ": " + e.getMessage());
                        errors++;
                    }
                }
                conn.commit();
            } catch (Exception e) {
                log.error("Connection error: " + e.getMessage());
                errors++;
            }

            String summary = "Pushed: " + pushed + "  Errors: " + errors;
            log.info("═══ SYNC TO CLOUD DONE — " + summary + " ═══");
            boolean ok = errors == 0;
            post(cb, summary, ok, summary);
        });
    }

    // ── FETCH FROM CLOUD ─────────────────────────────────────────────────────
    public void fetchFromCloud(Context ctx, SyncCallback cb) {
        executor.execute(() -> {
            log.info("═══ FETCH FROM CLOUD STARTED ═══");
            DBConnection.getInstance().configureFromAppConfig(ctx);

            int bookId = AppConfig.get(ctx).getActiveBookId();
            List<Transaction> remote = new ArrayList<>();

            try (Connection conn = DBConnection.getInstance().getConnection()) {
                log.info("Fetching transactions for book_id=" + bookId);

                // Fetch transactions
                String sql = "SELECT t.*, c.name as cat_name, sc.name as subcat_name " +
                        "FROM transactions t " +
                        "LEFT JOIN categories c ON t.category_id = c.id " +
                        "LEFT JOIN sub_categories sc ON t.sub_categories_id = sc.id " +
                        "WHERE t.book_id=? ORDER BY t.txn_datetime DESC";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setInt(1, bookId);
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        Transaction t = new Transaction();
                        t.setId(rs.getInt("id"));
                        t.setType("EXPENSE".equals(rs.getString("type"))
                                ? Transaction.Type.EXPENSE : Transaction.Type.INCOME);
                        Timestamp ts = rs.getTimestamp("txn_datetime");
                        t.setDateTime(timestampToLocalDateTime(ts));
                        t.setAmount(rs.getBigDecimal("amount"));
                        t.setCategoryId(rs.getInt("category_id"));
                        t.setCategoryName(rs.getString("cat_name"));
                        t.setSubCategoryId(rs.getInt("sub_categories_id"));
                        t.setSubCategoryName(rs.getString("subcat_name"));
                        t.setNote(rs.getString("note"));
                        t.setBookId(bookId);
                        t.setSynced(true);
                        remote.add(t);
                    }
                }

                log.info("Fetched " + remote.size() + " transactions from cloud");

                // Fetch categories
                fetchCategoriesAndStore(conn, ctx);

                // Fetch sub-categories
                fetchSubCategoriesAndStore(conn, ctx);

                // Fetch cash books
                fetchBooksAndStore(conn, ctx);

                // Replace local
                new TransactionDao(ctx).replaceAllFromRemote(bookId, remote);
                log.success("Local DB updated with " + remote.size() + " records.");

            } catch (Exception e) {
                log.error("Fetch failed: " + e.getMessage());
                post(cb, "Fetch failed", false, e.getMessage());
                return;
            }

            String summary = "Fetched " + remote.size() + " records";
            log.info("═══ FETCH DONE — " + summary + " ═══");
            post(cb, summary, true, summary);
        });
    }

    // ── TEST CONNECTION ───────────────────────────────────────────────────────
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

    // ── Private helpers ───────────────────────────────────────────────────────
    private int insertRemote(Connection conn, Transaction t, int bookId) throws Exception {
        String sql = "INSERT INTO transactions (type, txn_datetime, amount, category_id, " +
                "sub_categories_id, note, book_id) VALUES (?::transaction_type, ?, ?, ?, ?, ?, ?) " +
                "RETURNING id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, t.getType().name());
            ps.setTimestamp(2, localDateTimeToTimestamp(t.getDateTime()));
            ps.setBigDecimal(3, t.getAmount());
            ps.setInt(4, t.getCategoryId());
            ps.setInt(5, t.getSubCategoryId());
            ps.setString(6, t.getNote());
            ps.setInt(7, bookId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
            throw new Exception("No ID returned from INSERT");
        }
    }

    private void updateRemote(Connection conn, Transaction t) throws Exception {
        String sql = "UPDATE transactions SET type=?::transaction_type, txn_datetime=?, " +
                "amount=?, category_id=?, sub_categories_id=?, note=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, t.getType().name());
            ps.setTimestamp(2, localDateTimeToTimestamp(t.getDateTime()));
            ps.setBigDecimal(3, t.getAmount());
            ps.setInt(4, t.getCategoryId());
            ps.setInt(5, t.getSubCategoryId());
            ps.setString(6, t.getNote());
            ps.setInt(7, t.getId());
            ps.executeUpdate();
        }
    }

    private void deleteRemote(Connection conn, Transaction t) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM transactions WHERE id=?")) {
            ps.setInt(1, t.getId());
            ps.executeUpdate();
        }
    }

    private void fetchCategoriesAndStore(Connection conn, Context ctx) throws Exception {
        android.content.ContentValues cv;
        android.database.sqlite.SQLiteDatabase sdb = LocalDatabase.get(ctx).getWritableDatabase();
        sdb.delete("categories", null, null);
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, name, type FROM categories");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                cv = new android.content.ContentValues();
                cv.put("id", rs.getInt("id"));
                cv.put("name", rs.getString("name"));
                cv.put("type", rs.getString("type"));
                sdb.insert("categories", null, cv);
            }
        }
        log.info("Categories synced.");
    }

    private void fetchSubCategoriesAndStore(Connection conn, Context ctx) throws Exception {
        android.content.ContentValues cv;
        android.database.sqlite.SQLiteDatabase sdb = LocalDatabase.get(ctx).getWritableDatabase();
        sdb.delete("sub_categories", null, null);
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT sub_categories_id, name, category_id FROM sub_categories");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                cv = new android.content.ContentValues();
                cv.put("sub_categories_id", rs.getInt("sub_categories_id"));
                cv.put("name", rs.getString("name"));
                cv.put("category_id", rs.getInt("category_id"));
                sdb.insert("sub_categories", null, cv);
            }
        }
        log.info("Sub-categories synced.");
    }

    private void fetchBooksAndStore(Connection conn, Context ctx) throws Exception {
        android.content.ContentValues cv;
        android.database.sqlite.SQLiteDatabase sdb = LocalDatabase.get(ctx).getWritableDatabase();
        sdb.delete("cash_books", null, null);
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, description, created_at FROM cash_books");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                cv = new android.content.ContentValues();
                cv.put("id", rs.getInt("id"));
                cv.put("name", rs.getString("name"));
                cv.put("description", rs.getString("description"));
                Timestamp ts = rs.getTimestamp("created_at");
                cv.put("created_at", ts != null ? ts.toString() : "");
                sdb.insert("cash_books", null, cv);
            }
        }
        log.info("Cash books synced.");
    }

    private String getSyncAction(Context ctx, int localId) {
        android.database.Cursor c = LocalDatabase.get(ctx).getReadableDatabase()
                .rawQuery("SELECT sync_action FROM transactions WHERE id=?",
                        new String[]{String.valueOf(localId)});
        String action = "INSERT";
        if (c.moveToFirst()) action = c.getString(0);
        c.close();
        return action != null ? action : "INSERT";
    }

    private void post(SyncCallback cb, String progress, boolean ok, String summary) {
        if (cb == null) return;
        mainHandler.post(() -> {
            cb.onProgress(progress);
            cb.onComplete(ok, summary);
        });
    }
}