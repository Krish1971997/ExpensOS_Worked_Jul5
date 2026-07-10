package com.expenseos.ui.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.expenseos.R;
import com.expenseos.util.ConsoleLogger;

import java.util.List;

public class ConsoleFragment extends Fragment implements ConsoleLogger.Observer {

    private TextView tvConsole;
    private ScrollView scrollView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_console, container, false);

        tvConsole = root.findViewById(R.id.tv_console_output);
        scrollView = root.findViewById(R.id.scroll_console);

        tvConsole.setTypeface(Typeface.MONOSPACE);
        tvConsole.setTextSize(12f);
        tvConsole.setTextColor(Color.parseColor("#E0E0E0"));

        Button btnClear = root.findViewById(R.id.btn_clear_console);
        Button btnCopy = root.findViewById(R.id.btn_copy_console);

        btnClear.setOnClickListener(v -> {
            ConsoleLogger.get().clear();
            tvConsole.setText("Console cleared.\n");
        });

        btnCopy.setOnClickListener(v -> {
            String logs = ConsoleLogger.get().exportLogs();
            ClipboardManager cm = (ClipboardManager)
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("ExpenseOS Console Log", logs));
            Toast.makeText(getContext(), "✔ Console log copied to clipboard",
                    Toast.LENGTH_SHORT).show();
        });

        // Render existing logs
        renderAllLogs();

        // Register observer for live updates
        ConsoleLogger.get().addObserver(this);
        return root;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        ConsoleLogger.get().removeObserver(this);
    }

    @Override
    public void onNewLog(ConsoleLogger.LogEntry entry) {
        mainHandler.post(() -> appendLog(entry));
    }

    private void renderAllLogs() {
        List<ConsoleLogger.LogEntry> logs = ConsoleLogger.get().getLogs();
        StringBuilder sb = new StringBuilder();
        for (ConsoleLogger.LogEntry e : logs) sb.append(e.formatted()).append("\n");
        tvConsole.setText(sb.length() > 0 ? sb.toString() : "No logs yet. Start a sync to see output here.\n");
        scrollToBottom();
    }

    private void appendLog(ConsoleLogger.LogEntry entry) {
        String current = tvConsole.getText().toString();
        if (current.startsWith("No logs")) current = "";
        tvConsole.setText(current + entry.formatted() + "\n");

        // Color the last line based on level
        int color = switch (entry.level) {
            case SUCCESS -> Color.parseColor("#4CAF50");
            case ERROR -> Color.parseColor("#F44336");
            case WARN -> Color.parseColor("#FFC107");
            default -> Color.parseColor("#E0E0E0");
        };
        tvConsole.setTextColor(Color.parseColor("#E0E0E0")); // reset bulk
        scrollToBottom();
    }

    private void scrollToBottom() {
        scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }
}
