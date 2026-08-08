package com.expenseos.ui;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.expenseos.R;
import com.expenseos.ui.backup.BackupFragment;

/**
 * Hosts BackupFragment as a standalone screen. Needed because Backup moved
 * from the bottom nav (which swaps Fragments in-place inside HomeActivity)
 * to the side drawer (which launches separate Activities) — see the
 * Scheduler <-> Backup swap in HomeActivity/activity_home.xml.
 */
public class BackupActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_backup);

        findViewById(R.id.btnBackupBack).setOnClickListener(v -> finish());

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.backupFragmentContainer, new BackupFragment())
                    .commit();
        }
    }
}