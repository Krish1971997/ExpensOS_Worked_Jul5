package com.expenseos.util;

import android.content.Context;

import com.expenseos.dao.CashBookDao;
import com.expenseos.model.CashBook;

import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * Maps a calendar month (e.g. July 2026) to the cash book whose NAME
 * matches it — this app's convention is one cash book per month, named
 * with the full month name + year ("July 2026", "june 2026", etc, case
 * insensitive). Used by the Stats screens to do month-by-month navigation
 * without a dedicated "month" column anywhere.
 */
public class MonthBookResolver {

    /**
     * Returns the matching CashBook for this month, or null if none exists yet.
     */
    public static CashBook findBookForMonth(Context ctx, YearMonth ym) {
        String target = expectedName(ym);
        List<CashBook> all = new CashBookDao(ctx).findAll();
        for (CashBook b : all) {
            if (b.getName() != null && b.getName().trim().equalsIgnoreCase(target)) {
                return b;
            }
        }
        return null;
    }

    /**
     * "July 2026" — canonical expected name for a given month.
     */
    public static String expectedName(YearMonth ym) {
        String monthName = ym.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        return monthName + " " + ym.getYear();
    }
}