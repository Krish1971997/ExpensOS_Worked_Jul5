package com.expenseos.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.dao.TaskDao;
import com.expenseos.model.Task;
import com.google.android.material.tabs.TabLayout;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Task/reminder calendar for the Integrations tab — separate from
 * CalendarViewActivity (which is the per-cashbook transaction calendar).
 * Uses the `tasks` table, no bookId scoping.
 */
public class IntegrationsCalendarActivity extends AppCompatActivity {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private TaskDao taskDao;
    private YearMonth currentMonth;
    private LocalDate currentDay;
    private boolean listMode = false; // calendar vs flat list toggle
    private int viewMode = 0; // 0=Month 1=Week 2=Day 3=Year

    @Override
    protected void onCreate(Bundle s) {

        super.onCreate(s);
        setContentView(R.layout.activity_integrations_calendar);
        taskDao = new TaskDao(this);
        currentMonth = YearMonth.now();
        currentDay = LocalDate.now();

        com.google.android.material.bottomnavigation.BottomNavigationView bottomNav = findViewById(R.id.bottomNavIntegrationsCal);
        bottomNav.setSelectedItemId(R.id.navCalendar);
        bottomNav.setOnItemSelectedListener(item -> {
            if (item.getItemId() == R.id.navConfig) {
                startActivity(new Intent(this, IntegrationsActivity.class));
                overridePendingTransition(0, 0);
                finish();
                return true;
            }
            return true;
        });

        findViewById(R.id.btnCalBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnCalPrev).setOnClickListener(v -> step(-1));
        findViewById(R.id.btnCalNext).setOnClickListener(v -> step(1));
        findViewById(R.id.btnToggleList).setOnClickListener(v -> {
            listMode = !listMode;
            render();
        });

        TabLayout tabs = findViewById(R.id.tabViewMode);
        tabs.addTab(tabs.newTab().setText("Month"));
        tabs.addTab(tabs.newTab().setText("Week"));
        tabs.addTab(tabs.newTab().setText("Day"));
        tabs.addTab(tabs.newTab().setText("Year"));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                viewMode = tab.getPosition();
                render();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render(); // refresh after returning from AddTaskActivity / DayTasksDialog actions
    }

    private void step(int dir) {
        switch (viewMode) {
            case 0:
                currentMonth = currentMonth.plusMonths(dir);
                break;
            case 1:
                currentDay = currentDay.plusWeeks(dir);
                break;
            case 2:
                currentDay = currentDay.plusDays(dir);
                break;
            case 3:
                currentMonth = currentMonth.plusYears(dir);
                break;
        }
        render();
    }

    private void render() {
        RecyclerView rv = findViewById(R.id.rvCalendarBody);
        TextView tvTitle = findViewById(R.id.tvCalTitle);
        findViewById(R.id.btnToggleList).setVisibility(viewMode == 0 ? View.VISIBLE : View.GONE);

        if (listMode && viewMode == 0) {
            renderFlatList(rv, tvTitle);
            return;
        }

        switch (viewMode) {
            case 0:
                renderMonth(rv, tvTitle);
                break;
            case 1:
                renderWeek(rv, tvTitle);
                break;
            case 2:
                renderDay(rv, tvTitle);
                break;
            case 3:
                renderYear(rv, tvTitle);
                break;
        }
    }

    // ── Month grid (dots for days with tasks) ─────────────
    private void renderMonth(RecyclerView rv, TextView tvTitle) {
        tvTitle.setText(currentMonth.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + currentMonth.getYear());

        List<String> datesWithTasks = taskDao.findDatesInMonth(currentMonth.toString().substring(0, 7));

        List<LocalDate> cells = new ArrayList<>();
        LocalDate first = currentMonth.atDay(1);
        int leading = first.getDayOfWeek().getValue() % 7;
        for (int i = 0; i < leading; i++) cells.add(null);
        for (int d = 1; d <= currentMonth.lengthOfMonth(); d++) cells.add(currentMonth.atDay(d));
        while (cells.size() % 7 != 0) cells.add(null);

        rv.setLayoutManager(new GridLayoutManager(this, 7));
        rv.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            class VH extends RecyclerView.ViewHolder {
                final TextView tvNum;
                final View dot;

                VH(View v) {
                    super(v);
                    tvNum = v.findViewById(R.id.tvDayNumber);
                    dot = v.findViewById(R.id.viewTaskDot);
                }
            }

            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
                return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_integrations_calendar_day, p, false));
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
                VH h = (VH) holder;
                LocalDate date = cells.get(pos);
                if (date == null) {
                    h.tvNum.setText("");
                    h.dot.setVisibility(View.GONE);
                    h.itemView.setOnClickListener(null);
                    return;
                }
                h.tvNum.setText(String.valueOf(date.getDayOfMonth()));
                boolean isToday = date.equals(LocalDate.now());
                h.tvNum.setBackgroundResource(isToday ? R.drawable.bg_today_circle : 0);
                h.tvNum.setTextColor(getColor(isToday ? android.R.color.white : R.color.text));
                h.dot.setVisibility(datesWithTasks.contains(date.format(DATE_FMT)) ? View.VISIBLE : View.GONE);
                h.itemView.setOnClickListener(v ->
                        DayTasksDialog.show(IntegrationsCalendarActivity.this, date.format(DATE_FMT), () -> render()));
            }

            @Override
            public int getItemCount() {
                return cells.size();
            }
        });
    }

    // ── Week: 7 rows, one per day, with task count ────────
    private void renderWeek(RecyclerView rv, TextView tvTitle) {
        LocalDate monday = currentDay.minusDays((currentDay.getDayOfWeek().getValue() + 6) % 7);
        tvTitle.setText(monday.format(DateTimeFormatter.ofPattern("dd MMM")) + " – " +
                monday.plusDays(6).format(DateTimeFormatter.ofPattern("dd MMM yyyy")));

        List<LocalDate> week = new ArrayList<>();
        for (int i = 0; i < 7; i++) week.add(monday.plusDays(i));

        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            class VH extends RecyclerView.ViewHolder {
                final TextView tvLabel;
                final TextView tvCount;

                VH(View v) {
                    super(v);
                    tvLabel = v.findViewById(R.id.tvWeekDayLabel);
                    tvCount = v.findViewById(R.id.tvWeekDayCount);
                }
            }

            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
                return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_calendar_week_row, p, false));
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
                VH h = (VH) holder;
                LocalDate d = week.get(pos);
                List<Task> tasks = taskDao.findByDate(d.format(DATE_FMT));
                h.tvLabel.setText(d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + ", "
                        + d.format(DateTimeFormatter.ofPattern("dd MMM")));
                h.tvCount.setText(tasks.isEmpty() ? "No tasks" : tasks.size() + " task(s)");
                h.itemView.setOnClickListener(v ->
                        DayTasksDialog.show(IntegrationsCalendarActivity.this, d.format(DATE_FMT), () -> render()));
            }

            @Override
            public int getItemCount() {
                return week.size();
            }
        });
    }

    // ── Day: just open the DayTasksDialog content inline as a list ──
    private void renderDay(RecyclerView rv, TextView tvTitle) {
        tvTitle.setText(currentDay.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + ", "
                + currentDay.format(DateTimeFormatter.ofPattern("dd MMM yyyy")));
        List<Task> tasks = taskDao.findByDate(currentDay.format(DATE_FMT));
        renderTaskList(rv, tasks, currentDay.format(DATE_FMT));
    }

    // ── Year: 12 months, tap a month -> jump to Month view ────
    private void renderYear(RecyclerView rv, TextView tvTitle) {
        int year = currentMonth.getYear();
        tvTitle.setText(String.valueOf(year));
        List<YearMonth> months = new ArrayList<>();
        for (int m = 1; m <= 12; m++) months.add(YearMonth.of(year, m));

        rv.setLayoutManager(new GridLayoutManager(this, 3));
        rv.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            class VH extends RecyclerView.ViewHolder {
                final TextView tvMonth;
                final TextView tvCount;

                VH(View v) {
                    super(v);
                    tvMonth = v.findViewById(R.id.tvYearMonthLabel);
                    tvCount = v.findViewById(R.id.tvYearMonthCount);
                }
            }

            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
                return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_calendar_year_month, p, false));
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
                VH h = (VH) holder;
                YearMonth ym = months.get(pos);
                int count = taskDao.findDatesInMonth(ym.toString().substring(0, 7)).size();
                h.tvMonth.setText(ym.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
                h.tvCount.setText(count > 0 ? count + " day(s)" : "");
                h.itemView.setOnClickListener(v -> {
                    currentMonth = ym;
                    viewMode = 0;
                    ((TabLayout) findViewById(R.id.tabViewMode)).getTabAt(0).select();
                });
            }

            @Override
            public int getItemCount() {
                return months.size();
            }
        });
    }

    // ── Flat list toggle (all upcoming tasks) ─────────────
    private void renderFlatList(RecyclerView rv, TextView tvTitle) {
        tvTitle.setText("All tasks — " + currentMonth.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + currentMonth.getYear());
        List<Task> all = new ArrayList<>();
        for (String d : taskDao.findDatesInMonth(currentMonth.toString().substring(0, 7)))
            all.addAll(taskDao.findByDate(d));
        renderTaskList(rv, all, null);
    }

    private void renderTaskList(RecyclerView rv, List<Task> tasks, String fixedDateForAdd) {
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            class VH extends RecyclerView.ViewHolder {
                final TextView tvName;
                final TextView tvWhen;

                VH(View v) {
                    super(v);
                    tvName = v.findViewById(R.id.tvFlatTaskName);
                    tvWhen = v.findViewById(R.id.tvFlatTaskWhen);
                }
            }

            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
                return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_calendar_flat_task, p, false));
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
                VH h = (VH) holder;
                Task task = tasks.get(pos);
                h.tvName.setText(task.getName());
                h.tvWhen.setText(task.getTaskDateTime());
                h.itemView.setOnClickListener(v -> {
                    String date = task.getTaskDateTime().substring(0, 10);
                    DayTasksDialog.show(IntegrationsCalendarActivity.this, date, () -> render());
                });
            }

            @Override
            public int getItemCount() {
                return tasks.size();
            }
        });
    }
}