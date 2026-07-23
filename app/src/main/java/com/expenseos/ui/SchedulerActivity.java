package com.expenseos.ui;

import android.app.TimePickerDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.dao.SchedulerDao;
import com.expenseos.model.SchedulerConfig;
import com.expenseos.scheduler.SchedulerTimeUtil;
import com.expenseos.scheduler.SchedulerWorker;

import java.util.ArrayList;
import java.util.List;

public class SchedulerActivity extends AppCompatActivity {

    private static final String[] REPEAT_TYPES = {"HOURLY", "DAILY", "WEEKLY", "MONTHLY"};

    private SchedulerDao dao;
    private List<SchedulerConfig> schedulers;
    private RecyclerView rv;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_scheduler);
        dao = new SchedulerDao(this);

        findViewById(R.id.btnSchedulerBack).setOnClickListener(v -> finish());

        rv = findViewById(R.id.rvSchedulers);
        rv.setLayoutManager(new LinearLayoutManager(this));

        // Make sure the periodic tick is scheduled — safe to call repeatedly,
        // ExistingPeriodicWorkPolicy.KEEP means this only actually enqueues once.
        SchedulerWorker.schedulePeriodic(this);

        loadSchedulers();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSchedulers();
    }

    private void loadSchedulers() {
        schedulers = dao.findAll();
        rv.setAdapter(new SchedulerAdapter(schedulers, new SchedulerAdapter.Listener() {
            @Override
            public void onToggleEnabled(SchedulerConfig sc, boolean enabled) {
                sc.setEnabled(enabled);
                dao.update(sc);
            }

            @Override
            public void onRunNow(SchedulerConfig sc) {
                SchedulerWorker.runNow(SchedulerActivity.this, sc.getName());
                Toast.makeText(SchedulerActivity.this,
                        "Started " + sc.getDisplayName() + " in background", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onEdit(SchedulerConfig sc) {
                showEditDialog(sc);
            }
        }));
    }

    // ── Edit dialog ─────────────────────────────────────────────────
    private void showEditDialog(SchedulerConfig sc) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_edit_scheduler, null);

        Spinner spRepeat = v.findViewById(R.id.spRepeatType);
        View rowWeekDays = v.findViewById(R.id.rowWeekDays);
        View rowMonthDay = v.findViewById(R.id.rowMonthDay);
        EditText etMonthDay = v.findViewById(R.id.etMonthDay);
        Button btnPickTime = v.findViewById(R.id.btnPickTime);
        Switch swEnabled = v.findViewById(R.id.swEditEnabled);

        CheckBox[] dayBoxes = {
                v.findViewById(R.id.cbMon), v.findViewById(R.id.cbTue), v.findViewById(R.id.cbWed),
                v.findViewById(R.id.cbThu), v.findViewById(R.id.cbFri), v.findViewById(R.id.cbSat),
                v.findViewById(R.id.cbSun)
        };
        String[] dayCodes = {"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"};

        ArrayAdapter<String> repeatAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, REPEAT_TYPES);
        spRepeat.setAdapter(repeatAdapter);

        int curIdx = 0;
        for (int i = 0; i < REPEAT_TYPES.length; i++)
            if (REPEAT_TYPES[i].equals(sc.getRepeatType())) curIdx = i;
        spRepeat.setSelection(curIdx);

        final int[] hour = {sc.getRunHour()};
        final int[] minute = {sc.getRunMinute()};
        btnPickTime.setText(String.format("%02d:%02d", hour[0], minute[0]));
        btnPickTime.setOnClickListener(v1 -> new TimePickerDialog(this, (picker, h, m) -> {
            hour[0] = h;
            minute[0] = m;
            btnPickTime.setText(String.format("%02d:%02d", h, m));
        }, hour[0], minute[0], true).show());

        // Pre-check existing weekly days
        if ("WEEKLY".equals(sc.getRepeatType()) && sc.getRepeatDays() != null) {
            String existing = sc.getRepeatDays().toUpperCase();
            for (int i = 0; i < dayCodes.length; i++)
                dayBoxes[i].setChecked(existing.contains(dayCodes[i]));
        }
        if ("MONTHLY".equals(sc.getRepeatType()) && sc.getRepeatDays() != null) {
            etMonthDay.setText(sc.getRepeatDays().trim());
        }

        swEnabled.setChecked(sc.isEnabled());

        Runnable toggleRows = () -> {
            String sel = (String) spRepeat.getSelectedItem();
            rowWeekDays.setVisibility("WEEKLY".equals(sel) ? View.VISIBLE : View.GONE);
            rowMonthDay.setVisibility("MONTHLY".equals(sel) ? View.VISIBLE : View.GONE);
        };
        spRepeat.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> p, View v2, int pos, long id) {
                toggleRows.run();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> p) {
            }
        });
        toggleRows.run();

        new AlertDialog.Builder(this)
                .setTitle(sc.getDisplayName())
                .setView(v)
                .setPositiveButton("Save", (d, w) -> {
                    String repeatType = (String) spRepeat.getSelectedItem();
                    String repeatDays = null;
                    if ("WEEKLY".equals(repeatType)) {
                        List<String> picked = new ArrayList<>();
                        for (int i = 0; i < dayBoxes.length; i++)
                            if (dayBoxes[i].isChecked()) picked.add(dayCodes[i]);
                        repeatDays = String.join(",", picked);
                    } else if ("MONTHLY".equals(repeatType)) {
                        repeatDays = etMonthDay.getText().toString().trim();
                    }

                    sc.setRepeatType(repeatType);
                    sc.setRepeatDays(repeatDays);
                    sc.setRunHour(hour[0]);
                    sc.setRunMinute(minute[0]);
                    sc.setEnabled(swEnabled.isChecked());
                    sc.setNextRunAt(SchedulerTimeUtil.calcNextRun(sc));

                    dao.update(sc);
                    loadSchedulers();
                    Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
