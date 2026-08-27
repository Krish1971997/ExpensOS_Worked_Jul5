package com.expenseos.util;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.expenseos.db.LocalDB;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Everything the AI assistant is allowed to touch in the database. This is
 * the actual security boundary — not the system prompt. There is no code
 * path here that can execute anything but a validated SELECT.
 */
public class SafeQueryTools {

    private static final Pattern DISALLOWED = Pattern.compile(
            "\\b(INSERT|UPDATE|DELETE|DROP|ALTER|ATTACH|DETACH|PRAGMA|VACUUM|REPLACE|CREATE|TRUNCATE|GRANT|REVOKE)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TABLE_NAME_SAFE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private final SQLiteDatabase db;

    public SafeQueryTools(Context ctx) {
        this.db = LocalDB.getInstance(ctx).getReadableDatabase();
    }

    public String listTables() {
        JSONArray out = new JSONArray();
        try (Cursor c = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name != 'transaction_receipts' ORDER BY name",
                null)) {
            while (c.moveToNext()) out.put(c.getString(0));
        }
        return out.toString();
    }

    public String describeTable(String tableName) {
        if (tableName == null || !TABLE_NAME_SAFE.matcher(tableName).matches()) {
            return errorJson("Invalid table name");
        }
        if ("transaction_receipts".equalsIgnoreCase(tableName)) {
            return errorJson("This table is not available to the assistant");
        }
        JSONArray cols = new JSONArray();
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + tableName + ")", null)) {
            while (c.moveToNext()) {
                JSONObject col = new JSONObject();
                try {
                    col.put("name", c.getString(c.getColumnIndexOrThrow("name")));
                    col.put("type", c.getString(c.getColumnIndexOrThrow("type")));
                } catch (Exception ignored) {
                }
                cols.put(col);
            }
        } catch (Exception e) {
            return errorJson("Table not found");
        }
        return cols.toString();
    }

    /**
     * The one entry point for AI-authored SQL. Every gate here is a hard reject.
     */
    public String runQuery(String sql) {
        if (sql == null || sql.isBlank()) return errorJson("Empty query");
        String trimmed = sql.trim();
        String withoutTrailingSemi = trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        if (withoutTrailingSemi.contains(";"))
            return errorJson("Multiple statements are not allowed");

        String lower = withoutTrailingSemi.toLowerCase(Locale.ROOT).trim();
        if (!lower.startsWith("select")) return errorJson("Only SELECT queries are allowed");
        if (DISALLOWED.matcher(withoutTrailingSemi).find())
            return errorJson("Query contains a disallowed keyword");
        if (lower.contains("transaction_receipts"))
            return errorJson("transaction_receipts is not available to the assistant");

        JSONArray rows = new JSONArray();
        try (Cursor c = db.rawQuery(withoutTrailingSemi, null)) {
            int rowCount = 0;
            while (c.moveToNext() && rowCount < 200) {
                JSONObject row = new JSONObject();
                for (int i = 0; i < c.getColumnCount(); i++) {
                    String col = c.getColumnName(i);
                    try {
                        switch (c.getType(i)) {
                            case Cursor.FIELD_TYPE_INTEGER -> row.put(col, c.getLong(i));
                            case Cursor.FIELD_TYPE_FLOAT -> row.put(col, c.getDouble(i));
                            case Cursor.FIELD_TYPE_NULL -> row.put(col, JSONObject.NULL);
                            default -> row.put(col, c.getString(i));
                        }
                    } catch (Exception ignored) {
                    }
                }
                rows.put(row);
                rowCount++;
            }
        } catch (Exception e) {
            return errorJson("Query failed: " + e.getMessage());
        }
        return rows.toString();
    }

    private String errorJson(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("error", msg);
        } catch (Exception ignored) {
        }
        return o.toString();
    }
}