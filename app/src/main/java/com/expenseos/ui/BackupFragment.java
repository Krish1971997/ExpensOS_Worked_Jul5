package com.expenseos.ui;

import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.expenseos.R;
import com.expenseos.db.LocalDB;
import com.expenseos.db.SyncManager;

public class BackupFragment extends Fragment {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Keep button + status references — avoid re-finding after async
    private Button btnFullSync, btnPullCloud;
    private TextView tvBackupStatus, tvTotalRecords, tvPendingSync;

    @Override
    public View onCreateView(@NonNull LayoutInflater inf, ViewGroup pg, Bundle s) {
        return inf.inflate(R.layout.fragment_backup, pg, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        super.onViewCreated(v, s);

        btnFullSync = v.findViewById(R.id.btnFullSync);
        btnPullCloud = v.findViewById(R.id.btnPullCloud);
        tvBackupStatus = v.findViewById(R.id.tvBackupStatus);
        tvTotalRecords = v.findViewById(R.id.tvTotalRecords);
        tvPendingSync = v.findViewById(R.id.tvPendingSync);

        loadLocalStats();
        setupButtons();
    }

    private void setupButtons() {
        // ── Full Sync (Push + Pull) ─────────────────────────
        btnFullSync.setOnClickListener(v -> startSync());

        // ── Pull only — just shows info for now ─────────────
        btnPullCloud.setOnClickListener(v ->
                Toast.makeText(requireContext(),
                        "Use ⟳ Sync — both push & pull happen together",
                        Toast.LENGTH_SHORT).show());
    }

    private void startSync() {
        // Guard: check DB URL configured
        SharedPreferences prefs = requireContext()
                .getSharedPreferences("expenseos_prefs",
                        android.content.Context.MODE_PRIVATE);
        String url = prefs.getString("db_url", "");
        if (url.isEmpty()) {
            showStatus("✗ DB URL not configured. Go to Config tab.", false);
            return;
        }

        // Disable button, show loading
        btnFullSync.setEnabled(false);
        btnFullSync.setText("Syncing…");
        showStatus("⏳ Connecting to Neon DB…", null);

        // ── SyncManager runs on its own ExecutorService ─────
        SyncManager.sync(requireContext(), new SyncManager.SyncCallback() {
            @Override
            public void onSuccess(String msg) {
                mainHandler.post(() -> {
                    // Guard: fragment still attached?
                    if (!isAdded() || getView() == null) return;
                    btnFullSync.setEnabled(true);
                    btnFullSync.setText("⟳ Sync to Cloud (Push + Pull)");
                    showStatus("✓ " + msg, true);
                    loadLocalStats(); // refresh counts
                });
            }

            @Override
            public void onError(String err) {
                mainHandler.post(() -> {
                    if (!isAdded() || getView() == null) return;
                    btnFullSync.setEnabled(true);
                    btnFullSync.setText("⟳ Sync to Cloud (Push + Pull)");
                    showStatus("✗ Sync failed: " + err, false);
                });
            }
        });
    }

    private void loadLocalStats() {
        if (!isAdded() || getView() == null) return;

        SharedPreferences prefs = requireContext()
                .getSharedPreferences("expenseos_prefs",
                        android.content.Context.MODE_PRIVATE);
        int bookId = prefs.getInt("active_book_id", 0);

        // Total records in this book
        Cursor c1 = LocalDB.getInstance(requireContext())
                .getReadableDatabase()
                .rawQuery("SELECT COUNT(*) FROM transactions WHERE book_id=?",
                        new String[]{String.valueOf(bookId)});
        int total = 0;
        if (c1.moveToFirst()) total = c1.getInt(0);
        c1.close();

        // Pending sync
        Cursor c2 = LocalDB.getInstance(requireContext())
                .getReadableDatabase()
                .rawQuery("SELECT COUNT(*) FROM transactions WHERE synced=0 AND book_id=?",
                        new String[]{String.valueOf(bookId)});
        int pending = 0;
        if (c2.moveToFirst()) pending = c2.getInt(0);
        c2.close();

        tvTotalRecords.setText("Total local records: " + total);
        tvPendingSync.setText(pending > 0
                ? "⚠ Pending sync: " + pending
                : "✓ All records synced");
        tvPendingSync.setTextColor(requireContext().getResources()
                .getColor(pending > 0 ? R.color.amber : R.color.green, null));
    }

    /**
     * ok=true → green, ok=false → red, ok=null → amber (loading)
     */
    private void showStatus(String msg, Boolean ok) {
        tvBackupStatus.setVisibility(View.VISIBLE);
        tvBackupStatus.setText(msg);
        if (ok == null) {
            tvBackupStatus.setTextColor(requireContext().getResources()
                    .getColor(R.color.amber, null));
        } else if (ok) {
            tvBackupStatus.setTextColor(requireContext().getResources()
                    .getColor(R.color.green, null));
        } else {
            tvBackupStatus.setTextColor(requireContext().getResources()
                    .getColor(R.color.red, null));
        }
    }
}