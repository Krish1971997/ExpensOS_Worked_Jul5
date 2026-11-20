package com.expenseos.cloud;

import android.content.Context;
import android.util.Log;

import com.expenseos.sync.CloudRestore;
import com.expenseos.util.AppConfig;

/**
 * Fire-and-forget start-up warm-up so the first attachment open/upload has no delay, and the
 * cloud Postgres connection is already awake. Runs entirely off the main thread, after the first
 * frame, so app start-up never slows down.
 */
public final class CloudWarmup {

    private static boolean started;

    private CloudWarmup() {
    }

    public static synchronized void start(Context ctx) {
        if (started) return;
        started = true;
        final Context app = ctx.getApplicationContext();
        Thread t = new Thread(() -> {
            // 1) wake the cloud DB (same cheap COUNT query the restore uses)
            try {
                CloudRestore.cloudRowCount(app, "cash_books");
            } catch (Exception e) {
                Log.w("CloudWarmup", "DB ping: " + e.getMessage());
            }

            // 2) remove decrypted temp files left behind by an earlier "Open / Share" or camera shot
            try {
                FileCrypto.purgePlainTemp(app);
            } catch (Exception e) {
                Log.w("CloudWarmup", "purgePlainTemp: " + e.getMessage());
            }

            // 3) Supabase login so the first attachment open/upload has no delay
            if (AppConfig.get(app).isSupabaseConfigured()) {
                try {
                    SupabaseStorage.warmUp(app);
                } catch (Exception e) {
                    Log.w("CloudWarmup", "Supabase: " + e.getMessage());
                }
                // 4) leftover local blobs -> encrypted upload, then the local copy is nulled
                if (FileCrypto.isReady(app)) {
                    try {
                        AttachmentMigration.runAll(app);
                    } catch (Exception e) {
                        Log.w("CloudWarmup", "migrate: " + e.getMessage());
                    }
                }
            }
        }, "cloud-warmup");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }
}
