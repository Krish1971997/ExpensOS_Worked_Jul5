package com.expenseos.ui;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.expenseos.R;

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
        setContentView(R.layout.activity_config);

        findViewById(R.id.btnConfigBack).setOnClickListener(v -> finish());

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.configFragmentContainer, new ConfigFragment())
                    .commit();
        }
    }
}