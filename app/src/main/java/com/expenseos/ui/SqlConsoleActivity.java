package com.expenseos.ui;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.expenseos.R;
import com.expenseos.db.LocalDB;

import java.util.Locale;

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

    private boolean txnPending = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;
    private Runnable tickRunnable;
    private long txnDeadline;

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

        findViewById(R.id.btnSqlBack).setOnClickListener(v -> {
            if (txnPending) {
                Toast.makeText(this, "Commit or rollback the open transaction first", Toast.LENGTH_SHORT).show();
                return;
            }
            finish();
        });

        findViewById(R.id.btnSqlRun).setOnClickListener(v -> runQuery());
        btnCommit.setOnClickListener(v -> commitTxn());
        btnRollback.setOnClickListener(v -> rollbackTxn(false));
    }

    private void runQuery() {
        String sql = etSql.getText().toString().trim();
        if (sql.isEmpty()) return;
        if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).trim();

        String keyword = firstWord(sql).toUpperCase(Locale.ROOT);
        switch (keyword) {
            case "SELECT":
                runSelect(sql);
                break;
            case "INSERT":
            case "UPDATE":
            case "DELETE":
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
    private void runSelect(String sql) {
        try (Cursor c = db.rawQuery(sql, null)) {
            renderResults(c);
            tvStatus.setText(c.getCount() + " row" + (c.getCount() == 1 ? "" : "s") + " returned");
            tvStatus.setTextColor(getColor(R.color.text_secondary));
        } catch (Exception e) {
            showError(e.getMessage());
        }
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

        db.beginTransaction(); // deliberately NOT ended here — stays open until Commit/Rollback
        try {
            db.execSQL(sql);
            int affected;
            try (Cursor c = db.rawQuery("SELECT changes()", null)) {
                affected = c.moveToFirst() ? c.getInt(0) : 0;
            }
            resultTable.removeAllViews();
            resultScroll.setVisibility(View.GONE);
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