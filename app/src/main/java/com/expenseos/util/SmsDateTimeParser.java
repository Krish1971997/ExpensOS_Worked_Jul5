package com.expenseos.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the transaction date/time and payment channel (UPI/NEFT/etc.) out
 * of a bank SMS body, since the message often lags behind — sometimes by
 * hours — the moment it actually lands on the device.
 * <p>
 * Handles the date/time formats seen across banks:
 * "on 20Aug26 21:17"            (compact date + time)
 * "Dt 04-08-26 14:14:24"        (dashed date + time w/ seconds)
 * "on 20-Aug-2026" / "on 25-Jul-2026"  (date only, no time)
 * <p>
 * Fallback chain: date+time in the message wins outright; date-only in the
 * message is paired with the time the SMS was actually received (closest
 * real approximation); if nothing matches, the SMS-received timestamp is
 * used as-is.
 */
public class SmsDateTimeParser {

    private static final Pattern PAT_COMPACT_DATETIME =
            Pattern.compile("on\\s+(\\d{1,2}[A-Za-z]{3}\\d{2})\\s+(\\d{1,2}:\\d{2})");
    private static final Pattern PAT_DT_DATETIME =
            Pattern.compile("Dt\\s+(\\d{2}-\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2}:\\d{2})");
    private static final Pattern PAT_DATE_ONLY =
            Pattern.compile("on\\s+(\\d{1,2}-[A-Za-z]{3}-\\d{4})");

    public static LocalDateTime extractDateTime(String body, long smsReceivedMillis) {
        if (body == null) return receivedAt(smsReceivedMillis);

        Matcher m1 = PAT_COMPACT_DATETIME.matcher(body);
        if (m1.find()) {
            LocalDateTime dt = tryParse(m1.group(1), "ddMMMyy", m1.group(2), "H:mm");
            if (dt != null) return dt;
        }

        Matcher m2 = PAT_DT_DATETIME.matcher(body);
        if (m2.find()) {
            LocalDateTime dt = tryParse(m2.group(1), "dd-MM-yy", m2.group(2), "HH:mm:ss");
            if (dt != null) return dt;
        }

        Matcher m3 = PAT_DATE_ONLY.matcher(body);
        if (m3.find()) {
            try {
                LocalDate d = LocalDate.parse(m3.group(1),
                        DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH));
                // Date-only in the SMS — pair it with the time the SMS itself
                // arrived, since that's the closest real signal we have.
                return LocalDateTime.of(d, receivedAt(smsReceivedMillis).toLocalTime());
            } catch (Exception ignored) {
            }
        }

        // Nothing matched — fall back entirely to when the SMS was received.
        return receivedAt(smsReceivedMillis);
    }

    public static String extractPaymentType(String body) {
        if (body == null) return null;
        String upper = body.toUpperCase(Locale.ROOT);
        // P2A (Person-to-Account) / P2M (Person-to-Merchant) are UPI transfer
        // sub-types some banks (KVB, etc.) print instead of the word "UPI" —
        // check them before the plain "UPI" check below covers everything else.
        if (upper.contains("UPI") || upper.contains("P2A") || upper.contains("P2M")) return "UPI";
        if (upper.contains("NEFT")) return "NEFT";
        if (upper.contains("IMPS")) return "IMPS";
        if (upper.contains("RTGS")) return "RTGS";
        if (upper.contains("ATM")) return "ATM";
        if (upper.contains("POS") || upper.contains("CARD")) return "Card";
        return null; // unrecognized — caller decides the fallback (e.g. "Other")
    }

    private static LocalDateTime tryParse(String datePart, String dateFmt, String timePart, String timeFmt) {
        try {
            LocalDate d = LocalDate.parse(datePart, DateTimeFormatter.ofPattern(dateFmt, Locale.ENGLISH));
            LocalTime t = LocalTime.parse(timePart, DateTimeFormatter.ofPattern(timeFmt, Locale.ENGLISH));
            return LocalDateTime.of(d, t);
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDateTime receivedAt(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }
}