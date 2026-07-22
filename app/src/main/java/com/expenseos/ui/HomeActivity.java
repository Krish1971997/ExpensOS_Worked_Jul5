package com.expenseos.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;

import com.expenseos.R;
import com.expenseos.sync.SyncManager;
import com.expenseos.ui.home.HomeFragment;
import com.expenseos.ui.backup.BackupFragment;

public class HomeActivity extends AppCompatActivity {

    private int bookId;
    private String bookName;
    private DrawerLayout drawerLayout;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final int NAV_HOME = 0;
    private static final int NAV_REPORTS = 1;
    private static final int NAV_BACKUP = 2;
    private static final int NAV_CONFIG = 3;
    private int currentNav = NAV_HOME;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_home);

//        SharedPreferences prefs = getSharedPreferences("expenseos_prefs", MODE_PRIVATE);
//        bookId = prefs.getInt("active_book_id", 0);
//        bookName = prefs.getString("active_book_name", "");
        com.expenseos.util.AppConfig cfg = com.expenseos.util.AppConfig.get(this);
        bookId = cfg.getActiveBookId();
        bookName = cfg.getActiveBookName();

        if (bookId <= 0) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        drawerLayout = findViewById(R.id.drawerLayout);
        ((TextView) findViewById(R.id.tvBookTitle)).setText(bookName);
        ((TextView) findViewById(R.id.drawerBookName)).setText("📒 " + bookName);

        com.expenseos.sync.BackupScheduler.scheduleDaily(this);

        setupBottomNav();
        setupDrawer();
        setupSync();

        // Load HOME tab by default
        loadTab(NAV_HOME);
    }

    // ── Bottom Navigation ─────────────────────────────────
    private void setupBottomNav() {
        findViewById(R.id.navHome).setOnClickListener(v -> loadTab(NAV_HOME));
        findViewById(R.id.navReports).setOnClickListener(v -> loadTab(NAV_REPORTS));
        findViewById(R.id.navBackup).setOnClickListener(v -> loadTab(NAV_BACKUP));
        findViewById(R.id.navConfig).setOnClickListener(v -> loadTab(NAV_CONFIG));
    }

    private void loadTab(int tab) {
        currentNav = tab;
        updateNavHighlight(tab);

        // ── Use HomeFragment / ReportsFragment / BackupFragment / ConfigFragment
        Fragment frag;
        switch (tab) {
            case NAV_REPORTS:
                frag = new ReportsFragment();
                break;
            case NAV_BACKUP:
                frag = new BackupFragment();
                break;
            case NAV_CONFIG:
                frag = new ConfigFragment();
                break;
            default:
                frag = new HomeFragment();
                break; // ← HomeFragment (not DashboardFragment)
        }

        getSupportFragmentManager().beginTransaction().replace(R.id.fragmentContainer, frag).commit();
    }

    private void updateNavHighlight(int tab) {
        int[][] navLabelSets = {{R.id.navHomeLabel, NAV_HOME}, {R.id.navReportsLabel, NAV_REPORTS}, {R.id.navBackupLabel, NAV_BACKUP}, {R.id.navConfigLabel, NAV_CONFIG}};
        for (int[] set : navLabelSets) {
            TextView lbl = findViewById(set[0]);
            if (lbl != null)
                lbl.setTextColor(getResources().getColor(set[1] == tab ? R.color.primary : R.color.text_muted, null));
        }

        int[][] navIconSets = {{R.id.navHomeIcon, NAV_HOME}, {R.id.navReportsIcon, NAV_REPORTS}, {R.id.navBackupIcon, NAV_BACKUP}, {R.id.navConfigIcon, NAV_CONFIG}};
        for (int[] set : navIconSets) {
            android.widget.ImageView icon = findViewById(set[0]);
            if (icon != null)
                icon.setColorFilter(getResources().getColor(set[1] == tab ? R.color.primary : R.color.text_muted, null));
        }
    }

    // ── Side Drawer ───────────────────────────────────────
    private void setupDrawer() {
        View btnHamburger = findViewById(R.id.btnHamburger);
        if (btnHamburger != null)
            btnHamburger.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));

        View drawerBooks = findViewById(R.id.drawerBooks);
        if (drawerBooks != null) drawerBooks.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            startActivity(new Intent(this, MainActivity.class));
        });

        View drawerSettings = findViewById(R.id.drawerSettings);
        if (drawerSettings != null) drawerSettings.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            Intent i = new Intent(this, SettingsActivity.class);
            i.putExtra("bookScoped", true);
            i.putExtra("bookId", bookId);
            startActivity(i);
        });

        View drawerAudit = findViewById(R.id.drawerAudit);
        if (drawerAudit != null) drawerAudit.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            startActivity(new Intent(this, AuditLogActivity.class));
        });

        View drawerScheduler = findViewById(R.id.drawerScheduler);
        if (drawerScheduler != null) drawerScheduler.setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            startActivity(new Intent(this, SchedulerActivity.class));
        });
    }

    // ── Sync Button ───────────────────────────────────────
    private void setupSync() {
        Button btnSync = findViewById(R.id.btnSync);
        if (btnSync == null) return;

        btnSync.setOnClickListener(v -> {
            btnSync.setEnabled(false);
            btnSync.setText("Syncing…");

            // Chain push (syncToCloud) then pull (fetchFromCloud) — the real
            // SyncManager singleton doesn't expose one combined "sync" call.
            SyncManager.get().syncToCloud(this, new SyncManager.SyncCallback() {
                @Override
                public void onComplete(boolean pushOk, String pushSummary) {
                    if (!pushOk) {
                        mainHandler.post(() -> {
                            btnSync.setEnabled(true);
                            btnSync.setText("⟳ Sync");
                            Toast.makeText(HomeActivity.this, "Sync failed: " + pushSummary, Toast.LENGTH_LONG).show();
                        });
                        return;
                    }

                    SyncManager.get().fetchFromCloud(HomeActivity.this, new SyncManager.SyncCallback() {
                        @Override
                        public void onComplete(boolean pullOk, String pullSummary) {
                            mainHandler.post(() -> {
                                btnSync.setEnabled(true);
                                btnSync.setText("⟳ Sync");
                                if (pullOk) {
                                    Toast.makeText(HomeActivity.this, pushSummary + " | " + pullSummary, Toast.LENGTH_SHORT).show();
                                    // Refresh current fragment — do NOT call loadTab() — causes page jump
                                    Fragment cur = getSupportFragmentManager().findFragmentById(R.id.fragmentContainer);
                                    if (cur instanceof HomeFragment)
                                        ((HomeFragment) cur).refreshData();
                                } else {
                                    Toast.makeText(HomeActivity.this, "Sync failed: " + pullSummary, Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    });
                }
            });
        });
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START))
            drawerLayout.closeDrawer(GravityCompat.START);
        else super.onBackPressed();
    }
}