package com.expenseos.dao;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.expenseos.db.LocalDB;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class RecycleBinDao {
    private final LocalDB helper;
    private final SQLiteDatabase db;

    public RecycleBinDao(Context ctx) {
        helper = LocalDB.getInstance(ctx);
        db = helper.getWritableDatabase();
    }

    public void put(String tableName, int recordId, JSONObject rowJson) {
        long id = helper.getNextId("recycle_bin");
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("table_name", tableName);
        cv.put("record_id", recordId);
        cv.put("record_json", rowJson.toString());
        db.insertWithOnConflict("recycle_bin", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }


    public List<RecycledItem> findAll() {
        return query("SELECT id, table_name, record_id, record_json, deleted_at FROM recycle_bin ORDER BY deleted_at DESC", null);
    }

    public List<RecycledItem> findByTable(String tableName) {
        return query("SELECT id, table_name, record_id, record_json, deleted_at FROM recycle_bin WHERE table_name=? ORDER BY deleted_at DESC",
                new String[]{tableName});
    }

    private List<RecycledItem> query(String sql, String[] args) {
        List<RecycledItem> list = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, args)) {
            while (c.moveToNext())
                list.add(new RecycledItem(c.getInt(0), c.getString(1), c.getInt(2), c.getString(3), c.getString(4)));
        }
        return list;
    }

    /**
     * Re-inserts the row into its original table with its original id, then removes it from the bin.
     */
    public boolean restore(int binId) {
        RecycledItem item = null;
        try (Cursor c = db.rawQuery(
                "SELECT id, table_name, record_id, record_json, deleted_at FROM recycle_bin WHERE id=?",
                new String[]{String.valueOf(binId)})) {
            if (c.moveToFirst())
                item = new RecycledItem(c.getInt(0), c.getString(1), c.getInt(2), c.getString(3), c.getString(4));
        }
        if (item == null) return false;

        db.beginTransaction();
        try {
            JSONObject obj = new JSONObject(item.recordJson);

            // Arrays extraction
            org.json.JSONArray receiptsArr = obj.optJSONArray("receipts_data");
            org.json.JSONArray auditArr = obj.optJSONArray("audit_data");

            obj.remove("receipts_data");
            obj.remove("audit_data");

            ContentValues cv = new ContentValues();
            cv.put("id", item.recordId);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object val = obj.get(key);
                if (val == JSONObject.NULL) cv.putNull(key);
                else if (val instanceof Integer) cv.put(key, (Integer) val);
                else if (val instanceof Long) cv.put(key, (Long) val);
                else if (val instanceof Double) cv.put(key, (Double) val);
                else cv.put(key, val.toString());
            }

            long result = db.insertWithOnConflict(item.tableName, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
            if (result == -1) {
                db.endTransaction();
                return false;
            }

            // 🟢 1. Attachments Restore
            if (receiptsArr != null) {
                for (int i = 0; i < receiptsArr.length(); i++) {
                    JSONObject rObj = receiptsArr.getJSONObject(i);
                    ContentValues rCv = new ContentValues();
                    rCv.put("id", rObj.getInt("id"));
                    rCv.put("transaction_id", item.recordId);
                    rCv.put("file_name", rObj.optString("file_name"));
                    rCv.put("file_type", rObj.optString("file_type"));
                    rCv.put("file_size", rObj.optInt("file_size"));
                    if (!rObj.isNull("file_data")) {
                        rCv.put("file_data", android.util.Base64.decode(rObj.getString("file_data"), android.util.Base64.NO_WRAP));
                    }
                    db.insertWithOnConflict("transaction_receipts", null, rCv, SQLiteDatabase.CONFLICT_REPLACE);
                }
            }

            // 🟢 2. Audit Logs Restore
            if (auditArr != null) {
                for (int i = 0; i < auditArr.length(); i++) {
                    JSONObject aObj = auditArr.getJSONObject(i);
                    ContentValues aCv = new ContentValues();
                    aCv.put("id", aObj.getInt("id"));
                    aCv.put("transaction_id", item.recordId);
                    aCv.put("action", aObj.optString("action"));
                    aCv.put("changed_by", aObj.optString("changed_by"));
                    aCv.put("field_name", aObj.optString("field_name"));
                    aCv.put("old_value", aObj.optString("old_value"));
                    aCv.put("new_value", aObj.optString("new_value"));
                    aCv.put("note", aObj.optString("note"));
                    aCv.put("changed_at", aObj.optString("changed_at"));
                    db.insertWithOnConflict("transaction_audit_log", null, aCv, SQLiteDatabase.CONFLICT_REPLACE);
                }
            }

            db.delete("recycle_bin", "id=?", new String[]{String.valueOf(binId)});
            db.setTransactionSuccessful();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            db.endTransaction();
        }
    }

    public void purge(int binId) {
        db.delete("recycle_bin", "id=?", new String[]{String.valueOf(binId)});
    }

    public record RecycledItem(int id, String tableName, int recordId, String recordJson,
                               String deletedAt) {

        public String displayName() {
            try {
                JSONObject obj = new JSONObject(recordJson);
                if (obj.has("name")) return obj.getString("name");
                if (obj.has("note") && !obj.isNull("note")) return obj.getString("note");
            } catch (Exception ignored) {
            }
            return tableName + " #" + recordId;
        }
    }
}
