package com.expenseos.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.expenseos.R;
import com.expenseos.dao.ChatHistoryDao;
import com.expenseos.model.ChatMessage;
import com.expenseos.util.AiCandidate;
import com.expenseos.util.AiConfigStore;
import com.expenseos.util.AiException;
import com.expenseos.util.AiFailoverManager;
import com.expenseos.util.AiKeyConfig;
import com.expenseos.util.AiModelConfig;
import com.expenseos.util.AiRequest;
import com.expenseos.util.AppConfig;
import com.expenseos.util.MarkdownRenderer;
import com.expenseos.util.PdfAttachmentReader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Chat with the in-app AI Assistant.
 * - Multi-select delete (long-press), per-bubble Copy/Delete (⋮ menu), new-chat, history
 * - Markdown-rendered bot bubbles (### heading, **bold**, - bullet, --- divider)
 * - Inline image / PDF attachments + preview
 * - Multiple chart paths per turn (day-wise + category-wise)
 * - Sessions isolated by session_id (multi-chat like claude / chatgpt)
 * - Central failover (model/key priority) with ChatGPT-style error bubble + Retry
 * - Token-optimized: bounded neutral history window, no duplicated turns,
 * single in-flight guard (see AiFailoverManager / AiHistory / AiPrompts).
 */
public class ChatActivity extends AppCompatActivity {

    /**
     * How many recent neutral history turns are sent to the model per request.
     */
    private static final int HISTORY_WINDOW = 8;

    private LinearLayout messagesContainer;
    private ScrollView scrollView;
    private EditText etInput;
    private View attachPreviewRow;
    private TextView tvAttachName;
    private ImageButton btnSend, btnAttach, btnAttachRemove;
    private ImageButton btnMultiDelete, btnNew, btnHistory;
    private View selectionBar;
    private TextView tvSelectionCount;
    private android.widget.Button btnSelectAll, btnCancelSelection;

    private ChatHistoryDao historyDao;
    private AiFailoverManager failover;
    // Ovvoru runTurn() call-kum unique id — watchdog give-up pannina apparam
    // background thread late-ah result kொடுthalum, adhு STALE turn-ah irundha
    // UI-ah touch pannாma silently ignore pannum (typingBubble/typingTextView
    // corruption idha avoid pannும்).
    private final java.util.concurrent.atomic.AtomicInteger turnCounter = new java.util.concurrent.atomic.AtomicInteger(0);
    private int activeTurnId = 0;

    /**
     * Neutral conversation history: [{role:"user"|"assistant", text:"…"}].
     * Provider-agnostic — each client converts it to its own wire format, so
     * system prompts and tool traffic are never duplicated in this array.
     */
    private final JSONArray conversation = new JSONArray();

    private String pendingAttachmentPath;
    private String pendingAttachmentName;
    private boolean pendingAttachmentIsImage;
    private boolean pendingAttachmentIsPdf;

    // Multi-select state
    private boolean inSelectionMode = false;
    private final List<Integer> selectedIds = new ArrayList<>();
    // last bubble view per message id — track so when we delete selected rows
    // we can also remove their views without re-rendering everything
    private final java.util.HashMap<Integer, View> bubbleViews = new java.util.HashMap<>();

    // Single in-flight guard: send + retry share it so requests never stack
    private boolean requestInFlight = false;

    // Retry state — the exact original user turn, preserved across failures
    private String lastFailedUserDisplay;
    private String lastFailedEffective;
    private String lastFailedImagePath;
    private View typingBubble;
    private TextView typingTextView;
    private Button retryButton;

    // Session
    private String currentSessionId = "default";

    private final ActivityResultLauncher<String[]> filePicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) handlePickedFile(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        historyDao = new ChatHistoryDao(this);
        rebuildFailover();

        messagesContainer = findViewById(R.id.chatMessagesContainer);
        scrollView = findViewById(R.id.chatScrollView);
        etInput = findViewById(R.id.etChatInput);
        attachPreviewRow = findViewById(R.id.chatAttachPreviewRow);
        tvAttachName = findViewById(R.id.tvChatAttachName);
        btnSend = findViewById(R.id.btnChatSend);
        btnAttach = findViewById(R.id.btnChatAttach);
        btnAttachRemove = findViewById(R.id.btnChatAttachRemove);
        btnMultiDelete = findViewById(R.id.btnChatMultiDelete);
        btnNew = findViewById(R.id.btnChatNew);
        btnHistory = findViewById(R.id.btnChatHistory);
        selectionBar = findViewById(R.id.selectionBar);
        tvSelectionCount = findViewById(R.id.tvSelectionCount);
        btnSelectAll = findViewById(R.id.btnSelectAll);
        btnCancelSelection = findViewById(R.id.btnCancelSelection);

        findViewById(R.id.btnChatBack).setOnClickListener(v -> finish());
        btnSend.setOnClickListener(v -> {
            if (!requestInFlight) sendMessage();
        });
        btnAttach.setOnClickListener(v -> filePicker.launch(new String[]{"image/*", "application/pdf", "text/plain", "text/csv"}));
        btnAttachRemove.setOnClickListener(v -> clearPendingAttachment());
        btnNew.setOnClickListener(v -> startNewChat());
        btnHistory.setOnClickListener(v -> showHistory());
        btnMultiDelete.setOnClickListener(v -> deleteSelected());
        btnSelectAll.setOnClickListener(v -> toggleSelectAll());
        btnCancelSelection.setOnClickListener(v -> exitSelectionMode());

        loadSession("default");
    }

    /**
     * Builds the failover candidate list (provider → model → keys, in saved priority order).
     */
    private void rebuildFailover() {
        AiConfigStore store = new AiConfigStore(this);
        AiConfigStore.AiConfig cfg = store.load();
        List<AiCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < cfg.models.size(); i++) {
            AiModelConfig m = cfg.models.get(i);
            for (int k = 0; k < m.keys.size(); k++) {
                AiKeyConfig key = m.keys.get(k);
                if (key.key == null || key.key.isBlank()) continue;
                candidates.add(new AiCandidate(m.provider, m.model, key.key, key.desc, k));
            }
        }
        failover = new AiFailoverManager(this, candidates);
    }

    // ── Session loading ──────────────────────────────────────────────────────
    private void loadSession(String sessionId) {
        currentSessionId = sessionId == null ? "default" : sessionId;
        messagesContainer.removeAllViews();
        bubbleViews.clear();

        List<ChatMessage> history = historyDao.findBySession(currentSessionId);
        if (history.isEmpty()) {
            addBotBubble("Ask me anything about your ExpenseOS data — spending, categories, budgets, backups, etc. " +
                    "You can also attach a receipt image or a PDF statement.", null);
            return;
        }
        for (ChatMessage m : history) {
            int storedId = m.getId();
            if (m.isUser()) {
                addUserBubble(storedId, m.getContent(), m.getAttachmentPath(), m.getAttachmentName());
            } else {
                addBotBubble(storedId, m.getContent(), m.getChartPath());
            }
        }
    }

    private void startNewChat() {
        // No board-level delete — just stamp a fresh session and clear the view.
        // Future messages land in this new session; old sessions preserved for History.
        currentSessionId = "session_" + System.currentTimeMillis();
        messagesContainer.removeAllViews();
        bubbleViews.clear();
        addBotBubble("🆕 New chat started. Ask me anything about your ExpenseOS data.", null);
    }

    private void showHistory() {
        List<ChatHistoryDao.SessionSummary> sessions = historyDao.listSessions();
        String[] labels = new String[sessions.size()];
        for (int i = 0; i < sessions.size(); i++) {
            ChatHistoryDao.SessionSummary s = sessions.get(i);
            labels[i] = s.startedAt + "  •  " + s.preview + "  (" + s.messageCount + " msg)";
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Chat history");
        b.setItems(labels, (d, which) -> loadSession(sessions.get(which).sessionId));
        b.setNegativeButton("Clear current chat", (d, w) -> {
            historyDao.clearSession(currentSessionId);
            loadSession("default");
        });
        b.setNeutralButton("Close", null);
        b.show();
    }

    // ── Multi-select mode ─────────────────────────────────────────────────
    private void enterSelectionMode(int firstId) {
        if (inSelectionMode) {
            toggleSelected(firstId);
            return;
        }
        inSelectionMode = true;
        selectedIds.clear();
        selectedIds.add(firstId);
        updateSelectionChrome();
        refreshBubbleSelState();
    }

    private void toggleSelected(int id) {
        if (selectedIds.contains(id)) selectedIds.remove((Integer) id);
        else selectedIds.add(id);
        if (selectedIds.isEmpty()) {
            exitSelectionMode();
        } else {
            updateSelectionChrome();
            refreshBubbleSelState();
        }
    }

    private void toggleSelectAll() {
        if (selectedIds.size() < bubbleViews.size()) {
            selectedIds.clear();
            selectedIds.addAll(bubbleViews.keySet());
        } else {
            selectedIds.clear();
        }
        updateSelectionChrome();
        refreshBubbleSelState();
    }

    private void exitSelectionMode() {
        inSelectionMode = false;
        selectedIds.clear();
        updateSelectionChrome();
        refreshBubbleSelState();
    }

    private void deleteSelected() {
        if (selectedIds.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("Delete " + selectedIds.size() + " message(s)?")
                .setPositiveButton("Delete", (d, w) -> {
                    historyDao.deleteByIds(new ArrayList<>(selectedIds));
                    // remove bubbles from view
                    for (Integer id : selectedIds) {
                        View v = bubbleViews.remove(id);
                        if (v != null) messagesContainer.removeView(v);
                    }
                    exitSelectionMode();
                    Toast.makeText(this, "✓ Deleted", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateSelectionChrome() {
        int shown = inSelectionMode ? View.VISIBLE : View.GONE;
        selectionBar.setVisibility(shown);
        btnMultiDelete.setVisibility(shown);
        btnNew.setVisibility(inSelectionMode ? View.GONE : View.VISIBLE);
        btnHistory.setVisibility(inSelectionMode ? View.GONE : View.VISIBLE);
        if (inSelectionMode) tvSelectionCount.setText(selectedIds.size() + " selected");
    }

    private void refreshBubbleSelState() {
        for (java.util.Map.Entry<Integer, View> e : bubbleViews.entrySet()) {
            View v = e.getValue();
            boolean sel = selectedIds.contains(e.getKey());
            v.setBackgroundColor(sel ? Color.parseColor("#FEF3C7") : Color.TRANSPARENT);
        }
    }

    // ── Copy / Delete per-bubble menu ─────────────────────────────────────
    private void showCopyDeleteMenu(int storedId, String text) {
        String[] items = {"Copy", "Delete"};
        new AlertDialog.Builder(this)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        copyToClipboard(text);
                    } else {
                        confirmDeleteOne(storedId);
                    }
                })
                .show();
    }

    private void copyToClipboard(String text) {
        if (text == null || text.isEmpty()) return;
        // Copy only the raw message text — no UI metadata, no timestamps.
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("message", text));
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
    }

    private void confirmDeleteOne(int storedId) {
        if (storedId <= 0) {
            Toast.makeText(this, "Nothing to delete", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete this message?")
                .setPositiveButton("Delete", (d, w) -> {
                    List<Integer> ids = new ArrayList<>();
                    ids.add(storedId);
                    historyDao.deleteByIds(ids);
                    View v = bubbleViews.remove(storedId);
                    if (v != null) messagesContainer.removeView(v);
                    Toast.makeText(this, "✓ Deleted", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Attachment picking ──────────────────────────────────────────────
    private void handlePickedFile(Uri uri) {
        try {
            String name = queryFileName(uri);
            String mime = getContentResolver().getType(uri);
            boolean isImage = mime != null && mime.startsWith("image/");
            boolean isPdf = mime != null && mime.equals("application/pdf")
                    || (name != null && name.toLowerCase(Locale.ROOT).endsWith(".pdf"));

            File dir = new File(getFilesDir(), "ai_attachments");
            if (!dir.exists()) dir.mkdirs();
            File dest = new File(dir, System.currentTimeMillis() + "_" + (name != null ? name : "file"));

            try (InputStream is = getContentResolver().openInputStream(uri);
                 FileOutputStream os = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int n;
                while (is != null && (n = is.read(buf)) > 0) os.write(buf, 0, n);
            }

            pendingAttachmentPath = dest.getAbsolutePath();
            pendingAttachmentName = name != null ? name : dest.getName();
            pendingAttachmentIsImage = isImage;
            pendingAttachmentIsPdf = isPdf;

            String prefix = isImage ? "🖼 " : isPdf ? "📕 " : "📄 ";
            tvAttachName.setText(prefix + pendingAttachmentName);
            attachPreviewRow.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            addBotBubble("⚠ Couldn't read that file: " + e.getMessage(), null);
        }
    }

    private String queryFileName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void clearPendingAttachment() {
        pendingAttachmentPath = null;
        pendingAttachmentName = null;
        pendingAttachmentIsImage = false;
        pendingAttachmentIsPdf = false;
        attachPreviewRow.setVisibility(View.GONE);
    }

    private String readSmallTextFile(String path) {
        try {
            String lower = path.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".txt") && !lower.endsWith(".csv")) return null;
            byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            return text.length() > 8000 ? text.substring(0, 8000) + "\n…(truncated)" : text;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Sending ──────────────────────────────────────────────────────────
    private void sendMessage() {
        if (inSelectionMode) exitSelectionMode();
        String text = etInput.getText().toString().trim();
        if (text.isEmpty() && pendingAttachmentPath == null) return;

        String attPath = pendingAttachmentPath;
        String attName = pendingAttachmentName;
        boolean attIsImage = pendingAttachmentIsImage;
        boolean attIsPdf = pendingAttachmentIsPdf;

        etInput.setText("");
        clearPendingAttachment();

        addUserBubble(-1, text, attPath, attName);
        saveMessage(ChatMessage.ROLE_USER, text, attPath, attName, null, null);

        Prepared prep = prepareAttachment(text, attPath, attName, attIsImage, attIsPdf);
        runTurn(text, prep.effectiveMessage, prep.imagePathForApi);
    }

    /**
     * One user turn — shared by fresh sends and Retry (same message, no duplicates).
     */
    private void runTurn(String userDisplay, String effectiveMessage, String imagePathForApi) {
        setBusy(true);
        final int myTurnId = turnCounter.incrementAndGet();
        activeTurnId = myTurnId;

        TextView typingText = new TextView(this);
        typingBubble = addBotBubbleView(typingText, "Thinking…");
        typingTextView = typingText;

        // Token optimization: bounded recent-context window instead of the
        // whole (ever-growing) conversation.
        JSONArray historyWindow = new JSONArray();
        int from = Math.max(0, conversation.length() - HISTORY_WINDOW);
        for (int i = from; i < conversation.length(); i++) {
            try {
                historyWindow.put(conversation.getJSONObject(i));
            } catch (Exception ignored) {
            }
        }

        // remember how to reproduce this exact turn for Retry
        lastFailedUserDisplay = userDisplay;
        lastFailedEffective = effectiveMessage;
        lastFailedImagePath = imagePathForApi;

        // Remember the user turn in neutral history (deduped so a Retry after
        // a failure never records the same message twice).
        if (conversation.length() == 0 || !lastNeutralIs("user", userDisplay)) {
            appendNeutral("user", userDisplay);
        }

        final AiRequest request = new AiRequest(effectiveMessage, imagePathForApi, historyWindow, userDisplay);
        final boolean[] answered = {false};
        final android.os.Handler wh = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable watchdog = () -> {
            if (!answered[0]) {
                answered[0] = true;
                runOnUiThread(() -> {
                    // Turn-ah abandon pannுрோm, aana activeTurnId idhே vachchே
                    // vекkanum (myTurnId decrement pannадhу) — pazhaya thread
                    // eppadiyாவும் late-ah finish aana apparam "stale"-ah
                    // theriyanum, illainaale removeTypingBubble() adhoda OWN
                    // typing bubble-ah correct-ah remove pannuridும், aana
                    // andha bubble ippODhu screen-la illa (already removed
                    // idhே watchdog-la) — so andha late call no-op aagum.
                    removeTypingBubble();
                    addErrorBubble("No response after 90s — the provider didn't answer in time. (It may still complete in the background — please wait a moment before retrying.)");
                    setBusy(false);
                });
            }
        };

        wh.postDelayed(watchdog, 90000);

        new Thread(() -> {
            try {
                String answer = failover.runTurn(request, new AiProviderCallbackBridge(typingText), null);
                List<String> charts = failover.getLastChartPaths();
                String genImage = failover.getLastImagePath();
                answered[0] = true;
                wh.removeCallbacks(watchdog);
                runOnUiThread(() -> {
                    if (myTurnId != activeTurnId)
                        return; // watchdog already gave up on this turn — ignore the late result
                    lastFailedUserDisplay = null; // success clears the retry state
                    lastFailedEffective = null;
                    lastFailedImagePath = null;
                    removeTypingBubble();
                    String first = genImage != null ? genImage : (charts.isEmpty() ? null : charts.get(0));
                    int storedId = saveMessage(ChatMessage.ROLE_ASSISTANT, answer, null, null, first, activeProvider());
                    addBotBubble(storedId, answer, charts);
                    appendNeutral("assistant", answer);
                    setBusy(false);
                });
            } catch (AiException e) {
                answered[0] = true;
                wh.removeCallbacks(watchdog);
                runOnUiThread(() -> {
                    if (myTurnId != activeTurnId)
                        return; // stale — the UI already moved past this turn
                    removeTypingBubble();
                    addErrorBubble(e.getMessage());
                    setBusy(false);
                });
            } catch (Exception e) {
                answered[0] = true;
                wh.removeCallbacks(watchdog);
                runOnUiThread(() -> {
                    if (myTurnId != activeTurnId) return; // stale
                    removeTypingBubble();
                    addErrorBubble("Something went wrong — please retry.");
                    setBusy(false);
                });
            }
        }).start();
    }

    private void setBusy(boolean busy) {
        requestInFlight = busy;
        btnSend.setEnabled(!busy);
        btnSend.setAlpha(busy ? 0.5f : 1f);
        setRetryEnabled(!busy);
    }

    private void setRetryEnabled(boolean on) {
        if (retryButton != null) retryButton.setEnabled(on);
    }

    private void removeTypingBubble() {
        if (typingBubble != null) {
            messagesContainer.removeView(typingBubble);
            typingBubble = null;
            typingTextView = null;
        }
    }

    // ── Retry: same message, no duplicate user bubble/row ─────────────────
    private void retryLastFailure() {
        if (requestInFlight) return; // no parallel requests
        if (lastFailedUserDisplay == null) return;
        String display = lastFailedUserDisplay;
        String effective = lastFailedEffective;
        String image = lastFailedImagePath;
        runTurn(display, effective, image);
    }

    // ── Error bubble with Retry (ChatGPT/Claude style) ──────────────────────
    private void addErrorBubble(String safeMessage) {
        LinearLayout col = bubbleColumn(false);
        TextView tv = new TextView(this);
        tv.setText("⚠ AI response failed.\n" + (safeMessage == null ? "" : safeMessage));
        tv.setTextSize(14);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        tv.setBackgroundResource(R.drawable.bg_chat_bubble_bot);
        tv.setTextColor(ContextCompat.getColor(this, R.color.text));
        col.addView(tv);

        Button retry = new Button(this, null, 0);
        retry.setText("Retry");
        retry.setTextSize(13);
        retry.setAllCaps(false);
        retry.setTextColor(ContextCompat.getColor(this, R.color.primary));
        retry.setBackgroundResource(R.drawable.bg_retry_button);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(32));
        rlp.topMargin = dp(6);
        retry.setLayoutParams(rlp);
        retry.setOnClickListener(v -> {
            if (requestInFlight) return; // prevent simultaneous retries
            messagesContainer.removeView(col); // clear the error state bubble
            retryLastFailure();
        });
        col.addView(retry);
        retryButton = retry;

        messagesContainer.addView(col);
        scrollToBottom();
    }

    /**
     * Bridges provider progress callbacks to the typing bubble.
     */
    private class AiProviderCallbackBridge implements com.expenseos.util.AiProvider.Callback {
        private final TextView typingText;

        AiProviderCallbackBridge(TextView typingText) {
            this.typingText = typingText;
        }

        @Override
        public void onResult(String answer) {
        }

        @Override
        public void onError(String message) {
            // Unused — the failover manager surfaces errors via exceptions.
        }

        @Override
        public void onProgress(String stage) {
            runOnUiThread(() -> {
                typingText.setText(stage);
                scrollToBottom();
            });
        }
    }


    /**
     * True if the newest neutral-history entry is role with exactly this text.
     */
    private boolean lastNeutralIs(String role, String text) {
        try {
            if (conversation.length() == 0) return false;
            JSONObject last = conversation.getJSONObject(conversation.length() - 1);
            return role.equals(last.optString("role")) && text.equals(last.optString("text"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Appends one neutral turn to the shared history.
     */
    private void appendNeutral(String role, String text) {
        try {
            JSONObject e = new JSONObject();
            e.put("role", role);
            e.put("text", text == null ? "" : text);
            conversation.put(e);
        } catch (Exception ignored) {
        }
    }

    private String activeProvider() {
        try {
            return new AiConfigStore(this).load().activeProvider;
        } catch (Exception e) {
            return AppConfig.get(this).getAiProvider();
        }
    }

    // ── Attachment → request prep (shared by send; retry reuses stored result) ──
    private static class Prepared {
        String effectiveMessage;
        String imagePathForApi;
    }

    private Prepared prepareAttachment(String text, String attPath, String attName, boolean attIsImage, boolean attIsPdf) {
        Prepared p = new Prepared();
        p.effectiveMessage = text;
        if (attPath == null) return p;
        if (attIsImage) {
            p.imagePathForApi = attPath;
        } else if (attIsPdf) {
            String pngPath = PdfAttachmentReader.renderFirstPageToFile(this, attPath);
            if (pngPath != null) {
                p.imagePathForApi = pngPath;
                p.effectiveMessage = (text.isEmpty() ? "Please read this PDF." : text)
                        + "\n\n[Attached PDF: " + attName + " — first page rendered for vision.]";
            } else {
                p.effectiveMessage = (text.isEmpty() ? "" : text + " ")
                        + "[Attached PDF: " + attName + " — could not render.]";
            }
        } else {
            String extracted = readSmallTextFile(attPath);
            if (extracted != null) {
                p.effectiveMessage = (text.isEmpty() ? "Please look at this attached file." : text)
                        + "\n\n[Attached file: " + attName + "]\n" + extracted;
            } else {
                p.effectiveMessage = (text.isEmpty() ? "" : text + " ")
                        + "[User attached a file named \"" + attName + "\" — its content couldn't be read inline.]";
            }
        }
        return p;
    }

    // ── Persistence ──────────────────────────────────────────────────────
    private int saveMessage(String role, String content, String attPath, String attName, String chartPath, String provider) {
        ChatMessage m = new ChatMessage();
        m.setRole(role);
        m.setContent(content);
        m.setAttachmentPath(attPath);
        m.setAttachmentName(attName);
        m.setChartPath(chartPath);
        m.setProvider(provider);
        m.setSessionId(currentSessionId);
        return (int) historyDao.insert(m);
    }

    // ── Bubble rendering ──────────────────────────────────────────────
    private int addUserBubble(int storedId, String text, String attachmentPath, String attachmentName) {
        LinearLayout col = bubbleColumn(false);
        if (attachmentPath != null) {
            if (isImagePath(attachmentPath) || attachmentPath.toLowerCase(Locale.ROOT).endsWith(".png")) {
                col.addView(imageView(attachmentPath));
            } else {
                col.addView(fileChip(attachmentName));
            }
        }
        if (text != null && !text.isEmpty()) {
            TextView userBubble = textBubble(text, true);
            LinearLayout rightCol = new LinearLayout(this);
            rightCol.setOrientation(LinearLayout.VERTICAL);
            rightCol.setGravity(Gravity.END);
            rightCol.addView(userBubble);
            col.addView(rightCol);
        }
        int id = storedId < 0 ? -((int) java.util.UUID.randomUUID().hashCode()) : storedId;
        attachCopyDeleteMenu(col, storedId, text == null ? "" : text);
        attachLongPress(col, storedId);
        messagesContainer.addView(col);
        if (storedId > 0) bubbleViews.put(storedId, col);
        scrollToBottom();
        return storedId;
    }

    private int addBotBubble(String text, String chartPath) {
        return addBotBubble(-1, text, chartPath);
    }

    private int addBotBubble(int storedId, String text, String chartPath) {
        return addBotBubble(storedId, text, chartPath == null ? null : java.util.Collections.singletonList(chartPath));
    }

    private int addBotBubble(int storedId, String text, java.util.List<String> chartPaths) {
        LinearLayout col = bubbleColumn(false);
        if (text != null && !text.isEmpty()) {
            MarkdownRenderer.render(this, col, text);
        }
        if (chartPaths != null) {
            for (String p : chartPaths) {
                if (p == null) continue;
                col.addView(imageView(p));
            }
        }
        attachCopyDeleteMenu(col, storedId, text == null ? "" : text);
        attachLongPress(col, storedId);
        messagesContainer.addView(col);
        if (storedId > 0) bubbleViews.put(storedId, col);
        scrollToBottom();
        return storedId;
    }

    private View addBotBubbleView(TextView reusableTextView, String initialText) {
        LinearLayout col = bubbleColumn(false);
        reusableTextView.setText(initialText);
        reusableTextView.setTextSize(14);
        reusableTextView.setPadding(dp(12), dp(8), dp(12), dp(8));
        reusableTextView.setBackgroundResource(R.drawable.bg_chat_bubble_bot);
        reusableTextView.setTextColor(ContextCompat.getColor(this, R.color.text));
        col.addView(reusableTextView);
        // No long-press on the typing bubble — it isn't tracked for delete
        messagesContainer.addView(col);
        scrollToBottom();
        return col;
    }

    private LinearLayout bubbleColumn(boolean isUser) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = isUser ? Gravity.END : Gravity.START;
        lp.topMargin = dp(6);
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(8);
        col.setLayoutParams(lp);
        return col;
    }

    private void attachLongPress(View v, int storedId) {
        if (storedId <= 0) return;
        v.setLongClickable(true);
        v.setOnLongClickListener(e -> {
            enterSelectionMode(storedId);
            return true;
        });
    }

    /**
     * Three-dot affordance under the bubble: tap bubble to reveal, ⋮ for Copy/Delete.
     */
    private void attachCopyDeleteMenu(LinearLayout v, int storedId, String text) {
        LinearLayout menuRow = new LinearLayout(this);
        menuRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView more = new TextView(this);
        more.setText("⋮");
        more.setTextSize(16);
        more.setPadding(dp(6), 0, dp(6), 0);
        more.setAlpha(0.55f);
        more.setOnClickListener(x -> showCopyDeleteMenu(storedId, text));
        menuRow.addView(more);
        menuRow.setVisibility(View.GONE);
        v.addView(menuRow);
        v.setOnClickListener(x -> menuRow.setVisibility(
                menuRow.getVisibility() == View.GONE ? View.VISIBLE : View.GONE));
    }

    private TextView textBubble(String text, boolean isUser) {
        TextView tv = new TextView(this);
        tv.setText(MarkdownRenderer.applyInlineSpans(text));
        tv.setTextSize(14);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        tv.setBackgroundResource(isUser ? R.drawable.bg_chat_bubble_user : R.drawable.bg_chat_bubble_bot);
        tv.setTextColor(ContextCompat.getColor(this, isUser ? android.R.color.white : R.color.text));
        tv.setTextIsSelectable(true);
        return tv;
    }

    private ImageView imageView(String path) {
        ImageView iv = new ImageView(this);
        Bitmap bmp = BitmapFactory.decodeFile(path);
        if (bmp != null) {
            int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.85f);
            if (bmp.getWidth() > maxW) {
                int newH = (int) (bmp.getHeight() * ((float) maxW / bmp.getWidth()));
                bmp = Bitmap.createScaledBitmap(bmp, maxW, newH, true);
            }
        }
        iv.setImageBitmap(bmp);
        iv.setAdjustViewBounds(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        iv.setLayoutParams(lp);
        iv.setOnClickListener(v -> openImageExternally(path));
        return iv;
    }

    private TextView fileChip(String fileName) {
        TextView tv = new TextView(this);
        tv.setText("📄 " + (fileName != null ? fileName : "file"));
        tv.setTextSize(13);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        tv.setBackgroundResource(R.drawable.bg_chat_bubble_user);
        tv.setTextColor(ContextCompat.getColor(this, android.R.color.white));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        lp.topMargin = dp(2);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void openImageExternally(String path) {
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", new File(path));
            Intent i = new Intent(Intent.ACTION_VIEW);
            String lower = path.toLowerCase(Locale.ROOT);
            String mime = lower.endsWith(".pdf") ? "application/pdf" : "image/*";
            i.setDataAndType(uri, mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    private boolean isImagePath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp");
    }

    private void scrollToBottom() {
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
