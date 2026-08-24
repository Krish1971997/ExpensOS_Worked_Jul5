package com.expenseos.util;

import com.expenseos.model.PassbookEntry;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex-based bank SMS parser. Tuned against real samples from PNB, Union
 * Bank of India, and Federal Bank — all share the "debited"/"Rs|INR
 * <amount>" structure but differ in punctuation (Rs:500.00 vs Rs 20.00 vs
 * INR 26400.00) and where the payee name sits. Returns null for SMS that
 * don't look like a transaction (OTPs, promos, "UPI PIN set" confirmations).
 */
public class SmsParser {

    // Handles "Rs:500.00", "Rs 20.00", "Rs.500", "INR 26400.00" — colon,
    // space, or dot as the separator, all optional.
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "(?:Rs|INR)[.:\\s]?\\s?([0-9,]+(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE);

    private static final Pattern DEBIT_PATTERN = Pattern.compile(
            "\\b(debited|spent|paid|withdrawn|purchase of)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern CREDIT_PATTERN = Pattern.compile(
            "\\b(credited|received|deposited)\\b", Pattern.CASE_INSENSITIVE);

    // Skip OTP/PIN-set/promotional messages even if they happen to contain
    // "Rs" or "credited" somewhere in surrounding boilerplate text.
    private static final Pattern SKIP_PATTERN = Pattern.compile(
            "\\b(OTP|One Time Password|successfully set the UPI PIN|offer|cashback eligible|win|discount)\\b",
            Pattern.CASE_INSENSITIVE);

    // Payee extraction — tries each bank's phrasing in turn, first match wins.
    private static final Pattern[] PAYEE_PATTERNS = {
            Pattern.compile("(?:via|thru)\\s+UPI\\s+to\\s+([A-Za-z][A-Za-z .]{1,30}?)\\.", Pattern.CASE_INSENSITIVE), // Federal
            Pattern.compile("\\bto\\s+([A-Za-z][A-Za-z .]{1,30}?)\\s+thru\\s+UPI", Pattern.CASE_INSENSITIVE),          // PNB
            Pattern.compile("Fvg:\\s*([A-Za-z][A-Za-z .]{1,30}?)\\s+Avl", Pattern.CASE_INSENSITIVE),                  // Union Bank
    };

    public static PassbookEntry parse(long smsId, String sender, String body, long timestampMillis) {
        if (body == null || body.isEmpty()) return null;
        if (SKIP_PATTERN.matcher(body).find()) return null;

        Matcher amtMatcher = AMOUNT_PATTERN.matcher(body);
        if (!amtMatcher.find()) return null; // no amount found — not a transaction SMS

        String amountStr = amtMatcher.group(1).replace(",", "");
        BigDecimal amount;
        try {
            amount = new BigDecimal(amountStr);
        } catch (NumberFormatException e) {
            return null;
        }

        String type;
        if (DEBIT_PATTERN.matcher(body).find()) type = "DEBIT";
        else if (CREDIT_PATTERN.matcher(body).find()) type = "CREDIT";
        else return null; // has an amount but no clear direction — skip

// NEW — timestampMillis here is still passed straight through as the
// "SMS received" anchor (used as the fallback / to pair with a date-only
// match); the transaction's actual date/time now comes from the message
// body itself wherever the bank prints one, since that can trail the SMS
// arrival by anywhere from minutes to hours.
        String payee = extractPayee(body);
        String remark = payee != null ? (type.equals("DEBIT") ? "Paid to " : "From ") + payee : null;

        LocalDateTime txnDateTime = SmsDateTimeParser.extractDateTime(body, timestampMillis);
        long txnMillis = txnDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        String paymentType = SmsDateTimeParser.extractPaymentType(body);

        PassbookEntry e = new PassbookEntry();
        e.setSmsId(smsId);
        e.setType(type);
        e.setAmount(amount);
        e.setSender(sender);
        e.setRawBody(body);       // keep the full original SMS text
        e.setRemark(remark);      // short "Paid to X" / "From X" summary for the UI, may be null
        e.setTimestampMillis(txnMillis); // from the SMS body's own date/time when present, else SMS-received time
        e.setPaymentType(paymentType);
        e.setCopied(false);
        return e;
    }

    private static String extractPayee(String body) {
        for (Pattern p : PAYEE_PATTERNS) {
            Matcher m = p.matcher(body);
            if (m.find()) return m.group(1).trim();
        }
        return null;
    }
}