package com.expenseos.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.LinearLayout;
import android.widget.TextView;
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

    // Ordered lists — a notification/alarm can now have MANY reminders.
    private final List<Long> notifIds = new ArrayList<>();
    private final List<Long> alarmIds = new ArrayList<>();
    private LinearLayout listNotif, listAlarm;

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
        listNotif = findViewById(R.id.listNotif);
        listAlarm = findViewById(R.id.listAlarm);

        ArrayAdapter<String> dirAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, new String[]{"Before", "After"});
        spOffsetDirection.setAdapter(dirAdapter);

        // "+ New" in the picker → AddReminderActivity returns the new id; append it.
        notifReminderLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                addId(notifIds, result.getData().getLongExtra("reminder_id", 0));
                renderList(true);
            }
        });
        alarmReminderLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                addId(alarmIds, result.getData().getLongExtra("reminder_id", 0));
                renderList(false);
            }
        });

        setupTabs();
        btnNotifReminder.setOnClickListener(v -> showReminderPicker(true));
        btnAlarmReminder.setOnClickListener(v -> showReminderPicker(false));

        renderList(true);
        renderList(false);

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
            com.expenseos.util.AlarmTonePreview.play(this, toneUri);
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
            com.expenseos.util.AlarmTonePreview.play(this, toneUri);
        });
        btnAlarmTone.setOnClickListener(v -> showToneChooser());
        refreshToneButton();

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnSaveEvent).setOnClickListener(v -> save());
        com.expenseos.util.ScreenAnim.staggerIn((android.view.ViewGroup) findViewById(R.id.btnSaveEvent).getParent());

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
    @Override
    protected void onPause() {
        super.onPause();
        com.expenseos.util.AlarmTonePreview.stop();
    }

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
        CharSequence[] opts = {"Phone alarm tones", "Audio file (file manager)", "Default tone (reset)",
                "▶ Preview current tone"};
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
                    } else if (which == 2) {
                        toneUri = "";
                        toneName = "";
                        refreshToneButton();
                    } else {
                        // event's own tone, else the global one, else the phone default
                        com.expenseos.util.AlarmTonePreview.play(this, !toneUri.isEmpty() ? toneUri
                                : com.expenseos.util.AlarmToneStore.getUri(this));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Multi-reminder picker: tick any number of existing reminders ──
    private void addId(List<Long> list, long id) {
        if (id > 0 && !list.contains(id)) list.add(id);
    }

    private void showReminderPicker(boolean isNotif) {
        final List<Reminder> all = reminderDao.findAll();
        final List<Long> current = isNotif ? notifIds : alarmIds;
        final String[] labels = new String[all.size()];
        final boolean[] checked = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            Reminder r = all.get(i);
            labels[i] = r.getName() + " — " + r.getSummary();
            checked[i] = current.contains(r.getId());
        }

        new AlertDialog.Builder(this)
                .setTitle(isNotif ? "Notification reminders" : "Alarm reminders")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("OK", (d, w) -> {
                    // keep the order of already-added reminders, append newly ticked ones
                    List<Long> next = new ArrayList<>();
                    for (Long id : current)
                        for (int i = 0; i < all.size(); i++)
                            if (all.get(i).getId() == id && checked[i]) next.add(id);
                    for (int i = 0; i < all.size(); i++)
                        if (checked[i] && !next.contains(all.get(i).getId())) next.add(all.get(i).getId());
                    current.clear();
                    current.addAll(next);
                    renderList(isNotif);
                })
                .setNeutralButton("+ New", (d, w) -> {
                    Intent i = new Intent(this, AddReminderActivity.class);
                    (isNotif ? notifReminderLauncher : alarmReminderLauncher).launch(i);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Draws the selected reminders as numbered rows, each with a ✕ to remove. */
    private void renderList(boolean isNotif) {
        LinearLayout box = isNotif ? listNotif : listAlarm;
        List<Long> ids = isNotif ? notifIds : alarmIds;
        box.removeAllViews();
        if (ids.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No reminders added");
            empty.setPadding(0, 0, 0, 8);
            box.addView(empty);
            return;
        }
        int n = 1;
        for (final Long id : new ArrayList<>(ids)) {
            Reminder r = reminderDao.findById(id);
            if (r == null) {
                ids.remove(id);
                continue;
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, 6, 0, 6);

            TextView label = new TextView(this);
            label.setText(n + ". " + r.getName() + " (" + r.getSummary() + ")");
            label.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView x = new TextView(this);
            x.setText("✕");
            x.setTextSize(18);
            x.setPadding(24, 8, 8, 8);
            x.setOnClickListener(v -> {
                ids.remove(id);
                renderList(isNotif);
            });

            row.addView(label);
            row.addView(x);
            box.addView(row);
            n++;
        }
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
        notifIds.clear();
        for (EventReminder er : eventDao.findReminders(editingEventId, "NOTIFICATION"))
            addId(notifIds, er.getReminderId());
        alarmIds.clear();
        for (EventReminder er : eventDao.findReminders(editingEventId, "ALARM"))
            addId(alarmIds, er.getReminderId());
        renderList(true);
        renderList(false);
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
        if (notifIds.isEmpty() && alarmIds.isEmpty()) {
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

        // Cancel every scheduled alarm FIRST: setReminders() deletes/re-creates event_reminders
        // rows, and if task_alarms cascades from them the request codes would be lost and the
        // old AlarmManager entries could never be cancelled.
        com.expenseos.dao.TaskDao taskDaoForCancel = new com.expenseos.dao.TaskDao(this);
        for (com.expenseos.model.Task t : taskDaoForCancel.findAll())
            com.expenseos.scheduler.TaskAlarmScheduler.cancelForTask(this, t.getId());

        // Replaces ALL reminders of that type for this event (see EventDao.setReminders)
        eventDao.setReminders(eventId, "NOTIFICATION", notifIds);
        eventDao.setReminders(eventId, "ALARM", alarmIds);

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