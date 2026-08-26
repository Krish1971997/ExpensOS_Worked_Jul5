package com.expenseos.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.expenseos.R;
import com.expenseos.util.OpenAiClient;

import org.json.JSONArray;

public class ChatActivity extends AppCompatActivity {

    private LinearLayout messagesContainer;
    private ScrollView scrollView;
    private EditText etInput;
    private OpenAiClient aiClient;
    private final JSONArray conversation = new JSONArray(); // running message history

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_chat);

        aiClient = new OpenAiClient(this);
        messagesContainer = findViewById(R.id.chatMessagesContainer);
        scrollView = findViewById(R.id.chatScrollView);
        etInput = findViewById(R.id.etChatInput);

        findViewById(R.id.btnChatBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnChatSend).setOnClickListener(v -> sendMessage());

        addBotBubble("Ask me anything about your ExpenseOS data — spending, categories, budgets, backups, etc.");
    }

    private void sendMessage() {
        String text = etInput.getText().toString().trim();
        if (text.isEmpty()) return;
        etInput.setText("");
        addUserBubble(text);

        ImageButton btnSend = findViewById(R.id.btnChatSend);
        btnSend.setEnabled(false);
        View typingIndicator = addBotBubble("…thinking");

        new Thread(() -> aiClient.ask(text, conversation, new OpenAiClient.Callback() {
            @Override
            public void onResult(String answer) {
                runOnUiThread(() -> {
                    messagesContainer.removeView(typingIndicator);
                    addBotBubble(answer);
                    btnSend.setEnabled(true);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    messagesContainer.removeView(typingIndicator);
                    addBotBubble("⚠ " + message);
                    btnSend.setEnabled(true);
                });
            }
        })).start();
    }

    private View addUserBubble(String text) {
        return addBubble(text, true);
    }

    private View addBotBubble(String text) {
        return addBubble(text, false);
    }

    private View addBubble(String text, boolean isUser) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        tv.setBackgroundResource(isUser ? R.drawable.bg_chat_bubble_user : R.drawable.bg_chat_bubble_bot);
        tv.setTextColor(getColor(isUser ? android.R.color.white : R.color.text));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = isUser ? android.view.Gravity.END : android.view.Gravity.START;
        lp.topMargin = dp(6);
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(8);
        tv.setLayoutParams(lp);

        messagesContainer.addView(tv);
        scrollView.post(() -> scrollView.fullScroll(android.view.View.FOCUS_DOWN));
        return tv;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}