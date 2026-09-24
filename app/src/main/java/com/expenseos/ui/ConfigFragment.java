package com.expenseos.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.expenseos.R;
import com.expenseos.util.AppConfig;

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
    private EditText etGmailFrom, etGmailPass, etAlertEmail;
    private EditText etZohoClientId, etZohoClientSecret, etZohoRefreshToken, etWorkdriveFolderId;

    // Status & AI Spinner views
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
        enhanceAllFields();
        loadSavedValues();
        setupButtons();
    }

    /**
     * Wraps each field with a copy button (and a show/hide toggle for secrets),
     * without touching the XML layout.
     */
    private void enhanceAllFields() {
        enhanceField(etDbUrl, false);
        enhanceField(etDbUser, false);
        enhanceField(etDbPass, true);
        enhanceField(etGmailFrom, false);
        enhanceField(etGmailPass, true);
        enhanceField(etAlertEmail, false);
        enhanceField(etZohoClientId, false);
        enhanceField(etZohoClientSecret, true);
        enhanceField(etZohoRefreshToken, true);
        enhanceField(etWorkdriveFolderId, false);
    }

    private void enhanceField(EditText et, boolean isSecret) {
        if (et == null || !(et.getParent() instanceof ViewGroup parent)) return;
        int idx = parent.indexOfChild(et);
        ViewGroup.LayoutParams originalLp = et.getLayoutParams();
        parent.removeView(et);

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(originalLp); // keep whatever margins/width the field had in XML

        et.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(et);

        int iconSize = (int) (36 * getResources().getDisplayMetrics().density);
        int iconPad = (int) (6 * getResources().getDisplayMetrics().density);

        if (isSecret) {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            ImageButton toggle = new ImageButton(requireContext());
            toggle.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
            toggle.setPadding(iconPad, iconPad, iconPad, iconPad);
            toggle.setBackgroundResource(android.R.color.transparent);
            toggle.setImageResource(android.R.drawable.ic_menu_view);
            toggle.setContentDescription("Show/hide");
            toggle.setOnClickListener(v -> {
                int variation = et.getInputType() & InputType.TYPE_MASK_VARIATION;
                boolean currentlyMasked = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD;
                et.setInputType(InputType.TYPE_CLASS_TEXT | (currentlyMasked
                        ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        : InputType.TYPE_TEXT_VARIATION_PASSWORD));
                et.setSelection(et.getText().length());
            });
            row.addView(toggle);
        }

        ImageButton copy = new ImageButton(requireContext());
        copy.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
        copy.setPadding(iconPad, iconPad, iconPad, iconPad);
        copy.setBackgroundResource(android.R.color.transparent);
        copy.setImageResource(android.R.drawable.ic_menu_save);
        copy.setContentDescription("Copy");
        copy.setOnClickListener(v -> copyToClipboard(et.getText().toString()));
        row.addView(copy);

        parent.addView(row, idx);
    }

    private void copyToClipboard(String text) {
        if (text == null || text.isEmpty()) {
            toast("Nothing to copy");
            return;
        }
        ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("ExpenseOS", text));
        toast("✓ Copied");
    }

    private void bindViews(View v) {
        etDbUrl = v.findViewById(R.id.etCfgDbUrl);
        etDbUser = v.findViewById(R.id.etCfgDbUser);
        etDbPass = v.findViewById(R.id.etCfgDbPass);
        etGmailFrom = v.findViewById(R.id.etCfgGmailFrom);
        etGmailPass = v.findViewById(R.id.etCfgGmailPass);
        etAlertEmail = v.findViewById(R.id.etCfgAlertEmail);

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
        etAlertEmail.setText(AppConfig.get(requireContext()).getSchedulerAlertEmail());

        AppConfig cfg = AppConfig.get(requireContext());
        etZohoClientId.setText(cfg.getZohoClientId());
        etZohoClientSecret.setText(cfg.getZohoClientSecret());
        etZohoRefreshToken.setText(cfg.getZohoRefreshToken());
        etWorkdriveFolderId.setText(cfg.getWorkdriveFolderId());

    }

    private void setupButtons() {
        requireView().findViewById(R.id.btnSaveCfgDb).setOnClickListener(v -> {
            saveDbPrefs();
            toast("✓ DB Config saved!");
        });

        requireView().findViewById(R.id.btnSaveCfgGmail).setOnClickListener(v -> {
            saveGmailPrefs();
            toast("✓ Gmail Config saved!");
        });

        requireView().findViewById(R.id.btnSaveCfgZoho).setOnClickListener(v -> {
            AppConfig.get(requireContext()).setZoho(
                    etZohoClientId.getText().toString().trim(),
                    etZohoClientSecret.getText().toString().trim(),
                    etZohoRefreshToken.getText().toString().trim(),
                    etWorkdriveFolderId.getText().toString().trim());
            toast("✓ Zoho Config saved!");
        });

        btnTestConnection.setOnClickListener(v -> testConnection());
        btnSyncConfigToDb.setOnClickListener(v -> syncConfigToDb());
    }

    private void testConnection() {
        String url = etDbUrl.getText().toString().trim();
        String user = etDbUser.getText().toString().trim();
        String pass = etDbPass.getText().toString().trim();

        if (url.isEmpty()) {
            showResult(tvConnectionResult, "✗ DB URL is empty", false);
            return;
        }

        saveDbPrefs();
        setButtonState(btnTestConnection, false, "Testing…");
        showResult(tvConnectionResult, "⏳ Connecting to Neon DB…", null);

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

            final String finalResult = result;
            final boolean finalOk = ok;
            mainHandler.post(() -> {
                if (!isAdded() || getView() == null) return;
                setButtonState(btnTestConnection, true, "🔗 Test Connection");
                showResult(tvConnectionResult, finalResult, finalOk);
            });
        });
    }

    private void syncConfigToDb() {
        saveDbPrefs();
        saveGmailPrefs();

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

                conn.createStatement().execute(
                        "CREATE TABLE IF NOT EXISTS app_config (" +
                                "  key         VARCHAR(100) PRIMARY KEY," +
                                "  value       TEXT," +
                                "  updated_at  TIMESTAMP DEFAULT NOW()" +
                                ")"
                );

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

    private void saveDbPrefs() {
        String url = etDbUrl.getText().toString().trim();
        String user = etDbUser.getText().toString().trim();
        String pass = etDbPass.getText().toString().trim();

        AppConfig.get(requireContext()).setDb(url, user, pass);
        prefs.edit()
                .putString("db_url", url)
                .putString("db_user", user)
                .putString("db_pass", pass)
                .apply();
    }

    private void saveGmailPrefs() {
        String from = etGmailFrom.getText().toString().trim();
        String pass = etGmailPass.getText().toString().trim();
        String alertEmail = etAlertEmail.getText().toString().trim();

        AppConfig.get(requireContext()).setGmail(from, pass);
        AppConfig.get(requireContext()).setSchedulerAlertEmail(alertEmail);
        prefs.edit()
                .putString("gmail_from", from)
                .putString("gmail_pass", pass)
                .apply();
    }

    private void setButtonState(Button btn, boolean enabled, String text) {
        btn.setEnabled(enabled);
        btn.setText(text);
    }

    private void showResult(TextView tv, String msg, Boolean ok) {
        tv.setVisibility(View.VISIBLE);
        tv.setText(msg);
        if (ok == null) {
            tv.setTextColor(requireContext().getResources().getColor(R.color.amber, null));
        } else if (ok) {
            tv.setTextColor(requireContext().getResources().getColor(R.color.green, null));
        } else {
            tv.setTextColor(requireContext().getResources().getColor(R.color.red, null));
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