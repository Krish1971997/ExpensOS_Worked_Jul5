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
        put(tableName, recordId, null, rowJson);
    }

    // bookId is purely for filtering the Recycle Bin UI by cashbook —
    // restore() always puts a row back using the book_id embedded in its
    // OWN record_json, so it lands in the right book regardless of which
    // cashbook is currently active.
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


    public List<RecycledItem> findAll() {
        return query("SELECT id, table_name, record_id, record_json, deleted_at FROM recycle_bin ORDER BY deleted_at DESC", null);
    }

    public List<RecycledItem> findByTable(String tableName) {
        return query("SELECT id, table_name, record_id, record_json, deleted_at FROM recycle_bin WHERE table_name=? ORDER BY deleted_at DESC",
                new String[]{tableName});
    }

    public List<RecycledItem> findByBook(int bookId) {
        return query("SELECT id, table_name, record_id, record_json, deleted_at FROM recycle_bin WHERE book_id=? ORDER BY deleted_at DESC",
                new String[]{String.valueOf(bookId)});
    }

    /**
     * Re-inserts the row into its original table with its original id, then removes it from the bin.
     */
    private List<RecycledItem> query(String sql, String[] args) {
        List<RecycledItem> list = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, args)) {
            while (c.moveToNext())
                list.add(new RecycledItem(c.getInt(0), c.getString(1), c.getInt(2), c.getString(3), c.getString(4)));
        }
        return list;
    }

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

            // Every "extra" nested array a delete() might have snapshotted
            // alongside the main row — which ones actually exist depends on
            // table_name, so pull whatever's there and strip it before the
            // main row's ContentValues are built.
            org.json.JSONArray receiptsArr = obj.optJSONArray("receipts_data");
            org.json.JSONArray auditArr = obj.optJSONArray("audit_data");
            org.json.JSONArray customValuesArr = obj.optJSONArray("custom_values_data");
            org.json.JSONArray subCategoriesArr = obj.optJSONArray("sub_categories_data");
            org.json.JSONArray unlinkedTxnIds = obj.optJSONArray("unlinked_transaction_ids");
            org.json.JSONArray budgetCategoriesArr = obj.optJSONArray("budget_categories_data");

            obj.remove("receipts_data");
            obj.remove("audit_data");
            obj.remove("custom_values_data");
            obj.remove("sub_categories_data");
            obj.remove("unlinked_transaction_ids");
            obj.remove("budget_categories_data");

            ContentValues cv = new ContentValues();
            cv.put("id", item.recordId);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object val = obj.get(key);
                if (val == JSONObject.NULL) cv.putNull(key);
                else if ("file_data".equals(key) && val instanceof String)
                    cv.put(key, android.util.Base64.decode((String) val, android.util.Base64.NO_WRAP));
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

            // Attachments
            if (receiptsArr != null) {
                for (int i = 0; i < receiptsArr.length(); i++) {
                    JSONObject rObj = receiptsArr.getJSONObject(i);
                    ContentValues rCv = new ContentValues();
                    rCv.put("id", rObj.getInt("id"));
                    rCv.put("transaction_id", item.recordId);
                    rCv.put("file_name", rObj.optString("file_name"));
                    rCv.put("file_type", rObj.optString("file_type"));
                    rCv.put("file_size", rObj.optInt("file_size"));
                    if (!rObj.isNull("file_data"))
                        rCv.put("file_data", android.util.Base64.decode(rObj.getString("file_data"), android.util.Base64.NO_WRAP));
                    db.insertWithOnConflict("transaction_receipts", null, rCv, SQLiteDatabase.CONFLICT_REPLACE);
                }
            }

            // Audit logs
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

            // Custom field values — column_definitions restore re-attaches
            // by transaction_id (col_def_id = this row); a transaction
            // restored via CashBookDao's cascade snapshot re-attaches by
            // col_def_id (transaction_id = this row) — same shape, opposite
            // fixed side.
            if (customValuesArr != null) {
                for (int i = 0; i < customValuesArr.length(); i++) {
                    JSONObject vObj = customValuesArr.getJSONObject(i);
                    ContentValues vCv = new ContentValues();
                    vCv.put("id", vObj.getInt("id"));
                    if ("column_definitions".equals(item.tableName)) {
                        vCv.put("transaction_id", vObj.getInt("transaction_id"));
                        vCv.put("col_def_id", item.recordId);
                    } else {
                        vCv.put("transaction_id", item.recordId);
                        vCv.put("col_def_id", vObj.getInt("col_def_id"));
                    }
                    if (!vObj.isNull("value")) vCv.put("value", vObj.getString("value"));
                    db.insertWithOnConflict("transaction_custom_values", null, vCv, SQLiteDatabase.CONFLICT_REPLACE);
                }
            }

            // Sub-categories under a restored category
            if (subCategoriesArr != null) {
                for (int i = 0; i < subCategoriesArr.length(); i++) {
                    JSONObject sObj = subCategoriesArr.getJSONObject(i);
                    ContentValues sCv = new ContentValues();
                    sCv.put("id", sObj.getInt("id"));
                    sCv.put("name", sObj.getString("name"));
                    sCv.put("category_id", item.recordId);
                    db.insertWithOnConflict("sub_categories", null, sCv, SQLiteDatabase.CONFLICT_REPLACE);
                }
            }

            // budget_categories under a restored budget
            if (budgetCategoriesArr != null) {
                for (int i = 0; i < budgetCategoriesArr.length(); i++) {
                    JSONObject bcObj = budgetCategoriesArr.getJSONObject(i);
                    ContentValues bcCv = new ContentValues();
                    bcCv.put("id", bcObj.getInt("id"));
                    bcCv.put("budget_id", item.recordId);
                    bcCv.put("category_id", bcObj.getInt("category_id"));
                    bcCv.put("cat_limit", bcObj.getDouble("cat_limit"));
                    bcCv.put("alert_pct", bcObj.getInt("alert_pct"));
                    db.insertWithOnConflict("budget_categories", null, bcCv, SQLiteDatabase.CONFLICT_REPLACE);
                }
            }

            // Transactions that got unlinked (category_id/sub_cat_id set to
            // NULL) when a category/sub-category was deleted — re-link them.
            if (unlinkedTxnIds != null) {
                String col = "categories".equals(item.tableName) ? "category_id" : "sub_cat_id";
                for (int i = 0; i < unlinkedTxnIds.length(); i++) {
                    db.execSQL("UPDATE transactions SET " + col + "=? WHERE id=?",
                            new Object[]{item.recordId, unlinkedTxnIds.getInt(i)});
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
