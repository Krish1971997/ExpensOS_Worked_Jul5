package com.expenseos.util;

import android.content.Context;

import com.expenseos.dao.CashBookDao;
import com.expenseos.model.CashBook;

import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps a calendar month (e.g. July 2026) to the cash book whose NAME
 * matches it — this app's convention is one cash book per month, named
 * with the full month name + year ("July 2026", "june 2026", etc, case
 * insensitive), optionally followed by a series suffix ("September 2026
 * Credit Card"). Used by the Stats screens to do month-by-month navigation
 * without a dedicated "month" column anywhere.
 */
public class MonthBookResolver {

    // Captures an optional trailing suffix after "<Month> <Year>", e.g.
    // "September 2026 Credit Card" -> suffix group = "Credit Card".
    private static final Pattern MONTH_YEAR_PATTERN = Pattern.compile(
            "^(January|February|March|April|May|June|July|August|September|October|November|December)"
                    + "\\s+(\\d{4})(?:\\s+(.+))?$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Returns the matching CashBook for this month, or null if none exists
     * yet. Only matches plain "<Month> <Year>" books with no series suffix
     * — used by the standalone/global Stats entry point, which has no
     * "current book" context to scope by.
     */
    public static CashBook findBookForMonth(Context ctx, YearMonth ym) {
        return findBookForMonth(ctx, ym, "");
    }

    /**
     * Same lookup, but scoped to a specific series suffix — e.g. suffix=
     * "Credit Card" only matches "August 2026 Credit Card", not plain
     * "August 2026" or "August 2026 Business". Pass "" for plain
     * "<Month> <Year>" books (equivalent to the no-suffix overload).
     */
    public static CashBook findBookForMonth(Context ctx, YearMonth ym, String suffix) {
        String target = expectedName(ym, suffix);
        List<CashBook> all = new CashBookDao(ctx).findAll();
        for (CashBook b : all) {
            if (b.getName() != null && b.getName().trim().equalsIgnoreCase(target)) {
                return b;
            }
        }
        return null;
    }

    /**
     * "July 2026" — canonical expected name for a given month, no suffix.
     */
    public static String expectedName(YearMonth ym) {
        String monthName = ym.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        return monthName + " " + ym.getYear();
    }

    /**
     * "July 2026 Credit Card" — canonical expected name for a given month
     * within a specific series. Same as expectedName(ym) when suffix is
     * null/empty.
     */
    public static String expectedName(YearMonth ym, String suffix) {
        String base = expectedName(ym);
        if (suffix == null || suffix.isEmpty()) return base;
        return base + " " + suffix;
    }

    /**
     * Parses the YearMonth out of a book name matching "<Month> <Year>[...]"
     * — e.g. "September 2026 Credit Card" -> September 2026. Returns null
     * if the name doesn't match that pattern at all.
     */
    public static YearMonth parseYearMonth(String bookName) {
        if (bookName == null) return null;
        Matcher m = MONTH_YEAR_PATTERN.matcher(bookName.trim());
        if (!m.matches()) return null;
        try {
            java.time.Month month = java.time.Month.valueOf(m.group(1).toUpperCase(Locale.ENGLISH));
            int year = Integer.parseInt(m.group(2));
            return YearMonth.of(year, month);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Given an existing book name, extracts whatever comes after
     * "<Month> <Year>" — "" for a plain month book, "Credit Card" for
     * "September 2026 Credit Card". Returns "" (not null) if the name
     * doesn't match the "<Month> <Year>[...]" pattern at all (e.g. a
     * one-off book named "test" or "General"), so callers can safely
     * treat that as "no series" rather than special-casing null.
     */
    public static String extractSuffix(String bookName) {
        if (bookName == null) return "";
        Matcher m = MONTH_YEAR_PATTERN.matcher(bookName.trim());
        if (m.matches()) {
            String suffix = m.group(3);
            return suffix != null ? suffix.trim() : "";
        }
        return "";
    }
}