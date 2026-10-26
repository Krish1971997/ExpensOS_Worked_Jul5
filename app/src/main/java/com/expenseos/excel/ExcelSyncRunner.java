package com.expenseos.excel;

import android.content.Context;
import android.content.SharedPreferences;

import com.expenseos.util.AppConfig;
import com.expenseos.util.ConsoleLogger;
import com.expenseos.util.GmailSender;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

public final class ExcelSyncRunner {
    private static final long EMAIL_THROTTLE_MS = 6 * 60 * 60 * 1000L; // same error, same file: max 1 mail / 6h
    private static final int MAX_ROWS_IN_EMAIL = 100;
    private static final String XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private ExcelSyncRunner() {
    }

    public static class Outcome {
        public boolean ok = true;
        public int files;
        public String message = "";
    }

    public static Outcome run(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("excel_sync", Context.MODE_PRIVATE);
        Outcome o = new Outcome();
        StringBuilder msg = new StringBuilder();
        for (ExcelImportService svc : ExcelImportBatch.services(ctx)) {
            o.files++;
            ExcelImportService.SyncReport r = svc.sync();
            if (msg.length() > 0) msg.append(" | ");
            if (r.ok()) {
                sp.edit().remove("mail:" + r.fileName).apply();
                msg.append(r.fileName).append(": ").append(r.rows.size()).append(" rows (✓").append(r.valid)
                        .append(" ✗").append(r.invalid).append(")")
                        .append(r.referenceUpdated ? ", lists refreshed" : "")
                        .append(r.skippedBusy ? ", file busy — retry next run" : "");
            } else {
                o.ok = false;
                msg.append(r.fileName).append(": FAILED — ").append(r.error);
                emailFailure(ctx, sp, r);
            }
        }
        o.message = msg.toString();
        return o;
    }

    private static void emailFailure(Context ctx, SharedPreferences sp, ExcelImportService.SyncReport r) {
        ConsoleLogger log = ConsoleLogger.get();
        AppConfig cfg = AppConfig.get(ctx);
        String to = cfg.getSchedulerAlertEmail();
        if (to == null || to.isBlank() || cfg.getGmailFrom().isBlank() || cfg.getGmailAppPass().isBlank()) {
            log.warn("⚠️ Excel sync failure email skipped — alert email / Gmail credentials missing in Config.");
            return;
        }
        String key = "mail:" + r.fileName;
        String sig = String.valueOf((r.fileName + "|" + r.error).hashCode());
        long now = System.currentTimeMillis();
        String prev = sp.getString(key, "");
        int bar = prev.indexOf('|');
        if (bar > 0 && prev.substring(bar + 1).equals(sig)) {
            try {
                if (now - Long.parseLong(prev.substring(0, bar)) < EMAIL_THROTTLE_MS) {
                    log.info("📧 Same Excel sync error already emailed recently — skipped.");
                    return;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        try {
            StringBuilder h = new StringBuilder();
            h.append("<p><b>File:</b> ").append(esc(r.fileName)).append("</p>")
                    .append("<p><b>Mode:</b> ").append(esc(r.mode)).append("</p>")
                    .append("<p><b>Failed at:</b> ").append(LocalDateTime.now()).append("</p>")
                    .append("<p><b>Error:</b> ").append(esc(r.error)).append("</p>");
            if (r.stackTrace != null)
                h.append("<p><b>Details:</b></p><pre style=\"font-size:11px\">").append(esc(r.stackTrace)).append("</pre>");
            if (r.rows.isEmpty()) {
                h.append("<p><b>File data:</b> nothing could be read from the file.</p>");
            } else {
                h.append("<p><b>File data (").append(r.rows.size()).append(" rows read")
                        .append(r.rows.size() > MAX_ROWS_IN_EMAIL ? ", first " + MAX_ROWS_IN_EMAIL : "")
                        .append("):</b></p><table border=\"1\" cellpadding=\"4\" style=\"border-collapse:collapse;font-size:12px\">")
                        .append("<tr><th>Row</th><th>Cashbook</th><th>Type</th><th>Date</th><th>Time</th><th>Amount</th>")
                        .append("<th>Payment</th><th>Category</th><th>Sub</th><th>Description</th><th>Status</th><th>Error</th></tr>");
                int n = 0;
                for (ImportRow x : r.rows) {
                    if (n++ >= MAX_ROWS_IN_EMAIL) break;
                    h.append("<tr>");
                    for (String c : cells(x)) h.append("<td>").append(esc(c)).append("</td>");
                    h.append("</tr>");
                }
                h.append("</table>");
            }

            GmailSender.Attachment att = null;
            if (r.rawXlsx != null) {
                String nm = r.fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx") ? r.fileName : r.fileName + ".xlsx";
                att = new GmailSender.Attachment(nm, r.rawXlsx, XLSX_MIME);
            } else if (!r.rows.isEmpty()) {
                StringBuilder csv = new StringBuilder("Row,Cashbook,Type,Date,Time,Amount,Payment,Category,Sub,Description,Status,Error\n");
                for (ImportRow x : r.rows) {
                    String[] c = cells(x);
                    for (int i = 0; i < c.length; i++)
                        csv.append(i > 0 ? "," : "").append('"').append(c[i].replace("\"", "\"\"")).append('"');
                    csv.append('\n');
                }
                att = new GmailSender.Attachment(r.fileName.replaceAll("\\.[A-Za-z0-9]{2,10}$", "") + "_rows.csv",
                        csv.toString().getBytes(StandardCharsets.UTF_8), "text/csv");
            }

            GmailSender.send(ctx, to, "ExpenseOS Excel sync failed: " + r.fileName, h.toString(), att);
            sp.edit().putString(key, now + "|" + sig).apply();
            log.success("✔ Excel sync failure emailed to " + to + " (" + r.fileName + ")");
        } catch (Exception e) {
            log.error("✘ Could not send Excel sync failure email: " + (e.getMessage() != null ? e.getMessage() : e));
        }
    }

    private static String[] cells(ImportRow x) {
        return new String[]{String.valueOf(x.sheetRow), x.cashbook, x.type,
                x.date != null ? x.date.toString() : x.dateRaw, x.time != null ? x.time.toString() : x.timeRaw,
                x.amount != null ? x.amount.toPlainString() : x.amountRaw, x.paymentType, x.category,
                x.subCategory, x.description, x.status, x.error};
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}