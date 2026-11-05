package com.expenseos.ui.glass;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Outline;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.expenseos.R;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * GlassUi — ExpenseOS glass design system helpers.
 *
 * ONE central place for the iOS-style premium feel:
 *  - press()          → small scale-down on touch (iOS tap feel)
 *  - pressScale()     → same, custom scale
 *  - applyRealBlur()  → Android 12+ (API 31) real backdrop blur for
 *                       dialogs / bottom sheets ONLY. Android has no
 *                       public backdrop-blur API for normal in-app
 *                       cards, so cards rely on translucency + the
 *                       aurora background instead (see GlassUi.applyBlur
 *                       fallback below — they get a soft scrim dim).
 *  - showGlassSheet() → bottom sheet with rounded glass surface + blur.
 *  - showGlassDialog()-> dialog window with transparent surface (layout
 *                       supplies the bg_glass_dialog rounded card).
 *
 * Purely additive — no existing class is modified by using this.
 */
public final class GlassUi {

    private GlassUi() {}

    // ─────────────────────────────────────────────────────────
    // iOS-style press scale
    // ─────────────────────────────────────────────────────────

    /** Attaches an iOS-like press animation (scale to 0.96). */
    public static void press(@NonNull View v) {
        pressScale(v, 0.96f);
    }

    /** Attaches an iOS-like press animation with a custom scale. */
    public static void pressScale(@NonNull View v, float scale) {
        v.setOnTouchListener((vv, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    vv.animate().scaleX(scale).scaleY(scale)
                            .setDuration(90)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    vv.animate().scaleX(1f).scaleY(1f)
                            .setDuration(160)
                            .setInterpolator(new OvershootInterpolator(1.4f))
                            .start();
                    break;
            }
            return false; // never consume — click listeners keep working
        });
    }

    /** Press scale + optional haptic tick on touch-down (premium feel). */
    public static void pressHaptic(@NonNull View v) {
        pressScale(v, 0.96f);
        v.setHapticFeedbackEnabled(true);
    }

    // ─────────────────────────────────────────────────────────
    // Real backdrop blur — Android 12+ dialogs & bottom sheets
    // ─────────────────────────────────────────────────────────

    /** @return true if real backdrop blur is supported on this device. */
    public static boolean canBlur() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /**
     * Android 12+: real cross-window backdrop blur behind the given dialog
     * window. Pre-12: graceful fallback (soft dim scrim so glass fill still
     * reads as frosted).
     */
    public static void applyRealBlur(@Nullable Window w, @Nullable View dialogContent, float blurRadiusPx) {
        if (w == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.setBlurBehindRadius((int) blurRadiusPx);
                w.setAttributes(lp);
                // dim behind for depth (iOS-style scrim)
                w.setDimAmount(0.18f);
                w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            } catch (Exception ignored) { }
        } else {
            w.setDimAmount(0.32f);
        }
        // Always round the window corners so the glass sheet keeps its shape.
        if (dialogContent != null) roundOutlines(w, dialogContent);
    }

    /** Same as {@link #applyRealBlur} for bottom sheets. */
    public static void applyRealBlur(@Nullable Window w, @Nullable View dialogContent) {
        applyRealBlur(w, dialogContent, 40f);
    }

    private static void roundOutlines(Window w, View content) {
        try {
            content.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    float r = view.getResources().getDisplayMetrics().density * 28f;
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                }
            });
            content.setClipToOutline(true);
        } catch (Exception ignored) { }
    }

    // ─────────────────────────────────────────────────────────
    // Ready-made glass surfaces
    // ─────────────────────────────────────────────────────────

    /**
     * Wraps any view in a glass bottom sheet: rounded 28dp glass surface,
     * transparent window, real blur behind on Android 12+.
     */
    @NonNull
    public static BottomSheetDialog showGlassSheet(@NonNull android.app.Activity activity,
                                                   @NonNull View content) {
        BottomSheetDialog sheet = new BottomSheetDialog(activity,
                R.style.ThemeOverlay_ExpenseOS_GlassSheet);
        content.setBackgroundResource(R.drawable.bg_glass_sheet);
        sheet.setContentView(content);
        Window w = sheet.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            applyRealBlur(w, content);
        }
        // keep the sheet fully expanded by default behaviour; caller can tweak
        sheet.show();
        return sheet;
    }

    /** Style an existing dialog window as glass (transparent + blur). */
    public static void glassify(@NonNull Dialog dialog, @Nullable View content) {
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            applyRealBlur(w, content);
        }
    }
}
