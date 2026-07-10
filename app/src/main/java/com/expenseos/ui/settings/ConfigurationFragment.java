package com.expenseos.ui.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.expenseos.R;
import com.expenseos.sync.SyncManager;
import com.expenseos.util.AppConfig;

/**
 * Configuration page — mirrors web.xml context-params.
 * DB_URL, DB_USER, DB_PASSWORD, GMAIL_FROM, GMAIL_APP_PASS,
 * Zoho credentials, backup schedule, session timeout.
 */
public class ConfigurationFragment extends Fragment {

    // DB
    private EditText etDbUrl, etDbUser, etDbPassword;
    // Gmail
    private EditText etGmailFrom, etGmailAppPass;
    // Zoho
    private EditText etZohoClientId, etZohoSecret, etZohoRefresh, etWorkdriveFolder;
    // Backup schedule
    private EditText etBackupHour, etBackupMinute;
    // Session
    private EditText etSessionTimeout;
    // Status
    private TextView tvConnStatus;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_configuration, container, false);

        // ── DB fields ──────────────────────────────────────
        etDbUrl = root.findViewById(R.id.et_db_url);
        etDbUser = root.findViewById(R.id.et_db_user);
        etDbPassword = root.findViewById(R.id.et_db_password);

        // ── Gmail fields ───────────────────────────────────
        etGmailFrom = root.findViewById(R.id.et_gmail_from);
        etGmailAppPass = root.findViewById(R.id.et_gmail_app_pass);

        // ── Zoho fields ────────────────────────────────────
        etZohoClientId = root.findViewById(R.id.et_zoho_client_id);
        etZohoSecret = root.findViewById(R.id.et_zoho_secret);
        etZohoRefresh = root.findViewById(R.id.et_zoho_refresh);
        etWorkdriveFolder = root.findViewById(R.id.et_workdrive_folder);

        // ── Schedule fields ────────────────────────────────
        etBackupHour = root.findViewById(R.id.et_backup_hour);
        etBackupMinute = root.findViewById(R.id.et_backup_minute);
        etSessionTimeout = root.findViewById(R.id.et_session_timeout);

        tvConnStatus = root.findViewById(R.id.tv_conn_status);

        // Load saved values
        loadConfig();

        // Buttons
        root.findViewById(R.id.btn_save_config).setOnClickListener(v -> saveConfig());
        root.findViewById(R.id.btn_test_conn).setOnClickListener(v -> testConnection());

        return root;
    }

    private void loadConfig() {
        AppConfig cfg = AppConfig.get(requireContext());
        etDbUrl.setText(cfg.getDbUrl());
        etDbUser.setText(cfg.getDbUser());
        etDbPassword.setText(cfg.getDbPassword());
        etGmailFrom.setText(cfg.getGmailFrom());
        etGmailAppPass.setText(cfg.getGmailAppPass());
        etZohoClientId.setText(cfg.getZohoClientId());
        etZohoSecret.setText(cfg.getZohoClientSecret());
        etZohoRefresh.setText(cfg.getZohoRefreshToken());
        etWorkdriveFolder.setText(cfg.getWorkdriveFolderId());
        etBackupHour.setText(String.valueOf(cfg.getBackupHour()));
        etBackupMinute.setText(String.valueOf(cfg.getBackupMinute()));
        etSessionTimeout.setText(String.valueOf(cfg.getSessionTimeout()));
    }

    private void saveConfig() {
        AppConfig cfg = AppConfig.get(requireContext());

        String dbUrl = etDbUrl.getText().toString().trim();
        String dbUser = etDbUser.getText().toString().trim();
        String dbPass = etDbPassword.getText().toString().trim();
        if (dbUrl.isEmpty()) {
            Toast.makeText(getContext(), "DB URL is required", Toast.LENGTH_SHORT).show();
            return;
        }
        cfg.setDb(dbUrl, dbUser, dbPass);
        cfg.setGmail(etGmailFrom.getText().toString().trim(),
                etGmailAppPass.getText().toString().trim());
        cfg.setZoho(etZohoClientId.getText().toString().trim(),
                etZohoSecret.getText().toString().trim(),
                etZohoRefresh.getText().toString().trim(),
                etWorkdriveFolder.getText().toString().trim());

        int hour = parseIntSafe(etBackupHour.getText().toString(), 0);
        int min = parseIntSafe(etBackupMinute.getText().toString(), 0);
        cfg.setBackupSchedule(hour, min);
        cfg.setSessionTimeout(parseIntSafe(etSessionTimeout.getText().toString(), 60));

        Toast.makeText(getContext(), "✔ Configuration saved", Toast.LENGTH_SHORT).show();
        tvConnStatus.setText("Configuration saved. Tap 'Test Connection' to verify.");
    }

    private void testConnection() {
        Button btn = requireView().findViewById(R.id.btn_test_conn);
        btn.setEnabled(false);
        btn.setText("Testing…");
        tvConnStatus.setText("Connecting to Neon PostgreSQL…");
        tvConnStatus.setTextColor(0xFF888888);

        // Save current input first so DBConnection picks it up
        AppConfig cfg = AppConfig.get(requireContext());
        cfg.setDb(etDbUrl.getText().toString().trim(),
                etDbUser.getText().toString().trim(),
                etDbPassword.getText().toString().trim());

        SyncManager.get().testConnection(requireContext(), (ok, msg) -> {
            btn.setEnabled(true);
            btn.setText("Test Connection");
            if (ok) {
                tvConnStatus.setText("✔ Connected successfully!");
                tvConnStatus.setTextColor(0xFF1B8A1B);
            } else {
                tvConnStatus.setText("✘ " + msg);
                tvConnStatus.setTextColor(0xFFC0392B);
            }
        });
    }

    private int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
