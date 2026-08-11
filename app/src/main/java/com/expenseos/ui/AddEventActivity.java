package com.expenseos.ui;

import android.app.TimePickerDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.expenseos.R;
import com.expenseos.dao.EventDao;
import com.expenseos.dao.ReminderDao;
import com.expenseos.model.Event;
import com.expenseos.model.EventReminder;
import com.expenseos.model.Reminder;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;

public class AddEventActivity extends AppCompatActivity {

    private EventDao eventDao;
    private ReminderDao reminderDao;
    private long editingEventId = 0;

    private EditText etName, etDays, etHeader;
    private Spinner spOffsetDirection;
    private View blockNotification, blockAlarm;

    // Notification tab state
    private Spinner spNotifReminder;
    private Button btnNotifTime;
    private int notifHour = 19, notifMinute = 0; // default 7 PM
    private long notifReminderId = 0;

    // Alarm tab state
    private Spinner spAlarmReminder;
    private Button btnAlarmTime;
    private int alarmHour = 7, alarmMinute = 55;
    private long alarmReminderId = 0;

    private List<Reminder> reminderList;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_add_event);
        eventDao = new EventDao(this);
        reminderDao = new ReminderDao(this);

        etName = findViewById(R.id.etEventName);
        etDays = findViewById(R.id.etEventDays);
        etHeader = findViewById(R.id.etEventHeader);
        spOffsetDirection = findViewById(R.id.spOffsetDirection);
        blockNotification = findViewById(R.id.blockNotification);
        blockAlarm = findViewById(R.id.blockAlarm);
        spNotifReminder = findViewById(R.id.spNotifReminder);
        btnNotifTime = findViewById(R.id.btnNotifTime);
        spAlarmReminder = findViewById(R.id.spAlarmReminder);
        btnAlarmTime = findViewById(R.id.btnAlarmTime);

        ArrayAdapter<String> dirAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, new String[]{"Before", "After"});
        spOffsetDirection.setAdapter(dirAdapter);

        setupTabs();
        loadReminderSpinners();
        setupTimePickers();

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnSaveEvent).setOnClickListener(v -> save());

        editingEventId = getIntent().getLongExtra("event_id", 0);
        if (editingEventId > 0) loadForEdit();
    }

    private void setupTabs() {
        TabLayout tabs = findViewById(R.id.tabReminderType);
        tabs.addTab(tabs.newTab().setText("Notification"));
        tabs.addTab(tabs.newTab().setText("Alarm"));
        showTab(0);
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showTab(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
    }

    private void showTab(int pos) {
        blockNotification.setVisibility(pos == 0 ? View.VISIBLE : View.GONE);
        blockAlarm.setVisibility(pos == 1 ? View.VISIBLE : View.GONE);
    }

    private void setupTimePickers() {
        btnNotifTime.setText(String.format("%02d:%02d", notifHour, notifMinute));
        btnNotifTime.setOnClickListener(v -> new TimePickerDialog(this, (p, h, m) -> {
            notifHour = h;
            notifMinute = m;
            btnNotifTime.setText(String.format("%02d:%02d", h, m));
        }, notifHour, notifMinute, true).show());

        btnAlarmTime.setText(String.format("%02d:%02d", alarmHour, alarmMinute));
        btnAlarmTime.setOnClickListener(v -> new TimePickerDialog(this, (p, h, m) -> {
            alarmHour = h;
            alarmMinute = m;
            btnAlarmTime.setText(String.format("%02d:%02d", h, m));
        }, alarmHour, alarmMinute, true).show());
    }

    // ── Reminder spinners — existing list + "+ Add new…" as last entry ──
    private void loadReminderSpinners() {
        reminderList = reminderDao.findAll();
        List<String> labels = new ArrayList<>();
        for (Reminder r : reminderList) labels.add(r.getName());
        labels.add("+ Add new…");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels);

        spNotifReminder.setAdapter(adapter);
        spAlarmReminder.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));

        spNotifReminder.setOnItemSelectedListener(reminderSelectListener(true));
        spAlarmReminder.setOnItemSelectedListener(reminderSelectListener(false));
    }

    private AdapterView.OnItemSelectedListener reminderSelectListener(boolean isNotif) {
        return new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                if (pos == reminderList.size()) { // "+ Add new…" tapped
                    promptNewReminder(isNotif);
                } else {
                    long rid = reminderList.get(pos).getId();
                    if (isNotif) notifReminderId = rid;
                    else alarmReminderId = rid;
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        };
    }

    private void promptNewReminder(boolean isNotif) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_new_reminder, null);
        EditText et = v.findViewById(R.id.etReminderName);
        new AlertDialog.Builder(this)
                .setTitle("New Reminder")
                .setView(v)
                .setPositiveButton("Create", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Name required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    long id = reminderDao.insertOrGet(name);
                    loadReminderSpinners(); // refresh both spinners
                    Spinner target = isNotif ? spNotifReminder : spAlarmReminder;
                    for (int i = 0; i < reminderList.size(); i++)
                        if (reminderList.get(i).getId() == id) target.setSelection(i);
                    if (isNotif) notifReminderId = id;
                    else alarmReminderId = id;
                })
                .setNegativeButton("Cancel", (d, w) -> {
                    // revert spinner off "+ Add new…" back to first item
                    (isNotif ? spNotifReminder : spAlarmReminder).setSelection(0);
                })
                .show();
    }

    // ── Load for edit ─────────────────────────────────────
    private void loadForEdit() {
        for (Event e : eventDao.findAll()) {
            if (e.getId() == editingEventId) {
                etName.setText(e.getName());
                etDays.setText(String.valueOf(e.getOffsetDays()));
                etHeader.setText(e.getHeader());
                spOffsetDirection.setSelection("AFTER".equals(e.getOffsetDirection()) ? 1 : 0);
                break;
            }
        }
        List<EventReminder> notifs = eventDao.findReminders(editingEventId, "NOTIFICATION");
        if (!notifs.isEmpty()) {
            EventReminder er = notifs.get(0);
            notifHour = er.getTimeHour();
            notifMinute = er.getTimeMinute();
            notifReminderId = er.getReminderId();
            btnNotifTime.setText(String.format("%02d:%02d", notifHour, notifMinute));
            selectReminderInSpinner(spNotifReminder, notifReminderId);
        }
        List<EventReminder> alarms = eventDao.findReminders(editingEventId, "ALARM");
        if (!alarms.isEmpty()) {
            EventReminder er = alarms.get(0);
            alarmHour = er.getTimeHour();
            alarmMinute = er.getTimeMinute();
            alarmReminderId = er.getReminderId();
            btnAlarmTime.setText(String.format("%02d:%02d", alarmHour, alarmMinute));
            selectReminderInSpinner(spAlarmReminder, alarmReminderId);
        }
    }

    private void selectReminderInSpinner(Spinner sp, long reminderId) {
        for (int i = 0; i < reminderList.size(); i++)
            if (reminderList.get(i).getId() == reminderId) {
                sp.setSelection(i);
                return;
            }
    }

    // ── Save ───────────────────────────────────────────────
    private void save() {
        String name = etName.getText().toString().trim();
        String daysStr = etDays.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "Event name required", Toast.LENGTH_SHORT).show();
            return;
        }
        if (daysStr.isEmpty()) {
            Toast.makeText(this, "Days required", Toast.LENGTH_SHORT).show();
            return;
        }
        if (notifReminderId == 0 && alarmReminderId == 0) {
            Toast.makeText(this, "Select a reminder for at least Notification or Alarm", Toast.LENGTH_SHORT).show();
            return;
        }

        Event e = new Event();
        e.setId(editingEventId);
        e.setName(name);
        e.setOffsetDirection(spOffsetDirection.getSelectedItemPosition() == 1 ? "AFTER" : "BEFORE");
        e.setOffsetDays(Integer.parseInt(daysStr));
        e.setHeader(etHeader.getText().toString().trim());

        long eventId = editingEventId > 0 ? editingEventId : eventDao.insert(e);
        if (editingEventId > 0) eventDao.update(e);

        List<EventReminder> notifList = new ArrayList<>();
        if (notifReminderId > 0) {
            EventReminder er = new EventReminder();
            er.setReminderId(notifReminderId);
            er.setOffsetDirection("BEFORE"); // TODO: expose its own before/after+days field if needed later
            er.setOffsetDays(0);
            er.setTimeHour(notifHour);
            er.setTimeMinute(notifMinute);
            notifList.add(er);
        }
        eventDao.replaceReminders(eventId, "NOTIFICATION", notifList);

        List<EventReminder> alarmList = new ArrayList<>();
        if (alarmReminderId > 0) {
            EventReminder er = new EventReminder();
            er.setReminderId(alarmReminderId);
            er.setOffsetDirection("BEFORE");
            er.setOffsetDays(0);
            er.setTimeHour(alarmHour);
            er.setTimeMinute(alarmMinute);
            alarmList.add(er);
        }
        eventDao.replaceReminders(eventId, "ALARM", alarmList);

        Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show();
        finish();
    }
}