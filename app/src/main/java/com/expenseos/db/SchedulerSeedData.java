package com.expenseos.db;

import android.database.sqlite.SQLiteDatabase;

/**
 * One-time seed data for the `schedulers` table, ported from the web app's
 * export (schedulers_data_export.csv). last_run_at / last_run_status /
 * last_run_msg are intentionally left NULL here — this is a fresh install,
 * so there's no real run history yet; the SchedulerWorker will populate
 * these itself the first time each job actually runs on-device.
 *
 * Call this once from LocalDB.onCreate() (right after the CREATE TABLE
 * statements for `schedulers` / `scheduler_log`), guarded so it only ever
 * runs on first DB creation, not on every upgrade.
 */
public class SchedulerSeedData {

    public static void insert(SQLiteDatabase db) {
        db.execSQL("INSERT OR IGNORE INTO schedulers " +
                "(id, name, display_name, enabled, repeat_type, repeat_days, run_hour, run_minute, " +
                "last_run_at, last_run_status, last_run_msg, next_run_at, created_at, updated_at) VALUES " +

                "(1, 'BACKUP', 'Daily Backup', 1, 'DAILY', NULL, 21, 52, " +
                "NULL, NULL, NULL, '2026-07-17 21:52:00', '2026-06-18 13:58:28', '2026-06-18 13:58:28'), " +

                "(2, 'CASHBOOK', 'Auto Cash Book Creation', 1, 'MONTHLY', '1', 0, 0, " +
                "NULL, NULL, NULL, '2026-08-01 00:00:00', '2026-06-18 13:58:28', '2026-06-18 13:58:28'), " +

                "(3, 'BUDGET', 'Auto Budget Assignment', 1, 'MONTHLY', '1', 0, 30, " +
                "NULL, NULL, NULL, NULL, '2026-06-18 13:58:28', '2026-06-18 13:58:28'), " +

                "(4, 'NEON_SYNC_PUSH', 'Neon DB Cloud Sync - Push data', 1, 'HOURLY', NULL, 19, 18, " +
                "NULL, NULL, NULL, '2026-07-17 20:18:00', '2026-06-18 22:33:34', '2026-06-18 22:33:34'), " +

                "(5, 'NEON_SYNC_PULL', 'Neon DB Cloud Syn - Pull data', 1, 'DAILY', NULL, 18, 0, " +
                "NULL, NULL, NULL, '2026-07-18 18:00:00', '2026-06-25 22:17:28', '2026-06-25 22:17:28')"
        );
    }
}
