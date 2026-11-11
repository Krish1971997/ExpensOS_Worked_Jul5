package com.expenseos.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.expenseos.R;
import com.expenseos.android.timesheet.OdooClient;
import com.expenseos.util.AppConfig;
import com.expenseos.util.AppLockManager;

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
    private EditText etZohoClientId, etZohoClientSecret, etZohoRefreshToken, etWorkdriveFolderId, etImportExcelFileName;
    // Odoo Timesheet
    private EditText etOdooUrl, etOdooDb, etOdooUser, etOdooApiKey;

    // Status & AI Spinner views
    private Button btnTestConnection, btnSyncConfigToDb;
    private TextView tvConnectionResult, tvSyncConfigStatus;

    // App Lock rows (built in code)
    private androidx.appcompat.widget.SwitchCompat swAppLock, swAppLockBio;
    private View rowAppLockBio, rowAppLockPin, rowAppLockTimeout;
    private TextView tvAppLockTimeout;
    private final ActivityResultLauncher<Intent> lockLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    r -> refreshAppLockUi());

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
        setupAlarmSettings(v);
        setupAppLockSettings(v);
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
        enhanceField(etImportExcelFileName, false);
        enhanceField(etOdooUrl, false);
        enhanceField(etOdooDb, false);
        enhanceField(etOdooUser, false);
        enhanceField(etOdooApiKey, true);
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

    // ── Alarm settings: Gradually increase volume + Vibration (saved instantly) ──
    private static final int[] RAMP_OPTIONS = {0, 5, 10, 15, 20, 25, 30};

    private static String rampLabel(int sec) {
        return sec == 0 ? "Off" : sec + " seconds";
    }

    /**
     * Built in code (no XML change). Inserted right below the section that holds the Zoho save
     * button: climbs from that button up to the scroll content column, then adds after that section.
     */

    private void setupAlarmSettings(View v) {
        AppConfig cfg = AppConfig.get(requireContext());

        TextView rampValue = v.findViewById(R.id.tvAlarmRampValue);
        rampValue.setText(rampLabel(cfg.getAlarmVolumeRampSeconds()));
        v.findViewById(R.id.rowAlarmRamp).setOnClickListener(x -> showRampDialog(rampValue));

        androidx.appcompat.widget.SwitchCompat vib = v.findViewById(R.id.swAlarmVibration);
        vib.setChecked(cfg.isAlarmVibrationEnabled());
        vib.setOnCheckedChangeListener((b, on) -> cfg.setAlarmVibrationEnabled(on));
        v.findViewById(R.id.rowAlarmVibration).setOnClickListener(x -> vib.toggle());

        // Pre-alarm notification (Off / 15 / 30 / 60 / Custom)
        TextView preValue = v.findViewById(R.id.tvPreAlarmValue);
        preValue.setText(AppConfig.preAlarmLabel(cfg.getPreAlarmMinutes()));
        v.findViewById(R.id.rowPreAlarm).setOnClickListener(x ->
                AppConfig.showPreAlarmDialog(requireActivity(),
                        () -> preValue.setText(AppConfig.preAlarmLabel(cfg.getPreAlarmMinutes()))));
    }


    // ── App Lock (built in code, inserted right below the Pre-alarm row) ──
    private static final int[] LOCK_TIMEOUT_OPTIONS = {0, 30, 60, 300, 900};

    private static String lockTimeoutLabel(int sec) {
        if (sec == 0) return "Immediately";
        if (sec < 60) return sec + " seconds";
        return (sec / 60) + (sec == 60 ? " minute" : " minutes");
    }

    private int appLockDp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private LinearLayout appLockRow(View styleSource, String title) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(styleSource.getPaddingLeft(), styleSource.getPaddingTop(),
                styleSource.getPaddingRight(), styleSource.getPaddingBottom());
        row.setClickable(true);
        TypedValue tv = new TypedValue();
        requireContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);

        TextView t = new TextView(requireContext());
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(requireContext().getColor(R.color.text));
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private boolean appLockBiometricAvailable() {
        return androidx.biometric.BiometricManager.from(requireContext())
                .canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
                == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS;
    }

    private void setupAppLockSettings(View v) {
        View anchor = v.findViewById(R.id.rowPreAlarm);
        if (anchor == null || !(anchor.getParent() instanceof LinearLayout parent)) return;
        if (parent.getOrientation() != LinearLayout.VERTICAL) return;
        Context ctx = requireContext();
        int at = parent.indexOfChild(anchor) + 1;

        TextView header = new TextView(ctx);
        header.setText("APP LOCK");
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        header.setTypeface(header.getTypeface(), android.graphics.Typeface.BOLD);
        header.setTextColor(ctx.getColor(R.color.primary));
        header.setPadding(anchor.getPaddingLeft(), appLockDp(16),
                anchor.getPaddingRight(), appLockDp(4));

        LinearLayout rowLock = appLockRow(anchor, "App Lock (PIN)");
        swAppLock = new androidx.appcompat.widget.SwitchCompat(ctx);
        rowLock.addView(swAppLock);

        LinearLayout rowBio = appLockRow(anchor, "Unlock with fingerprint / face");
        swAppLockBio = new androidx.appcompat.widget.SwitchCompat(ctx);
        rowBio.addView(swAppLockBio);
        rowAppLockBio = rowBio;

        LinearLayout rowPin = appLockRow(anchor, "Change PIN");
        rowAppLockPin = rowPin;

        LinearLayout rowTimeout = appLockRow(anchor, "Lock after leaving app");
        tvAppLockTimeout = new TextView(ctx);
        tvAppLockTimeout.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvAppLockTimeout.setTextColor(ctx.getColor(R.color.text_muted));
        rowTimeout.addView(tvAppLockTimeout);
        rowAppLockTimeout = rowTimeout;

        ViewGroup.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        parent.addView(header, at++, lp);
        parent.addView(rowLock, at++, new LinearLayout.LayoutParams(lp));
        parent.addView(rowBio, at++, new LinearLayout.LayoutParams(lp));
        parent.addView(rowPin, at++, new LinearLayout.LayoutParams(lp));
        parent.addView(rowTimeout, at, new LinearLayout.LayoutParams(lp));

        rowLock.setOnClickListener(x -> swAppLock.performClick());
        swAppLock.setOnClickListener(x -> lockLauncher.launch(swAppLock.isChecked()
                ? LockActivity.setupIntent(ctx) : LockActivity.disableIntent(ctx)));
        rowBio.setOnClickListener(x -> swAppLockBio.performClick());
        swAppLockBio.setOnClickListener(x ->
                AppLockManager.setBiometricEnabled(ctx, swAppLockBio.isChecked()));
        rowPin.setOnClickListener(x -> lockLauncher.launch(LockActivity.setupIntent(ctx)));
        rowTimeout.setOnClickListener(x -> showLockTimeoutDialog());

        refreshAppLockUi();
    }

    private void refreshAppLockUi() {
        if (swAppLock == null || !isAdded()) return;
        Context ctx = requireContext();
        boolean on = AppLockManager.isEnabled(ctx);
        swAppLock.setChecked(on);
        swAppLockBio.setChecked(AppLockManager.isBiometricEnabled(ctx));
        tvAppLockTimeout.setText(lockTimeoutLabel(AppLockManager.getTimeoutSeconds(ctx)));
        rowAppLockPin.setVisibility(on ? View.VISIBLE : View.GONE);
        rowAppLockTimeout.setVisibility(on ? View.VISIBLE : View.GONE);
        rowAppLockBio.setVisibility(on && appLockBiometricAvailable() ? View.VISIBLE : View.GONE);
    }

    private void showLockTimeoutDialog() {
        Context ctx = requireContext();
        String[] labels = new String[LOCK_TIMEOUT_OPTIONS.length];
        int checked = 0;
        int current = AppLockManager.getTimeoutSeconds(ctx);
        for (int i = 0; i < LOCK_TIMEOUT_OPTIONS.length; i++) {
            labels[i] = lockTimeoutLabel(LOCK_TIMEOUT_OPTIONS[i]);
            if (LOCK_TIMEOUT_OPTIONS[i] == current) checked = i;
        }
        new androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("Lock after leaving app")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    AppLockManager.setTimeoutSeconds(ctx, LOCK_TIMEOUT_OPTIONS[which]);
                    tvAppLockTimeout.setText(labels[which]);
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showRampDialog(TextView valueView) {
        AppConfig cfg = AppConfig.get(requireContext());
        String[] labels = new String[RAMP_OPTIONS.length];
        int checked = 0;
        for (int i = 0; i < RAMP_OPTIONS.length; i++) {
            labels[i] = rampLabel(RAMP_OPTIONS[i]);
            if (RAMP_OPTIONS[i] == cfg.getAlarmVolumeRampSeconds()) checked = i;
        }
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Gradually increase volume")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    cfg.setAlarmVolumeRampSeconds(RAMP_OPTIONS[which]);
                    valueView.setText(rampLabel(RAMP_OPTIONS[which]));
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
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
        etImportExcelFileName = v.findViewById(R.id.etCfgImportExcelFileName);
        etImportExcelFileName.setHint("File1.zsheetlnk, File2.xlsx (comma separated)");
        etImportExcelFileName.setSingleLine(false);
        etImportExcelFileName.setMaxLines(4);

        etOdooUrl = v.findViewById(R.id.etCfgOdooUrl);
        etOdooDb = v.findViewById(R.id.etCfgOdooDb);
        etOdooUser = v.findViewById(R.id.etCfgOdooUser);
        etOdooApiKey = v.findViewById(R.id.etCfgOdooApiKey);
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
        etImportExcelFileName.setText(cfg.getImportExcelFileNamesRaw());
        etOdooUrl.setText(cfg.getOdooUrl());
        etOdooDb.setText(cfg.getOdooDb());
        etOdooUser.setText(cfg.getOdooUser());
        etOdooApiKey.setText(cfg.getOdooApiKey());

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
            AppConfig.get(requireContext()).setImportExcelFileName(
                    etImportExcelFileName.getText().toString().trim());
            toast("✓ Zoho Config saved!");
        });

        requireView().findViewById(R.id.btnSaveCfgOdoo).setOnClickListener(v -> {
            saveOdooPrefs();
            toast("✓ Odoo Config saved!");
        });
        requireView().findViewById(R.id.btnTestCfgOdoo).setOnClickListener(v -> testOdoo());

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

    private void saveOdooPrefs() {
        AppConfig.get(requireContext()).setOdoo(
                etOdooUrl.getText().toString().trim(),
                etOdooDb.getText().toString().trim(),
                etOdooUser.getText().toString().trim(),
                etOdooApiKey.getText().toString().trim());
    }

    private void testOdoo() {
        saveOdooPrefs();
        final Button btn = requireView().findViewById(R.id.btnTestCfgOdoo);
        final TextView tv = requireView().findViewById(R.id.tvCfgOdooResult);
        setButtonState(btn, false, "Testing…");
        showResult(tv, "⏳ Connecting to Odoo…", null);

        final Context app = requireContext().getApplicationContext();
        exec.execute(() -> {
            String result;
            boolean ok;
            try {
                OdooClient.init(app);
                result = "✓ Odoo connected";
                ok = true;
            } catch (Exception e) {
                result = "✗ " + e.getMessage();
                ok = false;
            }
            final String finalResult = result;
            final boolean finalOk = ok;
            mainHandler.post(() -> {
                if (!isAdded() || getView() == null) return;
                setButtonState(btn, true, "🔗 Test Odoo Connection");
                showResult(tv, finalResult, finalOk);
            });
        });
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