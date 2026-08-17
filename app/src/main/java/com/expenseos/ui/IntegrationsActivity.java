package com.expenseos.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.dao.EventDao;
import com.expenseos.dao.ReminderDao;
import com.expenseos.model.Event;
import com.expenseos.model.Reminder;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.tabs.TabLayout;

import java.util.List;

/**
 * Config page of the Integrations feature: Events / Reminders tabs, with a
 * bottom nav to switch over to the Calendar page (IntegrationsCalendarActivity).
 */
public class IntegrationsActivity extends AppCompatActivity {

    private EventDao eventDao;
    private ReminderDao reminderDao;
    private RecyclerView rv;
    private int subTab = 0; // 0 = Events, 1 = Reminders

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_integrations);
        eventDao = new EventDao(this);
        reminderDao = new ReminderDao(this);

        rv = findViewById(R.id.rvEvents);
        rv.setLayoutManager(new LinearLayoutManager(this));

        TabLayout tabs = findViewById(R.id.tabConfigType);
        tabs.addTab(tabs.newTab().setText("Events"));
        tabs.addTab(tabs.newTab().setText("Reminders"));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                subTab = tab.getPosition();
                load();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        findViewById(R.id.fabAdd).setOnClickListener(v ->
                startActivity(new Intent(this, subTab == 0 ? AddEventActivity.class : AddReminderActivity.class)));

        BottomNavigationView bottomNav = findViewById(R.id.bottomNavIntegrations);
        bottomNav.setSelectedItemId(R.id.navConfig);
        bottomNav.setOnItemSelectedListener(item -> {
            if (item.getItemId() == R.id.navCalendar) {
                startActivity(new Intent(this, IntegrationsCalendarActivity.class));
                overridePendingTransition(0, 0);
                finish();
                return true;
            }
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        if (subTab == 0) loadEvents();
        else loadReminders();
    }

    private void loadEvents() {
        List<Event> events = eventDao.findAll();
        findViewById(R.id.tvNoItems).setVisibility(events.isEmpty() ? View.VISIBLE : View.GONE);
        rv.setAdapter(new RecyclerView.Adapter<VH>() {
            @Override
            public VH onCreateViewHolder(android.view.ViewGroup p, int t) {
                return new VH(android.view.LayoutInflater.from(p.getContext()).inflate(R.layout.item_event, p, false));
            }

            @Override
            public void onBindViewHolder(VH h, int pos) {
                Event e = events.get(pos);
                h.tvName.setText(e.getName());
                h.tvSummary.setText(e.getSummary());
                h.itemView.setOnClickListener(v -> openEditEvent(e.getId()));
                h.btnMenu.setOnClickListener(v -> {
                    PopupMenu m = new PopupMenu(IntegrationsActivity.this, v);
                    m.getMenu().add(0, 1, 0, "Edit");
                    m.getMenu().add(0, 2, 1, "Delete");
                    m.setOnMenuItemClickListener(item -> {
                        if (item.getItemId() == 1) {
                            openEditEvent(e.getId());
                            return true;
                        }
                        confirmDeleteEvent(e);
                        return true;
                    });
                    m.show();
                });
            }

            @Override
            public int getItemCount() {
                return events.size();
            }
        });
    }

    private void loadReminders() {
        List<Reminder> reminders = reminderDao.findAll();
        findViewById(R.id.tvNoItems).setVisibility(reminders.isEmpty() ? View.VISIBLE : View.GONE);
        rv.setAdapter(new RecyclerView.Adapter<VH>() {
            @Override
            public VH onCreateViewHolder(android.view.ViewGroup p, int t) {
                return new VH(android.view.LayoutInflater.from(p.getContext()).inflate(R.layout.item_event, p, false));
            }

            @Override
            public void onBindViewHolder(VH h, int pos) {
                Reminder r = reminders.get(pos);
                h.tvName.setText(r.getName());
                h.tvSummary.setText(r.getSummary());
                h.itemView.setOnClickListener(v -> openEditReminder(r.getId()));
                h.btnMenu.setOnClickListener(v -> {
                    PopupMenu m = new PopupMenu(IntegrationsActivity.this, v);
                    m.getMenu().add(0, 1, 0, "Edit");
                    m.getMenu().add(0, 2, 1, "Delete");
                    m.setOnMenuItemClickListener(item -> {
                        if (item.getItemId() == 1) {
                            openEditReminder(r.getId());
                            return true;
                        }
                        confirmDeleteReminder(r);
                        return true;
                    });
                    m.show();
                });
            }

            @Override
            public int getItemCount() {
                return reminders.size();
            }
        });
    }

    private void openEditEvent(long id) {
        Intent i = new Intent(this, AddEventActivity.class);
        i.putExtra("event_id", id);
        startActivity(i);
    }

    private void openEditReminder(long id) {
        Intent i = new Intent(this, AddReminderActivity.class);
        i.putExtra("reminder_id", id);
        startActivity(i);
    }

    private void confirmDeleteEvent(Event e) {
        new AlertDialog.Builder(this)
                .setTitle("Delete \"" + e.getName() + "\"?")
                .setMessage("This removes the event and its notification/alarm links.")
                .setPositiveButton("Delete", (d, w) -> {
                    eventDao.delete(e.getId());
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
                    load();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeleteReminder(Reminder r) {
        new AlertDialog.Builder(this)
                .setTitle("Delete \"" + r.getName() + "\"?")
                .setMessage("Events using this reminder will lose that notification/alarm.")
                .setPositiveButton("Delete", (d, w) -> {
                    reminderDao.delete(r.getId());
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
                    load();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvName, tvSummary;
        View btnMenu;

        VH(View v) {
            super(v);
            tvName = v.findViewById(R.id.tvEventName);
            tvSummary = v.findViewById(R.id.tvEventSummary);
            btnMenu = v.findViewById(R.id.btnEventMenu);
        }
    }
}