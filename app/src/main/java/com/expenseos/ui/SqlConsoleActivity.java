package com.expenseos.ui;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.expenseos.R;
import com.expenseos.db.LocalDB;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Developer SQL console for the app's local SQLite DB.
 * <p>
 * SELECT runs read-only and renders a result table.
 * INSERT/UPDATE/DELETE execute inside a manually-managed transaction: the
 * statement runs immediately but is NOT committed until you tap Commit.
 * Tap Rollback — or let the {@link #TXN_TIMEOUT_MS} countdown expire — to
 * discard it. Only SELECT/INSERT/UPDATE/DELETE are accepted; this is a data
 * console, not a migration tool, so schema/pragma statements are rejected.
 * <p>
 * This bypasses every DAO-level validation and audit-log entry in the app —
 * treat it as a raw escape hatch for support/debugging, not a UI users hit
 * in the normal course of using the app.
 */
public class SqlConsoleActivity extends AppCompatActivity {

    private static final long TXN_TIMEOUT_MS = 60_000L; // auto-rollback if not committed within this

    private SQLiteDatabase db;
    private EditText etSql;
    private TextView tvStatus, tvTimer;
    private Button btnCommit, btnRollback;
    private TableLayout resultTable;
    private HorizontalScrollView resultScroll;

    // NEW
    private boolean txnPending = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;
    private Runnable tickRunnable;
    private long txnDeadline;

    // ── SELECT pagination — pgAdmin-style, 100 rows a page ────
    private static final int PAGE_SIZE = 100;
    private String lastSelectSql;
    private int currentPage = 0;
    private int totalRows = 0;
    private LinearLayout paginationRow;
    private TextView tvPageInfo;
    private Button btnPrevPage, btnNextPage;

    // ── Query history — lets you switch back to an earlier SELECT after
    // running an UPDATE, without retyping it. Newest first, deduped, capped.
    private static final int MAX_HISTORY = 10;
    private final List<String> queryHistory = new ArrayList<>();
    private HorizontalScrollView scrollHistory;
    private LinearLayout llHistory;

    // ── Autocomplete ─────────────────────────────────────
    private static final String[] SQL_KEYWORDS = {"SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "AND", "OR", "NOT", "NULL", "IN", "LIKE", "LIMIT", "OFFSET", "ORDER", "BY", "GROUP", "ASC", "DESC", "AS", "JOIN", "LEFT", "INNER", "ON", "DISTINCT", "COUNT", "SUM", "AVG", "MAX", "MIN"};
    private final List<String> tableNames = new ArrayList<>();
    private final List<String> allColumns = new ArrayList<>(); // deduped, across every table
    private HorizontalScrollView scrollSuggestions;
    private LinearLayout llSuggestions;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_sql_console);

        db = LocalDB.getInstance(this).getWritableDatabase();

        etSql = findViewById(R.id.etSqlInput);
        tvStatus = findViewById(R.id.tvSqlStatus);
        tvTimer = findViewById(R.id.tvSqlTimer);
        btnCommit = findViewById(R.id.btnSqlCommit);
        btnRollback = findViewById(R.id.btnSqlRollback);
        resultTable = findViewById(R.id.tableSqlResults);
        resultScroll = findViewById(R.id.scrollSqlResults);
        paginationRow = findViewById(R.id.rowSqlPagination);
        tvPageInfo = findViewById(R.id.tvSqlPageInfo);
        btnPrevPage = findViewById(R.id.btnSqlPrevPage);
        btnNextPage = findViewById(R.id.btnSqlNextPage);
        btnPrevPage.setOnClickListener(v -> loadPage(currentPage - 1));
        btnNextPage.setOnClickListener(v -> loadPage(currentPage + 1));

        findViewById(R.id.btnSqlBack).setOnClickListener(v -> {
            if (txnPending) {
                Toast.makeText(this, "Commit or rollback the open transaction first", Toast.LENGTH_SHORT).show();
                return;
            }
            finish();
        });

        scrollSuggestions = findViewById(R.id.scrollSqlSuggestions);
        llSuggestions = findViewById(R.id.llSqlSuggestions);

        scrollHistory = findViewById(R.id.scrollSqlHistory);
        llHistory = findViewById(R.id.llSqlHistory);

        findViewById(R.id.btnSqlRun).setOnClickListener(v -> runQuery());
        btnCommit.setOnClickListener(v -> commitTxn());
        btnRollback.setOnClickListener(v -> rollbackTxn(false));

        loadSchema();
        wireAutocomplete();
    }

    private void pushHistory(String sql) {
        queryHistory.remove(sql); // move to front if it's already there
        queryHistory.add(0, sql);
        while (queryHistory.size() > MAX_HISTORY) queryHistory.remove(queryHistory.size() - 1);
        renderHistory();
    }

    private void renderHistory() {
        llHistory.removeAllViews();
        if (queryHistory.isEmpty()) {
            scrollHistory.setVisibility(View.GONE);
            return;
        }
        scrollHistory.setVisibility(View.VISIBLE);
        for (String sql : queryHistory) {
            TextView chip = new TextView(this);
            String label = sql.length() > 28 ? sql.substring(0, 28) + "…" : sql;
            chip.setText(label);
            chip.setTextSize(11);
            chip.setTextColor(getColor(R.color.primary));
            chip.setBackgroundResource(R.drawable.bg_chip_suggestion);
            chip.setPadding(dp(10), dp(6), dp(10), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(6));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                etSql.setText(sql);
                etSql.setSelection(sql.length());
            });
            llHistory.addView(chip);
        }
    }

    // ── Load table/column names once, from sqlite_master + PRAGMA ────
    private void loadSchema() {
        try (Cursor c = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null)) {
            while (c.moveToNext()) tableNames.add(c.getString(0));
        } catch (Exception ignored) {
        }

        Set<String> cols = new LinkedHashSet<>();
        for (String table : tableNames) {
            try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
                int nameIdx = c.getColumnIndex("name");
                while (c.moveToNext()) cols.add(c.getString(nameIdx));
            } catch (Exception ignored) {
            }
        }
        allColumns.addAll(cols);
    }

    // ── Suggestion chips — filtered by whatever word the cursor is
    // currently inside, refreshed on every keystroke ─────────────────
    private void wireAutocomplete() {
        etSql.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                updateSuggestions();
            }
        });
    }

    private void updateSuggestions() {
        String text = etSql.getText().toString();
        int cursor = etSql.getSelectionStart();
        if (cursor < 0 || cursor > text.length()) {
            hideSuggestions();
            return;
        }

        int start = cursor;
        while (start > 0 && isWordChar(text.charAt(start - 1))) start--;
        String prefix = text.substring(start, cursor);

        if (prefix.isEmpty()) {
            hideSuggestions();
            return;
        }

        List<String> matches = new ArrayList<>();
        String prefixUpper = prefix.toUpperCase(Locale.ROOT);
        for (String kw : SQL_KEYWORDS) if (kw.startsWith(prefixUpper)) matches.add(kw);
        for (String t : tableNames)
            if (t.toUpperCase(Locale.ROOT).startsWith(prefixUpper)) matches.add(t);
        for (String col : allColumns)
            if (col.toUpperCase(Locale.ROOT).startsWith(prefixUpper)) matches.add(col);

        if (matches.isEmpty() || (matches.size() == 1 && matches.get(0).equalsIgnoreCase(prefix))) {
            hideSuggestions();
            return;
        }

        showSuggestions(matches, start, cursor);
    }

    private boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private void showSuggestions(List<String> matches, int wordStart, int wordEnd) {
        llSuggestions.removeAllViews();
        int max = Math.min(matches.size(), 15);
        for (int i = 0; i < max; i++) {
            String word = matches.get(i);
            llSuggestions.addView(suggestionChip(word, wordStart, wordEnd));
        }
        scrollSuggestions.setVisibility(View.VISIBLE);
    }

    private void hideSuggestions() {
        scrollSuggestions.setVisibility(View.GONE);
        llSuggestions.removeAllViews();
    }

    // A handful of keywords are almost always typed with a fixed next word
    // right after them — SELECT is nearly always "SELECT * FROM ", INSERT
    // is nearly always "INSERT INTO ". Expanding to the common phrase (and
    // leaving the cursor right after it) saves the obvious follow-up keystrokes.
    private static final java.util.Map<String, String> KEYWORD_EXPANSIONS = new java.util.HashMap<>();

    static {
        KEYWORD_EXPANSIONS.put("SELECT", "SELECT * FROM ");
        KEYWORD_EXPANSIONS.put("INSERT", "INSERT INTO ");
        KEYWORD_EXPANSIONS.put("DELETE", "DELETE FROM ");
        KEYWORD_EXPANSIONS.put("UPDATE", "UPDATE ");
        KEYWORD_EXPANSIONS.put("ORDER", "ORDER BY ");
        KEYWORD_EXPANSIONS.put("GROUP", "GROUP BY ");
    }

    private TextView suggestionChip(String word, int wordStart, int wordEnd) {
        TextView chip = new TextView(this);
        chip.setText(word);
        chip.setTextSize(12);
        chip.setTextColor(getColor(R.color.primary));
        chip.setBackgroundResource(R.drawable.bg_chip_suggestion);
        chip.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(6));
        chip.setLayoutParams(lp);
        chip.setGravity(Gravity.CENTER);

        chip.setOnClickListener(v -> {
            String text = etSql.getText().toString();
            String expansion = KEYWORD_EXPANSIONS.get(word.toUpperCase(Locale.ROOT));
            String replacement = expansion != null ? expansion : word + " ";
            String newText = text.substring(0, wordStart) + replacement + text.substring(wordEnd);
            etSql.setText(newText);
            etSql.setSelection(wordStart + replacement.length());
            hideSuggestions();
        });
        return chip;
    }

    private void runQuery() {
        String sql = etSql.getText().toString().trim();
        if (sql.isEmpty()) return;
        if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).trim();

        String keyword = firstWord(sql).toUpperCase(Locale.ROOT);
        switch (keyword) {
            case "SELECT":
                pushHistory(sql);
                runSelect(sql);
                break;
            case "INSERT":
            case "UPDATE":
            case "DELETE":
                pushHistory(sql);
                runDml(sql, keyword);
                break;
            default:
                showError("Only SELECT, INSERT, UPDATE, DELETE are allowed here.");
        }
    }

    private String firstWord(String sql) {
        int i = 0;
        while (i < sql.length() && !Character.isWhitespace(sql.charAt(i))) i++;
        return sql.substring(0, i);
    }

    // ── SELECT — always read-only; runs on the live connection so it also
    // sees any uncommitted change from a currently-open transaction ──────
    // ── SELECT — always read-only; runs on the live connection so it also
    // sees any uncommitted change from a currently-open transaction.
    // Results are paged (100/page) instead of loading everything at once —
    // a bare `SELECT * FROM transactions` on a table with a couple thousand
    // rows would otherwise stall the UI trying to render every row.
    private void runSelect(String sql) {
        lastSelectSql = sql;
        try {
            totalRows = countRows(sql);
        } catch (Exception e) {
            showError(e.getMessage());
            return;
        }
        loadPage(0);
    }

    private int countRows(String sql) throws Exception {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM (" + sql + ")", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    private void loadPage(int page) {
        if (lastSelectSql == null || page < 0) return;
        int offset = page * PAGE_SIZE;
        String pagedSql = "SELECT * FROM (" + lastSelectSql + ") LIMIT " + PAGE_SIZE + " OFFSET " + offset;
        try (Cursor c = db.rawQuery(pagedSql, null)) {
            renderResults(c);
        } catch (Exception e) {
            showError(e.getMessage());
            return;
        }

        currentPage = page;
        int shownFrom = totalRows == 0 ? 0 : offset + 1;
        int shownTo = Math.min(offset + PAGE_SIZE, totalRows);
        tvStatus.setText(totalRows + " row" + (totalRows == 1 ? "" : "s") + " total — showing " + shownFrom + "–" + shownTo);
        tvStatus.setTextColor(getColor(R.color.text_secondary));
        updatePaginationUi();
    }

    private void updatePaginationUi() {
        int totalPages = (int) Math.ceil(totalRows / (double) PAGE_SIZE);
        boolean showPager = totalRows > PAGE_SIZE;
        paginationRow.setVisibility(showPager ? View.VISIBLE : View.GONE);
        if (!showPager) return;
        tvPageInfo.setText("Page " + (currentPage + 1) + " of " + Math.max(1, totalPages));
        btnPrevPage.setEnabled(currentPage > 0);
        btnNextPage.setEnabled((currentPage + 1) < totalPages);
    }

    private void renderResults(Cursor c) {
        resultTable.removeAllViews();
        resultScroll.setVisibility(View.VISIBLE);

        TableRow header = new TableRow(this);
        for (String col : c.getColumnNames()) header.addView(cell(col, true));
        resultTable.addView(header);

        c.moveToPosition(-1);
        while (c.moveToNext()) {
            TableRow row = new TableRow(this);
            for (int i = 0; i < c.getColumnCount(); i++) {
                String val;
                switch (c.getType(i)) {
                    case Cursor.FIELD_TYPE_NULL:
                        val = "NULL";
                        break;
                    case Cursor.FIELD_TYPE_BLOB:
                        val = "<blob>";
                        break;
                    default:
                        val = c.getString(i);
                }
                row.addView(cell(val, false));
            }
            resultTable.addView(row);
        }
    }

    private TextView cell(String text, boolean header) {
        TextView tv = new TextView(this);
        tv.setText(text != null ? text : "NULL");
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        tv.setTextSize(12);
        tv.setSingleLine(true);
        if (header) {
            tv.setTypeface(null, android.graphics.Typeface.BOLD);
            tv.setBackgroundColor(0xFFF1F5F9);
        }
        return tv;
    }

    // ── INSERT/UPDATE/DELETE — manual commit, auto-rollback on timeout ──
    private void runDml(String sql, String keyword) {
        if (txnPending) {
            Toast.makeText(this, "Commit or rollback the open transaction before running another statement", Toast.LENGTH_SHORT).show();
            return;
        }

// NEW — non-exclusive so a SELECT on this same connection can still run
// while the transaction is pending. beginTransaction() takes an EXCLUSIVE
// lock by default, which was silently blocking every subsequent query
// (no error, no toast — it just hangs waiting on the lock).
        db.beginTransactionNonExclusive(); // deliberately NOT ended here — stays open until Commit/Rollback
        try {
            db.execSQL(sql);
            int affected;
            try (Cursor c = db.rawQuery("SELECT changes()", null)) {
                affected = c.moveToFirst() ? c.getInt(0) : 0;
            }
            resultTable.removeAllViews();
            resultScroll.setVisibility(View.GONE);
            paginationRow.setVisibility(View.GONE); // DML has no result page to browse
            tvStatus.setText(keyword + " OK — " + affected + " row" + (affected == 1 ? "" : "s") +
                    " affected. NOT committed yet — tap Commit to keep it.");
            tvStatus.setTextColor(getColor(R.color.amber));
            startTxnTimer();
        } catch (Exception e) {
            db.endTransaction(); // no setTransactionSuccessful() -> the failed statement is rolled back
            showError(e.getMessage());
        }
    }

    private void startTxnTimer() {
        txnPending = true;
        setTxnUiState(true);
        txnDeadline = System.currentTimeMillis() + TXN_TIMEOUT_MS;

        timeoutRunnable = () -> {
            Toast.makeText(this, "Transaction timed out — rolled back automatically", Toast.LENGTH_LONG).show();
            rollbackTxn(true);
        };
        handler.postDelayed(timeoutRunnable, TXN_TIMEOUT_MS);

        tickRunnable = new Runnable() {
            @Override
            public void run() {
                if (!txnPending) return;
                long remaining = Math.max(0, txnDeadline - System.currentTimeMillis());
                tvTimer.setText("Auto-rollback in " + (remaining / 1000) + "s");
                if (remaining > 0) handler.postDelayed(this, 500);
            }
        };
        handler.post(tickRunnable);
    }

    private void commitTxn() {
        if (!txnPending) return;
        db.setTransactionSuccessful();
        db.endTransaction();
        cancelTimer();
        tvStatus.setText("✓ Committed");
        tvStatus.setTextColor(getColor(R.color.green));
        txnPending = false;
        setTxnUiState(false);
    }

    private void rollbackTxn(boolean auto) {
        if (!txnPending) return;
        db.endTransaction(); // no setTransactionSuccessful() called -> rolls back
        cancelTimer();
        tvStatus.setText(auto ? "⏱ Rolled back (timeout)" : "↩ Rolled back");
        tvStatus.setTextColor(getColor(R.color.red));
        txnPending = false;
        setTxnUiState(false);
    }

    private void cancelTimer() {
        if (timeoutRunnable != null) handler.removeCallbacks(timeoutRunnable);
        if (tickRunnable != null) handler.removeCallbacks(tickRunnable);
        tvTimer.setText("");
    }

    private void setTxnUiState(boolean pending) {
        btnCommit.setVisibility(pending ? View.VISIBLE : View.GONE);
        btnRollback.setVisibility(pending ? View.VISIBLE : View.GONE);
    }

    private void showError(String msg) {
        resultTable.removeAllViews();
        resultScroll.setVisibility(View.GONE);
        paginationRow.setVisibility(View.GONE);
        tvStatus.setText("✕ " + (msg != null ? msg : "Error"));
        tvStatus.setTextColor(getColor(R.color.red));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        if (txnPending) {
            Toast.makeText(this, "Commit or rollback the open transaction first", Toast.LENGTH_SHORT).show();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Safety net — never leave a transaction dangling on the shared
        // connection if the Activity is killed mid-transaction.
        if (txnPending) {
            try {
                db.endTransaction();
            } catch (Exception ignored) {
            }
        }
        cancelTimer();
    }
}