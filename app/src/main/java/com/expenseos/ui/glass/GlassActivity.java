package com.expenseos.ui.glass;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

/**
 * GlassActivity — optional base class for screens that want the full
 * glass treatment: edge-to-edge layout under transparent system bars so
 * the aurora background flows beneath the status bar / nav bar.
 *
 * Existing activities do NOT have to extend this — the theme already
 * paints transparent system bars + aurora window background globally.
 * Use it only when a screen also needs onApplyWindowInsets tweaks.
 */
public abstract class GlassActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyGlassSystemBars();
    }

    /** Transparent status + navigation bar, light/dark icon aware. */
    protected void applyGlassSystemBars() {
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
}
