package com.expenseos.sync;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import com.expenseos.db.LocalDB;
import com.expenseos.model.PassbookEntry;
import com.expenseos.util.SmsParser;

public class SmsReaderService {

    /**
     * Reads the SMS inbox, parses transaction messages, upserts into passbook_entries. Returns count found.
     */
    public static int scanInboxAndStore(Context ctx) {
        Uri uri = Uri.parse("content://sms/inbox");
        String[] projection = {"_id", "address", "body", "date"};

        int found = 0;
        SQLiteDatabase local = LocalDB.getInstance(ctx).getWritableDatabase();

        try (Cursor c = ctx.getContentResolver().query(uri, projection, null, null, "date DESC")) {
            if (c == null) return 0;

            java.util.Map<String, String> cardMap = new java.util.HashMap<>();
            try (Cursor mc = local.rawQuery("SELECT last4, payment_type FROM card_mappings", null)) {
                while (mc.moveToNext()) cardMap.put(mc.getString(0), mc.getString(1));
            } catch (
                    Exception ignored) { // table innum create aagala na card mapping skip, scan crash aagakoodathu
            }

            java.util.List<String> cardTypeNames = new java.util.ArrayList<>();
            try (Cursor pc = local.rawQuery("SELECT name FROM payment_types", null)) {
                while (pc.moveToNext()) {
                    String n = pc.getString(0);
                    if (n != null && n.toLowerCase(java.util.Locale.ROOT).contains("card"))
                        cardTypeNames.add(n);
                }
            } catch (Exception ignored) {
            }

            local.beginTransaction();
            try {
                while (c.moveToNext()) {
                    long smsId = c.getLong(c.getColumnIndexOrThrow("_id"));
                    String sender = c.getString(c.getColumnIndexOrThrow("address"));
                    String body = c.getString(c.getColumnIndexOrThrow("body"));
                    long date = c.getLong(c.getColumnIndexOrThrow("date"));

                    PassbookEntry entry = SmsParser.parse(smsId, sender, body, date);
                    if (entry == null) continue;

                    String last4 = SmsParser.extractCardLast4(body);
                    if (last4 != null && cardMap.containsKey(last4)) {
                        entry.setPaymentType(cardMap.get(last4));
                    } else {
                        // card number illa → body la payment type name (card) irundha adhai edu, longest match win
                        String lower = body.toLowerCase(java.util.Locale.ROOT);
                        String best = null;
                        for (String n : cardTypeNames) {
                            if (lower.contains(n.toLowerCase(java.util.Locale.ROOT)) && (best == null || n.length() > best.length()))
                                best = n;
                        }
                        if (best != null) entry.setPaymentType(best);
                    }

                    ContentValues cv = new ContentValues();
                    cv.put("sms_id", entry.getSmsId());
                    cv.put("type", entry.getType());
                    cv.put("amount", entry.getAmount().toPlainString());
                    cv.put("sender", entry.getSender());
                    cv.put("raw_body", entry.getRawBody());
                    cv.put("remark", entry.getRemark());
                    cv.put("payment_type", entry.getPaymentType()); // null-safe — ContentValues.put(String,null) stores NULL
                    cv.put("timestamp_millis", entry.getTimestampMillis()); // now the SMS body's own date/time when parsed
                    cv.put("copied", 0);
                    long rowId = local.insertWithOnConflict("passbook_entries", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
                    if (rowId == -1) {
                        // already irukku → copy aagadha rows ku mattum payment type / remark / time refresh
                        local.execSQL("UPDATE passbook_entries SET payment_type=?, remark=?, timestamp_millis=? " +
                                        "WHERE sms_id=? AND copied=0 " +
                                        "AND (IFNULL(payment_type,'')<>IFNULL(?,'') OR IFNULL(remark,'')<>IFNULL(?,'') " +
                                        "OR timestamp_millis<>?)",
                                new Object[]{entry.getPaymentType(), entry.getRemark(), entry.getTimestampMillis(),
                                        entry.getSmsId(), entry.getPaymentType(), entry.getRemark(), entry.getTimestampMillis()});
                    }
                    found++;
                }
                local.setTransactionSuccessful();
            } finally {
                local.endTransaction();
            }
            return found;
        }
    }
}