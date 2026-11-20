package com.expenseos.ui;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.expenseos.R;
import com.expenseos.util.ConfigExporter;

/**
 * Hosts ConfigFragment (DB/Gmail config) as a standalone screen. Needed
 * because Config moved from the bottom nav (in-place Fragment swap inside
 * HomeActivity) to the side drawer (launches separate Activities) — see the
 * Settings <-> Configuration swap in HomeActivity/activity_home.xml.
 */
public class ConfigActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Allow screenshots on this screen even if a global FLAG_SECURE is set elsewhere
//        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_config);

        findViewById(R.id.btnConfigBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnConfigExport).setOnClickListener(v -> ConfigExporter.start(this));

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.configFragmentContainer, new ConfigFragment())
                    .commit();
        }
    }
}