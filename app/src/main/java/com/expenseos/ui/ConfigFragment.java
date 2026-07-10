package com.expenseos.ui;

import com.expenseos.R;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ConfigFragment extends Fragment {

    private SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    // DB Config
    private EditText etDbUrl, etDbUser, etDbPass;
    // Gmail Config
    private EditText etGmailFrom, etGmailPass;
    // App Config
    private EditText etBackupHour, etBackupMinute, etSessionTimeout, etAppName;
    private Switch swAutoSync;

    // Status views — keep references to avoid findViewById on wrong view
    private Button btnTestConnection, btnSyncConfigToDb;
    private TextView tvConnectionResult, tvSyncConfigStatus;

    @Override
    public View onCreateView(@NonNull LayoutInflater inf, ViewGroup pg, Bundle s) {
        return inf.inflate(R.layout.fragment_config, pg, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        super.onViewCreated(v, s);
        prefs = requireContext().getSharedPreferences(
                "expenseos_prefs", android.content.Context.MODE_PRIVATE);

        bindViews(v);
        loadSavedValues();
        setupButtons();
    }

    private void bindViews(View v) {
        etDbUrl = v.findViewById(R.id.etCfgDbUrl);
        etDbUser = v.findViewById(R.id.etCfgDbUser);
        etDbPass = v.findViewById(R.id.etCfgDbPass);
        etGmailFrom = v.findViewById(R.id.etCfgGmailFrom);
        etGmailPass = v.findViewById(R.id.etCfgGmailPass);
        etBackupHour = v.findViewById(R.id.etCfgBackupHour);
        etBackupMinute = v.findViewById(R.id.etCfgBackupMinute);
        etSessionTimeout = v.findViewById(R.id.etCfgSessionTimeout);
        etAppName = v.findViewById(R.id.etCfgAppName);
        swAutoSync = v.findViewById(R.id.swAutoSync);

        btnTestConnection = v.findViewById(R.id.btnTestConnection);
        btnSyncConfigToDb = v.findViewById(R.id.btnSyncConfigToDb);
        tvConnectionResult = v.findViewById(R.id.tvConnectionResult);
        tvSyncConfigStatus = v.findViewById(R.id.tvSyncConfigStatus);
    }

    private void loadSavedValues() {
        etDbUrl.setText(prefs.getString("db_url", ""));
        etDbUser.setText(prefs.getString("db_user", ""));
        etDbPass.setText(prefs.getString("db_pass", ""));
        etGmailFrom.setText(prefs.getString("gmail_from", ""));
        etGmailPass.setText(prefs.getString("gmail_pass", ""));
        etBackupHour.setText(prefs.getString("backup_hour", "0"));
        etBackupMinute.setText(prefs.getString("backup_minute", "0"));
        etSessionTimeout.setText(prefs.getString("session_timeout", "60"));
        etAppName.setText(prefs.getString("app_name", "ExpenseOS"));
        swAutoSync.setChecked(prefs.getBoolean("auto_sync", false));
    }

    private void setupButtons() {
        // ── Save DB Config ──────────────────────────────────
        requireView().findViewById(R.id.btnSaveCfgDb)
                .setOnClickListener(v -> {
                    saveDbPrefs();
                    toast("✓ DB Config saved!");
                });

        // ── Save Gmail Config ───────────────────────────────
        requireView().findViewById(R.id.btnSaveCfgGmail)
                .setOnClickListener(v -> {
                    saveGmailPrefs();
                    toast("✓ Gmail Config saved!");
                });

        // ── Save App Config ─────────────────────────────────
        requireView().findViewById(R.id.btnSaveCfgApp)
                .setOnClickListener(v -> {
                    saveAppPrefs();
                    toast("✓ App Config saved!");
                });

        // ── Test DB Connection ──────────────────────────────
        btnTestConnection.setOnClickListener(v -> testConnection());

        // ── Sync Config to DB ───────────────────────────────
        btnSyncConfigToDb.setOnClickListener(v -> syncConfigToDb());
    }

    // ── Test Connection ───────────────────────────────────
    private void testConnection() {
        String url = etDbUrl.getText().toString().trim();
        String user = etDbUser.getText().toString().trim();
        String pass = etDbPass.getText().toString().trim();

        if (url.isEmpty()) {
            showResult(tvConnectionResult, "✗ DB URL is empty", false);
            return;
        }

        // Save current values first
        saveDbPrefs();

        setButtonState(btnTestConnection, false, "Testing…");
        showResult(tvConnectionResult, "⏳ Connecting to Neon DB…", null);

        // ── Background thread — JDBC call ──────────────────
        exec.execute(() -> {
            String result;
            boolean ok;
            try {
                Class.forName("org.postgresql.Driver");
                Connection conn = DriverManager.getConnection(url, user, pass);
                ResultSet rs = conn.createStatement()
                        .executeQuery("SELECT COUNT(*) FROM transactions");
                rs.next();
                int cnt = rs.getInt(1);
                conn.close();
                result = "✓ Connected! Transactions in DB: " + cnt;
                ok = true;
            } catch (ClassNotFoundException e) {
                result = "✗ Driver not found: " + e.getMessage();
                ok = false;
            } catch (Exception e) {
                result = "✗ " + e.getMessage();
                ok = false;
            }

            // ── Back to UI thread ───────────────────────────
            final String finalResult = result;
            final boolean finalOk = ok;
            mainHandler.post(() -> {
                // Fragment still attached? Guard required
                if (!isAdded() || getView() == null) return;
                setButtonState(btnTestConnection, true, "🔗 Test Connection");
                showResult(tvConnectionResult, finalResult, finalOk);
            });
        });
    }

    // ── Sync all config to Neon app_config table ──────────
    private void syncConfigToDb() {
        // Save all locally first
        saveDbPrefs();
        saveGmailPrefs();
        saveAppPrefs();

        String url = prefs.getString("db_url", "");
        String user = prefs.getString("db_user", "");
        String pass = prefs.getString("db_pass", "");

        if (url.isEmpty()) {
            showResult(tvSyncConfigStatus, "✗ DB URL not configured!", false);
            return;
        }

        setButtonState(btnSyncConfigToDb, false, "Syncing…");
        showResult(tvSyncConfigStatus, "⏳ Pushing config to Neon DB…", null);

        exec.execute(() -> {
            String result;
            boolean ok;
            try {
                Class.forName("org.postgresql.Driver");
                Connection conn = DriverManager.getConnection(url, user, pass);

                // Create table if not exists
                conn.createStatement().execute(
                        "CREATE TABLE IF NOT EXISTS app_config (" +
                                "  key         VARCHAR(100) PRIMARY KEY," +
                                "  value       TEXT," +
                                "  updated_at  TIMESTAMP DEFAULT NOW()" +
                                ")"
                );

                // Upsert config (passwords NOT synced for security)
                String[][] configs = {
                        {"backup.schedule.hour", prefs.getString("backup_hour", "0")},
                        {"backup.schedule.minute", prefs.getString("backup_minute", "0")},
                        {"session.timeout", prefs.getString("session_timeout", "60")},
                        {"auto.sync.enabled", prefs.getBoolean("auto_sync", false) ? "true" : "false"},
                        {"app.display.name", prefs.getString("app_name", "ExpenseOS")},
                        {"gmail.from", prefs.getString("gmail_from", "")},
                };

                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO app_config(key, value, updated_at) " +
                                "VALUES(?, ?, NOW()) " +
                                "ON CONFLICT(key) DO UPDATE " +
                                "SET value = EXCLUDED.value, updated_at = NOW()"
                );
                for (String[] cfg : configs) {
                    ps.setString(1, cfg[0]);
                    ps.setString(2, cfg[1]);
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.close();
                result = "✓ Config synced to Neon DB! (" + configs.length + " settings)";
                ok = true;
            } catch (Exception e) {
                result = "✗ Sync failed: " + e.getMessage();
                ok = false;
            }

            final String finalResult = result;
            final boolean finalOk = ok;
            mainHandler.post(() -> {
                if (!isAdded() || getView() == null) return;
                setButtonState(btnSyncConfigToDb, true, "☁ Save All + Sync Config to DB");
                showResult(tvSyncConfigStatus, finalResult, finalOk);
            });
        });
    }

    // ── SharedPreferences helpers ─────────────────────────
    private void saveDbPrefs() {
        prefs.edit()
                .putString("db_url", etDbUrl.getText().toString().trim())
                .putString("db_user", etDbUser.getText().toString().trim())
                .putString("db_pass", etDbPass.getText().toString().trim())
                .apply();
    }

    private void saveGmailPrefs() {
        prefs.edit()
                .putString("gmail_from", etGmailFrom.getText().toString().trim())
                .putString("gmail_pass", etGmailPass.getText().toString().trim())
                .apply();
    }

    private void saveAppPrefs() {
        prefs.edit()
                .putString("backup_hour", etBackupHour.getText().toString().trim())
                .putString("backup_minute", etBackupMinute.getText().toString().trim())
                .putString("session_timeout", etSessionTimeout.getText().toString().trim())
                .putBoolean("auto_sync", swAutoSync.isChecked())
                .putString("app_name", etAppName.getText().toString().trim())
                .apply();
    }

    // ── UI helpers ────────────────────────────────────────
    private void setButtonState(Button btn, boolean enabled, String text) {
        btn.setEnabled(enabled);
        btn.setText(text);
    }

    /**
     * ok = true  → green
     * ok = false → red
     * ok = null  → amber (loading)
     */
    private void showResult(TextView tv, String msg, Boolean ok) {
        tv.setVisibility(View.VISIBLE);
        tv.setText(msg);
        if (ok == null) {
            tv.setTextColor(requireContext().getResources()
                    .getColor(R.color.amber, null));
        } else if (ok) {
            tv.setTextColor(requireContext().getResources()
                    .getColor(R.color.green, null));
        } else {
            tv.setTextColor(requireContext().getResources()
                    .getColor(R.color.red, null));
        }
    }

    private void toast(String msg) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        exec.shutdown();
    }
}