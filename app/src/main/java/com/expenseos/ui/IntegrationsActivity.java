package com.expenseos.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.dao.EventDao;
import com.expenseos.model.Event;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;

import java.util.List;

public class IntegrationsActivity extends AppCompatActivity {

    private EventDao dao;
    private List<Event> events;
    private RecyclerView rv;
    private androidx.activity.result.ActivityResultLauncher<Intent> signInLauncher;


    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_integrations);

        signInLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(), result -> {
                    com.google.android.gms.tasks.Task<GoogleSignInAccount> task =
                            GoogleSignIn.getSignedInAccountFromIntent(result.getData());
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

        dao = new EventDao(this);

        findViewById(R.id.btnIntegrationsBack).setOnClickListener(v -> finish());
        findViewById(R.id.fabAddEvent).setOnClickListener(v ->
                startActivity(new Intent(this, AddEventActivity.class)));

        // 👇 ithu add pannunga
        findViewById(R.id.btnOpenCalendar).setOnClickListener(v ->
                startActivity(new Intent(this, IntegrationsCalendarActivity.class)));

        rv = findViewById(R.id.rvEvents);
        rv.setLayoutManager(new LinearLayoutManager(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        events = dao.findAll();
        findViewById(R.id.tvNoEvents).setVisibility(events.isEmpty() ? View.VISIBLE : View.GONE);
        rv.setAdapter(new Adapter());
    }

    class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvSummary;
            View btnMenu;

            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tvEventName);
                tvSummary = v.findViewById(R.id.tvEventSummary);
                btnMenu = v.findViewById(R.id.btnEventMenu);
            }
        }

        @Override
        public VH onCreateViewHolder(android.view.ViewGroup p, int t) {
            return new VH(android.view.LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_event, p, false));
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            Event e = events.get(pos);
            h.tvName.setText(e.getName());
            h.tvSummary.setText(e.getSummary());

            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(IntegrationsActivity.this, AddEventActivity.class);
                i.putExtra("event_id", e.getId());
                startActivity(i);
            });

            h.btnMenu.setOnClickListener(v -> {
                PopupMenu m = new PopupMenu(IntegrationsActivity.this, v);
                m.getMenu().add(0, 1, 0, "Edit");
                m.getMenu().add(0, 2, 1, "Delete");
                m.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 1) {
                        Intent i = new Intent(IntegrationsActivity.this, AddEventActivity.class);
                        i.putExtra("event_id", e.getId());
                        startActivity(i);
                        return true;
                    } else if (item.getItemId() == 2) {
                        confirmDelete(e);
                        return true;
                    }
                    return false;
                });
                m.show();
            });
        }

        @Override
        public int getItemCount() {
            return events.size();
        }
    }

    private void confirmDelete(Event e) {
        new AlertDialog.Builder(this)
                .setTitle("Delete \"" + e.getName() + "\"?")
                .setMessage("This removes the event and its notification/alarm reminders.")
                .setPositiveButton("Delete", (d, w) -> {
                    dao.delete(e.getId());
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
                    load();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateGoogleButtonLabel() {
        Button b = findViewById(R.id.btnGoogleConnect);
        boolean signedIn = com.expenseos.sync.GoogleCalendarSyncManager.isSignedIn(this);
        b.setText(signedIn ? "✔ Google Connected" : "Connect Google Calendar");
    }
}