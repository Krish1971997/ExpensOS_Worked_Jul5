package com.expenseos;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import com.expenseos.ui.AlarmRingActivity;
import com.expenseos.ui.LockActivity;
import com.expenseos.util.AppLockManager;

public class ExpenseOSApp extends Application implements Application.ActivityLifecycleCallbacks {

    private int startedCount = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
    }

    private boolean isExempt(Activity a) {
        // Lock screen itself + alarm ring (alarm must ring even when app is locked)
        return a instanceof LockActivity || a instanceof AlarmRingActivity;
    }

    @Override
    public void onActivityCreated(Activity a, Bundle b) {
        // Hides content in Recents and blocks screenshots while app lock is on.
        // Remove this block if you want screenshots allowed.
        // Screenshots stay allowed. With app lock on, only the Recents thumbnail is hidden (Android 13+).
        if (AppLockManager.isEnabled(this) && android.os.Build.VERSION.SDK_INT >= 33) {
            a.setRecentsScreenshotEnabled(false);
        }
    }

    @Override
    public void onActivityStarted(Activity a) {
        if (startedCount++ == 0) AppLockManager.onAppForegrounded(this);
        if (isExempt(a)) return;
        if (AppLockManager.shouldLock(this) && !AppLockManager.isLockScreenShowing()) {
            AppLockManager.setLockScreenShowing(true);
            a.startActivity(LockActivity.unlockIntent(a));
            a.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }
    }

    @Override
    public void onActivityStopped(Activity a) {
        // Rotation etc. is not "going to background"
        if (--startedCount == 0 && !a.isChangingConfigurations()) {
            AppLockManager.onAppBackgrounded();
        }
    }

    @Override
    public void onActivityResumed(Activity a) {
    }

    @Override
    public void onActivityPaused(Activity a) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity a, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity a) {
    }
}