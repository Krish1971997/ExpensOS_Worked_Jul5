package com.expenseos.util;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;

/**
 * Horizontal swipe → previous / next. Feed it from Activity.dispatchTouchEvent;
 * it only observes touches (never consumes), so lists, buttons and scrolling
 * keep working normally.
 */
public final class SwipeNavHelper {

    public interface Callback {
        void onPrev();

        void onNext();
    }

    /**
     * false: finger moves → (right)  = previous, finger moves ← (left) = next
     * (same as Google Calendar / ViewPager). Set true to flip.
     */
    private static final boolean REVERSED = false;

    private final GestureDetector detector;

    public SwipeNavHelper(Context ctx, Callback cb) {
        final float density = ctx.getResources().getDisplayMetrics().density;
        final float minDist = density * 70;
        final float minVel = density * 400;
        detector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) < minDist || Math.abs(dx) < Math.abs(dy) * 1.5f
                        || Math.abs(vx) < minVel) return false;
                boolean fingerRight = dx > 0;
                if (fingerRight != REVERSED) cb.onPrev();
                else cb.onNext();
                return true;
            }
        });
    }

    public void onTouch(MotionEvent ev) {
        detector.onTouchEvent(ev);
    }
}