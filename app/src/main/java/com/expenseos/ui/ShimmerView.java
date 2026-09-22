package com.expenseos.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.appcompat.widget.AppCompatTextView;

/**
 * Premium shimmer placeholder view. Drop it in wherever you'd otherwise put
 * a ProgressBar / "Loading…" text — it shows crisp skeleton bars that sweep
 * a horizontal highlight once on layout, then settle into the same gray
 * as the design system.
 *
 * Reused by:
 *  • activity_splash (skeleton under logo)
 *  • ChatActivity skeleton while waiting for AI first byte
 *  • ZoomablePdfPreviewActivity "rendering N / M" line
 *  • any future loading state — just include ShimmerView in the layout.
 */
public class ShimmerView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int baseColor = 0xFF1F2937;
    private final int highlightColor = 0xFF334155;
    private final int trackColor = 0xFF0F172A;
    private float sweep = 0f;
    private ValueAnimator anim;

    public ShimmerView(Context c) { super(c); init(); }
    public ShimmerView(Context c, AttributeSet a) { super(c, a); init(); }
    public ShimmerView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        setLayerType(LAYER_TYPE_HARDWARE, paint);
    }

    public void start() {
        if (anim != null && anim.isRunning()) return;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(900);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(v -> {
            sweep = (float) v.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    public void stop() {
        if (anim != null) { anim.cancel(); anim = null; }
        sweep = 0f;
        invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        LinearGradient g = new LinearGradient(
                -w + w * 2 * sweep, 0,
                w + w * 2 * sweep, 0,
                new int[]{trackColor, highlightColor, trackColor},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP);
        paint.setShader(g);
        c.drawRoundRect(0, 0, w, h, 12f, 12f, paint);
        paint.setShader(null);
    }
}
