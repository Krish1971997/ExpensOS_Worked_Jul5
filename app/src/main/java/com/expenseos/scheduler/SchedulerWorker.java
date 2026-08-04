package com.expenseos.scheduler;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.expenseos.dao.SchedulerDao;
import com.expenseos.model.SchedulerConfig;
import com.expenseos.sync.SyncManager;
import com.expenseos.util.ConsoleLogger;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Android replacement for the web app's SchedulerEngine (which relied on a
 * long-lived ScheduledExecutorService — not viable on Android since the
 * process can be killed anytime). WorkManager guarantees this runs even
 * across reboots/doze, but the OS enforces a 15-minute minimum interval for
 * periodic work, so schedulers configured for sub-15-min gaps will still
 * only fire on this tick's cadence.
 */
public class SchedulerWorker extends Worker {

    public static final String WORK_NAME = "scheduler_periodic_tick";
    private static final String KEY_RUN_ONLY = "run_only_name";

    private final ConsoleLogger log = ConsoleLogger.get();

    public SchedulerWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        SchedulerDao dao = new SchedulerDao(ctx);
        String runOnly = getInputData().getString(KEY_RUN_ONLY);
        LocalDateTime now = LocalDateTime.now();

        log.info(runOnly != null
                ? "Scheduler tick — manual run requested: " + runOnly
                : "Scheduler tick — checking due jobs at " + now);

        int dueCount = 0;
        for (SchedulerConfig s : dao.findAll()) {
            boolean shouldRun = (runOnly != null)
                    ? runOnly.equals(s.getName())
                    : SchedulerTimeUtil.isDue(s, now);
            if (shouldRun) {
                dueCount++;
                runScheduler(ctx, dao, s);
            }
        }

        if (dueCount == 0) log.info("Scheduler tick — nothing due right now.");

        return Result.success();
    }

    private void runScheduler(Context ctx, SchedulerDao dao, SchedulerConfig s) {
        log.info("▶ Running scheduler: " + s.getDisplayName() + " (" + s.getName() + ")");
        long logId = dao.logStart(s.getId());
        String message;
        int rows = 0;
        boolean ok;
        try {
            switch (s.getName()) {
                case "BACKUP": {
                    com.expenseos.sync.BackupManager.get().createBackupScheduled(ctx.getApplicationContext());
                    // createBackupScheduled() already runs its own background task with a
                    // silent no-op callback (see BackupManager) — treat enqueue as success here,
                    // detailed result will show up as a new row in Backup & Restore's list.
                    ok = true;
                    message = "Scheduled backup triggered";
                    break;
                }
                case "CASHBOOK": {
                    CashBookResult r = runCashBook(ctx);
                    ok = true;
                    message = r.message;
                    rows = r.created ? 1 : 0;
                    break;
                }
                case "BUDGET": {
                    // No Budget feature ported to the Android app yet — skip
                    // gracefully instead of failing every tick.
                    ok = true;
                    message = "Budget feature not available on mobile yet — skipped";
                    break;
                }
                case "NEON_SYNC_PUSH": {
                    LocalDateTime fromDate = computeFromDate(s);
                    SyncOutcome o = runSync(ctx, true, fromDate);
                    ok = o.ok;
                    message = o.summary;
                    rows = o.rows;
                    break;
                }
                case "NEON_SYNC_PULL": {
                    LocalDateTime fromDate = computeFromDate(s);
                    SyncOutcome o = runSync(ctx, false, fromDate);
                    ok = o.ok;
                    message = o.summary;
                    rows = o.rows;
                    break;
                }
                default:
                    ok = false;
                    message = "Unknown scheduler: " + s.getName();
            }
            if (!ok) throw new RuntimeException(message);

            LocalDateTime nextRun = SchedulerTimeUtil.calcNextRun(s);
            dao.logFinish((int) logId, s.getId(), "SUCCESS", message, rows, nextRun);
            log.success("✔ " + s.getDisplayName() + " — " + message
                    + (rows > 0 ? " (" + rows + " rows)" : ""));
        } catch (Exception e) {
            LocalDateTime nextRun = SchedulerTimeUtil.calcNextRun(s);
            dao.logFinish((int) logId, s.getId(), "FAILED", e.getMessage(), 0, nextRun);
            log.error("✘ " + s.getDisplayName() + " failed: " + e.getMessage());
        }
    }

    // ── CASHBOOK: create next month's cash book if it doesn't exist ────
    private CashBookResult runCashBook(Context ctx) {
        java.time.LocalDate thisMonth = java.time.LocalDate.now().withDayOfMonth(1);
        String name = thisMonth.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy"));

        com.expenseos.dao.CashBookDao bookDao = new com.expenseos.dao.CashBookDao(ctx);
        for (com.expenseos.model.CashBook b : bookDao.findAll()) {
            if (name.equalsIgnoreCase(b.getName()))
                return new CashBookResult(false, "Cash book already exists: " + name);
        }
        bookDao.insert(name, "Auto-created by scheduler");
        return new CashBookResult(true, "Created cash book: " + name);
    }

    private static class CashBookResult {
        boolean created;
        String message;

        CashBookResult(boolean created, String message) {
            this.created = created;
            this.message = message;
        }
    }

    // ── Windowing — mirrors web SchedulerEngine.execute()'s NEON_SYNC_PUSH/
    // PULL block: sync from max(7-days-ago, last successful run), so a
    // scheduler that hasn't run in a while doesn't try to resync everything
    // since day one, but also never has a gap longer than 7 days even if
    // last_run_at is missing/very old. Passing this fromDate into
    // SyncManager makes it filter every table's push/pull by updated_at,
    // instead of the previous "always push/pull literally everything".
    private LocalDateTime computeFromDate(SchedulerConfig s) {
        LocalDateTime oneWeekAgo = LocalDateTime.now().minusDays(7);
        LocalDateTime lastRun = s.getLastRunAt();
        // First-ever run for this scheduler: lastRunAt is null. Without this
        // guard, lastRun.isBefore(...) below throws an NPE and the sync
        // silently fails every time.
        if (lastRun == null) lastRun = oneWeekAgo;
        return lastRun.isBefore(oneWeekAgo) ? lastRun : oneWeekAgo;
    }

    // ── Blocking wrapper around SyncManager's callback-based API ────
    // Worker.doWork() already runs on a background thread supplied by
    // WorkManager, so blocking here with a latch is safe and simplest.
    // NOTE: SyncManager/its DAOs already push their own detailed step-by-step
    // ConsoleLogger lines (connecting, pushing each row, etc.) — we only add
    // the start/end markers here so scheduled runs are easy to spot in the
    // console among manual ones.
    private SyncOutcome runSync(Context ctx, boolean push, LocalDateTime fromDate) {
        log.info((push ? "↑ Scheduled push" : "↓ Scheduled pull") + " starting (since " + fromDate + ")…");

        CountDownLatch latch = new CountDownLatch(1);
        SyncOutcome outcome = new SyncOutcome();

        SyncManager.SyncCallback cb = new SyncManager.SyncCallback() {
            @Override
            public void onComplete(boolean ok, String summary) {
                outcome.ok = ok;
                outcome.summary = summary;
                latch.countDown();
            }
        };

        if (push) SyncManager.get().syncToCloud(ctx, fromDate, cb);
        else SyncManager.get().fetchFromCloud(ctx, fromDate, cb);

        try {
            if (!latch.await(2, TimeUnit.MINUTES)) {
                outcome.ok = false;
                outcome.summary = "Timed out after 2 minutes";
                log.error((push ? "↑ Scheduled push" : "↓ Scheduled pull") + " timed out");
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return outcome;
    }

    private static class SyncOutcome {
        boolean ok = false;
        String summary = "";
        int rows = 0;
    }

    // ── Static scheduling helpers ────────────────────────────────────

    /**
     * Call once (e.g. HomeActivity.onCreate) — KEEP policy makes this idempotent.
     */
    public static void schedulePeriodic(Context ctx) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                SchedulerWorker.class, 15, TimeUnit.MINUTES).build();
        WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req);
        ConsoleLogger.get().info("Scheduler periodic tick registered (every 15 min).");
    }

    /**
     * Force-run one scheduler immediately, regardless of its next_run_at.
     */
    public static void runNow(Context ctx, String schedulerName) {
        ConsoleLogger.get().info("Manual run requested: " + schedulerName);
        Data input = new Data.Builder().putString(KEY_RUN_ONLY, schedulerName).build();
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(SchedulerWorker.class)
                .setInputData(input)
                .build();
        WorkManager.getInstance(ctx).enqueue(req);
    }
}