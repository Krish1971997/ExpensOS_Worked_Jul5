package com.expenseos.android.timesheet;

import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.expenseos.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TimesheetActivity extends AppCompatActivity {

    private static final int DEFAULT_PROJECT_ID = 27;    // Michelin Camso Support
    private static final int DEFAULT_ACTIVITY_ID = 29;   // Implementation

    public static void start(Context c) {
        c.startActivity(new Intent(c, TimesheetActivity.class));
    }

    private interface Job<T> {
        T run() throws Exception;
    }

    private interface Ok<T> {
        void run(T result);
    }

    private interface DateCb {
        void on(String iso);
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<Integer, List<OdooClient.Item>> taskCache = new HashMap<>();
    private List<OdooClient.Item> activities = new ArrayList<>();

    private View root, svMain;
    private Spinner spProject, spTask, spActivity;
    private CheckBox cbShowDone;
    private MaterialButton btnDate, btnSave;
    private EditText etHours, etDesc;
    private TextView tvHoursHint, tvAttNet, tvAttInfo, tvTsTotal, tvTsInfo;
    private LinearLayout llEntries;
    private ProgressBar pbBusy;

    private String dateIso;
    private int dayToken = 0;
    private int tasksFor = -1;
    private int busy = 0;

    // ---------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_timesheet);
        com.expenseos.util.UiUtils.styleStatusBar(getWindow(), this);

        root = findViewById(R.id.tsRoot);
        svMain = findViewById(R.id.svMain);
        spProject = findViewById(R.id.spProject);
        spTask = findViewById(R.id.spTask);
        spActivity = findViewById(R.id.spActivity);
        cbShowDone = findViewById(R.id.cbShowDone);
        btnDate = findViewById(R.id.btnDate);
        btnSave = findViewById(R.id.btnSave);
        etHours = findViewById(R.id.etHours);
        etDesc = findViewById(R.id.etDesc);
        tvHoursHint = findViewById(R.id.tvHoursHint);
        tvAttNet = findViewById(R.id.tvAttNet);
        tvAttInfo = findViewById(R.id.tvAttInfo);
        tvTsTotal = findViewById(R.id.tvTsTotal);
        tvTsInfo = findViewById(R.id.tvTsInfo);
        llEntries = findViewById(R.id.llEntries);
        pbBusy = findViewById(R.id.pbBusy);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> loadDay());

        // Smooth entrance animation
        svMain.setAlpha(0f);
        svMain.setTranslationY(48f);
        svMain.animate().alpha(1f).translationY(0f).setDuration(350).start();

        dateIso = todayIso();
        showDate();
        fill(spProject, new ArrayList<>(), "Loading...");
        fill(spTask, new ArrayList<>(), "Select a project first");
        fill(spActivity, new ArrayList<>(), "Loading...");
        spTask.setEnabled(false);

        spProject.setOnItemSelectedListener(new Sel(() -> {
            if (selId(spProject) != tasksFor) loadTasks();
        }));
        cbShowDone.setOnCheckedChangeListener((cb, checked) -> loadTasks());
        btnDate.setOnClickListener(v -> pickDate(dateIso, iso -> {
            dateIso = iso;
            showDate();
            loadDay();
        }));
        btnSave.setOnClickListener(v -> saveNew());
        etHours.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b2, int c) {
            }

            public void afterTextChanged(Editable e) {
                updateHint(e.toString());
            }
        });

        loadInitial();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    // ---------------------------------------------------------------
    // Background helper
    // ---------------------------------------------------------------

    private <T> void async(Job<T> job, Ok<T> ok) {
        async(job, ok, null);
    }

    private <T> void async(Job<T> job, Ok<T> ok, Runnable fin) {
        setBusy(true);
        io.execute(() -> {
            try {
                final T r = job.run();
                ui.post(() -> {
                    setBusy(false);
                    if (fin != null) fin.run();
                    if (!isDestroyed()) ok.run(r);
                });
            } catch (Exception e) {
                final String msg = String.valueOf(e.getMessage());
                ui.post(() -> {
                    setBusy(false);
                    if (fin != null) fin.run();
                    if (!isDestroyed()) toast(msg);
                });
            }
        });
    }

    private void setBusy(boolean on) {
        busy += on ? 1 : -1;
        pbBusy.setVisibility(busy > 0 ? View.VISIBLE : View.GONE);
    }

    private void toast(String msg) {
        if (msg == null) msg = "";
        if (msg.length() > 300) msg = msg.substring(0, 300) + "...";
        Snackbar.make(root, msg, Snackbar.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------

    private void loadInitial() {
        final Context app = getApplicationContext();
        async(() -> {
            OdooClient.init(app);
            List<List<OdooClient.Item>> r = new ArrayList<>();
            r.add(OdooClient.listProjects());
            r.add(OdooClient.listActivities());
            return r;
        }, r -> {
            activities = r.get(1);
            fill(spProject, r.get(0), "-- Select project --");
            fill(spActivity, activities, "-- Select activity --");
            select(spProject, DEFAULT_PROJECT_ID);
            select(spActivity, DEFAULT_ACTIVITY_ID);
            loadTasks();
            loadDay();
        });
    }

    private void loadTasks() {
        final int pid = selId(spProject);
        tasksFor = pid;
        if (pid == 0) {
            fill(spTask, new ArrayList<>(), "Select a project first");
            spTask.setEnabled(false);
            return;
        }
        final int prev = selId(spTask);
        final boolean done = cbShowDone.isChecked();
        spTask.setEnabled(false);
        async(() -> OdooClient.listTasks(pid, done), tasks -> {
            if (pid != tasksFor) return;   // project changed meanwhile
            fill(spTask, tasks, tasks.isEmpty() ? "-- No tasks found --" : "-- Select task --");
            select(spTask, prev);
            spTask.setEnabled(true);
        });
    }

    private void loadDay() {
        final int token = ++dayToken;
        final String iso = dateIso;
        async(() -> OdooClient.dayInfo(iso), d -> {
            if (token == dayToken) renderDay(d);
        });
    }

    private void renderDay(JSONObject d) {
        try {
            // Attendance box
            JSONObject a = d.getJSONObject("attendance");
            if (a.optBoolean("found")) {
                long gross = a.optLong("grossMinutes");
                boolean incompleteOnly = a.optBoolean("incomplete") && gross == 0;
                tvAttNet.setText(incompleteOnly ? "-" : fmtHM((int) a.optLong("netMinutes")) + " Hrs");
                StringBuilder info = new StringBuilder("In " + a.optString("firstIn"));
                if (a.optBoolean("open")) {
                    info.append(" | Out: not yet (now ").append(a.optString("nowTime")).append(")");
                } else if (!a.optString("lastOut").isEmpty()) {
                    info.append(" | Out ").append(a.optString("lastOut"));
                }
                if (a.optBoolean("incomplete")) info.append(" | Out time missing in some entries");
                if (!incompleteOnly) {
                    info.append("\nGross ").append(fmtHM((int) gross)).append(" - ")
                            .append(fmtHM(a.optInt("breakMinutes"))).append(" break");
                }
                tvAttInfo.setText(info.toString());
            } else {
                tvAttNet.setText("-");
                tvAttInfo.setText(a.optString("note", "No attendance record for this date"));
            }

            // Timesheet box + entry cards
            JSONArray rows = d.getJSONArray("timesheets");
            int totalMin = 0;
            for (int i = 0; i < rows.length(); i++)
                totalMin += decToMin(rows.getJSONObject(i).optDouble("hours"));
            tvTsTotal.setText(fmtHM(totalMin) + " Hrs");
            tvTsInfo.setText(rows.length() + (rows.length() == 1 ? " entry" : " entries"));

            taskCache.clear();
            llEntries.removeAllViews();
            if (rows.length() == 0) {
                TextView empty = new TextView(this);
                empty.setText("No timesheet entries for this date");
                empty.setPadding(8, 16, 8, 16);
                llEntries.addView(empty);
            }
            for (int i = 0; i < rows.length(); i++) {
                final JSONObject r = rows.getJSONObject(i);
                View v = getLayoutInflater().inflate(R.layout.item_timesheet_entry, llEntries, false);
                ((TextView) v.findViewById(R.id.tvTask)).setText(r.optString("task"));
                ((TextView) v.findViewById(R.id.tvHours)).setText(fmtHM(decToMin(r.optDouble("hours"))));
                ((TextView) v.findViewById(R.id.tvProject)).setText(r.optString("project"));
                String act = r.optString("activity");
                TextView tvAct = v.findViewById(R.id.tvActivity);
                tvAct.setText(act);
                tvAct.setVisibility(act.isEmpty() ? View.GONE : View.VISIBLE);
                ((TextView) v.findViewById(R.id.tvDesc)).setText(r.optString("description"));
                v.setOnClickListener(x -> showEditDialog(r));

                v.setAlpha(0f);
                v.setTranslationY(30f);
                llEntries.addView(v);
                v.animate().alpha(1f).translationY(0f).setStartDelay(i * 50L).setDuration(250).start();
            }
        } catch (Exception e) {
            toast("Display error: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // New entry
    // ---------------------------------------------------------------

    private void saveNew() {
        final int pid = selId(spProject);
        final int tid = selId(spTask);
        final int act = selId(spActivity);
        final String desc = etDesc.getText().toString().trim();
        final int min = parseMinutes(etHours.getText().toString());
        final String iso = dateIso;

        if (pid == 0 || tid == 0 || desc.isEmpty()) {
            toast("Project, task, hours and description are required");
            return;
        }
        if (min < 0) {
            toast("Enter hours as H.MM, minutes 00-59 (example 2.30 = 2h 30m)");
            return;
        }
        btnSave.setEnabled(false);
        async(() -> OdooClient.createTimesheet(pid, tid, act == 0 ? null : Integer.valueOf(act),
                        iso, toOdooHours(min), desc),
                id -> {
                    toast("Timesheet saved (" + fmtHM(min) + " Hrs). Odoo id = " + id);
                    etHours.setText("");
                    etDesc.setText("");
                    loadDay();
                },
                () -> btnSave.setEnabled(true));
    }

    // ---------------------------------------------------------------
    // Edit dialog
    // ---------------------------------------------------------------

    private void showEditDialog(final JSONObject r) {
        View v = getLayoutInflater().inflate(R.layout.dialog_timesheet_edit, null);
        TextView tvProject = v.findViewById(R.id.tvEdProject);
        final Spinner spEdTask = v.findViewById(R.id.spEdTask);
        final Spinner spEdActivity = v.findViewById(R.id.spEdActivity);
        final MaterialButton btnEdDate = v.findViewById(R.id.btnEdDate);
        final EditText etEdHours = v.findViewById(R.id.etEdHours);
        final EditText etEdDesc = v.findViewById(R.id.etEdDesc);

        final int projectId = r.optInt("projectId");
        final String[] editDate = {r.optString("date")};

        tvProject.setText(r.optString("project"));
        btnEdDate.setText(isoToDmy(editDate[0]));
        btnEdDate.setOnClickListener(x -> pickDate(editDate[0], iso -> {
            editDate[0] = iso;
            btnEdDate.setText(isoToDmy(iso));
        }));
        etEdHours.setText(fmtHM(decToMin(r.optDouble("hours"))));
        etEdDesc.setText(r.optString("description"));

        fill(spEdActivity, activities, "-- None --");
        select(spEdActivity, r.optInt("activityId"));

        // Tasks of this project (Done included), cached per project
        fill(spEdTask, new ArrayList<>(), "Loading tasks...");
        final Ok<List<OdooClient.Item>> setTasks = tasks -> {
            fill(spEdTask, tasks, "-- Select task --");
            select(spEdTask, r.optInt("taskId"));
        };
        List<OdooClient.Item> cached = taskCache.get(projectId);
        if (cached != null) {
            setTasks.run(cached);
        } else {
            async(() -> OdooClient.listTasks(projectId, true), tasks -> {
                taskCache.put(projectId, tasks);
                setTasks.run(tasks);
            });
        }

        final AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setTitle("Edit timesheet")
                .setView(v)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        dlg.show();

        // Own click listener so the dialog stays open when validation fails
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
            final int min = parseMinutes(etEdHours.getText().toString());
            final int taskId = selId(spEdTask);
            final int actId = selId(spEdActivity);
            final String desc = etEdDesc.getText().toString().trim();
            final int id = r.optInt("id");
            final String date = editDate[0];

            if (min < 0) {
                etEdHours.setError("H.MM, minutes 00-59 (2.30 = 2h 30m)");
                return;
            }
            if (taskId == 0) {
                toast("Select a task");
                return;
            }
            if (desc.isEmpty()) {
                etEdDesc.setError("Description is required");
                return;
            }
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            async(() -> {
                OdooClient.updateTimesheet(id, date, taskId, actId == 0 ? null : Integer.valueOf(actId),
                        toOdooHours(min), desc);
                return Boolean.TRUE;
            }, res -> {
                dlg.dismiss();
                toast("Entry updated");
                loadDay();
            }, () -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true));
        });
    }

    // ---------------------------------------------------------------
    // UI helpers
    // ---------------------------------------------------------------

    private record Sel(Runnable r) implements AdapterView.OnItemSelectedListener {

        @Override
        public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
            r.run();
        }

        @Override
        public void onNothingSelected(AdapterView<?> p) {
        }
    }

    private void fill(Spinner sp, List<OdooClient.Item> items, String placeholder) {
        List<OdooClient.Item> l = new ArrayList<>();
        l.add(new OdooClient.Item(0, placeholder, ""));
        l.addAll(items);
        ArrayAdapter<OdooClient.Item> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, l);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
    }

    private void select(Spinner sp, int id) {
        if (id == 0) return;
        for (int i = 0; i < sp.getCount(); i++) {
            if (((OdooClient.Item) sp.getItemAtPosition(i)).id() == id) {
                sp.setSelection(i);
                return;
            }
        }
    }

    private int selId(Spinner sp) {
        Object o = sp.getSelectedItem();
        return o instanceof OdooClient.Item ? ((OdooClient.Item) o).id() : 0;
    }

    private void showDate() {
        btnDate.setText(isoToDmy(dateIso));
    }

    private void updateHint(String s) {
        if (s.trim().isEmpty()) {
            tvHoursHint.setText("");
            return;
        }
        int m = parseMinutes(s);
        if (m >= 0) {
            tvHoursHint.setTextColor(0xFF555555);
            tvHoursHint.setText("= " + (m / 60) + "h " + String.format(Locale.US, "%02d", m % 60) + "m");
        } else {
            tvHoursHint.setTextColor(0xFF9B1C1C);
            tvHoursHint.setText("Use H.MM, minutes 00-59");
        }
    }

    private void pickDate(String iso, DateCb cb) {
        int y = Integer.parseInt(iso.substring(0, 4));
        int m = Integer.parseInt(iso.substring(5, 7));
        int d = Integer.parseInt(iso.substring(8, 10));
        new DatePickerDialog(this, (view, yy, mm, dd) ->
                cb.on(String.format(Locale.US, "%04d-%02d-%02d", yy, mm + 1, dd)), y, m - 1, d).show();
    }

    // ---------------------------------------------------------------
    // Date / hours formats (same rules as the web app)
    // ---------------------------------------------------------------

    private static String todayIso() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    private static String isoToDmy(String iso) {
        if (iso == null || iso.length() < 10) return "";
        return iso.substring(8, 10) + "/" + iso.substring(5, 7) + "/" + iso.substring(0, 4);
    }

    private static final Pattern HM = Pattern.compile("^(\\d{1,2})(?:\\.(\\d{1,2}))?$");

    /**
     * "2.30" / "2.3" / "2" -> total minutes, or -1 when invalid.
     */
    private static int parseMinutes(String s) {
        Matcher m = HM.matcher(s.trim().replace(',', '.'));
        if (!m.matches()) return -1;
        int h = Integer.parseInt(m.group(1));
        int mm = 0;
        if (m.group(2) != null) {
            String g = m.group(2);
            if (g.length() == 1) g += "0";    // "2.3" means 2.30
            mm = Integer.parseInt(g);
        }
        if (mm > 59) return -1;
        int total = h * 60 + mm;
        return total <= 0 ? -1 : total;
    }

    /**
     * Total minutes -> "8.30".
     */
    private static String fmtHM(int totalMin) {
        return (totalMin / 60) + "." + String.format(Locale.US, "%02d", totalMin % 60);
    }

    /**
     * Odoo decimal hours (8.5) -> total minutes (510).
     */
    private static int decToMin(double h) {
        return (int) Math.round(h * 60);
    }

    /**
     * Minutes -> decimal hours for Odoo (2.30 typed -> 2.5).
     */
    private static double toOdooHours(int minutes) {
        return Math.round(minutes / 60.0 * 1e6) / 1e6;
    }
}