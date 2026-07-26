package com.expenseos.ui;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.expenseos.R;
import com.expenseos.ui.settings.ConsoleFragment;

public class LogActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_console_log);

        findViewById(R.id.btnConsoleBack).setOnClickListener(v -> finish());

        if (savedInstanceState() == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.consoleFragmentContainer, new ConsoleFragment())
                    .commit();
        }
    }

    private Bundle savedInstanceState() {
        return null; // placeholder to keep onCreate readable; see note below
    }
}