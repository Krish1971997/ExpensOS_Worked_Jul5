package com.expenseos.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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
    private Button btnNotifReminder, btnAlarmReminder;

    private long notifReminderId = 0;
    private long alarmReminderId = 0;

    private String toneUri = "";   // per-event alarm tone ("" = use global/default)
    private String toneName = "";
    private Button btnAlarmTone;
    private ActivityResultLauncher<Intent> ringtoneLauncher;
    private ActivityResultLauncher<Intent> audioFileLauncher;

    private ActivityResultLauncher<Intent> notifReminderLauncher;
    private ActivityResultLauncher<Intent> alarmReminderLauncher;

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
        btnNotifReminder = findViewById(R.id.btnNotifReminder);
        btnAlarmReminder = findViewById(R.id.btnAlarmReminder);

        ArrayAdapter<String> dirAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, new String[]{"Before", "After"});
        spOffsetDirection.setAdapter(dirAdapter);

        notifReminderLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                notifReminderId = result.getData().getLongExtra("reminder_id", 0);
                refreshReminderButton(btnNotifReminder, notifReminderId);
            }
        });
        alarmReminderLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                alarmReminderId = result.getData().getLongExtra("reminder_id", 0);
                refreshReminderButton(btnAlarmReminder, alarmReminderId);
            }
        });

        setupTabs();
        btnNotifReminder.setOnClickListener(v -> showReminderPicker(true));
        btnAlarmReminder.setOnClickListener(v -> showReminderPicker(false));

        btnAlarmTone = findViewById(R.id.btnAlarmTone);
        ringtoneLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
            android.net.Uri picked = result.getData()
                    .getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            if (picked == null) return;
            String n;
            try {
                n = android.media.RingtoneManager.getRingtone(this, picked).getTitle(this);
            } catch (Exception ex) {
                n = toneNameOf(picked);
            }
            toneUri = picked.toString();
            toneName = n;
            refreshToneButton();
        });
        audioFileLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null
                    || result.getData().getData() == null) return;
            android.net.Uri file = result.getData().getData();
            try {
                getContentResolver().takePersistableUriPermission(file,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            toneUri = file.toString();
            toneName = toneNameOf(file);
            refreshToneButton();
        });
        btnAlarmTone.setOnClickListener(v -> showToneChooser());
        refreshToneButton();

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnSaveEvent).setOnClickListener(v -> save());

        editingEventId = getIntent().getLongExtra("event_id", 0);
        if (editingEventId > 0) {
            ((android.widget.TextView) findViewById(R.id.tvAddEventTitle)).setText("Edit Event");
            loadForEdit();
        }
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

    // ── Per-event alarm tone ──────────────────────────────
    private String toneNameOf(android.net.Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last != null ? last.substring(Math.max(0, last.length() - 40)) : "Custom tone";
    }

    private void refreshToneButton() {
        btnAlarmTone.setText("🔔 Alarm tone: " + (toneUri.isEmpty()
                ? "Default (" + com.expenseos.util.AlarmToneStore.getName(this) + ")"
                : toneName));
    }

    private void showToneChooser() {
        CharSequence[] opts = {"Phone alarm tones", "Audio file (file manager)", "Default tone (reset)"};
        new AlertDialog.Builder(this)
                .setTitle("Alarm tone")
                .setItems(opts, (d, which) -> {
                    if (which == 0) {
                        Intent i = new Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER);
                        i.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
                                android.media.RingtoneManager.TYPE_ALARM);
                        i.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "Select alarm tone");
                        i.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false);
                        i.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
                        if (!toneUri.isEmpty())
                            i.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                    android.net.Uri.parse(toneUri));
                        ringtoneLauncher.launch(i);
                    } else if (which == 1) {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("audio/*");
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                        try {
                            audioFileLauncher.launch(i);
                        } catch (android.content.ActivityNotFoundException ex) {
                            Toast.makeText(this, "No file manager found", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        toneUri = "";
                        toneName = "";
                        refreshToneButton();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Reminder picker: popup list of existing reminders + "Create new" ──
    private void showReminderPicker(boolean isNotif) {
        List<Reminder> reminders = reminderDao.findAll();
        List<String> labels = new ArrayList<>();
        for (Reminder r : reminders) labels.add(r.getName() + " — " + r.getSummary());
        labels.add("+ Create new reminder");

        new AlertDialog.Builder(this)
                .setTitle("Select reminder")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which == reminders.size()) {
                        Intent i = new Intent(this, AddReminderActivity.class);
                        (isNotif ? notifReminderLauncher : alarmReminderLauncher).launch(i);
                    } else {
                        long id = reminders.get(which).getId();
                        if (isNotif) {
                            notifReminderId = id;
                            refreshReminderButton(btnNotifReminder, id);
                        } else {
                            alarmReminderId = id;
                            refreshReminderButton(btnAlarmReminder, id);
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refreshReminderButton(Button btn, long reminderId) {
        Reminder r = reminderDao.findById(reminderId);
        btn.setText(r != null ? r.getName() + " (" + r.getSummary() + ")" : "Select reminder");
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
            notifReminderId = notifs.get(0).getReminderId();
            refreshReminderButton(btnNotifReminder, notifReminderId);
        }
        List<EventReminder> alarms = eventDao.findReminders(editingEventId, "ALARM");
        if (!alarms.isEmpty()) {
            alarmReminderId = alarms.get(0).getReminderId();
            refreshReminderButton(btnAlarmReminder, alarmReminderId);
        }
        if (com.expenseos.util.AlarmToneStore.hasEventTone(this, editingEventId)) {
            toneUri = com.expenseos.util.AlarmToneStore.getEventUri(this, editingEventId);
            toneName = com.expenseos.util.AlarmToneStore.getEventName(this, editingEventId);
        }
        refreshToneButton();
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

        if (notifReminderId > 0) eventDao.setReminder(eventId, "NOTIFICATION", notifReminderId);
        if (alarmReminderId > 0) eventDao.setReminder(eventId, "ALARM", alarmReminderId);

        if (toneUri.isEmpty()) com.expenseos.util.AlarmToneStore.clearForEvent(this, eventId);
        else com.expenseos.util.AlarmToneStore.saveForEvent(this, eventId, toneUri, toneName);

        // Event offsets/links may have changed — every task linked to this
        // event now fires at different times. Recompute all task alarms.
        new Thread(() ->
                com.expenseos.scheduler.TaskAlarmRescheduler.rescheduleAll(this),
                "event-save-reschedule").start();

        Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show();
        finish();
    }
}