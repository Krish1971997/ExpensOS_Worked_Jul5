package com.expenseos.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.adapter.SearchResultAdapter;
import com.expenseos.dao.CashBookDao;
import com.expenseos.db.LocalDB;
import com.expenseos.model.CashBook;
import com.expenseos.model.SearchResult;
import com.expenseos.util.AppConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Global search — one box that searches three layers:
 * <p>
 * 1. <b>Screens</b> — a static registry of every screen/page in the app,
 * matched on title + description + hidden keywords.<br>
 * 2. <b>Masters</b> — cash books, categories, sub-categories, payment
 * types, keyword mappings and custom columns (SQLite).<br>
 * 3. <b>Data</b> — transactions (note, category, payment type, date, amount).
 * <p>
 * Results come back grouped, and every row is navigable: a transaction
 * opens its detail screen, a book becomes the active book, a master
 * opens the Settings screen where it can be edited.
 */
public class GlobalSearchActivity extends AppCompatActivity {

    private static final int REQ_SMS_PERMISSION = 101;

    /** One entry in the static screen/page registry. */
    private static final class ScreenEntry {
        final String title, subtitle, keywords, action;

        ScreenEntry(String title, String subtitle, String keywords, String action) {
            this.title = title;
            this.subtitle = subtitle;
            this.keywords = keywords;
            this.action = action;
        }
    }

    private static final List<ScreenEntry> SCREENS = new ArrayList<>();

    static {
        SCREENS.add(new ScreenEntry("Cash Books", "Book selector — open or create a cash book",
                "cashbook cash books home main ledger books", "cashbooks"));
        SCREENS.add(new ScreenEntry("Stats", "Category charts, trends and monthly breakdown",
                "stats statistics charts analytics report", "stats"));
        SCREENS.add(new ScreenEntry("AI Bot", "Ask the AI assistant about your money",
                "ai bot chat assistant ask question", "aibot"));
        SCREENS.add(new ScreenEntry("Passbook (SMS)", "Parse bank / UPI SMS into transactions",
                "passbook sms bank upi inbox import message", "passbook"));
        SCREENS.add(new ScreenEntry("Food Tracker", "Daily food and meal expense tracker",
                "food tracker meal breakfast lunch dinner eat", "food"));
        SCREENS.add(new ScreenEntry("Integrations", "Google Calendar events and reminders",
                "integrations calendar events reminders sync google", "integrations"));
        SCREENS.add(new ScreenEntry("All Transactions", "Every entry with filters and search",
                "all transactions list entries history", "alltxn"));
        SCREENS.add(new ScreenEntry("SQL Console", "Raw SELECT / INSERT / UPDATE / DELETE console",
                "sql console query database developer debug table", "sqlconsole"));
        SCREENS.add(new ScreenEntry("Settings", "Categories, sub-categories, payment types, columns",
                "settings config masters category subcategory payment type column keyword", "settings"));
        SCREENS.add(new ScreenEntry("Budget", "Monthly budget and allocations",
                "budget allocation limit plan monthly", "budget"));
        SCREENS.add(new ScreenEntry("Scheduler", "Automated jobs and their run log",
                "scheduler jobs automation backup task cron", "scheduler"));
        SCREENS.add(new ScreenEntry("Audit Log", "Every change made to a transaction",
                "audit log history changes trail who", "audit"));
        SCREENS.add(new ScreenEntry("Recycle Bin", "Restore soft-deleted records",
                "recycle bin trash restore deleted undo", "recycle"));
        SCREENS.add(new ScreenEntry("Insights & Reports", "Generated insights and reports",
                "insights reports summary analysis", "insights"));
        SCREENS.add(new ScreenEntry("Calendar View", "Transactions on a calendar",
                "calendar view dates month day", "calendar"));
        SCREENS.add(new ScreenEntry("Reminders", "Reminders list",
                "reminders alerts notifications list", "reminders"));
        SCREENS.add(new ScreenEntry("Backup", "Local and cloud backup / restore",
                "backup restore export zip cloud neon", "backup"));
        SCREENS.add(new ScreenEntry("Settlement Links", "Net settlement between books",
                "settlement link net transfer settle", "settlement"));
        SCREENS.add(new ScreenEntry("Bulk Add", "Add many transactions at once",
                "bulk add multiple import batch", "bulkadd"));
        SCREENS.add(new ScreenEntry("Generate Report", "Build a PDF / Excel report",
                "generate report pdf excel export download", "genreport"));
    }

    private SQLiteDatabase db;
    private EditText etSearch;
    private RecyclerView rv;
    private TextView tvEmpty, tvSummary;
    private SearchResultAdapter adapter;
    private final List<SearchResult> results = new ArrayList<>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Runnable pending;
    private long searchToken = 0L;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_global_search);

        db = LocalDB.getInstance(this).getWritableDatabase();

        etSearch = findViewById(R.id.etGlobalSearch);
        rv = findViewById(R.id.rvSearchResults);
        tvEmpty = findViewById(R.id.tvSearchEmpty);
        tvSummary = findViewById(R.id.tvSearchSummary);

        findViewById(R.id.btnGlobalSearchBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnGlobalSearchClear).setOnClickListener(v -> {
            etSearch.setText("");
            etSearch.requestFocus();
        });

        adapter = new SearchResultAdapter(results, this::open);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {
            }

            public void onTextChanged(CharSequence c, int a, int b, int d) {
            }

            public void afterTextChanged(Editable e) {
                scheduleSearch(e.toString());
            }
        });

        etSearch.requestFocus();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    /** 220 ms debounce — one DB pass per pause in typing, not per keystroke. */
    private void scheduleSearch(String q) {
        if (pending != null) main.removeCallbacks(pending);
        final String query = q;
        pending = () -> {
            final long token = ++searchToken;
            io.execute(() -> {
                final List<SearchResult> out = search(query);
                main.post(() -> {
                    if (token != searchToken) return; // a newer query already won
                    render(out, query);
                });
            });
        };
        main.postDelayed(pending, 220);
    }

    // ── The search itself (background thread) ────────────────────────
    private List<SearchResult> search(String raw) {
        String query = raw == null ? "" : raw.trim();
        List<SearchResult> out = new ArrayList<>();
        if (query.isEmpty()) return out;

        String like = "%" + query + "%";

        // 1 ── Screens / pages (static registry) ────────────────────
        List<SearchResult> screens = new ArrayList<>();
        for (ScreenEntry e : SCREENS) {
            if (matches(query, e.title, e.subtitle, e.keywords)) {
                screens.add(SearchResult.item("Screens", e.title, e.subtitle, "SCREEN", e.action, -1));
            }
        }

        // 2 ── Masters (SQLite tables that hold configuration data) ──
        List<SearchResult> masters = new ArrayList<>();

        try (Cursor c = db.rawQuery(
                "SELECT id, name, IFNULL(description,'') FROM cash_books " +
                        "WHERE name LIKE ? OR IFNULL(description,'') LIKE ? ORDER BY name LIMIT 25",
                new String[]{like, like})) {
            while (c.moveToNext()) {
                String desc = c.getString(2);
                masters.add(SearchResult.item("Masters", c.getString(1),
                        desc.isEmpty() ? "Cash book" : "Cash book — " + desc,
                        "BOOK", "openbook", c.getInt(0)));
            }
        }

        try (Cursor c = db.rawQuery(
                "SELECT id, name, type FROM categories WHERE name LIKE ? ORDER BY name LIMIT 25",
                new String[]{like})) {
            while (c.moveToNext()) {
                masters.add(SearchResult.item("Masters", c.getString(1),
                        "Category · " + c.getString(2), "CATEGORY", "settings", c.getInt(0)));
            }
        }

        try (Cursor c = db.rawQuery(
                "SELECT s.id, s.name, IFNULL(c.name,'—') FROM sub_categories s " +
                        "LEFT JOIN categories c ON c.id = s.category_id " +
                        "WHERE s.name LIKE ? ORDER BY s.name LIMIT 25",
                new String[]{like})) {
            while (c.moveToNext()) {
                masters.add(SearchResult.item("Masters", c.getString(1),
                        "Sub-category of " + c.getString(2), "SUB-CAT", "settings", c.getInt(0)));
            }
        }

        try (Cursor c = db.rawQuery(
                "SELECT id, name FROM payment_types WHERE name LIKE ? ORDER BY name LIMIT 15",
                new String[]{like})) {
            while (c.moveToNext()) {
                masters.add(SearchResult.item("Masters", c.getString(1),
                        "Payment type", "PAY TYPE", "settings", c.getInt(0)));
            }
        }

        try (Cursor c = db.rawQuery(
                "SELECT id, keyword, type FROM keyword_mappings WHERE keyword LIKE ? ORDER BY keyword LIMIT 15",
                new String[]{like})) {
            while (c.moveToNext()) {
                masters.add(SearchResult.item("Masters", c.getString(1),
                        "Auto-suggest keyword · " + c.getString(2), "KEYWORD", "settings", c.getInt(0)));
            }
        }

        try (Cursor c = db.rawQuery(
                "SELECT id, col_name, type FROM column_definitions WHERE col_name LIKE ? ORDER BY col_name LIMIT 15",
                new String[]{like})) {
            while (c.moveToNext()) {
                masters.add(SearchResult.item("Masters", c.getString(1),
                        "Custom column · " + c.getString(2), "COLUMN", "settings", c.getInt(0)));
            }
        }

        // 3 ── Data: transactions ───────────────────────────────────
        List<SearchResult> txns = new ArrayList<>();
        String sql = "SELECT t.id, t.type, t.txn_datetime, t.amount, IFNULL(t.note,''), " +
                "IFNULL(t.payment_type,''), IFNULL(c.name,'—'), IFNULL(b.name,'—') " +
                "FROM transactions t " +
                "LEFT JOIN categories c ON c.id = t.category_id " +
                "LEFT JOIN cash_books b ON b.id = t.book_id " +
                "WHERE t.note LIKE ? OR c.name LIKE ? OR t.payment_type LIKE ? " +
                "   OR t.txn_datetime LIKE ? OR CAST(t.amount AS TEXT) LIKE ? " +
                "ORDER BY t.txn_datetime DESC LIMIT 50";
        try (Cursor c = db.rawQuery(sql, new String[]{like, like, like, like, like})) {
            while (c.moveToNext()) {
                String type = c.getString(1);
                String when = c.getString(2);
                if (when != null && when.length() > 10) when = when.substring(0, 10);
                String note = c.getString(4);
                String cat = c.getString(6);
                String book = c.getString(7);
                String pay = c.getString(5);
                String subtitle = when + " · " + cat + " · " + book
                        + (pay.isEmpty() ? "" : " · " + pay)
                        + (note.isEmpty() ? "" : " · " + note);
                String title = (type == null ? "TXN" : type) + "  ₹" + trimAmount(c.getString(3));
                txns.add(SearchResult.item("Data · Transactions", title, subtitle,
                        "TXN", "txn", c.getInt(0)));
            }
        }

        // ── Group the results ───────────────────────────────────────
        if (!screens.isEmpty()) {
            out.add(SearchResult.header("SCREENS · " + screens.size()));
            out.addAll(screens);
        }
        if (!masters.isEmpty()) {
            out.add(SearchResult.header("MASTERS · " + masters.size()));
            out.addAll(masters);
        }
        if (!txns.isEmpty()) {
            out.add(SearchResult.header("DATA · TRANSACTIONS · " + txns.size()));
            out.addAll(txns);
        }
        return out;
    }

    private static String trimAmount(String raw) {
        if (raw == null) return "0";
        try {
            double d = Double.parseDouble(raw);
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return String.format(Locale.US, "%,.0f", d);
            }
            return String.format(Locale.US, "%,.2f", d);
        } catch (Exception e) {
            return raw;
        }
    }

    private static boolean matches(String query, String... haystacks) {
        String q = query.toLowerCase(Locale.ROOT);
        for (String h : haystacks) {
            if (h != null && h.toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        return false;
    }

    private void render(List<SearchResult> out, String query) {
        results.clear();
        results.addAll(out);
        adapter.notifyDataSetChanged();

        boolean empty = out.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            tvEmpty.setText(query.trim().isEmpty()
                    ? "Type to search across screens, masters and transactions."
                    : "No matches for \"" + query.trim() + "\".");
        }

        int rows = 0;
        for (SearchResult r : out) if (r.viewType == SearchResult.TYPE_ITEM) rows++;
        tvSummary.setText(query.trim().isEmpty()
                ? "Type to search across screens, masters (books, categories, payment types) and transactions."
                : rows + " result" + (rows == 1 ? "" : "s") + " for \"" + query.trim() + "\"");
    }

    // ── Navigation ──────────────────────────────────────────────────
    private void open(SearchResult r) {
        if (r.action == null) return;
        switch (r.action) {
            case "openbook": {
                CashBook b = new CashBookDao(this).findById(r.refId);
                if (b == null) {
                    Toast.makeText(this, "Book no longer exists", Toast.LENGTH_SHORT).show();
                    return;
                }
                AppConfig.get(this).setActiveBook(b.getId(), b.getName());
                startActivity(new Intent(this, HomeActivity.class));
                return;
            }
            case "txn": {
                Intent i = new Intent(this, TransactionDetailActivity.class);
                i.putExtra("txnId", r.refId);
                startActivity(i);
                return;
            }
            case "passbook":
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.READ_SMS}, REQ_SMS_PERMISSION);
                    return;
                }
                startActivity(new Intent(this, PassbookActivity.class));
                return;
            case "settings":
                startActivity(new Intent(this, SettingsActivity.class));
                return;
            case "cashbooks":
                startActivity(new Intent(this, MainActivity.class));
                return;
            case "stats":
                startActivity(new Intent(this, StatsActivity.class));
                return;
            case "aibot":
                startActivity(new Intent(this, ChatActivity.class));
                return;
            case "food":
                startActivity(new Intent(this, FoodTrackerActivity.class));
                return;
            case "integrations":
                startActivity(new Intent(this, IntegrationsActivity.class));
                return;
            case "alltxn":
                startActivity(new Intent(this, AllTransactionsActivity.class));
                return;
            case "sqlconsole":
                startActivity(new Intent(this, SqlConsoleActivity.class));
                return;
            case "budget":
                startActivity(new Intent(this, BudgetActivity.class));
                return;
            case "scheduler":
                startActivity(new Intent(this, SchedulerActivity.class));
                return;
            case "audit":
                startActivity(new Intent(this, AuditLogActivity.class));
                return;
            case "recycle":
                startActivity(new Intent(this, RecycleBinActivity.class));
                return;
            case "insights":
                startActivity(new Intent(this, InsightsActivity.class));
                return;
            case "calendar":
                startActivity(new Intent(this, CalendarViewActivity.class));
                return;
            case "reminders":
                startActivity(new Intent(this, RemindersListActivity.class));
                return;
            case "backup":
                startActivity(new Intent(this, BackupActivity.class));
                return;
            case "settlement":
                startActivity(new Intent(this, SettlementLinkActivity.class));
                return;
            case "bulkadd":
                startActivity(new Intent(this, BulkAddActivity.class));
                return;
            case "genreport":
                startActivity(new Intent(this, GenerateReportActivity.class));
                return;
            default:
                Toast.makeText(this, "No screen registered for " + r.action, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}
