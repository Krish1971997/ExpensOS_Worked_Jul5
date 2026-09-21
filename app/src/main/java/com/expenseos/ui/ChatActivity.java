package com.expenseos.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.SpannableStringBuilder;
import android.view.Gravity;
import android.view.View;
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
import com.expenseos.util.AiClientFactory;
import com.expenseos.util.AiProvider;
import com.expenseos.util.AppConfig;
import com.expenseos.util.MarkdownRenderer;
import com.expenseos.util.PdfAttachmentReader;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Chat with the in-app AI Assistant.
 * - Multi-select delete (long-press), new-chat, history
 * - Markdown-rendered bot bubbles (### heading, **bold**, - bullet, --- divider)
 * - Inline image / PDF attachments + preview
 * - Multiple chart paths per turn (day-wise + category-wise)
 * - Sessions isolated by session_id (multi-chat like claude / chatgpt)
 */
public class ChatActivity extends AppCompatActivity {

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
    private AiProvider aiClient;
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
        aiClient = AiClientFactory.create(this);

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
        btnSend.setOnClickListener(v -> sendMessage());
        btnAttach.setOnClickListener(v -> filePicker.launch(new String[]{"image/*", "application/pdf", "text/plain", "text/csv"}));
        btnAttachRemove.setOnClickListener(v -> clearPendingAttachment());
        btnNew.setOnClickListener(v -> startNewChat());
        btnHistory.setOnClickListener(v -> showHistory());
        btnMultiDelete.setOnClickListener(v -> deleteSelected());
        btnSelectAll.setOnClickListener(v -> toggleSelectAll());
        btnCancelSelection.setOnClickListener(v -> exitSelectionMode());

        loadSession("default");
    }

    // ── Session loading ──────────────────────────────────────────────────────
    private void loadSession(String sessionId) {
        currentSessionId = sessionId == null ? "default" : sessionId;
        messagesContainer.removeAllViews();
        bubbleViews.clear();
        conversation.length(); // keep array; it's rebuilt on sendMessage anyway

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
            // Rebuild the in-memory provider-format history
        }
    }

    private void startNewChat() {
        // No board-level delete — just stamp a fresh session and clear the view.
        // Future messages land in this new session; old sessions preserved for History.
        currentSessionId = "session_" + System.currentTimeMillis();
        messagesContainer.removeAllViews();
        bubbleViews.clear();
        conversation.length();
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

    private View getOrCreateBubbleView(int id, java.util.function.Supplier<View> factory) {
        View existing = bubbleViews.get(id);
        if (existing != null) return existing;
        View v = factory.get();
        bubbleViews.put(id, v);
        return v;
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

        String effectiveMessage = text;
        String imagePathForApi = null;
        if (attPath != null) {
            if (attIsImage) {
                imagePathForApi = attPath;
            } else if (attIsPdf) {
                // Render the first page as a PNG, and pass it as the vision image.
                // Also annotate the prompt so the AI knows there's a PDF attached.
                String pngPath = PdfAttachmentReader.renderFirstPageToFile(this, attPath);
                if (pngPath != null) {
                    imagePathForApi = pngPath;
                    effectiveMessage = (text.isEmpty() ? "Please read this PDF." : text)
                            + "\n\n[Attached PDF: " + attName + " — first page rendered for vision.]";
                } else {
                    effectiveMessage = (text.isEmpty() ? "" : text + " ")
                            + "[Attached PDF: " + attName + " — could not render.]";
                }
            } else {
                String extracted = readSmallTextFile(attPath);
                if (extracted != null) {
                    effectiveMessage = (text.isEmpty() ? "Please look at this attached file." : text)
                            + "\n\n[Attached file: " + attName + "]\n" + extracted;
                } else {
                    effectiveMessage = (text.isEmpty() ? "" : text + " ")
                            + "[User attached a file named \"" + attName + "\" — its content couldn't be read inline.]";
                }
            }
        }

        btnSend.setEnabled(false);
        final boolean[] answered = {false};
        final android.os.Handler wh = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable watchdog = () -> {
            if (!answered[0]) {
                addBotBubble("⚠ No response after 90s. Check your AI provider + API key in Config, then resend.", null);
                btnSend.setEnabled(true);
            }
        };
        wh.postDelayed(watchdog, 90000);
        TextView typingText = new TextView(this);
        View typing = addBotBubbleView(typingText, "Thinking…");

        String finalMessage = effectiveMessage;
        String finalImagePath = imagePathForApi;
        new Thread(() -> aiClient.ask(finalMessage, finalImagePath, conversation, new AiProvider.Callback() {
            @Override
            public void onResult(String answer) {
                answered[0] = true;
                wh.removeCallbacks(watchdog);
                java.util.List<String> charts = aiClient.getLastChartPaths();
                String imagePath = aiClient.getLastImagePath();
                runOnUiThread(() -> {
                    messagesContainer.removeView(typing);
                    // First previewable path wins as the inline chart "title" saved
                    // record, but we render ALL chart paths (multi-chart bubble).
                    String first = imagePath != null ? imagePath : (charts.isEmpty() ? null : charts.get(0));
                    int storedId = saveMessage(ChatMessage.ROLE_ASSISTANT, answer, null, null, first, AppConfig.get(ChatActivity.this).getAiProvider());
                    addBotBubble(storedId, answer, charts);
                    btnSend.setEnabled(true);
                });
            }

            @Override
            public void onError(String message) {
                answered[0] = true;
                wh.removeCallbacks(watchdog);
                runOnUiThread(() -> {
                    messagesContainer.removeView(typing);
                    addBotBubble(-1, "⚠ " + message, (String) null);
                    btnSend.setEnabled(true);
                });
            }

            @Override
            public void onProgress(String stage) {
                runOnUiThread(() -> {
                    typingText.setText(stage);
                    scrollToBottom();
                });
            }
        })).start();
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
            // wrap in a sub-layout so image above can float
            LinearLayout rightCol = new LinearLayout(this);
            rightCol.setOrientation(LinearLayout.VERTICAL);
            rightCol.setGravity(Gravity.END);
            rightCol.addView(userBubble);
            col.addView(rightCol);
        }
        int id = storedId < 0 ? -((int) java.util.UUID.randomUUID().hashCode()) : storedId;
        attachLongPress(col, id);
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
            // Render the markdown subset (###, **bold**, - bullet, --- divider)
            MarkdownRenderer.render(this, col, text);
        }
        if (chartPaths != null) {
            for (String p : chartPaths) {
                if (p == null) continue;
                col.addView(imageView(p));
            }
        }
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
        // No long-press on the typing bubble — give it an artificial id so we don't track it
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
        v.setLongClickable(true);
        v.setOnLongClickListener(e -> {
            if (storedId > 0) enterSelectionMode(storedId);
            return true;
        });
    }

    private TextView textBubble(String text, boolean isUser) {
        TextView tv = new TextView(this);
        // If MarkdownRenderer has a static CharSequence method:
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
        // Cap max width so very large charts still fit screen
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
            // Guess mime — pdf read view otherwise image viewer
            String lower = path.toLowerCase(Locale.ROOT);
            String mime = lower.endsWith(".pdf") ? "application/pdf"
                    : "image/*";
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

    public static SpannableStringBuilder applyInlineSpansStatic(String text) {
        if (text == null) return new SpannableStringBuilder("");
        SpannableStringBuilder ssb = new SpannableStringBuilder(text);
        // Apply bold / italic spans if needed or return formatted builder
        return ssb;
    }
}