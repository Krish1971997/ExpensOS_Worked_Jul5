package com.expenseos.util;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * In-memory console for real-time sync logging.
 * Observers (UI) are notified on every new log line.
 */
public class ConsoleLogger {

    public enum Level {INFO, SUCCESS, ERROR, WARN}

    public static class LogEntry {
        public final long timestamp;
        public final Level level;
        public final String message;

        public LogEntry(Level level, String message) {
            this.timestamp = System.currentTimeMillis();
            this.level = level;
            this.message = message;
        }

        public String formatted() {
            String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(new Date(timestamp));
            String prefix = switch (level) {
                case INFO -> "[INFO]   ";
                case SUCCESS -> "[✔ OK]   ";
                case ERROR -> "[✘ ERR]  ";
                case WARN -> "[⚠ WARN] ";
            };
            return ts + "  " + prefix + message;
        }
    }

    public interface Observer {
        void onNewLog(LogEntry entry);
    }

    private static ConsoleLogger instance;
    private final List<LogEntry> logs = new ArrayList<>();
    private final List<Observer> observers = new ArrayList<>();
    private static final int MAX_LOGS = 500;

    public static ConsoleLogger get() {
        if (instance == null) instance = new ConsoleLogger();
        return instance;
    }

    public void info(String msg) {
        add(Level.INFO, msg);
        Log.i("Console", msg);
    }

    public void success(String msg) {
        add(Level.SUCCESS, msg);
        Log.i("Console", msg);
    }

    public void error(String msg) {
        add(Level.ERROR, msg);
        Log.e("Console", msg);
    }

    public void warn(String msg) {
        add(Level.WARN, msg);
        Log.w("Console", msg);
    }

    private synchronized void add(Level level, String msg) {
        if (logs.size() >= MAX_LOGS) logs.remove(0);
        LogEntry entry = new LogEntry(level, msg);
        logs.add(entry);
        for (Observer obs : observers) obs.onNewLog(entry);
    }

    public synchronized List<LogEntry> getLogs() {
        return new ArrayList<>(logs);
    }

    public synchronized void clear() {
        logs.clear();
    }

    public void addObserver(Observer o) {
        observers.add(o);
    }

    public void removeObserver(Observer o) {
        observers.remove(o);
    }

    /**
     * Export all logs as a single string for copy/share
     */
    public String exportLogs() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ExpenseOS Sync Console Log ===\n\n");
        for (LogEntry e : getLogs()) {
            sb.append(e.formatted()).append("\n");
        }
        return sb.toString();
    }
}
