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

    // Retry knobs — 3 retries after the first failure (4 attempts total),
    // 30s buffer between each. NOTE: this blocks the worker thread for up
    // to ~90s on a persistently-failing scheduler, which can delay other
    // due schedulers in the same 15-min tick — acceptable for occasional
    // transient failures (network blips etc); lower these if it becomes an
    // issue with many schedulers failing at once.
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BUFFER_MS = 30_000;

    private static final int FAILURE_THRESHOLD = 3; // consecutive real ticks, not in-process retries

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
                    ok = true;
                    message = "Scheduled backup triggered";
                    break;
                }
                case "CASHBOOK": {
                    CashBookResult r = runCashBook(ctx);
                    ok = true;
                    message = r.message;
                    rows = r.count;
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

            dao.resetFailureCount(s.getId()); // clear any earlier failure streak on success
            LocalDateTime nextRun = SchedulerTimeUtil.calcNextRun(s);
            dao.logFinish((int) logId, s.getId(), "SUCCESS", message, rows, nextRun);
            log.success("✔ " + s.getDisplayName() + " — " + message
                    + (rows > 0 ? " (" + rows + " rows)" : ""));
        } catch (Exception e) {
            LocalDateTime nextRun = SchedulerTimeUtil.calcNextRun(s);
            dao.logFinish((int) logId, s.getId(), "FAILED", e.getMessage(), 0, nextRun);

            int failureCount = dao.incrementFailureCount(s.getId());
            log.error("✘ " + s.getDisplayName() + " failed (failure " + failureCount + "/" + FAILURE_THRESHOLD
                    + " consecutive ticks): " + e.getMessage());

            if (failureCount >= FAILURE_THRESHOLD) {
                sendFailureAlert(ctx, s, e, failureCount);
                dao.resetFailureCount(s.getId()); // start a fresh streak after alerting
            } else {
                log.info("📧 Alert email held back — needs " + (FAILURE_THRESHOLD - failureCount)
                        + " more consecutive failure(s) first.");
            }
        }
    }

    // The actual dispatch — same switch/behavior as before, just extracted
    // so runScheduler() can retry it. Throws on failure, same as the old
    // "if (!ok) throw" pattern.
    private AttemptResult attemptScheduler(Context ctx, SchedulerConfig s) throws Exception {
        String message;
        int rows = 0;
        boolean ok;
        switch (s.getName()) {
            case "BACKUP": {
                com.expenseos.sync.BackupManager.get().createBackupScheduled(ctx.getApplicationContext());
                ok = true;
                message = "Scheduled backup triggered";
                break;
            }
            case "CASHBOOK": {
                CashBookResult r = runCashBook(ctx);
                ok = true;
                message = r.message;
                rows = r.count;
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
        return new AttemptResult(message, rows);
    }

    private record AttemptResult(String message, int rows) {
    }

    // Best-effort email on any scheduler failure — swallowed on error so an
    // alert-sending problem (bad SMTP creds, no network) never masks the
    // original failure that's already been logged above.
    private void sendFailureAlert(Context ctx, SchedulerConfig s, Exception failure, int failureCount) {
        log.info("📧 Attempting to send failure alert email for: " + s.getDisplayName()
                + " (" + failureCount + " consecutive failures)");

        // 0. Network check — avoids a multi-minute SMTP connect timeout
        // hanging the worker when there's clearly no usable connection.
        // NOTE: this only confirms general internet connectivity, not that
        // smtp.gmail.com:587 specifically is reachable (e.g. a firewall
        // could still block that port) — the try/catch below still covers that case.
        if (!hasUsableNetwork(ctx)) {
            log.warn("⚠️ Scheduler failure alert skipped — no network connection available right now.");
            return;
        }

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
            String subject = "ExpenseOS scheduler failed " + failureCount + "x: " + s.getDisplayName();
            String html = "<p><b>Scheduler:</b> " + s.getDisplayName() + " (" + s.getName() + ")</p>"
                    + "<p><b>Consecutive failures:</b> " + failureCount + "</p>"
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

    private boolean hasUsableNetwork(Context ctx) {
        android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        android.net.Network network = cm.getActiveNetwork();
        if (network == null) return false;
        android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null
                && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    // ── CASHBOOK: create next month's cash book if it doesn't exist ────
    // ── CASHBOOK: create this month's set of 3 books if they don't exist ────
    private CashBookResult runCashBook(Context ctx) {
        java.time.LocalDate thisMonth = java.time.LocalDate.now().withDayOfMonth(1);
        java.time.LocalDate nextMonth = thisMonth.plusMonths(2);

        String thisMonthName = thisMonth.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy"));
        String nextMonthName = nextMonth.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy"));

        String[] namesToCreate = {
                thisMonthName,                          // e.g. "August 2026"
                thisMonthName + " Expense",              // e.g. "August 2026 Expense"
                nextMonthName + " Credit Card",          // e.g. "September 2026 Credit Card"
                thisMonthName + " Food"                  // e.g. "August 2026 Food" — FoodTrackerActivity idha use pannudhu
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
    // comparison — SEPARATELY for each cashbook series (plain "<Month>
    // <Year>", "<Month> <Year> Expense", "<Month> <Year> Credit Card" —
    // same 3 suffixes runCashBook() creates every month), one individual
    // email per series. Runs at 12:05 AM on the 1st, so "current month"
    // has ~0 data yet — compares the month that just ended against the
    // one before it.
    private static final String[] MONTHLY_REPORT_SUFFIXES = {"", "Expense", "Credit Card"};

    private MonthlyReportOutcome runMonthlyCategoryReport(Context ctx) {
        MonthlyReportOutcome outcome = new MonthlyReportOutcome();
        String alertEmail = com.expenseos.util.AppConfig.get(ctx).getSchedulerAlertEmail();
        int sent = 0, skipped = 0;
        List<String> failures = new ArrayList<>();

// NEW — Credit Card cashbooks use the CURRENT month's anchor (Sep/Aug/Jul
// when run in September), not last month's, since a credit card
// statement covers spend up to its own cycle date — unlike the plain/
// Expense series where "last month just ended" is the meaningful window.
// Everything else keeps the existing includeCurrent=false behaviour.
        for (String suffix : MONTHLY_REPORT_SUFFIXES) {
            String label = suffix.isEmpty() ? "Main" : suffix;
            boolean includeCurrentMonth = "Credit Card".equals(suffix);
            try {
                com.expenseos.util.CategoryComparisonReport.Result result =
                        com.expenseos.util.CategoryComparisonReport.buildForSuffix(ctx, suffix, 3, includeCurrentMonth);

                if (result.rows.isEmpty()) {
                    skipped++;
                    continue;
                }

                java.io.ByteArrayOutputStream pdfBytes = new java.io.ByteArrayOutputStream();
                com.expenseos.util.CategoryComparisonReport.writePdf(result, pdfBytes);

                String subject = "Monthly Category Report" + (suffix.isEmpty() ? "" : " (" + suffix + ")") + " — " +
                        result.months.get(result.months.size() - 1).getMonth().getDisplayName(
                                java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH);
                String html = com.expenseos.util.CategoryComparisonReport.buildHtmlEmail(result);
                String fileSuffix = suffix.isEmpty() ? "" : "_" + suffix.toLowerCase(java.util.Locale.ENGLISH).replace(" ", "_");
                com.expenseos.util.GmailSender.Attachment attachment = new com.expenseos.util.GmailSender.Attachment(
                        "monthly_category_report" + fileSuffix + ".pdf", pdfBytes.toByteArray(), "application/pdf");

                com.expenseos.util.GmailSender.send(ctx, alertEmail, subject, html, attachment);
                sent++;
            } catch (Exception e) {
                failures.add(label + ": " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            }
        }

        // Separate email — last month's Food tracker report. Counted in the
        // same sent/skipped/failures tally so this scheduler's one log entry
        // reflects everything it sent this run.
        try {
            if (sendLastMonthFoodReport(ctx, alertEmail)) sent++;
            else skipped++; // no "<Month> <Year> Food" book existed for last month
        } catch (Exception e) {
            failures.add("Food: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
        }

        if (!failures.isEmpty()) {
            outcome.ok = false;
            outcome.message = "Sent " + sent + ", skipped " + skipped + " (no data), failed: " + String.join("; ", failures);
        } else {
            outcome.ok = true;
            outcome.message = "Sent " + sent + " report email(s), skipped " + skipped + " series with no data";
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
            com.expenseos.dao.BudgetTemplateDao templateDao = new com.expenseos.dao.BudgetTemplateDao(ctx);

            if (!templateDao.hasGlobalTemplate()) {
                outcome.ok = true;
                outcome.message = "No budget template configured — skipped";
                return outcome;
            }

            java.util.Map<Integer, java.math.BigDecimal> amounts = templateDao.loadGlobalAmounts();
            if (amounts.isEmpty()) {
                outcome.ok = true;
                outcome.message = "Budget template is empty — skipped";
                return outcome;
            }

            java.math.BigDecimal overallLimit = java.math.BigDecimal.ZERO;
            for (java.math.BigDecimal amt : amounts.values()) overallLimit = overallLimit.add(amt);

            java.time.LocalDate now = java.time.LocalDate.now();
            int year = now.getYear();
            int month = now.getMonthValue();

            com.expenseos.dao.BudgetDao budgetDao = new com.expenseos.dao.BudgetDao(ctx);
            com.expenseos.dao.CashBookDao bookDao = new com.expenseos.dao.CashBookDao(ctx);
            java.util.List<com.expenseos.model.CashBook> books = bookDao.findAll();

            int booksUpdated = 0, categoriesAllocated = 0;

            for (com.expenseos.model.CashBook book : books) {
                int bookId = book.getId();

                // Don't overwrite a budget the user already has for this
                // month in this book.
                if (budgetDao.findByMonth(bookId, year, month) != null) continue;

                com.expenseos.model.Budget b = new com.expenseos.model.Budget();
                b.setBookId(bookId);
                b.setYear(year);
                b.setMonth(month);
                b.setOverallLimit(overallLimit);
                int budgetId = budgetDao.upsert(b);

                for (java.util.Map.Entry<Integer, java.math.BigDecimal> e : amounts.entrySet()) {
                    com.expenseos.model.BudgetCategory bc = new com.expenseos.model.BudgetCategory();
                    bc.setBudgetId(budgetId);
                    bc.setCategoryId(e.getKey());
                    bc.setCatLimit(e.getValue()); // exact amount from the shared template — no scaling
                    bc.setAlertPct(80);
                    budgetDao.upsertCategory(bc);
                    categoriesAllocated++;
                }
                booksUpdated++;
            }

            outcome.ok = true;
            outcome.categoriesAllocated = categoriesAllocated;
            outcome.message = booksUpdated > 0
                    ? "Budget auto-created for " + java.time.Month.of(month) + " " + year
                      + " across " + booksUpdated + " book(s), ₹" + overallLimit.stripTrailingZeros().toPlainString() + " each"
                    : "All books already have a budget for " + java.time.Month.of(month) + " " + year + " — skipped";
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
            if (!latch.await(3, TimeUnit.MINUTES)) {
                outcome.ok = false;
                outcome.summary = "Timed out after 3 minutes";
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

    // ── FOOD TRACKER REPORT — last month's "<Month> <Year> Food" book
// (SchedulerWorker.runCashBook() creates one every month, same convention
// as the other 3 series). Generated headlessly here — no Activity/UI
// involved — since a scheduled tick has no screen to read from. PDF
// attachment = full day-by-day breakdown (same layout as
// FoodTrackerActivity.writeFoodTrackerPdf()); email body = just the
// Breakfast/Lunch/Dinner totals + a highlighted grand total, so the email
// itself stays short and the detail lives in the PDF.
    private boolean sendLastMonthFoodReport(Context ctx, String alertEmail) throws Exception {
        java.time.LocalDate lastMonth = java.time.LocalDate.now().minusMonths(1).withDayOfMonth(1);
        String bookName = lastMonth.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy")) + " Food";

        com.expenseos.dao.CashBookDao bookDao = new com.expenseos.dao.CashBookDao(ctx);
        com.expenseos.model.CashBook foodBook = null;
        for (com.expenseos.model.CashBook b : bookDao.findAll())
            if (bookName.equalsIgnoreCase(b.getName())) {
                foodBook = b;
                break;
            }
        if (foodBook == null) return false; // e.g. first month ever running this scheduler

        com.expenseos.dao.CategoryDao catDao = new com.expenseos.dao.CategoryDao(ctx);
        List<com.expenseos.model.Category> matches = catDao.findByName("Food", "EXPENSE", null, null);
        if (matches.isEmpty()) return false; // Food category was never created
        int foodCategoryId = matches.get(0).getId();

        com.expenseos.dao.TransactionDao txnDao = new com.expenseos.dao.TransactionDao(ctx);
        java.util.Map<String, com.expenseos.model.Transaction> existing =
                txnDao.findFoodEntriesForBook(foodBook.getId(), foodCategoryId);

        java.time.YearMonth ym = java.time.YearMonth.from(lastMonth);
        List<FoodDayRow> rows = new ArrayList<>();
        for (int day = 1; day <= ym.lengthOfMonth(); day++) {
            java.time.LocalDate date = ym.atDay(day);
            FoodDayRow row = new FoodDayRow();
            row.date = date;
            row.breakfast = amt(existing.get(date + "|BREAKFAST"));
            row.lunch = amt(existing.get(date + "|LUNCH"));
            row.dinner = amt(existing.get(date + "|DINNER"));
            rows.add(row);
        }

        java.io.ByteArrayOutputStream pdfBytes = new java.io.ByteArrayOutputStream();
        writeFoodReportPdf(bookName, rows, pdfBytes);

        String html = buildFoodReportEmailHtml(bookName, rows);
        com.expenseos.util.GmailSender.Attachment attachment = new com.expenseos.util.GmailSender.Attachment(
                "food_tracker.pdf", pdfBytes.toByteArray(), "application/pdf");
        com.expenseos.util.GmailSender.send(ctx, alertEmail, bookName, html, attachment);
        return true;
    }

    private static java.math.BigDecimal amt(com.expenseos.model.Transaction t) {
        return t != null ? t.getAmount() : java.math.BigDecimal.ZERO;
    }

    private static class FoodDayRow {
        java.time.LocalDate date;
        java.math.BigDecimal breakfast = java.math.BigDecimal.ZERO;
        java.math.BigDecimal lunch = java.math.BigDecimal.ZERO;
        java.math.BigDecimal dinner = java.math.BigDecimal.ZERO;
    }

    // PDF attachment — same table layout as FoodTrackerActivity.writeFoodTrackerPdf().
    private void writeFoodReportPdf(String title, List<FoodDayRow> rows, java.io.OutputStream out) throws Exception {
        com.itextpdf.text.Document doc = new com.itextpdf.text.Document(com.itextpdf.text.PageSize.A4, 24, 24, 32, 32);
        com.itextpdf.text.pdf.PdfWriter.getInstance(doc, out);
        doc.open();

        com.itextpdf.text.Font titleFont = new com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 16, com.itextpdf.text.Font.BOLD);
        com.itextpdf.text.Font headFont = new com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 9, com.itextpdf.text.Font.BOLD, com.itextpdf.text.BaseColor.WHITE);
        com.itextpdf.text.Font cellFont = new com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 9, com.itextpdf.text.Font.NORMAL);

        com.itextpdf.text.Paragraph titleP = new com.itextpdf.text.Paragraph(title, titleFont);
        titleP.setAlignment(com.itextpdf.text.Element.ALIGN_CENTER);
        titleP.setSpacingAfter(16);
        doc.add(titleP);

        com.itextpdf.text.pdf.PdfPTable table = new com.itextpdf.text.pdf.PdfPTable(new float[]{0.6f, 1.6f, 1.4f, 1f, 1f, 1f, 1f});
        table.setWidthPercentage(100);
        for (String h : new String[]{"#", "Date", "Day", "Breakfast", "Lunch", "Dinner", "Total"}) {
            com.itextpdf.text.pdf.PdfPCell cell = new com.itextpdf.text.pdf.PdfPCell(new com.itextpdf.text.Paragraph(h, headFont));
            cell.setBackgroundColor(new com.itextpdf.text.BaseColor(37, 99, 235));
            cell.setPadding(5);
            table.addCell(cell);
        }

        com.itextpdf.text.BaseColor weekendBg = new com.itextpdf.text.BaseColor(0xE3, 0xF2, 0xFD);
        com.itextpdf.text.BaseColor amountBg = new com.itextpdf.text.BaseColor(0xFF, 0xF9, 0xC4);

        java.math.BigDecimal grand = java.math.BigDecimal.ZERO;
        for (int i = 0; i < rows.size(); i++) {
            FoodDayRow row = rows.get(i);
            java.math.BigDecimal total = row.breakfast.add(row.lunch).add(row.dinner);
            grand = grand.add(total);

            boolean isWeekend = row.date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                    || row.date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY;
            com.itextpdf.text.BaseColor rowBg = isWeekend ? weekendBg : null;

            addFoodPdfCell(table, String.valueOf(i + 1), cellFont, rowBg);
            addFoodPdfCell(table, row.date.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")), cellFont, rowBg);
            addFoodPdfCell(table, row.date.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH), cellFont, rowBg);
            addFoodPdfCell(table, row.breakfast.toPlainString(), cellFont, row.breakfast.compareTo(java.math.BigDecimal.ZERO) > 0 ? amountBg : rowBg);
            addFoodPdfCell(table, row.lunch.toPlainString(), cellFont, row.lunch.compareTo(java.math.BigDecimal.ZERO) > 0 ? amountBg : rowBg);
            addFoodPdfCell(table, row.dinner.toPlainString(), cellFont, row.dinner.compareTo(java.math.BigDecimal.ZERO) > 0 ? amountBg : rowBg);
            addFoodPdfCell(table, total.toPlainString(), cellFont, rowBg);
        }
        doc.add(table);

        com.itextpdf.text.Paragraph totalP = new com.itextpdf.text.Paragraph("Month Total: ₹" + grand.toPlainString(),
                new com.itextpdf.text.Font(com.itextpdf.text.Font.FontFamily.HELVETICA, 12, com.itextpdf.text.Font.BOLD));
        totalP.setSpacingBefore(12);
        doc.add(totalP);
        doc.close();
    }

    private void addFoodPdfCell(com.itextpdf.text.pdf.PdfPTable table, String text, com.itextpdf.text.Font font, com.itextpdf.text.BaseColor bg) {
        com.itextpdf.text.pdf.PdfPCell cell = new com.itextpdf.text.pdf.PdfPCell(new com.itextpdf.text.Paragraph(text, font));
        cell.setPadding(5);
        if (bg != null) cell.setBackgroundColor(bg);
        table.addCell(cell);
    }

    // Email body — just the totals, highlighted grand total. The day-by-day
// breakdown lives in the PDF attachment instead, so the email stays short.
    private String buildFoodReportEmailHtml(String title, List<FoodDayRow> rows) {
        java.math.BigDecimal totalBreakfast = java.math.BigDecimal.ZERO;
        java.math.BigDecimal totalLunch = java.math.BigDecimal.ZERO;
        java.math.BigDecimal totalDinner = java.math.BigDecimal.ZERO;
        for (FoodDayRow row : rows) {
            totalBreakfast = totalBreakfast.add(row.breakfast);
            totalLunch = totalLunch.add(row.lunch);
            totalDinner = totalDinner.add(row.dinner);
        }
        java.math.BigDecimal grandTotal = totalBreakfast.add(totalLunch).add(totalDinner);

        String sb = "<html><body style='font-family:Arial,sans-serif;'>" +
                "<h2>" + title + "</h2>" +
                "<table style='border-collapse:collapse;width:100%;max-width:360px;'>" +
                foodSummaryRow("Breakfast", totalBreakfast, false) +
                foodSummaryRow("Lunch", totalLunch, false) +
                foodSummaryRow("Dinner", totalDinner, false) +
                foodSummaryRow("Total", grandTotal, true) +
                "</table>" +
                "<p style='color:#888;font-size:12px;margin-top:16px;'>Full day-by-day breakdown is attached as a PDF.</p>" +
                "</body></html>";
        return sb;
    }

    private String foodSummaryRow(String label, java.math.BigDecimal amount, boolean highlight) {
        String rowStyle = highlight
                ? "background:#2563EB;color:#fff;font-weight:bold;"
                : "border-bottom:1px solid #eee;";
        return "<tr style='" + rowStyle + "'>"
                + "<td style='padding:8px;'>" + label + "</td>"
                + "<td style='padding:8px;text-align:right;'>₹" + amount.toPlainString() + "</td>"
                + "</tr>";
    }
}