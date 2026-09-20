package com.expenseos.util;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.expenseos.R;

import java.math.BigDecimal;
import java.text.DecimalFormat;

/**
 * ExpenseOS premium UI helper.
 *
 * Central place for: animated stat counters, styled toasts, reduced-motion
 * detection, system-bar theming and small accessibility conveniences.
 *
 * Purely additive — no existing class is modified by using this.
 */
public final class UiUtils {

    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");

    private UiUtils() {
    }

    // ─────────────────────────────────────────────────────────────
    // Animated counters
    // ─────────────────────────────────────────────────────────────

    /** Animates a TextView from its current value to {@code target}. */
    public static void animateAmount(@NonNull TextView tv, BigDecimal target) {
        animateAmount(tv, target, "₹");
    }

    public static void animateAmount(@NonNull TextView tv, BigDecimal target, String prefix) {
        if (target == null) target = BigDecimal.ZERO;
        final BigDecimal end = target;

        if (isReducedMotion(tv.getContext())) {
            tv.setText(prefix + MONEY.format(end));
            return;
        }

        BigDecimal current = parseAmount(tv.getText() == null ? null : tv.getText().toString());
        final BigDecimal start = current != null ? current : BigDecimal.ZERO;

        if (start.compareTo(end) == 0) {
            tv.setText(prefix + MONEY.format(end));
            return;
        }

        final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(620);
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(a -> {
            float f = a.getAnimatedFraction();
            BigDecimal v = start.add(end.subtract(start).multiply(BigDecimal.valueOf(f)));
            tv.setText(prefix + MONEY.format(v));
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                tv.setText(prefix + MONEY.format(end));
            }
        });
        anim.start();
    }

    /** Animates an integer count (e.g. "Showing N entries"). */
    public static void animateCount(@NonNull TextView tv, int target, String format) {
        if (isReducedMotion(tv.getContext())) {
            tv.setText(String.format(format, target));
            return;
        }
        final ValueAnimator anim = ValueAnimator.ofInt(0, target);
        anim.setDuration(520);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> tv.setText(String.format(format, (int) a.getAnimatedValue())));
        anim.start();
    }

    private static BigDecimal parseAmount(String raw) {
        if (raw == null) return null;
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (Character.isDigit(c) || c == '.' || c == '-') sb.append(c);
        }
        if (sb.length() == 0 || sb.toString().equals("-") || sb.toString().equals(".")) return null;
        try {
            return new BigDecimal(sb.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Accessibility
    // ─────────────────────────────────────────────────────────────

    /** True when the user asked the OS to minimise animation. */
    public static boolean isReducedMotion(Context ctx) {
        if (ctx == null) return false;
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                return android.provider.Settings.Global.getFloat(cr,
                        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    // Themed toast (replaces the default grey system toast)
    // ─────────────────────────────────────────────────────────────

    public static void toast(Context ctx, CharSequence msg) {
        if (ctx == null || msg == null) return;
        Toast t = new Toast(ctx);
        Context app = ctx.getApplicationContext();

        TextView tv = new TextView(app);
        tv.setText(msg);
        tv.setTextSize(13f);
        tv.setTextColor(ContextCompat.getColor(app, R.color.toast_text));
        tv.setPadding(dp(app, 18), dp(app, 12), dp(app, 18), dp(app, 12));
        tv.setBackground(ContextCompat.getDrawable(app, R.drawable.bg_toast));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tv.setElevation(dp(app, 8));
        }
        tv.setMaxLines(4);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);

        FrameLayout wrap = new FrameLayout(app);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(app, 96);
        wrap.addView(tv, lp);

        t.setView(wrap);
        t.setDuration(Toast.LENGTH_LONG);
        t.show();
    }

    // ─────────────────────────────────────────────────────────────
    // System bars
    // ─────────────────────────────────────────────────────────────

    /**
     * Paints the status bar with the app background and picks the matching
     * light/dark icon set, so the toolbar blends into the page.
     */
    public static void styleStatusBar(@NonNull Window window, Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(ContextCompat.getColor(ctx, R.color.bg));
        }
        View decor = window.getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = decor.getSystemUiVisibility();
            boolean night = isNightMode(ctx);
            if (night) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }
    }

    public static boolean isNightMode(Context ctx) {
        if (ctx == null) return false;
        int mask = ctx.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mask == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    // ─────────────────────────────────────────────────────────────
    // Misc
    // ─────────────────────────────────────────────────────────────

    public static int dp(Context ctx, int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }

    /** Ripple-free transparent background, handy for custom list rows. */
    public static void clearBackground(@NonNull View v) {
        v.setBackground(new ColorDrawable(Color.TRANSPARENT));
    }
}
