package com.expenseos.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * Minimal pinch-to-zoom + pan ImageView — no external library. Used inside
 * ReportPdfPreviewActivity's page RecyclerView: at scale 1x (default),
 * vertical drag passes through to the RecyclerView so page-to-page scroll
 * keeps working; once pinched past 1x, drags pan the zoomed image and the
 * RecyclerView is told not to intercept until the finger lifts.
 */
public class ZoomableImageView extends AppCompatImageView {

    private static final float MIN_SCALE = 1f;
    private static final float MAX_SCALE = 5f;

    private static final float BUTTON_ZOOM_STEP = 0.5f;

    private final Matrix matrix = new Matrix();
    private final float[] matrixValues = new float[9];
    private Bitmap currentBitmap;
    private float baseScale = 1f;   // "fit-to-width" scale — scale=1 (MIN_SCALE) andha fit-ah represent pannudhu
    private float scale = 1f;
    private float lastTouchX, lastTouchY;

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;

    public ZoomableImageView(Context ctx) {
        super(ctx);
        init(ctx);
    }

    public ZoomableImageView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        init(ctx);
    }

    private void init(Context ctx) {
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(ctx, new ScaleListener());
        gestureDetector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (scale > MIN_SCALE) {
                    applyFitMatrix();
                } else {
                    scale = 2.5f;
                    matrix.postScale(2.5f, 2.5f, e.getX(), e.getY());
                    fixTranslation();
                    setImageMatrix(matrix);
                }
                return true;
            }
        });
        setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    // Rendaavadhu finger keezha vekkumbodhu-ye (pinch start)
                    // RecyclerView-ah "don't intercept" solli vidanum —
                    // illaina scroll gesture adha munnadiyae grab pannidum.
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (scale > MIN_SCALE && event.getPointerCount() == 1) {
                        float dx = event.getX() - lastTouchX;
                        float dy = event.getY() - lastTouchY;
                        matrix.postTranslate(dx, dy);
                        fixTranslation();
                        setImageMatrix(matrix);
                        lastTouchX = event.getX();
                        lastTouchY = event.getY();
                    }
                    if (getParent() != null)
                        getParent().requestDisallowInterceptTouchEvent(scale > MIN_SCALE || event.getPointerCount() > 1);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_POINTER_UP:
                    if (event.getPointerCount() <= 1 && scale <= MIN_SCALE && getParent() != null)
                        getParent().requestDisallowInterceptTouchEvent(false);
                    break;
            }
            return true;
        });
    }

    /**
     * "+"/"−" button click-ku call pannunga — image center-ah vachi zoom.
     */
    public void zoomIn() {
        applyScaleAroundCenter(Math.min(scale + BUTTON_ZOOM_STEP, MAX_SCALE));
    }

    public void zoomOut() {
        applyScaleAroundCenter(Math.max(scale - BUTTON_ZOOM_STEP, MIN_SCALE));
    }

    private void applyScaleAroundCenter(float newScale) {
        if (getDrawable() == null || newScale == scale) return;
        float factor = newScale / scale;
        scale = newScale;
        matrix.postScale(factor, factor, getWidth() / 2f, getHeight() / 2f);
        fixTranslation();
        setImageMatrix(matrix);
    }

    @Override
    public void setImageBitmap(Bitmap bm) {
        currentBitmap = bm;
        super.setImageBitmap(bm);
        requestLayout();   // page-ku page height maaraalam (aspect ratio) — remeasure pannanum
        applyFitMatrix();  // width already known-na (RecyclerView recycle) idhu ippove fit pannidum
    }

    // View-oda ACTUAL width-ku bitmap-ah exact-ah fit pannura scale — MATRIX
    // scaleType raw pixels-ah 1:1 kaatum (fit pannaadhu), adhunala idha
    // manual-ah calculate panni base scale-ah vekkanum. onMeasure() (keezha)
    // height-ah idhe ratio-la already set pannirukkum, so width-ku fit
    // pannina automatic-ah height-um exact-ah fit aagum.
    private void applyFitMatrix() {
        if (currentBitmap == null || getWidth() == 0) return;
        baseScale = getWidth() / (float) currentBitmap.getWidth();
        scale = MIN_SCALE;
        matrix.reset();
        matrix.postScale(baseScale, baseScale);
        setImageMatrix(matrix);
    }

    // Bitmap-oda aspect ratio-ku match aagura height-ah measure pannunga —
    // idhu illainaa layout_height=WRAP_CONTENT bitmap-oda RAW pixel height-ah
    // (screen density kanakku pannaama) use pannidum, romba periya/thappa
    // aagum.
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (currentBitmap != null && currentBitmap.getWidth() > 0) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = Math.round(width * (currentBitmap.getHeight() / (float) currentBitmap.getWidth()));
            setMeasuredDimension(width, height);
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyFitMatrix();
    }

    private void fixTranslation() {
        if (getDrawable() == null) return;
        matrix.getValues(matrixValues);
        float transX = matrixValues[Matrix.MTRANS_X];
        float transY = matrixValues[Matrix.MTRANS_Y];
        float effectiveScale = baseScale * scale;
        float scaledWidth = getDrawable().getIntrinsicWidth() * effectiveScale;
        float scaledHeight = getDrawable().getIntrinsicHeight() * effectiveScale;

        float minTransX = Math.min(0, getWidth() - scaledWidth);
        float minTransY = Math.min(0, getHeight() - scaledHeight);

        float fixedX = Math.max(minTransX, Math.min(transX, 0));
        float fixedY = Math.max(minTransY, Math.min(transY, 0));

        if (fixedX != transX || fixedY != transY)
            matrix.postTranslate(fixedX - transX, fixedY - transY);
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float newScale = Math.max(MIN_SCALE, Math.min(scale * detector.getScaleFactor(), MAX_SCALE));
            float factor = newScale / scale;
            scale = newScale;
            matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
            fixTranslation();
            setImageMatrix(matrix);
            return true;
        }
    }
}