package com.expenseos.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
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
    private final JSONArray conversation = new JSONArray(); // running provider-format history (in-memory, this session)

    private String pendingAttachmentPath;  // absolute path in app files dir, once copied
    private String pendingAttachmentName;
    private boolean pendingAttachmentIsImage;

    private final ActivityResultLauncher<String[]> filePicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) handlePickedFile(uri);
            });

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
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

        findViewById(R.id.btnChatBack).setOnClickListener(v -> finish());
        btnSend.setOnClickListener(v -> sendMessage());
        btnAttach.setOnClickListener(v -> filePicker.launch(new String[]{"image/*", "application/pdf", "text/plain", "text/csv"}));
        btnAttachRemove.setOnClickListener(v -> clearPendingAttachment());

        loadHistory();
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
            // Rebuild the in-memory provider-format history so the model
            // still has context from earlier turns this session. Kept simple
            // (plain role/content) — providers normalize this on first use.
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

        String attPath = pendingAttachmentPath;
        String attName = pendingAttachmentName;
        boolean attIsImage = pendingAttachmentIsImage;

        etInput.setText("");
        clearPendingAttachment();

        addUserBubble(text, attPath, attName);
        saveMessage(ChatMessage.ROLE_USER, text, attPath, attName, null, null);

        // Non-image text attachments (csv/txt) — read as text and fold into
        // the prompt as context, since these providers' tool-calling APIs
        // don't take arbitrary file uploads the way images do.
        String effectiveMessage = text;
        String imagePathForApi = null;
        if (attPath != null) {
            if (attIsImage) {
                imagePathForApi = attPath;
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

        btnSend.setEnabled(false);
        View typing = addBotBubble("…thinking", null);

        String finalMessage = effectiveMessage;
        String finalImagePath = imagePathForApi;
        new Thread(() -> aiClient.ask(finalMessage, finalImagePath, conversation, new AiProvider.Callback() {
            @Override
            public void onResult(String answer) {
                String chartPath = aiClient.getLastChartPath();
                runOnUiThread(() -> {
                    messagesContainer.removeView(typing);
                    addBotBubble(answer, chartPath);
                    saveMessage(ChatMessage.ROLE_ASSISTANT, answer, null, null, chartPath, AppConfig.get(ChatActivity.this).getAiProvider());
                    btnSend.setEnabled(true);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    messagesContainer.removeView(typing);
                    addBotBubble("⚠ " + message, null);
                    btnSend.setEnabled(true);
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
        tv.setText(text);
        tv.setTextSize(14);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        tv.setBackgroundResource(isUser ? R.drawable.bg_chat_bubble_user : R.drawable.bg_chat_bubble_bot);
        tv.setTextColor(getColor(isUser ? android.R.color.white : R.color.text));
        return tv;
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