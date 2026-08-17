package com.expenseos.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.expenseos.R;
import com.expenseos.dao.ReminderDao;
import com.expenseos.model.Reminder;
import com.expenseos.util.TimePickerHelper;

public class AddReminderActivity extends AppCompatActivity {

    private ReminderDao dao;
    private long editingId = 0;

    private EditText etName, etDays;
    private Spinner spUnit;
    private Button btnTime;
    private int hour = 9, minute = 0;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_add_reminder);
        dao = new ReminderDao(this);

        etName = findViewById(R.id.etReminderName);
        etDays = findViewById(R.id.etReminderDays);
        spUnit = findViewById(R.id.spReminderUnit);
        btnTime = findViewById(R.id.btnReminderTime);

        spUnit.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, new String[]{"Day before", "Week before"}));

        refreshTimeLabel();
        btnTime.setOnClickListener(v ->
                TimePickerHelper.show(this, getSupportFragmentManager(), hour, minute, (h, m) -> {
                    hour = h;
                    minute = m;
                    refreshTimeLabel();
                }));

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnSaveReminder).setOnClickListener(v -> save());

        editingId = getIntent().getLongExtra("reminder_id", 0);
        if (editingId > 0) loadForEdit();
    }

    private void refreshTimeLabel() {
        btnTime.setText(String.format("%02d:%02d", hour, minute));
    }

    private void loadForEdit() {
        Reminder r = dao.findById(editingId);
        if (r == null) return;
        etName.setText(r.getName());
        etDays.setText(String.valueOf(r.getOffsetValue()));
        spUnit.setSelection("WEEK".equals(r.getOffsetUnit()) ? 1 : 0);
        hour = r.getTimeHour();
        minute = r.getTimeMinute();
        refreshTimeLabel();
    }

    private void save() {
        String name = etName.getText().toString().trim();
        String daysStr = etDays.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "Name required", Toast.LENGTH_SHORT).show();
            return;
        }
        if (daysStr.isEmpty()) {
            Toast.makeText(this, "Days required", Toast.LENGTH_SHORT).show();
            return;
        }

        Reminder r = new Reminder();
        r.setId(editingId);
        r.setName(name);
        r.setOffsetValue(Integer.parseInt(daysStr));
        r.setOffsetUnit(spUnit.getSelectedItemPosition() == 1 ? "WEEK" : "DAY");
        r.setTimeHour(hour);
        r.setTimeMinute(minute);

        long savedId = editingId > 0 ? editingId : dao.insert(r);
        if (editingId > 0) dao.update(r);

        Intent result = new Intent();
        result.putExtra("reminder_id", savedId);
        setResult(RESULT_OK, result);

        Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show();
        finish();
    }
}