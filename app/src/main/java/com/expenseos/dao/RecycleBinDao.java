package com.expenseos.dao;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.CursorWindow;
import android.database.sqlite.SQLiteCursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Base64;

import com.expenseos.cloud.FileCrypto;
import com.expenseos.cloud.SupabaseStorage;
import com.expenseos.db.LocalDB;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class RecycleBinDao {
    private final LocalDB helper;
    private final SQLiteDatabase db;
    private final Context ctx;

    public RecycleBinDao(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        helper = LocalDB.getInstance(ctx);
        db = helper.getWritableDatabase();
    }

    public void put(String tableName, int recordId, JSONObject rowJson) {
        put(tableName, recordId, null, rowJson);
    }

    public void put(String tableName, int recordId, Integer bookId, JSONObject rowJson) {
        long id = helper.getNextId("recycle_bin");
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("table_name", tableName);
        cv.put("record_id", recordId);
        if (bookId != null) cv.put("book_id", bookId);
        else cv.putNull("book_id");
        cv.put("record_json", rowJson.toString());
        db.insertWithOnConflict("recycle_bin", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // record_json-ஐ ഒഴിവാக்கி json_extract மூலம் name/note மட்டும் எடுப்பதால் Cursor limit பிரச்சனை வராது
    public List<RecycledItem> findAll() {
        return query("SELECT id, table_name, record_id, json_extract(record_json, '$.name'), json_extract(record_json, '$.note'), deleted_at FROM recycle_bin ORDER BY deleted_at DESC", null);
    }

    public List<RecycledItem> findByTable(String tableName) {
        return query("SELECT id, table_name, record_id, json_extract(record_json, '$.name'), json_extract(record_json, '$.note'), deleted_at FROM recycle_bin WHERE table_name=? ORDER BY deleted_at DESC",
                new String[]{tableName});
    }

    public List<RecycledItem> findByBook(int bookId) {
        return query("SELECT id, table_name, record_id, json_extract(record_json, '$.name'), json_extract(record_json, '$.note'), deleted_at FROM recycle_bin WHERE book_id=? ORDER BY deleted_at DESC",
                new String[]{String.valueOf(bookId)});
    }

    private List<RecycledItem> query(String sql, String[] args) {
        List<RecycledItem> list = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, args)) {
            while (c.moveToNext()) {
                String name = c.getString(3);
                String note = c.getString(4);
                list.add(new RecycledItem(c.getInt(0), c.getString(1), c.getInt(2), name, note, c.getString(5)));
            }
        }
        return list;
    }

    public boolean restore(int binId) {
        RecycledItemSummary itemSummary = null;
        String recordJsonStr = null;

        CursorWindow window = new CursorWindow("recycle_bin_restore", 10 * 1024 * 1024); // 10 MB

        try (Cursor c = db.rawQuery(
                "SELECT id, table_name, record_id, record_json, deleted_at FROM recycle_bin WHERE id=?",
                new String[]{String.valueOf(binId)})) {

            if (c instanceof SQLiteCursor) {
                ((SQLiteCursor) c).setWindow(window);
            }

            if (c.moveToFirst()) {
                itemSummary = new RecycledItemSummary(c.getInt(0), c.getString(1), c.getInt(2), c.getString(4));
                recordJsonStr = c.getString(3);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            window.close(); // Memory leak illamal window-a close panrom
        }

        if (itemSummary == null || recordJsonStr == null) return false;

        db.beginTransaction();
        try {
            JSONObject obj = new JSONObject(recordJsonStr);

            JSONArray receiptsArr = obj.optJSONArray("receipts_data");
            JSONArray auditArr = obj.optJSONArray("audit_data");
            JSONArray customValuesArr = obj.optJSONArray("custom_values_data");
            JSONArray subCategoriesArr = obj.optJSONArray("sub_categories_data");
            JSONArray unlinkedTxnIds = obj.optJSONArray("unlinked_transaction_ids");
            JSONArray budgetCategoriesArr = obj.optJSONArray("budget_categories_data");

            obj.remove("receipts_data");
            obj.remove("audit_data");
            obj.remove("custom_values_data");
            obj.remove("sub_categories_data");
            obj.remove("unlinked_transaction_ids");
            obj.remove("budget_categories_data");

            ContentValues cv = new ContentValues();
            cv.put("id", itemSummary.recordId);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object val = obj.get(key);
                if (val == JSONObject.NULL) cv.putNull(key);
                else if ("file_data".equals(key) && val instanceof String)
                    cv.put(key, Base64.decode((String) val, Base64.NO_WRAP));
                else if (val instanceof Integer) cv.put(key, (Integer) val);
                else if (val instanceof Long) cv.put(key, (Long) val);
                else if (val instanceof Double) cv.put(key, (Double) val);
                else cv.put(key, val.toString());
            }

            long result = db.insertWithOnConflict(itemSummary.tableName, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
            if (result == -1) {
                db.endTransaction();
                return false;
            }

            if (receiptsArr != null) {
                for (int i = 0; i < receiptsArr.length(); i++) {
                    JSONObject rObj = receiptsArr.getJSONObject(i);
                    ContentValues rCv = new ContentValues();
                    rCv.put("id", rObj.getInt("id"));
                    rCv.put("transaction_id", itemSummary.recordId);
                    rCv.put("file_name", rObj.optString("file_name"));
                    rCv.put("file_type", rObj.optString("file_type"));
                    rCv.put("file_size", rObj.optInt("file_size"));
                    if (!rObj.isNull("storage_path")) rCv.put("storage_path", rObj.getString("storage_path"));
                    if (!rObj.isNull("file_data"))
                        rCv.put("file_data", Base64.decode(rObj.getString("file_data"), Base64.NO_WRAP));
                    db.insertWithOnConflict("transaction_receipts", null, rCv, SQLiteDatabase.CONFLICT_REPLACE);
                }
            }

            if (auditArr != null) {
                for (int i = 0; i < auditArr.length(); i++) {
                    JSONObject aObj = auditArr.getJSONObject(i);
                    ContentValues aCv = new ContentValues();
                    aCv.put("id", aObj.getInt("id"));
                    aCv.put("transaction_id", itemSummary.recordId);
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

            if (customValuesArr != null) {
                for (int i = 0; i < customValuesArr.length(); i++) {
                    JSONObject vObj = customValuesArr.getJSONObject(i);
                    ContentValues vCv = new ContentValues();
                    vCv.put("id", vObj.getInt("id"));
                    if ("column_definitions".equals(itemSummary.tableName)) {
                        vCv.put("transaction_id", vObj.getInt("transaction_id"));
                        vCv.put("col_def_id", itemSummary.recordId);
                    } else {
                        vCv.put("transaction_id", itemSummary.recordId);
                        vCv.put("col_def_id", vObj.getInt("col_def_id"));
                    }
                    if (!vObj.isNull("value")) vCv.put("value", vObj.getString("value"));
                    db.insertWithOnConflict("transaction_custom_values", null, vCv, SQLiteDatabase.CONFLICT_REPLACE);
                }
            }

            if (subCategoriesArr != null) {
                for (int i = 0; i < subCategoriesArr.length(); i++) {
                    JSONObject sObj = subCategoriesArr.getJSONObject(i);
                    ContentValues sCv = new ContentValues();
                    sCv.put("id", sObj.getInt("id"));
                    sCv.put("name", sObj.getString("name"));
                    sCv.put("category_id", itemSummary.recordId);
                    db.insertWithOnConflict("sub_categories", null, sCv, SQLiteDatabase.CONFLICT_REPLACE);
                }
            }

            if (budgetCategoriesArr != null) {
                for (int i = 0; i < budgetCategoriesArr.length(); i++) {
                    JSONObject bcObj = budgetCategoriesArr.getJSONObject(i);
                    ContentValues bcCv = new ContentValues();
                    bcCv.put("id", bcObj.getInt("id"));
                    bcCv.put("budget_id", itemSummary.recordId);
                    bcCv.put("category_id", bcObj.getInt("category_id"));
                    bcCv.put("cat_limit", bcObj.getDouble("cat_limit"));
                    bcCv.put("alert_pct", bcObj.getInt("alert_pct"));
                    db.insertWithOnConflict("budget_categories", null, bcCv, SQLiteDatabase.CONFLICT_REPLACE);
                }
            }

            if (unlinkedTxnIds != null) {
                String col = "categories".equals(itemSummary.tableName) ? "category_id" : "sub_cat_id";
                for (int i = 0; i < unlinkedTxnIds.length(); i++) {
                    db.execSQL("UPDATE transactions SET " + col + "=? WHERE id=?",
                            new Object[]{itemSummary.recordId, unlinkedTxnIds.getInt(i)});
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

    /**
     * Removes the recycle-bin row and, if it referenced cloud objects, deletes those in the
     * background. Restore keeps the object (only the link comes back), so this is the only place
     * where a cloud attachment is actually destroyed.
     */
    public void purge(int binId) {
        List<String> paths = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT json_extract(record_json,'$.storage_path') FROM recycle_bin "
                + "WHERE id=? AND table_name='transaction_receipts'", new String[]{String.valueOf(binId)})) {
            if (c.moveToFirst() && !c.isNull(0)) paths.add(c.getString(0));
        } catch (Exception ignored) {
        }
        try (Cursor c = db.rawQuery("SELECT json_extract(j.value,'$.storage_path') "
                + "FROM recycle_bin r, json_each(r.record_json,'$.receipts_data') j WHERE r.id=?",
                new String[]{String.valueOf(binId)})) {
            while (c.moveToNext()) if (!c.isNull(0)) paths.add(c.getString(0));
        } catch (Exception ignored) {
        }
        db.delete("recycle_bin", "id=?", new String[]{String.valueOf(binId)});
        if (paths.isEmpty()) return;
        final List<String> toDelete = new ArrayList<>(paths);
        new Thread(() -> {
            for (String p : toDelete) {
                try {
                    SupabaseStorage.delete(ctx, p);
                } catch (Exception e) {
                    android.util.Log.w("RecycleBinDao", "Cloud delete failed: " + p + " " + e.getMessage());
                }
            }
        }).start();
    }

    // ── One-time migration: embedded base64 blobs -> encrypted Supabase objects ──

    /**
     * Moves attachment bytes embedded in old recycle_bin snapshots to encrypted Supabase objects
     * and replaces them with a storage_path in the JSON. Background thread only. Returns the
     * number of attachments moved.
     */
    public int migrateAttachmentsToCloud() {
        // Only the ids are selected here, so the big JSON is never loaded into a cursor window
        List<Integer> ids = new ArrayList<>();
        String sel = "SELECT id FROM recycle_bin WHERE "
                + "(table_name='transaction_receipts' AND json_extract(record_json,'$.file_data') IS NOT NULL) "
                + "OR (table_name='transactions' AND EXISTS (SELECT 1 FROM json_each(record_json,'$.receipts_data') j "
                + "WHERE json_extract(j.value,'$.file_data') IS NOT NULL))";
        try (Cursor c = db.rawQuery(sel, null)) {
            while (c.moveToNext()) ids.add(c.getInt(0));
        }
        int moved = 0;
        for (int binId : ids) {
            try {
                moved += migrateBinRow(binId);
            } catch (Exception e) {
                // e.g. cloud unreachable, or the cash book is inactive; the row stays unchanged and is retried next time
                android.util.Log.w("RecycleBinDao", "bin migrate " + binId + " failed: " + e.getMessage());
            }
        }
        return moved;
    }

    private int migrateBinRow(int binId) throws Exception {
        String tableName;
        int recordId;
        String json;
        CursorWindow window = new CursorWindow("bin_migrate", 64L * 1024 * 1024);
        try (Cursor c = db.rawQuery("SELECT table_name, record_id, record_json FROM recycle_bin WHERE id=?",
                new String[]{String.valueOf(binId)})) {
            if (c instanceof SQLiteCursor) ((SQLiteCursor) c).setWindow(window);
            if (!c.moveToFirst()) return 0;
            tableName = c.getString(0);
            recordId = c.getInt(1);
            json = c.getString(2);
        } finally {
            window.close();
        }

        JSONObject obj = new JSONObject(json);
        int moved = 0;
        if ("transaction_receipts".equals(tableName)) {
            moved += moveBlob(obj, obj.optInt("transaction_id"), recordId);
        } else if ("transactions".equals(tableName)) {
            JSONArray arr = obj.optJSONArray("receipts_data");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject r = arr.getJSONObject(i);
                    moved += moveBlob(r, recordId, r.getInt("id"));
                }
            }
        }
        if (moved > 0) {
            // The JSON is saved only after every upload of this row succeeded
            ContentValues cv = new ContentValues();
            cv.put("record_json", obj.toString());
            db.update("recycle_bin", cv, "id=?", new String[]{String.valueOf(binId)});
        }
        return moved;
    }

    /**
     * Uploads one embedded base64 blob (encrypted) and swaps it for a storage_path in the JSON.
     */
    private int moveBlob(JSONObject o, int txnId, int receiptId) throws Exception {
        if (o.isNull("file_data")) return 0;
        byte[] data = Base64.decode(o.getString("file_data"), Base64.NO_WRAP);
        String path = SupabaseStorage.pathFor(txnId, receiptId);
        SupabaseStorage.upload(ctx, path, "application/octet-stream", FileCrypto.encrypt(ctx, data, path));
        o.remove("file_data");
        o.put("storage_path", path);
        return 1;
    }

    private static class RecycledItemSummary {
        int id;
        String tableName;
        int recordId;
        String deletedAt;

        RecycledItemSummary(int id, String tableName, int recordId, String deletedAt) {
            this.id = id;
            this.tableName = tableName;
            this.recordId = recordId;
            this.deletedAt = deletedAt;
        }
    }

    public record RecycledItem(int id, String tableName, int recordId, String name, String note,
                               String deletedAt) {
        public String displayName() {
            if (name != null && !name.isEmpty()) return name;
            if (note != null && !note.isEmpty()) return note;
            return tableName + " #" + recordId;
        }
    }
}