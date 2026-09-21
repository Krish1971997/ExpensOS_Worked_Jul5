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

/**
 * Persists AI Assistant chat history and now also the concept of "sessions":
 * every message carries session_id so the user can have multiple chats
 * (like chat / claude style) and swipe between them via History.
 */
public class ChatHistoryDao {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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
        cv.put("session_id", m.getSessionId() == null ? "default" : m.getSessionId());
        db.insert("ai_chat_messages", null, cv);
        trimOld();
        return id;
    }

    public List<ChatMessage> findAll() {
        return findBySession("default");
    }

    public List<ChatMessage> findBySession(String sessionId) {
        List<ChatMessage> list = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT id, role, content, attachment_path, attachment_name, chart_path, provider, created_at " +
                        "FROM ai_chat_messages WHERE session_id=? ORDER BY id ASC",
                new String[]{sessionId == null ? "default" : sessionId})) {
            while (c.moveToNext()) list.add(mapRow(c));
        }
        return list;
    }

    /** Lists every session id + the first USER preview + count. Used by History. */
    public List<SessionSummary> listSessions() {
        List<SessionSummary> out = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT session_id, MIN(created_at) as started, COUNT(*) as cnt, " +
                        "  (SELECT content FROM ai_chat_messages m2 WHERE m2.session_id=m1.session_id AND m2.role='user' ORDER BY m2.id ASC LIMIT 1) AS preview " +
                        "FROM ai_chat_messages m1 " +
                        "GROUP BY session_id ORDER BY MIN(id) DESC", null)) {
            while (c.moveToNext()) {
                String id = c.getString(0);
                String started = c.isNull(1) ? "" : c.getString(1);
                int cnt = c.getInt(2);
                String preview = c.isNull(3) ? "(no user message)" : c.getString(3);
                if (preview != null && preview.length() > 60) preview = preview.substring(0, 60) + "…";
                out.add(new SessionSummary(id, started, cnt, preview == null ? "" : preview));
            }
        }
        return out;
    }

    public void clearAll() {
        db.delete("ai_chat_messages", null, null);
    }

    public void clearSession(String sessionId) {
        db.delete("ai_chat_messages", "session_id=?", new String[]{sessionId == null ? "default" : sessionId});
    }

    /** Bulk delete selected rows (used by long-press multi-select). */
    public int deleteByIds(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        StringBuilder qmarks = new StringBuilder();
        String[] args = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) qmarks.append(',');
            qmarks.append('?');
            args[i] = String.valueOf(ids.get(i));
        }
        return db.delete("ai_chat_messages", "id IN (" + qmarks + ")", args);
    }

    private void trimOld() {
        // Keep latest 200 rows across ALL sessions combined (cheap cap so DB
        // can't grow forever, just like before)
        db.execSQL("DELETE FROM ai_chat_messages WHERE id NOT IN " +
                "(SELECT id FROM ai_chat_messages ORDER BY id DESC LIMIT 200)");
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

    public static class SessionSummary {
        public final String sessionId;
        public final String startedAt;
        public final int messageCount;
        public final String preview;
        public SessionSummary(String sessionId, String startedAt, int messageCount, String preview) {
            this.sessionId = sessionId;
            this.startedAt = startedAt;
            this.messageCount = messageCount;
            this.preview = preview;
        }
    }
}
