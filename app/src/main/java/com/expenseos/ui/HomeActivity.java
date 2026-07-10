package com.expenseos.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;

import com.expenseos.R;
import com.expenseos.db.SyncManager;

public class HomeActivity extends AppCompatActivity {

    private int bookId;
    private String bookName;
    private DrawerLayout drawerLayout;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Bottom nav item ids
    private static final int NAV_HOME = 0;
    private static final int NAV_REPORTS = 1;
    private static final int NAV_BACKUP = 2;
    private static final int NAV_CONFIG = 3;

    private int currentNav = NAV_HOME;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_home);

        SharedPreferences prefs = getSharedPreferences("expenseos_prefs", MODE_PRIVATE);
        bookId = prefs.getInt("active_book_id", 0);
        bookName = prefs.getString("active_book_name", "");

        // If no book selected → go to book selector
        if (bookId <= 0) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        drawerLayout = findViewById(R.id.drawerLayout);
        ((TextView) findViewById(R.id.tvBookTitle)).setText(bookName);
        ((TextView) findViewById(R.id.drawerBookName)).setText("📒 " + bookName);

        setupBottomNav();
        setupDrawer();
        setupSync();

        // Default tab: Home
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
                frag = new DashboardFragment();
                break;
        }

        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragmentContainer, frag)
                .commit();
    }

    private void updateNavHighlight(int tab) {
        // Reset all labels to muted
        int[][] navSets = {
                {R.id.navHomeLabel, NAV_HOME},
                {R.id.navReportsLabel, NAV_REPORTS},
                {R.id.navBackupLabel, NAV_BACKUP},
                {R.id.navConfigLabel, NAV_CONFIG}
        };
        for (int[] set : navSets) {
            TextView lbl = findViewById(set[0]);
            lbl.setTextColor(getResources().getColor(
                    set[1] == tab ? R.color.primary : R.color.text_muted, null));
        }
    }

    // ── Side Drawer ───────────────────────────────────────
    private void setupDrawer() {
        findViewById(R.id.btnHamburger).setOnClickListener(v ->
                drawerLayout.openDrawer(GravityCompat.START));

        // Books → go to book selector
        findViewById(R.id.drawerBooks).setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            startActivity(new Intent(this, MainActivity.class));
        });

        // Settings → categories / sub-categories
        findViewById(R.id.drawerSettings).setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            startActivity(new Intent(this, SettingsActivity.class));
        });

        // Audit Log
        findViewById(R.id.drawerAudit).setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            startActivity(new Intent(this, AuditLogActivity.class));
        });
    }

    // ── Sync Button ───────────────────────────────────────
    private void setupSync() {
        Button btnSync = findViewById(R.id.btnSync);
        btnSync.setOnClickListener(v -> {
            btnSync.setEnabled(false);
            btnSync.setText("Syncing…");
            SyncManager.sync(this, new SyncManager.SyncCallback() {
                @Override
                public void onSuccess(String msg) {
                    mainHandler.post(() -> {
                        btnSync.setEnabled(true);
                        btnSync.setText("⟳ Sync");
                        Toast.makeText(HomeActivity.this, msg, Toast.LENGTH_SHORT).show();
                        // Refresh current fragment
                        loadTab(currentNav);
                    });
                }

                @Override
                public void onError(String err) {
                    mainHandler.post(() -> {
                        btnSync.setEnabled(true);
                        btnSync.setText("⟳ Sync");
                        Toast.makeText(HomeActivity.this,
                                "Sync failed: " + err, Toast.LENGTH_LONG).show();
                    });
                }
            });
        });
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }
}