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
            while (c.moveToNext()) {
                long smsId = c.getLong(c.getColumnIndexOrThrow("_id"));
                String sender = c.getString(c.getColumnIndexOrThrow("address"));
                String body = c.getString(c.getColumnIndexOrThrow("body"));
                long date = c.getLong(c.getColumnIndexOrThrow("date"));

                PassbookEntry entry = SmsParser.parse(smsId, sender, body, date);
                if (entry == null) continue;

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
                local.insertWithOnConflict("passbook_entries", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
                found++;
            }
        }
        return found;
    }
}