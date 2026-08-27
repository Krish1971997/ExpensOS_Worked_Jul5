package com.expenseos.dao;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.expenseos.db.LocalDB;
import com.expenseos.model.ChatMessage;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class ChatHistoryDao {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_HISTORY = 200; // keep the DB small — trims oldest beyond this

    private final LocalDB helper;
    private final SQLiteDatabase db;

    public ChatHistoryDao(Context ctx) {
        helper = LocalDB.getInstance(ctx);
        db = helper.getWritableDatabase();
    }

    public long insert(ChatMessage m) {
        long id = helper.getNextId("ai_chat_messages");
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("role", m.getRole());
        cv.put("content", m.getContent());
        cv.put("attachment_path", m.getAttachmentPath());
        cv.put("attachment_name", m.getAttachmentName());
        cv.put("chart_path", m.getChartPath());
        cv.put("provider", m.getProvider());
        cv.put("created_at", LocalDateTime.now().format(TS_FMT));
        db.insert("ai_chat_messages", null, cv);
        trimOld();
        return id;
    }

    public List<ChatMessage> findAll() {
        List<ChatMessage> list = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT id, role, content, attachment_path, attachment_name, chart_path, provider, created_at " +
                        "FROM ai_chat_messages ORDER BY id ASC", null)) {
            while (c.moveToNext()) list.add(mapRow(c));
        }
        return list;
    }

    public void clearAll() {
        db.delete("ai_chat_messages", null, null);
    }

    private void trimOld() {
        db.execSQL("DELETE FROM ai_chat_messages WHERE id NOT IN " +
                "(SELECT id FROM ai_chat_messages ORDER BY id DESC LIMIT " + MAX_HISTORY + ")");
    }

    private ChatMessage mapRow(Cursor c) {
        ChatMessage m = new ChatMessage();
        m.setId(c.getInt(0));
        m.setRole(c.getString(1));
        m.setContent(c.isNull(2) ? null : c.getString(2));
        m.setAttachmentPath(c.isNull(3) ? null : c.getString(3));
        m.setAttachmentName(c.isNull(4) ? null : c.getString(4));
        m.setChartPath(c.isNull(5) ? null : c.getString(5));
        m.setProvider(c.isNull(6) ? null : c.getString(6));
        m.setCreatedAt(c.getString(7));
        return m;
    }
}