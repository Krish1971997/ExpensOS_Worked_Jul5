package com.expenseos.ui.backup;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
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

    private final ActivityResultLauncher<String[]> openFileLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) restoreFromUri(uri);
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_backup_zip, container, false);

        tvStats = root.findViewById(R.id.tv_backup_stats);
        rvBackups = root.findViewById(R.id.rv_backups);
        rvBackups.setLayoutManager(new LinearLayoutManager(getContext()));

        Button btnCreate = root.findViewById(R.id.btn_create_backup);
        Button btnRestore = root.findViewById(R.id.btn_upload_backup);

        btnCreate.setOnClickListener(v -> {
            EditText etDesc = root.findViewById(R.id.et_backup_desc);
            String desc = etDesc.getText().toString().trim();
            btnCreate.setEnabled(false);
            btnCreate.setText("Creating…");
            BackupManager.get().createBackup(requireContext(), desc, (ok, msg, file) -> {
                btnCreate.setEnabled(true);
                btnCreate.setText("+ Create Backup");
                Toast.makeText(getContext(), ok ? "✔ " + msg : "✘ " + msg,
                        Toast.LENGTH_LONG).show();
                if (ok && file != null) {
                    shareFile(file); // prompt to share/save
                }
                loadBackups();
            });
        });

        btnRestore.setOnClickListener(v ->
                openFileLauncher.launch(new String[]{"application/zip", "*/*"}));

        loadBackups();
        return root;
    }

    private void loadBackups() {
        List<BackupManager.BackupEntry> list =
                BackupManager.get().listBackups(requireContext());
        tvStats.setText("Total: " + list.size() + " backup(s)");
        backupAdapter = new BackupAdapter(list, entry -> {
            // Download/share the backup file
            File f = new File(entry.filePath);
            if (f.exists()) {
                shareFile(f);
            } else {
                Toast.makeText(getContext(), "File not found: " + entry.filePath,
                        Toast.LENGTH_SHORT).show();
            }
        });
        rvBackups.setAdapter(backupAdapter);
    }

    private void shareFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(requireContext(),
                    requireContext().getPackageName() + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Export Backup"));
        } catch (Exception e) {
            Toast.makeText(getContext(), "Cannot share: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void restoreFromUri(Uri uri) {
        try {
            // Copy to cache
            File cacheFile = new File(requireContext().getCacheDir(), "restore_import.zip");
            try (java.io.InputStream is = requireContext().getContentResolver()
                    .openInputStream(uri);
                 java.io.FileOutputStream fos = new java.io.FileOutputStream(cacheFile)) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
            }
            BackupManager.get().restoreBackup(requireContext(), cacheFile, (ok, msg, f) -> {
                Toast.makeText(getContext(), ok ? "✔ " + msg : "✘ " + msg,
                        Toast.LENGTH_LONG).show();
                loadBackups();
            });
        } catch (Exception e) {
            Toast.makeText(getContext(), "Import error: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }
}
