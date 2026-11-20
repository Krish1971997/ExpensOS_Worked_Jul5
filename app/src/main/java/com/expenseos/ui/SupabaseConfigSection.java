package com.expenseos.ui;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.expenseos.R;
import com.expenseos.cloud.AttachmentMigration;
import com.expenseos.cloud.SupabaseStorage;
import com.expenseos.util.AppConfig;

/**
 * Binds the "SUPABASE STORAGE (ATTACHMENTS)" card in the Config screen:
 * save (secrets encrypted on the phone), test the login + bucket, and run the one-time migration
 * of existing attachments to encrypted Supabase objects.
 */
public final class SupabaseConfigSection {

    private SupabaseConfigSection() {
    }

    public static void bind(View root, Activity a) {
        AppConfig cfg = AppConfig.get(a);
        EditText etUrl = root.findViewById(R.id.etCfgSbUrl);
        EditText etBucket = root.findViewById(R.id.etCfgSbBucket);
        EditText etKey = root.findViewById(R.id.etCfgSbAnonKey);
        EditText etEmail = root.findViewById(R.id.etCfgSbEmail);
        EditText etPass = root.findViewById(R.id.etCfgSbPassword);
        TextView tv = root.findViewById(R.id.tvCfgSbResult);

        if (etUrl == null || etBucket == null || etKey == null || etEmail == null
                || etPass == null || tv == null) return;

        etUrl.setText(cfg.getSupabaseUrl());
        etBucket.setText(cfg.getSupabaseBucket());
        etEmail.setText(cfg.getSupabaseEmail());
        if (!cfg.getSupabaseAnonKey().isEmpty()) etKey.setHint("•••••••• (saved — blank = keep)");
        if (!cfg.getSupabasePassword().isEmpty()) etPass.setHint("•••••••• (saved — blank = keep)");

        root.findViewById(R.id.btnSaveCfgSb).setOnClickListener(v -> {
            String key = etKey.getText().toString().trim();
            if (key.isEmpty()) key = cfg.getSupabaseAnonKey();
            String pass = etPass.getText().toString();
            if (pass.isEmpty()) pass = cfg.getSupabasePassword();
            cfg.setSupabase(etUrl.getText().toString(), etBucket.getText().toString(),
                    key, etEmail.getText().toString(), pass);
            etKey.setText("");
            etPass.setText("");
            etUrl.setText(cfg.getSupabaseUrl());   // shows the normalized URL (host only)
            etKey.setHint(cfg.getSupabaseAnonKey().isEmpty() ? "anon / publishable key" : "•••••••• (saved — blank = keep)");
            etPass.setHint(cfg.getSupabasePassword().isEmpty() ? "password" : "•••••••• (saved — blank = keep)");
            Toast.makeText(a, "Supabase config saved", Toast.LENGTH_SHORT).show();   // the result line may be off-screen
            show(tv, true, "✓ Saved (secrets encrypted on this phone)");
        });

        root.findViewById(R.id.btnTestCfgSb).setOnClickListener(v -> {
            show(tv, true, "Testing…");
            new Thread(() -> {
                try {
                    SupabaseStorage.testConnection(a);
                    a.runOnUiThread(() -> show(tv, true, "✓ Login + bucket access OK"));
                } catch (Exception e) {
                    a.runOnUiThread(() -> show(tv, false, "✗ " + e.getMessage()));
                }
            }).start();
        });

        View btnMigrate = root.findViewById(R.id.btnMigrateCfgSb);
        if (btnMigrate != null) btnMigrate.setOnClickListener(v -> {
            v.setEnabled(false);
            show(tv, true, "Migrating… keep the app open until this finishes");
            new Thread(() -> {
                try {
                    String msg = AttachmentMigration.runAll(a);
                    a.runOnUiThread(() -> show(tv, true, "✓ " + msg));
                } catch (Exception e) {
                    a.runOnUiThread(() -> show(tv, false, "✗ " + e.getMessage()));
                } finally {
                    a.runOnUiThread(() -> v.setEnabled(true));
                }
            }).start();
        });
    }

    private static void show(TextView tv, boolean ok, String msg) {
        tv.setVisibility(View.VISIBLE);
        tv.setTextColor(Color.parseColor(ok ? "#2E7D32" : "#C62828"));
        tv.setText(msg);
    }
}
