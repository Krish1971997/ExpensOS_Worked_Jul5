package com.expenseos.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.expenseos.R;
import com.expenseos.dao.CategoryDao;

public class SettingsActivity extends AppCompatActivity {

    private EditText etDbUrl, etDbUser, etDbPass;
    private EditText etGmailFrom, etGmailPass;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_settings);
        prefs = getSharedPreferences("expenseos_prefs", MODE_PRIVATE);

        etDbUrl = findViewById(R.id.etDbUrl);
        etDbUser = findViewById(R.id.etDbUser);
        etDbPass = findViewById(R.id.etDbPass);
        etGmailFrom = findViewById(R.id.etGmailFrom);
        etGmailPass = findViewById(R.id.etGmailPass);

        // Load saved values
        etDbUrl.setText(prefs.getString("db_url", ""));
        etDbUser.setText(prefs.getString("db_user", ""));
        etDbPass.setText(prefs.getString("db_pass", ""));
        etGmailFrom.setText(prefs.getString("gmail_from", ""));
        etGmailPass.setText(prefs.getString("gmail_pass", ""));

        // Save DB Config
        findViewById(R.id.btnSaveDb).setOnClickListener(v -> {
            prefs.edit()
                    .putString("db_url", etDbUrl.getText().toString().trim())
                    .putString("db_user", etDbUser.getText().toString().trim())
                    .putString("db_pass", etDbPass.getText().toString().trim())
                    .apply();
            Toast.makeText(this, "DB config saved!", Toast.LENGTH_SHORT).show();
        });

        // Save Gmail Config
        findViewById(R.id.btnSaveGmail).setOnClickListener(v -> {
            prefs.edit()
                    .putString("gmail_from", etGmailFrom.getText().toString().trim())
                    .putString("gmail_pass", etGmailPass.getText().toString().trim())
                    .apply();
            Toast.makeText(this, "Gmail config saved!", Toast.LENGTH_SHORT).show();
        });

        // Add Income Category
        EditText etIncCat = findViewById(R.id.etNewIncomeCat);
        findViewById(R.id.btnAddIncomeCat).setOnClickListener(v -> {
            String name = etIncCat.getText().toString().trim();
            if (!name.isEmpty()) {
                new CategoryDao(this).insert(name, "INCOME");
                etIncCat.setText("");
                Toast.makeText(this, "Category added!", Toast.LENGTH_SHORT).show();
            }
        });

        // Add Expense Category
        EditText etExpCat = findViewById(R.id.etNewExpenseCat);
        findViewById(R.id.btnAddExpenseCat).setOnClickListener(v -> {
            String name = etExpCat.getText().toString().trim();
            if (!name.isEmpty()) {
                new CategoryDao(this).insert(name, "EXPENSE");
                etExpCat.setText("");
                Toast.makeText(this, "Category added!", Toast.LENGTH_SHORT).show();
            }
        });

        // Back
        findViewById(R.id.btnBackSettings).setOnClickListener(v -> finish());
    }
}