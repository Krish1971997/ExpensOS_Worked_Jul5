package com.expenseos.util;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Month-end spending forecast: spent so far + expected spend on the remaining days.
 */
public final class SpendingForecast {

    public static final class Result {
        public double spentSoFar, forecast, low, high, dailyAvg, lastMonthTotal;
        public Double safeDailyLimit;   // null when there is no last month or no days left
        public int daysElapsed, daysLeft, monthsOfHistory;
        public double vsLastMonthPct;   // +9.5 → forecast is 9.5 % above last month (valid when lastMonthTotal > 0)
        public String confidence;       // LOW | MEDIUM | HIGH
        public boolean enoughData;
    }

    private SpendingForecast() {
    }

    /**
     * @param daily expense total per day — current month + up to the previous 3 months
     */
    public static Result compute(Map<LocalDate, BigDecimal> daily, LocalDate today) {
        YearMonth cur = YearMonth.from(today);
        int dim = cur.lengthOfMonth();
        int elapsed = today.getDayOfMonth();
        int left = dim - elapsed;

        // 1) this month so far — one-off spikes are capped so a single big bill doesn't inflate the run-rate
        double spent = 0;
        List<Double> days = new ArrayList<>();
        List<Double> nonZero = new ArrayList<>();
        for (int d = 1; d <= elapsed; d++) {
            BigDecimal b = daily.get(cur.atDay(d));
            double v = b == null ? 0 : b.doubleValue();
            days.add(v);
            spent += v;
            if (v > 0) nonZero.add(v);
        }
        double cap = nonZero.size() >= 5 ? median(nonZero) * 4 : Double.MAX_VALUE;
        double capped = 0;
        for (double v : days) capped += Math.min(v, cap);
        double dailyAvg = capped / elapsed;
        double runRest = dailyAvg * left;

        // 2) history — what was spent on the *remaining* days of each of the last 3 months
        List<Double> histRest = new ArrayList<>();
        double lastMonth = 0;
        for (int k = 1; k <= 3; k++) {
            YearMonth pm = cur.minusMonths(k);
            double tot = 0, rest = 0;
            boolean any = false;
            for (int d = 1; d <= pm.lengthOfMonth(); d++) {
                BigDecimal b = daily.get(pm.atDay(d));
                if (b == null) continue;
                any = true;
                tot += b.doubleValue();
                if (d > elapsed) rest += b.doubleValue();
            }
            if (!any) continue;
            histRest.add(rest);
            if (k == 1) lastMonth = tot;
        }
        double histRestAvg = histRest.isEmpty() ? 0
                : (histRest.size() >= 3 ? median(histRest) : mean(histRest));

        // 3) blend: early in the month trust history, late in the month trust the current run-rate
        double p = (double) elapsed / dim;
        double w = histRest.isEmpty() ? 1.0 : p;
        double rest = w * runRest + (1 - w) * histRestAvg;

        double u = 0.15 + 0.35 * (1 - p) + (histRest.isEmpty() ? 0.10 : 0);

        Result r = new Result();
        r.spentSoFar = spent;
        r.forecast = spent + rest;
        r.low = spent + rest * (1 - u);
        r.high = spent + rest * (1 + u);
        r.dailyAvg = dailyAvg;
        r.lastMonthTotal = lastMonth;
        r.daysElapsed = elapsed;
        r.daysLeft = left;
        r.monthsOfHistory = histRest.size();
        r.enoughData = spent > 0 || !histRest.isEmpty();
        r.vsLastMonthPct = lastMonth > 0 ? (r.forecast - lastMonth) / lastMonth * 100.0 : 0;
        r.safeDailyLimit = (lastMonth > 0 && left > 0) ? Math.max(0, (lastMonth - spent) / left) : null;
        r.confidence = histRest.size() >= 3 && p >= 0.5 ? "HIGH"
                : (!histRest.isEmpty() && p >= 0.25) ? "MEDIUM" : "LOW";
        return r;
    }

    private static double mean(List<Double> l) {
        double s = 0;
        for (double v : l) s += v;
        return l.isEmpty() ? 0 : s / l.size();
    }

    private static double median(List<Double> l) {
        List<Double> c = new ArrayList<>(l);
        Collections.sort(c);
        int n = c.size();
        return n % 2 == 1 ? c.get(n / 2) : (c.get(n / 2 - 1) + c.get(n / 2)) / 2.0;
    }
}