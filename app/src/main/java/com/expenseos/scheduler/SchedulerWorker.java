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
import java.util.ArrayList;
import java.util.List;
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

    private void
    runScheduler(Context ctx, SchedulerDao dao, SchedulerConfig s) {
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
                    rows = r.count;   // <-- was r.created ? 1 : 0, now actual count (0-3)
                    break;
                }
                case "BUDGET": {
                    BudgetOutcome o = runBudgetAllocation(ctx);
                    ok = o.ok;
                    message = o.message;
                    rows = o.categoriesAllocated;
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
                case "MONTHLY_CATEGORY_REPORT": {
                    MonthlyReportOutcome o = runMonthlyCategoryReport(ctx);
                    ok = o.ok;
                    message = o.message;
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
            sendFailureAlert(ctx, s, e);
        }
    }

    // Best-effort email on any scheduler failure — swallowed on error so an
    // alert-sending problem (bad SMTP creds, no network) never masks the
    // original failure that's already been logged above.
    private void sendFailureAlert(Context ctx, SchedulerConfig s, Exception failure) {
        log.info("📧 Attempting to send failure alert email for: " + s.getDisplayName());

        com.expenseos.util.AppConfig appConfig = com.expenseos.util.AppConfig.get(ctx);
        String alertEmail = appConfig.getSchedulerAlertEmail();
        String gmailFrom = appConfig.getGmailFrom();
        String gmailAppPass = appConfig.getGmailAppPass();

        // 1. Alert Email Check
        if (alertEmail == null || alertEmail.isBlank()) {
            log.warn("⚠️ Scheduler failure alert skipped — no alert email configured in AppConfig!");
            return;
        }

        log.info("📧 Target Alert Email: " + alertEmail);
        log.info("📧 Configured Gmail From: " + gmailFrom);

        // 2. Sender Credentials Check (GMAIL_FROM & GMAIL_APP_PASS)
        if (gmailFrom == null || gmailFrom.isBlank() || gmailAppPass == null || gmailAppPass.isBlank()) {
            log.error("✘ Cannot send email! Sender credentials (getGmailFrom / getGmailAppPass) are missing in AppConfig.");
            return;
        }

        try {
            String subject = "ExpenseOS scheduler failed: " + s.getDisplayName();
            String html = "<p><b>Scheduler:</b> " + s.getDisplayName() + " (" + s.getName() + ")</p>"
                    + "<p><b>Failed at:</b> " + LocalDateTime.now() + "</p>"
                    + "<p><b>Error:</b> " + (failure.getMessage() != null ? failure.getMessage() : failure.toString()) + "</p>";

            log.info("📧 Sending failure alert email via GmailSender...");
            com.expenseos.util.GmailSender.send(ctx, alertEmail, subject, html, null);
            log.success("✔ Failure alert successfully emailed to " + alertEmail + " for " + s.getDisplayName());

        } catch (Exception mailEx) {
            log.error("✘ Failed to send scheduler failure alert to " + alertEmail + "!");
            log.error("✘ Exception Reason: " + (mailEx.getMessage() != null ? mailEx.getMessage() : mailEx.toString()));

            // Console UI-இல் முழு Stack Trace தெரிய
            java.io.StringWriter sw = new java.io.StringWriter();
            mailEx.printStackTrace(new java.io.PrintWriter(sw));
            log.error("✘ Details: " + sw);
        }
    }

    // ── CASHBOOK: create next month's cash book if it doesn't exist ────
    // ── CASHBOOK: create this month's set of 3 books if they don't exist ────
    private CashBookResult runCashBook(Context ctx) {
        java.time.LocalDate thisMonth = java.time.LocalDate.now().withDayOfMonth(1);
        java.time.LocalDate nextMonth = thisMonth.plusMonths(1);

        String thisMonthName = thisMonth.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy"));
        String nextMonthName = nextMonth.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy"));

        String[] namesToCreate = {
                thisMonthName,                          // e.g. "August 2026"
                thisMonthName + " Expense",              // e.g. "August 2026 Expense"
                nextMonthName + " Credit Card"           // e.g. "September 2026 Credit Card"
        };

        com.expenseos.dao.CashBookDao bookDao = new com.expenseos.dao.CashBookDao(ctx);
        java.util.List<com.expenseos.model.CashBook> existing = bookDao.findAll();

        int created = 0;
        List<String> createdNames = new ArrayList<>();
        List<String> skippedNames = new ArrayList<>();

        for (String name : namesToCreate) {
            boolean already = false;
            for (com.expenseos.model.CashBook b : existing) {
                if (name.equalsIgnoreCase(b.getName())) {
                    already = true;
                    break;
                }
            }
            if (already) {
                skippedNames.add(name);
                continue;
            }
            bookDao.insert(name, "Auto-created by scheduler");
            createdNames.add(name);
            created++;
        }

        String message;
        if (created == 0) {
            message = "All cash books already exist: " + String.join(", ", skippedNames);
        } else {
            message = "Created: " + String.join(", ", createdNames)
                    + (skippedNames.isEmpty() ? "" : " (already existed: " + String.join(", ", skippedNames) + ")");
        }
        return new CashBookResult(created > 0, message, created);
    }

    private static class CashBookResult {
        boolean created;
        String message;
        int count;

        CashBookResult(boolean created, String message, int count) {
            this.created = created;
            this.message = message;
            this.count = count;
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

    // ── MONTHLY_CATEGORY_REPORT: emails the last-3-months category
    // comparison for the active cashbook. Runs at 12:05 AM on the 1st, so
    // "current month" has ~0 data yet — compares the month that just ended
    // against the one before it (matches the CASHBOOK case's "current
    // month at trigger time" convention, one turn earlier since this looks
    // BACKWARD instead of creating something forward).
    private MonthlyReportOutcome runMonthlyCategoryReport(Context ctx) {
        MonthlyReportOutcome outcome = new MonthlyReportOutcome();
        try {
            int bookId = com.expenseos.util.AppConfig.get(ctx).getActiveBookId();
            com.expenseos.util.CategoryComparisonReport.Result result =
                    com.expenseos.util.CategoryComparisonReport.build(ctx, bookId, 3, false);

            if (result.rows.isEmpty()) {
                outcome.ok = true;
                outcome.message = "No expense data to report — skipped email";
                return outcome;
            }

            java.io.ByteArrayOutputStream pdfBytes = new java.io.ByteArrayOutputStream();
            com.expenseos.util.CategoryComparisonReport.writePdf(result, pdfBytes);

            String subject = "Monthly Category Report — " +
                    result.months.get(result.months.size() - 1).getMonth().getDisplayName(
                            java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH);
            String html = com.expenseos.util.CategoryComparisonReport.buildHtmlEmail(result);
            com.expenseos.util.GmailSender.Attachment attachment = new com.expenseos.util.GmailSender.Attachment(
                    "monthly_category_report.pdf", pdfBytes.toByteArray(), "application/pdf");

            com.expenseos.util.GmailSender.send(ctx, null, subject, html, attachment);
            outcome.ok = true;
            outcome.message = "Report emailed (" + result.rows.size() + " categories)";
        } catch (Exception e) {
            outcome.ok = false;
            outcome.message = e.getMessage() != null ? e.getMessage() : e.toString();
        }
        return outcome;
    }

    private static class MonthlyReportOutcome {
        boolean ok = false;
        String message = "";
    }

    // ── BUDGET: applies the saved allocation-template (% split per
    // category, set once via BudgetConfigActivity) to the CURRENT month for
    // the active book. Mirrors MONTHLY_CATEGORY_REPORT's single-active-book
    // convention rather than looping every book. If no template was ever
    // saved for this book, skip cleanly (don't fail the tick).
    private BudgetOutcome runBudgetAllocation(Context ctx) {
        BudgetOutcome outcome = new BudgetOutcome();
        try {
            int bookId = com.expenseos.util.AppConfig.get(ctx).getActiveBookId();
            com.expenseos.dao.BudgetTemplateDao templateDao = new com.expenseos.dao.BudgetTemplateDao(ctx);

            if (!templateDao.hasTemplate(bookId)) {
                outcome.ok = true;
                outcome.message = "No budget template configured for this book — skipped";
                return outcome;
            }

            java.time.LocalDate now = java.time.LocalDate.now();
            int year = now.getYear();
            int month = now.getMonthValue();

            java.math.BigDecimal overallLimit = templateDao.loadDefaultOverallLimit(bookId);
            java.util.Map<Integer, java.math.BigDecimal> percents = templateDao.loadPercents(bookId);

            if (overallLimit == null || percents.isEmpty()) {
                outcome.ok = true;
                outcome.message = "Budget template incomplete — skipped";
                return outcome;
            }

            com.expenseos.dao.BudgetDao budgetDao = new com.expenseos.dao.BudgetDao(ctx);

            // Don't overwrite a budget the user already has for this month
            // (e.g. they already opened Budget tab and set/adjusted it manually).
            if (budgetDao.findByMonth(bookId, year, month) != null) {
                outcome.ok = true;
                outcome.message = "Budget already exists for " + month + "/" + year + " — skipped";
                return outcome;
            }

            com.expenseos.model.Budget b = new com.expenseos.model.Budget();
            b.setBookId(bookId);
            b.setYear(year);
            b.setMonth(month);
            b.setOverallLimit(overallLimit);
            int budgetId = budgetDao.upsert(b);

            int count = 0;
            for (java.util.Map.Entry<Integer, java.math.BigDecimal> e : percents.entrySet()) {
                java.math.BigDecimal amt = overallLimit.multiply(e.getValue())
                        .divide(java.math.BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
                com.expenseos.model.BudgetCategory bc = new com.expenseos.model.BudgetCategory();
                bc.setBudgetId(budgetId);
                bc.setCategoryId(e.getKey());
                bc.setCatLimit(amt);
                bc.setAlertPct(80);
                budgetDao.upsertCategory(bc);
                count++;
            }

            outcome.ok = true;
            outcome.categoriesAllocated = count;
            outcome.message = "Budget auto-created for " + java.time.Month.of(month) + " " + year
                    + " (" + count + " categories, ₹" + overallLimit.stripTrailingZeros().toPlainString() + " total)";
        } catch (Exception e) {
            outcome.ok = false;
            outcome.message = e.getMessage() != null ? e.getMessage() : e.toString();
        }
        return outcome;
    }

    private static class BudgetOutcome {
        boolean ok = false;
        String message = "";
        int categoriesAllocated = 0;
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
     * Seeds the "MONTHLY_CATEGORY_REPORT" scheduler row if it doesn't exist
     * yet (SchedulerDao.insertScheduler uses CONFLICT_IGNORE on the unique
     * `name` column, so calling this on every app start is safe/idempotent).
     * Runs at 00:05 on the 1st of each month.
     */
    public static void ensureMonthlyCategoryReportScheduler(Context ctx) {
        SchedulerDao dao = new SchedulerDao(ctx);
        if (dao.findByName("MONTHLY_CATEGORY_REPORT") != null) return;

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime firstOfThisMonth = now.withDayOfMonth(1).withHour(0).withMinute(5).withSecond(0).withNano(0);
        LocalDateTime nextRun = now.isBefore(firstOfThisMonth)
                ? firstOfThisMonth
                : firstOfThisMonth.plusMonths(1);

        dao.insertScheduler("MONTHLY_CATEGORY_REPORT", "Monthly Category Report Email",
                true, "MONTHLY", "1", 0, 5, nextRun);
        ConsoleLogger.get().info("Monthly Category Report scheduler seeded — next run: " + nextRun);
    }

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