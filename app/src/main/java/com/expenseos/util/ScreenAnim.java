package com.expenseos.util;

import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

public final class ScreenAnim {

    private ScreenAnim() {
    }

    /**
     * Staggered fade + slide-up of the direct children of a form container.
     */
    public static void staggerIn(ViewGroup container) {
        if (container == null) return;
        float dp = container.getResources().getDisplayMetrics().density;
        for (int i = 0; i < container.getChildCount(); i++) {
            View v = container.getChildAt(i);
            v.setAlpha(0f);
            v.setTranslationY(16 * dp);
            v.animate()
                    .alpha(1f)
                    .translationY(0)
                    .setStartDelay(40L * i)
                    .setDuration(240)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }
}