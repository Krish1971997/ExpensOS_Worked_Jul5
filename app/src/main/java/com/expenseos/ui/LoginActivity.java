package com.expenseos.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.expenseos.R;
import com.expenseos.dao.LocalDatabase;
import com.expenseos.sync.CloudRestore;
import com.expenseos.util.AppConfig;
import com.expenseos.util.AuthManager;
import com.expenseos.util.CatalystClient;
import com.expenseos.util.ConfigDownloader;
import com.expenseos.util.DBConnection;

public class LoginActivity extends AppCompatActivity {

    private Button btn;
    private ProgressBar bar;
    private TextView tvMsg;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        boolean loggedIn = AuthManager.isLoggedIn(this);
        if (loggedIn && AppConfig.get(this).isConfigured()) {
            goHome();
            return;
        }
        setContentView(R.layout.activity_login);
        btn = findViewById(R.id.btnZohoLogin);
        bar = findViewById(R.id.loginProgress);
        tvMsg = findViewById(R.id.tvLoginMsg);
        btn.setOnClickListener(v -> {
            if (AuthManager.isLoggedIn(this))
                runSetup();   // already signed in: just retry the setup
            else startLogin();
        });
        if (loggedIn) runSetup();   // signed in earlier, but the configuration was never downloaded
    }

    private void startLogin() {
        setBusy(true, null);
        AuthManager.presentLogin(this, new AuthManager.LoginCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> runSetup());
            }

            @Override
            public void onFailure(String reason) {
                runOnUiThread(() -> setBusy(false, "Login failed: " + reason));
            }
        });
    }

    /**
     * After the Zoho login: verify access -> download config from Catalyst -> offer the cloud restore.
     */
    private void runSetup() {
        setBusy(true, null);
        tvMsg.setTextColor(0xFF64748B);
        tvMsg.setText("Downloading configuration...");
        final Context app = getApplicationContext();
        new Thread(() -> {
            try {
                final String email = CatalystClient.get(app, "/me").optString("email");
                final int groups = ConfigDownloader.download(app);
                DBConnection.getInstance().configureFromAppConfig(app);

                boolean localEmpty = isLocalEmpty(app);
                long cloudBooks = 0;
                if (localEmpty) {
                    try {
                        cloudBooks = CloudRestore.cloudRowCount(app, "cash_books");
                    } catch (Exception ignored) {
                    }
                }
                final boolean offerRestore = localEmpty && cloudBooks > 0;
                final long books = cloudBooks;
                runOnUiThread(() -> {
                    Toast.makeText(app, "✔ " + email + " - " + groups + " settings saved", Toast.LENGTH_SHORT).show();
                    if (offerRestore) askRestore(books);
                    else goHome();
                });
            } catch (Exception e) {
                runOnUiThread(() -> showError("Setup failed: " + e.getMessage()));
            }
        }).start();
    }

    private void askRestore(long books) {
        new AlertDialog.Builder(this)
                .setTitle("Restore from cloud?")
                .setMessage("Your cloud backup has " + books + " cash book(s). Download your data to this phone now?")
                .setCancelable(false)
                .setPositiveButton("Yes, restore", (d, w) -> RestoreProgressDialog.run(this, ok -> goHome()))
                .setNegativeButton("No, skip", (d, w) -> goHome())
                .show();
    }

    private void showError(String msg) {
        setBusy(false, msg);
        tvMsg.setTextColor(0xFFD32F2F);
        btn.setText("Retry");
    }

    private static boolean isLocalEmpty(Context app) {
        try (android.database.Cursor c = LocalDatabase.get(app).getReadableDatabase()
                .rawQuery("SELECT COUNT(*) FROM cash_books", null)) {
            c.moveToFirst();
            return c.getInt(0) == 0;
        }
    }

    private void setBusy(boolean busy, String msg) {
        btn.setEnabled(!busy);
        bar.setVisibility(busy ? View.VISIBLE : View.GONE);
        tvMsg.setText(msg == null ? "" : msg);
    }

    private void goHome() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }
}