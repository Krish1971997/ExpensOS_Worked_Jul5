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
import com.expenseos.dao.ReminderDao;
import com.expenseos.model.Reminder;

import java.util.List;

public class RemindersListActivity extends AppCompatActivity {

    private ReminderDao dao;
    private List<Reminder> reminders;
    private RecyclerView rv;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_reminders_list);
        dao = new ReminderDao(this);

        findViewById(R.id.fabAddReminder).setOnClickListener(v ->
                startActivity(new Intent(this, AddReminderActivity.class)));

        rv = findViewById(R.id.rvReminders);
        rv.setLayoutManager(new LinearLayoutManager(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        reminders = dao.findAll();
        findViewById(R.id.tvNoReminders).setVisibility(reminders.isEmpty() ? View.VISIBLE : View.GONE);
        rv.setAdapter(new Adapter());
    }

    class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvSummary;
            View btnMenu;

            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tvEventName);       // reusing item_event.xml layout ids
                tvSummary = v.findViewById(R.id.tvEventSummary);
                btnMenu = v.findViewById(R.id.btnEventMenu);
            }
        }

        @Override
        public VH onCreateViewHolder(android.view.ViewGroup p, int t) {
            return new VH(android.view.LayoutInflater.from(p.getContext()).inflate(R.layout.item_event, p, false));
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            Reminder r = reminders.get(pos);
            h.tvName.setText(r.getName());
            h.tvSummary.setText(r.getSummary());

            h.itemView.setOnClickListener(v -> openEdit(r));
            h.btnMenu.setOnClickListener(v -> {
                PopupMenu m = new PopupMenu(RemindersListActivity.this, v);
                m.getMenu().add(0, 1, 0, "Edit");
                m.getMenu().add(0, 2, 1, "Delete");
                m.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 1) {
                        openEdit(r);
                        return true;
                    }
                    if (item.getItemId() == 2) {
                        confirmDelete(r);
                        return true;
                    }
                    return false;
                });
                m.show();
            });
        }

        @Override
        public int getItemCount() {
            return reminders.size();
        }
    }

    private void openEdit(Reminder r) {
        Intent i = new Intent(this, AddReminderActivity.class);
        i.putExtra("reminder_id", r.getId());
        startActivity(i);
    }

    private void confirmDelete(Reminder r) {
        new AlertDialog.Builder(this)
                .setTitle("Delete \"" + r.getName() + "\"?")
                .setMessage("Events using this reminder will lose that notification/alarm.")
                .setPositiveButton("Delete", (d, w) -> {
                    dao.delete(r.getId());
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
                    load();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}