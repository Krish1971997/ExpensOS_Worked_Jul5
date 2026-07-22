package com.expenseos.ui.backup;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.adapter.BackupAdapter;
import com.expenseos.sync.BackupManager;

import java.io.File;
import java.util.List;

public class BackupFragment extends Fragment {

    private RecyclerView rvBackups;
    private BackupAdapter backupAdapter;
    private TextView tvStats;
    private TextView tvStatTotal, tvStatSuccess, tvStatScheduled, tvStatFailed, tvScheduleBanner;
    private RadioGroup rgBackupMode;

    private final ActivityResultLauncher<String[]> openFileLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) restoreFromUri(uri);
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_backup_zip, container, false);

        tvStats = root.findViewById(R.id.tv_backup_stats);
        tvStatTotal = root.findViewById(R.id.tv_stat_total);
        tvStatSuccess = root.findViewById(R.id.tv_stat_success);
        tvStatScheduled = root.findViewById(R.id.tv_stat_scheduled);
        tvStatFailed = root.findViewById(R.id.tv_stat_failed);
        tvScheduleBanner = root.findViewById(R.id.tv_schedule_banner);
        rgBackupMode = root.findViewById(R.id.rgBackupMode);
        rvBackups = root.findViewById(R.id.rv_backups);
        rvBackups.setLayoutManager(new LinearLayoutManager(getContext()));

        Button btnCreate = root.findViewById(R.id.btn_create_backup);
        Button btnRestoreZip = root.findViewById(R.id.btn_upload_backup);

        btnCreate.setOnClickListener(v -> {
            EditText etDesc = root.findViewById(R.id.et_backup_desc);
            String desc = etDesc.getText().toString().trim();
            BackupManager.BackupMode mode = rgBackupMode.getCheckedRadioButtonId() == R.id.rbCloud
                    ? BackupManager.BackupMode.CLOUD : BackupManager.BackupMode.LOCAL;

            btnCreate.setEnabled(false);
            btnCreate.setText(mode == BackupManager.BackupMode.CLOUD ? "Uploading…" : "Creating…");

            BackupManager.get().createBackup(requireContext(), desc, mode, (ok, msg, file) -> {
                btnCreate.setEnabled(true);
                btnCreate.setText("+ Create Backup");
                Toast.makeText(getContext(), ok ? "✔ " + msg : "✘ " + msg, Toast.LENGTH_LONG).show();
                if (ok && file != null) shareFile(file); // only LOCAL returns a file
                loadBackups();
            });
        });

        btnRestoreZip.setOnClickListener(v ->
                openFileLauncher.launch(new String[]{"application/zip", "*/*"}));

        loadBackups();
        return root;
    }

    private void loadBackups() {
        List<BackupManager.BackupEntry> list = BackupManager.get().listBackups(requireContext());
        tvStats.setText("Total: " + list.size() + " backup(s)");

        int success = 0, scheduled = 0, failed = 0;
        for (BackupManager.BackupEntry e : list) {
            if ("SUCCESS".equals(e.status)) success++;
            if ("SCHEDULED".equals(e.backupType)) scheduled++;
            if ("FAILED".equals(e.status)) failed++;
        }
        tvStatTotal.setText(String.valueOf(list.size()));
        tvStatSuccess.setText(String.valueOf(success));
        tvStatScheduled.setText(String.valueOf(scheduled));
        tvStatFailed.setText(String.valueOf(failed));

        com.expenseos.util.AppConfig cfg = com.expenseos.util.AppConfig.get(requireContext());
        tvScheduleBanner.setText(String.format(
                "📅 Daily Auto-Backup Active — Runs every day at %02d:%02d. A safety backup is always created before any restore.",
                cfg.getBackupHour(), cfg.getBackupMinute()));

        backupAdapter = new BackupAdapter(list,
                entry -> { // download
                    Toast.makeText(getContext(), "Preparing download…", Toast.LENGTH_SHORT).show();
                    BackupManager.get().downloadForShare(requireContext(), entry, (ok, msg, file) -> {
                        if (ok && file != null) {
                            shareFile(file); // opens Android share-sheet → user picks "Save to Downloads" / Drive / etc.
                        } else {
                            Toast.makeText(getContext(), "✘ " + msg, Toast.LENGTH_LONG).show();
                        }
                    });
                },
                entry -> { /* restore — same as before */
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle("Restore Backup")
                            .setMessage("This will REPLACE all current data with the contents of \""
                                    + entry.fileName + "\" (" + entry.backupMode + "). Continue?")
                            .setPositiveButton("Restore", (d, w) -> {
                                Toast.makeText(getContext(), "Restoring…", Toast.LENGTH_SHORT).show();
                                BackupManager.get().restoreBackup(requireContext(), entry, (ok, msg, f) -> {
                                    Toast.makeText(getContext(), ok ? "✔ " + msg : "✘ " + msg, Toast.LENGTH_LONG).show();
                                    loadBackups();
                                });
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                },
                entry -> { /* delete — new */
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle("Delete Backup")
                            .setMessage("Delete \"" + entry.fileName + "\" permanently"
                                    + ("CLOUD".equals(entry.backupMode) ? " (from Zoho WorkDrive too)" : "") + "?")
                            .setPositiveButton("Delete", (d, w) ->
                                    BackupManager.get().deleteBackup(requireContext(), entry, (ok, msg, f) -> {
                                        Toast.makeText(getContext(), ok ? "✔ " + msg : "✘ " + msg, Toast.LENGTH_SHORT).show();
                                        loadBackups();
                                    }))
                            .setNegativeButton("Cancel", null)
                            .show();
                });
        rvBackups.setAdapter(backupAdapter);
    }

    private void shareFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(requireContext(),
                    requireContext().getPackageName() + ".fileprovider",   // <-- ".provider" → ".fileprovider"
                    file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Export Backup"));
        } catch (Exception e) {
            Toast.makeText(getContext(), "Cannot share: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void restoreFromUri(Uri uri) {
        try {
            File cacheFile = new File(requireContext().getCacheDir(), "restore_import.zip");
            try (java.io.InputStream is = requireContext().getContentResolver().openInputStream(uri);
                 java.io.FileOutputStream fos = new java.io.FileOutputStream(cacheFile)) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
            }
            BackupManager.get().restoreBackup(requireContext(), cacheFile, (ok, msg, f) -> {
                Toast.makeText(getContext(), ok ? "✔ " + msg : "✘ " + msg, Toast.LENGTH_LONG).show();
                loadBackups();
            });
        } catch (Exception e) {
            Toast.makeText(getContext(), "Import error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}