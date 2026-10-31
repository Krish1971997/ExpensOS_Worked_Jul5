package com.expenseos.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.expenseos.R;
import com.expenseos.dao.TaskDao;
import com.expenseos.model.Task;
import com.expenseos.scheduler.TaskAlarmScheduler;
import com.expenseos.util.ReminderSummary;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Read-only task preview (Google Calendar style).
 * Top bar: ✕ back · ✎ edit · ⋮ (Duplicate, Delete).
 * Open it from the calendar / list with:
 * Intent i = new Intent(ctx, TaskPreviewActivity.class);
 * i.putExtra(AddTaskActivity.EXTRA_TASK_ID, task.getId());
 * startActivity(i);
 */
public class TaskPreviewActivity extends AppCompatActivity {

    private static final DateTimeFormatter TASK_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter SHOW_FMT =
            DateTimeFormatter.ofPattern("EEEE, dd MMM yyyy · hh:mm a", Locale.ENGLISH);

    private TaskDao taskDao;
    private long taskId;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_task_preview);
        taskDao = new TaskDao(this);
        taskId = getIntent().getLongExtra(AddTaskActivity.EXTRA_TASK_ID, 0);
        if (taskId <= 0) {
            finish();
            return;
        }

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnPreviewEdit).setOnClickListener(v -> openEdit());
        findViewById(R.id.btnPreviewMenu).setOnClickListener(v -> {
            PopupMenu m = new PopupMenu(this, v);
            m.getMenu().add(0, 1, 0, "Duplicate");
            m.getMenu().add(0, 2, 1, "Delete");
            m.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) {
                    duplicate();
                    return true;
                }
                if (item.getItemId() == 2) {
                    confirmDelete();
                    return true;
                }
                return false;
            });
            m.show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        load(); // refresh after returning from Edit
    }

    private void load() {
        Task t = taskDao.findById(taskId);
        if (t == null) { // deleted
            finish();
            return;
        }
        ((TextView) findViewById(R.id.tvPreviewName)).setText(t.getName());

        // colour: square beside the title + tinted banner
        int color = getColor(R.color.primary);
        String hex = t.getColor();
        if (hex != null && !hex.isEmpty()) {
            try {
                color = Color.parseColor(hex);
            } catch (Exception ignored) {
            }
        }
        GradientDrawable sq = new GradientDrawable();
        sq.setShape(GradientDrawable.RECTANGLE);
        sq.setCornerRadius(6 * getResources().getDisplayMetrics().density);
        sq.setColor(color);
        findViewById(R.id.vPreviewColor).setBackground(sq);
        findViewById(R.id.vPreviewBanner).setBackgroundColor(color);

        LocalDateTime dt = LocalDateTime.parse(t.getTaskDateTime(), TASK_FMT);
        LinearLayout rows = findViewById(R.id.previewRows);
        rows.removeAllViews();

        addRow(rows, android.R.drawable.ic_menu_my_calendar, dt.format(SHOW_FMT), null);

        String d = t.getDescription();
        if (d != null && !d.isEmpty())
            addRow(rows, android.R.drawable.ic_menu_info_details, d, null);

        List<Long> eventIds = taskDao.findEventIds(taskId);
        // one row per reminder, e.g. "🔔 Train · Mon, 12 Nov 2026 · 07:00 PM"
        for (ReminderSummary.Row r : ReminderSummary.rows(this, dt.toLocalDate(), eventIds)) {
            addRow(rows,
                    r.alarm ? android.R.drawable.ic_lock_idle_alarm : android.R.drawable.ic_popup_reminder,
                    (r.alarm ? "Alarm" : "Notification") + " · " + r.eventName,
                    r.when + (r.passed ? "  (already passed)" : ""));
        }
    }

    private void addRow(LinearLayout parent, int iconRes, String title, String subtitle) {
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, (int) (14 * dp), 0, (int) (14 * dp));

        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(getColor(R.color.text_muted));
        row.addView(icon, new LinearLayout.LayoutParams((int) (26 * dp), (int) (26 * dp)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cp.setMarginStart((int) (26 * dp));
        row.addView(col, cp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(17);
        t.setTextColor(getColor(R.color.text));
        col.addView(t);
        if (subtitle != null) {
            TextView st = new TextView(this);
            st.setText(subtitle);
            st.setTextSize(14);
            st.setTextColor(getColor(R.color.text_muted));
            col.addView(st);
        }
        parent.addView(row);
    }

    private void openEdit() {
        Intent i = new Intent(this, AddTaskActivity.class);
        i.putExtra(AddTaskActivity.EXTRA_TASK_ID, taskId);
        startActivity(i);
    }

    private void duplicate() {
        Task src = taskDao.findById(taskId);
        if (src == null) return;
        List<Long> eventIds = taskDao.findEventIds(taskId);

        Task copy = new Task();
        copy.setId(0);
        copy.setName(src.getName() + " (Copy)"); // same as DayTasksDialog.duplicateTask
        copy.setTaskDateTime(src.getTaskDateTime());
        copy.setDescription(src.getDescription());
        copy.setColor(src.getColor());
        copy.setEventIds(eventIds);
        long newId = taskDao.insert(copy);

        TaskAlarmScheduler.scheduleForTask(this, newId, copy.getTaskDateTime(), eventIds);
        Toast.makeText(this, "Duplicated", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private void confirmDelete() {
        Task t = taskDao.findById(taskId);
        if (t == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Delete \"" + t.getName() + "\"?")
                .setMessage("Its notifications and alarms will be cancelled.")
                .setPositiveButton("Delete", (d, w) -> {
                    // identical to DayTasksDialog single delete
                    TaskAlarmScheduler.cancelForTask(this, taskId);
                    com.expenseos.sync.GoogleCalendarSyncManager.delete(this, t.getGoogleEventId(), (ok, msg) -> {
                    });
                    taskDao.delete(taskId);
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}