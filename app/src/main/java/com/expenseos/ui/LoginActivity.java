package com.expenseos.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.expenseos.R;
import com.expenseos.util.AuthManager;

public class LoginActivity extends AppCompatActivity {

    private Button btn;
    private ProgressBar bar;
    private TextView tvMsg;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        if (AuthManager.isLoggedIn(this)) {
            goHome();
            return;
        }
        setContentView(R.layout.activity_login);
        btn = findViewById(R.id.btnZohoLogin);
        bar = findViewById(R.id.loginProgress);
        tvMsg = findViewById(R.id.tvLoginMsg);
        btn.setOnClickListener(v -> startLogin());
    }

    private void startLogin() {
        setBusy(true, null);
        AuthManager.presentLogin(this, new AuthManager.LoginCallback() {
            @Override
            public void onSuccess() {
                final android.content.Context app = getApplicationContext();
                new Thread(() -> {
                    String msg;
                    try {
                        msg = "✔ Server: " + com.expenseos.util.CatalystClient.get(app, "/me").optString("email");

                        // Only restore on a fresh install (restore uses REPLACE, so it can overwrite unsynced local rows)
                        boolean localEmpty;
                        try (android.database.Cursor c = com.expenseos.dao.LocalDatabase.get(app)
                                .getReadableDatabase().rawQuery("SELECT COUNT(*) FROM cash_books", null)) {
                            c.moveToFirst();
                            localEmpty = c.getInt(0) == 0;
                        }
                        if (localEmpty) {
                            com.expenseos.sync.SyncManager.get().restoreAllFromCloud(app, new com.expenseos.sync.SyncManager.SyncCallback() {
                                @Override
                                public void onComplete(boolean success, String summary) {
                                    // Show summary, then goHome()
                                }
                            });
                        }

                    } catch (Exception e) {
                        msg = "✘ Server check failed: " + e.getMessage();
                    }
                    final String m = msg;
                    runOnUiThread(() -> {
                        android.widget.Toast.makeText(app, m, android.widget.Toast.LENGTH_LONG).show();
                        goHome();
                    });
                }).start();
            }

            @Override
            public void onFailure(String reason) {
                runOnUiThread(() -> setBusy(false, "Login failed: " + reason));
            }
        });
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