package com.expenseos.ui;

import android.content.Context;
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
import com.expenseos.model.EventReminder;
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

    // ── Alarm tone picker (phone tones / file-manager audio) ─────────
    private final androidx.activity.result.ActivityResultLauncher<android.content.Intent> ringtoneLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                        android.net.Uri picked = result.getData()
                                .getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
                        if (picked != null) {
                            com.expenseos.util.AlarmToneStore.save(this, picked.toString(), toneName(this, picked));
                            refreshToneLabel();
                        }
                    });

    private final androidx.activity.result.ActivityResultLauncher<android.content.Intent> audioFileLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() != RESULT_OK || result.getData() == null
                                || result.getData().getData() == null) return;
                        android.net.Uri file = result.getData().getData();
                        try {
                            // Persist read access so the alarm can still play it after restarts
                            getContentResolver().takePersistableUriPermission(file,
                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Exception ignored) {
                            // Some providers don't grant persistable rights — tone may not survive reboot
                        }
                        com.expenseos.util.AlarmToneStore.save(this, file.toString(), toneName(this, file));
                        refreshToneLabel();
                    });

    private static String toneName(Context ctx, android.net.Uri uri) {
        try (android.database.Cursor c = ctx.getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last != null ? last.substring(Math.max(0, last.length() - 40)) : "Custom tone";
    }

    private void showToneChooser() {
        CharSequence[] opts = {"Phone alarm tones", "Audio file (file manager)", "Default tone (reset)"};
        new AlertDialog.Builder(this)
                .setTitle("Alarm tone")
                .setItems(opts, (d, which) -> {
                    if (which == 0) {
                        // the phone's own alarm sounds
                        android.content.Intent i = new android.content.Intent(
                                android.media.RingtoneManager.ACTION_RINGTONE_PICKER);
                        i.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
                                android.media.RingtoneManager.TYPE_ALARM);
                        i.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "Select alarm tone");
                        i.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false);
                        i.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
                        ringtoneLauncher.launch(i);
                    } else if (which == 1) {
                        // any audio file on the device
                        android.content.Intent i = new android.content.Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                        i.setType("audio/*");
                        i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                        try {
                            audioFileLauncher.launch(i);
                        } catch (android.content.ActivityNotFoundException e) {
                            Toast.makeText(this, "No file manager found", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        com.expenseos.util.AlarmToneStore.clear(this);
                        refreshToneLabel();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refreshToneLabel() {
        ((android.widget.Button) findViewById(R.id.btnAlarmTone))
                .setText("🔔 Alarm tone: " + com.expenseos.util.AlarmToneStore.getName(this));
    }

    private androidx.activity.result.ActivityResultLauncher<android.content.Intent> signInLauncher;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_integrations);
        eventDao = new EventDao(this);
        reminderDao = new ReminderDao(this);

        // Alarm tone — phone tones or a file-manager audio file, played by
        // AlarmRingActivity when an ALARM reminder fires.
        refreshToneLabel();
        findViewById(R.id.btnAlarmTone).setOnClickListener(v -> showToneChooser());

        // POST_NOTIFICATIONS — task NOTIFICATION reminders need it on Android
        // 13+; asked here too in case the app was opened straight into this screen.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                && androidx.core.content.ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1002);
        }

        signInLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(), result -> {
                    com.google.android.gms.tasks.Task<com.google.android.gms.auth.api.signin.GoogleSignInAccount> task =
                            com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(result.getData());
                    try {
                        task.getResult(com.google.android.gms.common.api.ApiException.class);
                        Toast.makeText(this, "Connected to Google Calendar", Toast.LENGTH_SHORT).show();
                        updateGoogleButtonLabel();
                    } catch (com.google.android.gms.common.api.ApiException e) {
                        Toast.makeText(this, "Google sign-in failed: " + e.getStatusCode(), Toast.LENGTH_SHORT).show();
                    }
                });

        findViewById(R.id.btnGoogleConnect).setOnClickListener(v -> {
            if (com.expenseos.sync.GoogleCalendarSyncManager.isSignedIn(this)) {
                com.expenseos.sync.GoogleCalendarSyncManager.signOut(this, () -> {
                    Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show();
                    updateGoogleButtonLabel();
                });
            } else {
                signInLauncher.launch(com.expenseos.sync.GoogleCalendarSyncManager.getSignInClient(this).getSignInIntent());
            }
        });
        updateGoogleButtonLabel();

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
        com.expenseos.scheduler.TaskAlarmRescheduler.rescheduleAllAsync(this);
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
                // Table-like row: offset summary + the linked NOTIFICATION / ALARM
                // reminder names, so the config reads like a grid.
                StringBuilder meta = new StringBuilder();
                List<EventReminder> n = eventDao.findReminders(e.getId(), "NOTIFICATION");
                List<EventReminder> a = eventDao.findReminders(e.getId(), "ALARM");
                meta.append("🔔 ").append(n.isEmpty() ? "—" : n.get(0).getReminderName());
                meta.append("   ⏰ ").append(a.isEmpty() ? "—" : a.get(0).getReminderName());
                h.tvSummary.setText(e.getSummary() + "\n" + meta);
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
                    com.expenseos.scheduler.TaskAlarmRescheduler.rescheduleAllAsync(this);
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

    private void updateGoogleButtonLabel() {
        android.widget.Button b = findViewById(R.id.btnGoogleConnect);
        boolean signedIn = com.expenseos.sync.GoogleCalendarSyncManager.isSignedIn(this);
        b.setText(signedIn ? "✔ Google Connected" : "Connect Google Calendar");
    }
}