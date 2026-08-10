package com.expenseos.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.expenseos.R;

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
    private EditText etZohoClientId, etZohoClientSecret, etZohoRefreshToken, etWorkdriveFolderId;

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

        etZohoClientId = v.findViewById(R.id.etCfgZohoClientId);
        etZohoClientSecret = v.findViewById(R.id.etCfgZohoClientSecret);
        etZohoRefreshToken = v.findViewById(R.id.etCfgZohoRefreshToken);
        etWorkdriveFolderId = v.findViewById(R.id.etCfgWorkdriveFolderId);

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

        // Zoho fields are saved via AppConfig.setZoho() (see btnSaveCfgZoho
        // handler below), never to `prefs` — so they must be loaded from
        // AppConfig too, or this always reads back empty after leaving and
        // returning to this screen even though the save itself succeeded.
        com.expenseos.util.AppConfig cfg = com.expenseos.util.AppConfig.get(requireContext());
        etZohoClientId.setText(cfg.getZohoClientId());
        etZohoClientSecret.setText(cfg.getZohoClientSecret());
        etZohoRefreshToken.setText(cfg.getZohoRefreshToken());
        etWorkdriveFolderId.setText(cfg.getWorkdriveFolderId());
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

        requireView().findViewById(R.id.btnSaveCfgZoho).setOnClickListener(v -> {
            com.expenseos.util.AppConfig.get(requireContext()).setZoho(
                    etZohoClientId.getText().toString().trim(),
                    etZohoClientSecret.getText().toString().trim(),
                    etZohoRefreshToken.getText().toString().trim(),
                    etWorkdriveFolderId.getText().toString().trim());
            toast("✓ Zoho Config saved!");
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
//        saveAppPrefs();

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
                        {"auto.sync.enabled", Boolean.toString(prefs.getBoolean("auto_sync", false))},
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
        String url = etDbUrl.getText().toString().trim();
        String user = etDbUser.getText().toString().trim();
        String pass = etDbPass.getText().toString().trim();

        // Save to BOTH — AppConfig (used by SyncManager/Restore dialog) is the
        // source of truth; keep the old prefs write too so nothing else silently breaks.
        com.expenseos.util.AppConfig.get(requireContext()).setDb(url, user, pass);
        prefs.edit()
                .putString("db_url", url)
                .putString("db_user", user)
                .putString("db_pass", pass)
                .apply();
    }

    private void saveGmailPrefs() {
        String from = etGmailFrom.getText().toString().trim();
        String pass = etGmailPass.getText().toString().trim();

        // Save to BOTH — AppConfig (used by GmailSender for the Monthly
        // Category Report) is the source of truth; keep the old prefs write
        // too so nothing else silently breaks.
        com.expenseos.util.AppConfig.get(requireContext()).setGmail(from, pass);
        prefs.edit()
                .putString("gmail_from", from)
                .putString("gmail_pass", pass)
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