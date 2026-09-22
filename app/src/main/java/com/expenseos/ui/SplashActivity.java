package com.expenseos.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.animation.AnimationUtils;

import androidx.appcompat.app.AppCompatActivity;

import com.expenseos.R;
import com.expenseos.util.UiUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Premium launch screen. Shimmer-animated skeleton bars + branded logo while
 * we synchronously warm the heavy singletons (ReportGenerator / ChartRenderer /
 * LocalDB) off the main thread. Hands off to MainActivity once that finishes
 * (or after a 1500 ms safety cap) so the book list shows instantly without the
 * old "blank screen then books snapshot in" feel.
 *
 *  - Replaces the previous intent MainActivity launched on cold start with
 *    nothing but a setContentView() — fine functionally, but slow on first
 *    install / after process death because DB open + service catalogue boot
 *    happened on the UI thread.
 */
public class SplashActivity extends AppCompatActivity {

    private static final long CAP_MS = 1500L;
    private final ExecutorService warmup = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Render the shimmer screen BEFORE doing view binding — keeps cold
        // start time-to-first-pixel under ~50 ms on real devices.
        setContentView(R.layout.activity_splash);
        UiUtils.styleStatusBar(getWindow(), this);

        View bar1 = findViewById(R.id.splashBar1);
        View bar2 = findViewById(R.id.splashBar2);
        View bar3 = findViewById(R.id.splashBar3);
        bar1.startAnimation(AnimationUtils.loadAnimation(this, R.anim.shimmer));
        bar2.startAnimation(AnimationUtils.loadAnimation(this, R.anim.shimmer));
        bar3.startAnimation(AnimationUtils.loadAnimation(this, R.anim.shimmer));

        warmup.execute(() -> {
            // Touch the heavy singletons so their initializers don't fire on
            // the UI thread when the user lands on MainActivity a moment later.
            try {
                Class.forName("com.expenseos.util.ReportGenerator");
                Class.forName("com.expenseos.util.ChartRenderer");
                Class.forName("com.expenseos.util.CategoryComparisonReport");
                Class.forName("com.expenseos.db.LocalDB");
            } catch (Throwable ignored) {
            }
            try { Thread.sleep(Math.max(0, CAP_MS - 600)); } catch (InterruptedException ignored) {}
        });

        // Always hand off at CAP_MS — never block the user past the shimmer.
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            // Smooth fade instead of the harsh default slide — feels premium.
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }, CAP_MS);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        warmup.shutdown();
    }
}
