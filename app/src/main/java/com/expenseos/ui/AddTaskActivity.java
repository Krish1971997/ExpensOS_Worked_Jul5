package com.expenseos.ui;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.expenseos.R;
import com.expenseos.dao.EventDao;
import com.expenseos.dao.TaskDao;
import com.expenseos.model.Event;
import com.expenseos.model.Task;
import com.expenseos.scheduler.TaskAlarmScheduler;
import com.expenseos.util.TaskColors;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class AddTaskActivity extends AppCompatActivity {

    public static final String EXTRA_DATE = "date"; // yyyy-MM-dd, pre-fill when creating from a day tap
    public static final String EXTRA_TASK_ID = "task_id"; // set when editing

    private TaskDao taskDao;
    private EventDao eventDao;
    private long editingTaskId = 0;

    private EditText etName, etDescription;
    private Button btnDate, btnTime, btnEvents;
    private Spinner spColor;
    private LocalDate date;
    private LocalTime time;
    private List<Event> allEvents;
    private final List<Long> selectedEventIds = new ArrayList<>();

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TASK_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_add_task);
        taskDao = new TaskDao(this);
        eventDao = new EventDao(this);

        etName = findViewById(R.id.etTaskName);
        etDescription = findViewById(R.id.etTaskDescription);
        btnDate = findViewById(R.id.btnTaskDate);
        btnTime = findViewById(R.id.btnTaskTime);
        btnEvents = findViewById(R.id.btnTaskEvents);
        spColor = findViewById(R.id.spTaskColor);

        allEvents = eventDao.findAll();
        if (allEvents.isEmpty()) {
            Toast.makeText(this, "Create an Event first (Integrations tab) before adding tasks", Toast.LENGTH_LONG).show();
        }

        setupColorSpinner();

        String prefillDate = getIntent().getStringExtra(EXTRA_DATE);
        date = prefillDate != null ? LocalDate.parse(prefillDate, DATE_FMT) : LocalDate.now();
        time = LocalTime.of(9, 0);
        refreshDateTimeButtons();

        btnDate.setOnClickListener(v -> new DatePickerDialog(this, (p, y, m, d) -> {
            date = LocalDate.of(y, m + 1, d);
            refreshDateTimeButtons();
        }, date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth()).show());

        btnTime.setOnClickListener(v -> new TimePickerDialog(this, (p, h, min) -> {
            time = LocalTime.of(h, min);
            refreshDateTimeButtons();
        }, time.getHour(), time.getMinute(), true).show());

        btnEvents.setOnClickListener(v -> showEventPicker());

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnSaveTask).setOnClickListener(v -> save());

        editingTaskId = getIntent().getLongExtra(EXTRA_TASK_ID, 0);
        if (editingTaskId > 0) loadForEdit();
    }

    private void setupColorSpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, TaskColors.names()) {
            @Override
            public View getView(int pos, View convertView, android.view.ViewGroup parent) {
                View v = super.getView(pos, convertView, parent);
                tintSwatch(v, getItem(pos));
                return v;
            }

            @Override
            public View getDropDownView(int pos, View convertView, android.view.ViewGroup parent) {
                View v = super.getDropDownView(pos, convertView, parent);
                tintSwatch(v, getItem(pos));
                return v;
            }

            private void tintSwatch(View v, String name) {
                String hex = TaskColors.hexFor(name);
                if (v instanceof android.widget.TextView tv) {
                    if (!hex.isEmpty()) {
                        GradientDrawable dot = new GradientDrawable();
                        dot.setShape(GradientDrawable.OVAL);
                        dot.setColor(Color.parseColor(hex));
                        dot.setSize(28, 28);
                        tv.setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null);
                        tv.setCompoundDrawablePadding(16);
                    } else {
                        tv.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
                    }
                }
            }
        };
        spColor.setAdapter(adapter);
    }

    private void refreshDateTimeButtons() {
        btnDate.setText(date.format(DateTimeFormatter.ofPattern("dd MMM yyyy")));
        btnTime.setText(time.format(DateTimeFormatter.ofPattern("hh:mm a")));
    }

    private void showEventPicker() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(32, 16, 32, 16);
        List<CheckBox> boxes = new ArrayList<>();
        for (Event e : allEvents) {
            CheckBox cb = new CheckBox(this);
            cb.setText(e.getName());
            cb.setChecked(selectedEventIds.contains(e.getId()));
            container.addView(cb);
            boxes.add(cb);
        }
        new AlertDialog.Builder(this)
                .setTitle("Select event(s)")
                .setView(container)
                .setPositiveButton("OK", (d, w) -> {
                    selectedEventIds.clear();
                    List<String> names = new ArrayList<>();
                    for (int i = 0; i < boxes.size(); i++) {
                        if (boxes.get(i).isChecked()) {
                            selectedEventIds.add(allEvents.get(i).getId());
                            names.add(allEvents.get(i).getName());
                        }
                    }
                    btnEvents.setText(names.isEmpty() ? "Select event(s)" : String.join(", ", names));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void loadForEdit() {
        Task t = taskDao.findById(editingTaskId);
        if (t == null) return;
        etName.setText(t.getName());
        etDescription.setText(t.getDescription());
        spColor.setSelection(java.util.Arrays.asList(TaskColors.names()).indexOf(TaskColors.nameFor(t.getColor())));

        java.time.LocalDateTime dt = java.time.LocalDateTime.parse(t.getTaskDateTime(), TASK_FMT);
        date = dt.toLocalDate();
        time = dt.toLocalTime();
        refreshDateTimeButtons();

        selectedEventIds.clear();
        selectedEventIds.addAll(taskDao.findEventIds(editingTaskId));
        List<String> names = new ArrayList<>();
        for (Event e : allEvents) if (selectedEventIds.contains(e.getId())) names.add(e.getName());
        btnEvents.setText(names.isEmpty() ? "Select event(s)" : String.join(", ", names));
    }

    private void save() {
        String name = etName.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "Task name required", Toast.LENGTH_SHORT).show();
            return;
        }
        if (selectedEventIds.isEmpty()) {
            Toast.makeText(this, "Select at least one event", Toast.LENGTH_SHORT).show();
            return;
        }

        Task t = new Task();
        t.setId(editingTaskId);
        t.setName(name);
        t.setTaskDateTime(date.format(DATE_FMT) + " " + time.format(DateTimeFormatter.ofPattern("HH:mm")));
        t.setDescription(etDescription.getText().toString().trim());
        String colorName = (String) spColor.getSelectedItem();
        t.setColor(TaskColors.hexFor(colorName));
        t.setEventIds(selectedEventIds);

        long taskId;
        if (editingTaskId > 0) {
            taskDao.update(t);
            taskId = editingTaskId;
        } else taskId = taskDao.insert(t);

        TaskAlarmScheduler.scheduleForTask(this, taskId, t.getTaskDateTime(), selectedEventIds);

        if (com.expenseos.sync.GoogleCalendarSyncManager.isSignedIn(this)) {
            com.expenseos.sync.GoogleCalendarSyncManager.upsert(this, taskId, (ok, msg) -> {
                if (!ok) Toast.makeText(this, "Google sync: " + msg, Toast.LENGTH_SHORT).show();
            });
        }

        Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }
}