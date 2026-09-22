package com.expenseos.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.expenseos.R;
import com.expenseos.dao.ChatHistoryDao;
import com.expenseos.model.ChatMessage;
import com.expenseos.util.AiClientFactory;
import com.expenseos.util.AiProvider;
import com.expenseos.util.AppConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

public class ChatActivity extends AppCompatActivity {

    private LinearLayout messagesContainer;
    private ScrollView scrollView;
    private EditText etInput;
    private View attachPreviewRow;
    private TextView tvAttachName;
    private ImageButton btnSend, btnAttach, btnAttachRemove;

    private ChatHistoryDao historyDao;
    private AiProvider aiClient;
    private final JSONArray conversation = new JSONArray(); // provider-format history

    private String pendingAttachmentPath;
    private String pendingAttachmentName;
    private boolean pendingAttachmentIsImage;

    // In-flight turn bookkeeping — one question at a time, always resolvable.
    private boolean inFlight = false;
    private String lastText, lastAttPath;
    private boolean lastAttIsImage = false;

    private Handler uiHandler;
    private Runnable watchdog;
    private Runnable dotsAnim;
    private View typingBubble;
    private TextView typingText;

    private final ActivityResultLauncher<String[]> filePicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) handlePickedFile(uri);
            });

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_chat);

        uiHandler = new Handler(Looper.getMainLooper());
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

        findViewById(R.id.btnChatBack).setOnClickListener(v -> finish());
        btnSend.setOnClickListener(v -> sendMessage());
        btnAttach.setOnClickListener(v -> filePicker.launch(new String[]{"image/*", "application/pdf", "text/plain", "text/csv"}));
        btnAttachRemove.setOnClickListener(v -> clearPendingAttachment());

        loadHistory();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopTyping();
    }

    // ── History ──────────────────────────────────────────────────────
    private void loadHistory() {
        List<ChatMessage> history = historyDao.findAll();
        if (history.isEmpty()) {
            addBotBubble("Ask me anything about your ExpenseOS data — spending, categories, budgets, backups, etc. " +
                    "You can also attach a receipt image or a CSV/text file.", null);
            return;
        }
        for (ChatMessage m : history) {
            if (m.isUser()) {
                addUserBubble(m.getContent(), m.getAttachmentPath(), m.getAttachmentName());
            } else {
                addBotBubble(m.getContent(), m.getChartPath());
            }
        }
    }

    // ── Attachment picking ──────────────────────────────────────────
    private void handlePickedFile(Uri uri) {
        try {
            String name = queryFileName(uri);
            String mime = getContentResolver().getType(uri);
            boolean isImage = mime != null && mime.startsWith("image/");

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

            tvAttachName.setText((isImage ? "🖼 " : "📄 ") + pendingAttachmentName);
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
        attachPreviewRow.setVisibility(View.GONE);
    }

    // ── Sending ──────────────────────────────────────────────────────
    private void sendMessage() {
        String text = etInput.getText().toString().trim();
        if (text.isEmpty() && pendingAttachmentPath == null) return;
        if (inFlight) return; // one turn at a time — stops the duplicate-question pile-up

        String attPath = pendingAttachmentPath;
        String attName = pendingAttachmentName;
        boolean attIsImage = pendingAttachmentIsImage;

        etInput.setText("");
        clearPendingAttachment();

        addUserBubble(text, attPath, attName);
        saveMessage(ChatMessage.ROLE_USER, text, attPath, attName, null, null);

        lastText = text;
        lastAttPath = attPath;
        lastAttIsImage = attIsImage;

        runTurn(text, attPath, attName, attIsImage);
    }

    /** Re-runs the previous question without echoing the user bubble again. */
    private void retryLast() {
        if (inFlight || lastText == null) return;
        runTurn(lastText, lastAttPath, null, lastAttIsImage);
    }

    private void runTurn(String text, String attPath, String attName, boolean attIsImage) {
        String effectiveMessage = text;

        if (attPath != null) {
            if (attIsImage) {
                // image goes through as vision input below
            } else {
                String extracted = readSmallTextFile(attPath);
                if (extracted != null) {
                    effectiveMessage = (text.isEmpty() ? "Please look at this attached file." : text)
                            + "\n\n[Attached file: " + attName + "]\n" + extracted;
                } else {
                    effectiveMessage = (text.isEmpty() ? "" : text + " ")
                            + "[User attached a file named \"" + attName + "\" — its content couldn't be read inline, "
                            + "so only the filename is available. Answer using app data only.]";
                }
            }
        }

        inFlight = true;
        btnSend.setEnabled(false);
        btnSend.setAlpha(0.45f);

        startTyping("Contacting the assistant…");

        // Remember the turn so follow-ups keep context.
        try {
            JSONObject u = new JSONObject();
            u.put("role", "user");
            u.put("parts", new JSONArray().put(new JSONObject().put("text", effectiveMessage)));
            conversation.put(u);
        } catch (Exception ignored) {
        }

        final String finalMessage = effectiveMessage;
        final String finalImagePath = attIsImage ? attPath : null;

        new Thread(() -> aiClient.ask(finalMessage, finalImagePath, conversation, new AiProvider.Callback() {
            @Override
            public void onResult(String answer) {
                String chartPath = aiClient.getLastChartPath();
                String imagePath = aiClient.getLastImagePath();
                String pathToShow = imagePath != null ? imagePath : chartPath;
                String safeAnswer = (answer == null || answer.trim().isEmpty())
                        ? "I couldn't find anything to report for that." : answer;

                try {
                    JSONObject m = new JSONObject();
                    m.put("role", "model");
                    m.put("parts", new JSONArray().put(new JSONObject().put("text", safeAnswer)));
                    conversation.put(m);
                } catch (Exception ignored) {
                }

                runOnUiThread(() -> {
                    stopTyping();
                    addBotBubble(safeAnswer, pathToShow);
                    saveMessage(ChatMessage.ROLE_ASSISTANT, safeAnswer, null, null, pathToShow,
                            AppConfig.get(ChatActivity.this).getAiProvider());
                    endTurn();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    stopTyping();
                    addErrorBubble(message);
                    endTurn();
                });
            }

            @Override
            public void onProgress(String stage) {
                runOnUiThread(() -> {
                    if (typingText != null) typingText.setText(stage);
                    scrollToBottom();
                });
            }
        })).start();
    }

    private void endTurn() {
        inFlight = false;
        btnSend.setEnabled(true);
        btnSend.setAlpha(1f);
    }

    // ── Typing indicator (premium, animated) ────────────────────────
    private void startTyping(String initial) {
        stopTyping();

        LinearLayout col = bubbleColumn(false);
        typingText = new TextView(this);
        typingText.setText(initial);
        typingText.setTextSize(14);
        typingText.setPadding(dp(14), dp(10), dp(14), dp(10));
        typingText.setBackgroundResource(R.drawable.bg_chat_typing);
        typingText.setTextColor(getColor(R.color.text));
        col.addView(typingText);
        messagesContainer.addView(col);
        typingBubble = col;
        scrollToBottom();

        final int[] step = {0};
        dotsAnim = new Runnable() {
            @Override
            public void run() {
                if (typingText == null) return;
                String base = typingText.getText().toString().replaceAll("\\.+$", "");
                if (base.length() > 60) base = base.substring(0, 60);
                typingText.setText(base + ".".repeat(step[0] % 4));
                step[0]++;
                uiHandler.postDelayed(this, 450);
            }
        };
        uiHandler.postDelayed(dotsAnim, 450);

        // Hard stop: never leave the user staring at a dead "thinking" bubble.
        watchdog = () -> {
            if (inFlight) {
                stopTyping();
                addErrorBubble("No response in 60s — the assistant may be offline or your key/quota needs checking.");
                endTurn();
            }
        };
        uiHandler.postDelayed(watchdog, 60000);
    }

    private void stopTyping() {
        if (dotsAnim != null) uiHandler.removeCallbacks(dotsAnim);
        if (watchdog != null) uiHandler.removeCallbacks(watchdog);
        if (typingBubble != null) {
            messagesContainer.removeView(typingBubble);
            typingBubble = null;
        }
        typingText = null;
        dotsAnim = null;
        watchdog = null;
    }

    private String readSmallTextFile(String path) {
        try {
            String lower = path.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".txt") && !lower.endsWith(".csv")) return null;
            byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            return text.length() > 6000 ? text.substring(0, 6000) + "\n…(truncated)" : text;
        } catch (Exception e) {
            return null;
        }
    }

    private void saveMessage(String role, String content, String attPath, String attName, String chartPath, String provider) {
        ChatMessage m = new ChatMessage();
        m.setRole(role);
        m.setContent(content);
        m.setAttachmentPath(attPath);
        m.setAttachmentName(attName);
        m.setChartPath(chartPath);
        m.setProvider(provider);
        historyDao.insert(m);
    }

    // ── Bubble rendering ─────────────────────────────────────────────
    private View addUserBubble(String text, String attachmentPath, String attachmentName) {
        LinearLayout col = bubbleColumn(true);

        if (attachmentPath != null) {
            if (isImagePath(attachmentPath)) {
                col.addView(imageView(attachmentPath));
            } else {
                col.addView(fileChip(attachmentName));
            }
        }
        if (text != null && !text.isEmpty()) {
            col.addView(textBubble(text, true));
        }
        messagesContainer.addView(col);
        scrollToBottom();
        return col;
    }

    private View addBotBubble(String text, String chartPath) {
        LinearLayout col = bubbleColumn(false);
        if (text != null && !text.isEmpty()) {
            col.addView(textBubble(text, false));
        }
        if (chartPath != null) {
            col.addView(imageView(chartPath));
        }
        messagesContainer.addView(col);
        scrollToBottom();
        return col;
    }

    /** Error bubble that always offers a one-tap Retry — no dead ends. */
    private View addErrorBubble(String message) {
        LinearLayout col = bubbleColumn(false);
        TextView tv = textBubble("⚠ " + (message == null ? "Something went wrong." : message), false);
        col.addView(tv);

        TextView retry = new TextView(this);
        retry.setText("↻ Retry");
        retry.setTextSize(13);
        retry.setPadding(dp(16), dp(8), dp(16), dp(8));
        retry.setBackgroundResource(R.drawable.bg_filter_chip);
        retry.setTextColor(getColor(R.color.primary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        retry.setLayoutParams(lp);
        retry.setOnClickListener(v -> retryLast());
        col.addView(retry);

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

    private TextView textBubble(String text, boolean isUser) {
        TextView tv = new TextView(this);
        tv.setText(renderSimpleMarkdown(text));
        tv.setTextSize(14);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        tv.setBackgroundResource(isUser ? R.drawable.bg_chat_bubble_user : R.drawable.bg_chat_bubble_bot);
        tv.setTextColor(getColor(isUser ? android.R.color.white : R.color.text));
        tv.setTextIsSelectable(true);
        return tv;
    }

    private CharSequence renderSimpleMarkdown(String text) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\*\\*(.+?)\\*\\*|\\*(.+?)\\*")
                .matcher(text);
        int last = 0;
        while (m.find()) {
            sb.append(text, last, m.start());
            boolean bold = m.group(1) != null;
            String inner = bold ? m.group(1) : m.group(2);
            int start = sb.length();
            sb.append(inner);
            sb.setSpan(new android.text.style.StyleSpan(
                            bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.ITALIC),
                    start, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            last = m.end();
        }
        sb.append(text, last, text.length());
        return sb;
    }

    private ImageView imageView(String path) {
        ImageView iv = new ImageView(this);
        Bitmap bmp = BitmapFactory.decodeFile(path);
        iv.setImageBitmap(bmp);
        iv.setAdjustViewBounds(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.WRAP_CONTENT);
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
        tv.setTextColor(getColor(android.R.color.white));
        return tv;
    }

    private void openImageExternally(String path) {
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", new File(path));
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "image/*");
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
