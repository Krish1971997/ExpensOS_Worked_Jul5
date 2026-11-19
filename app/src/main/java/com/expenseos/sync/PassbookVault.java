package com.expenseos.sync;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the phone's passbook_entries table encrypted at rest. sms_id, timestamp_millis and copied stay plain (keys / flags).
 */
public final class PassbookVault {

    private static final String[] COLS = {"type", "amount", "sender", "raw_body", "remark", "payment_type"};

    private PassbookVault() {
    }

    /**
     * Value for display: decrypts, plain passes through, null = encrypted but this phone has no key (locked).
     */
    public static String dec(String stored) {
        try {
            return CloudCrypto.dec(stored);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /**
     * Encrypts every not-yet-encrypted passbook field. Idempotent; does nothing while no key is loaded.
     */
    public static int encryptPending(SQLiteDatabase db) {
        return rewrite(db, true);
    }

    /**
     * Reverse (used when the user removes the passphrase).
     */
    public static int decryptAll(SQLiteDatabase db) {
        return rewrite(db, false);
    }

    private static int rewrite(SQLiteDatabase db, boolean encrypt) {
        if (!CloudCrypto.isEnabled()) return 0;
        List<Long> ids = new ArrayList<>();
        List<ContentValues> changes = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT sms_id, type, amount, sender, raw_body, remark, payment_type FROM passbook_entries", null)) {
            while (c.moveToNext()) {
                ContentValues cv = new ContentValues();
                for (int i = 0; i < COLS.length; i++) {
                    String v = c.getString(i + 1);
                    if (v == null) continue;
                    if (encrypt && !CloudCrypto.isEncrypted(v)) {
                        cv.put(COLS[i], CloudCrypto.enc(v));
                    } else if (!encrypt && CloudCrypto.isEncrypted(v)) {
                        String p = dec(v);
                        if (p != null) cv.put(COLS[i], p);
                    }
                }
                if (cv.size() > 0) {
                    ids.add(c.getLong(0));
                    changes.add(cv);
                }
            }
        }
        if (changes.isEmpty()) return 0;
        db.beginTransaction();
        try {
            for (int i = 0; i < changes.size(); i++)
                db.update("passbook_entries", changes.get(i), "sms_id=?", new String[]{String.valueOf(ids.get(i))});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return changes.size();
    }
}