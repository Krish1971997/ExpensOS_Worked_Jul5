package com.expenseos.cloud;

import android.content.Context;

import com.expenseos.dao.ReceiptDao;
import com.expenseos.dao.RecycleBinDao;
import com.expenseos.db.LocalDB;
import com.expenseos.util.AppConfig;

import java.io.IOException;

/**
 * One-time move of existing attachments (transaction_receipts rows and recycle_bin snapshots) to
 * encrypted Supabase objects. Background thread only.
 */
public final class AttachmentMigration {

    private AttachmentMigration() {
    }

    /** Background thread only. Returns a human-readable summary. */
    public static String runAll(Context ctx) throws IOException {
        if (!AppConfig.get(ctx).isSupabaseConfigured())
            throw new IOException("Supabase Storage is not configured. Fill it in under Config first.");
        if (!FileCrypto.isReady(ctx)) throw new FileCrypto.NotReadyException();
        SupabaseStorage.warmUp(ctx); // fail fast on login / network problems

        ReceiptDao receiptDao = new ReceiptDao(ctx);
        int receipts = receiptDao.migrateLocalBlobsToCloud();
        int bin = new RecycleBinDao(ctx).migrateAttachmentsToCloud();
        int pending = receiptDao.countPendingLocalBlobs();

        if (receipts + bin > 0) {
            try {
                // Gives the freed blob space back to the phone; can be slow on a big database
                LocalDB.getInstance(ctx).getWritableDatabase().execSQL("VACUUM");
            } catch (Exception ignored) {
            }
        }
        return "Moved " + receipts + " attachment(s) and " + bin + " recycle bin attachment(s). Still pending: "
                + pending + ". Now run a full sync (no from-date).";
    }
}
