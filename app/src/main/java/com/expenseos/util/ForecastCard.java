package com.expenseos.util;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.expenseos.R;

import java.text.NumberFormat;
import java.util.Locale;

public final class ForecastCard {

    private ForecastCard() {
    }

    public static View build(Context c, SpendingForecast.Result r, String monthLabel) {
        float dp = c.getResources().getDisplayMetrics().density;
        NumberFormat money = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
        money.setMaximumFractionDigits(0);

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * dp);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(16 * dp);
        bg.setStroke((int) dp, 0x33808080); // outline only → fine in light and dark theme
        card.setBackground(bg);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, (int) (8 * dp), 0, (int) (8 * dp));
        card.setLayoutParams(clp);

        card.addView(tv(c, "📈 Spending Forecast · " + monthLabel, 13, c.getColor(R.color.text_muted), false));

        if (!r.enoughData) {
            card.addView(tv(c, "Not enough data yet — add a few expenses.", 14, c.getColor(R.color.text), false));
            return card;
        }

        TextView big = tv(c, money.format(r.forecast), 30, c.getColor(R.color.text), true);
        big.setPadding(0, (int) (6 * dp), 0, 0);
        card.addView(big);
        card.addView(tv(c, "expected by month end  ·  likely " + money.format(r.low) + " – " + money.format(r.high)
                + "  ·  confidence " + r.confidence, 12, c.getColor(R.color.text_muted), false));

        TextView sofar = tv(c, "So far " + money.format(r.spentSoFar) + "  ·  " + money.format(r.dailyAvg)
                + "/day  ·  " + r.daysLeft + " days left", 14, c.getColor(R.color.text), false);
        sofar.setPadding(0, (int) (10 * dp), 0, 0);
        card.addView(sofar);

        if (r.lastMonthTotal > 0) {
            boolean up = r.vsLastMonthPct >= 0;
            // more expense than last month = red ▲, less = green ▼
            TextView cmp = tv(c, (up ? "▲ " : "▼ ") + String.format(Locale.ENGLISH, "%.1f%%", Math.abs(r.vsLastMonthPct))
                            + (up ? " more" : " less") + " than last month (" + money.format(r.lastMonthTotal) + ")",
                    14, c.getColor(up ? R.color.red : R.color.green), true);
            cmp.setPadding(0, (int) (4 * dp), 0, 0);
            card.addView(cmp);
        }
        if (r.safeDailyLimit != null) {
            TextView lim = tv(c, "To stay under last month: max " + money.format(r.safeDailyLimit) + "/day",
                    13, c.getColor(R.color.text_muted), false);
            lim.setPadding(0, (int) (4 * dp), 0, 0);
            card.addView(lim);
        }
        return card;
    }

    private static TextView tv(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }
}