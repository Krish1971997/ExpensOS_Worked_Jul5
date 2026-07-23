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

        for (SchedulerConfig s : dao.findAll()) {
            boolean shouldRun = (runOnly != null)
                    ? runOnly.equals(s.getName())
                    : SchedulerTimeUtil.isDue(s, now);
            if (shouldRun) {
                runScheduler(ctx, dao, s);
            }
        }
        return Result.success();
    }

    private void runScheduler(Context ctx, SchedulerDao dao, SchedulerConfig s) {
        long logId = dao.logStart(s.getId());
        String message;
        int rows = 0;
        boolean ok;
        try {
            switch (s.getName()) {
                case "BACKUP": {
                    CountDownLatch latch = new CountDownLatch(1);
                    boolean[] okHolder = {false};
                    String[] msgHolder = {""};
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
                    SyncOutcome o = runSync(ctx, true);
                    ok = o.ok;
                    message = o.summary;
                    rows = o.rows;
                    break;
                }
                case "NEON_SYNC_PULL": {
                    SyncOutcome o = runSync(ctx, false);
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
        } catch (Exception e) {
            LocalDateTime nextRun = SchedulerTimeUtil.calcNextRun(s);
            dao.logFinish((int) logId, s.getId(), "FAILED", e.getMessage(), 0, nextRun);
        }
    }

    // ── CASHBOOK: create next month's cash book if it doesn't exist ────
    private CashBookResult runCashBook(Context ctx) {
        java.time.LocalDate nextMonth = java.time.LocalDate.now().plusMonths(1).withDayOfMonth(1);
        String name = nextMonth.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy"));

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

    // ── Blocking wrapper around SyncManager's callback-based API ────
    // Worker.doWork() already runs on a background thread supplied by
    // WorkManager, so blocking here with a latch is safe and simplest.
    private SyncOutcome runSync(Context ctx, boolean push) {
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

        if (push) SyncManager.get().syncToCloud(ctx, cb);
        else SyncManager.get().fetchFromCloud(ctx, cb);

        try {
            latch.await(2, TimeUnit.MINUTES);
        } catch (InterruptedException ignored) {
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
    }

    /**
     * Force-run one scheduler immediately, regardless of its next_run_at.
     */
    public static void runNow(Context ctx, String schedulerName) {
        Data input = new Data.Builder().putString(KEY_RUN_ONLY, schedulerName).build();
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(SchedulerWorker.class)
                .setInputData(input)
                .build();
        WorkManager.getInstance(ctx).enqueue(req);
    }
}
