package com.expenseos.ui;

import android.app.Activity;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.expenseos.R;
import com.expenseos.dao.TaskDao;
import com.expenseos.model.Task;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DayTasksDialog {

    public interface OnChanged {
        void onChanged();
    }

    public static void show(Activity activity, String yyyyMmDd, OnChanged onChanged) {
        TaskDao dao = new TaskDao(activity);
        List<Task> tasks = dao.findByDate(yyyyMmDd);

        View v = LayoutInflater.from(activity).inflate(R.layout.dialog_day_tasks, null);
        TextView tvEmpty = v.findViewById(R.id.tvDayEmpty);
        RecyclerView rv = v.findViewById(R.id.rvDayTasks);
        View btnMultiActions = v.findViewById(R.id.layoutMultiActions);
        rv.setLayoutManager(new LinearLayoutManager(activity));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(yyyyMmDd)
                .setView(v)
                .setPositiveButton("+ New Task", (d, w) -> {
                    Intent i = new Intent(activity, AddTaskActivity.class);
                    i.putExtra(AddTaskActivity.EXTRA_DATE, yyyyMmDd);
                    activity.startActivity(i);
                })
                .setNegativeButton("Close", null)
                .create();

        tvEmpty.setVisibility(tasks.isEmpty() ? View.VISIBLE : View.GONE);

        Set<Long> selectedIds = new HashSet<>();
        boolean[] multiSelectMode = {false};

        Runnable[] refreshRef = new Runnable[1];
        Runnable refresh = () -> {
            boolean multi = selectedIds.size() > 1;
            btnMultiActions.setVisibility(!selectedIds.isEmpty() ? View.VISIBLE : View.GONE);
            v.findViewById(R.id.btnMultiEdit).setEnabled(!multi); // edit disabled when >1 selected
        };
        refreshRef[0] = refresh;

        rv.setAdapter(new RecyclerView.Adapter<RowVH>() {
            @Override
            public RowVH onCreateViewHolder(android.view.ViewGroup p, int t) {
                return new RowVH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_day_task, p, false));
            }

            @Override
            public void onBindViewHolder(RowVH h, int pos) {
                Task task = tasks.get(pos);
                h.tvName.setText(task.getName());
                h.tvTime.setText(task.getTaskDateTime().length() >= 16 ? task.getTaskDateTime().substring(11) : "");
                if (task.getColor() != null && !task.getColor().isEmpty()) {
                    h.colorDot.setBackgroundColor(android.graphics.Color.parseColor(task.getColor()));
                    h.colorDot.setVisibility(View.VISIBLE);
                } else h.colorDot.setVisibility(View.GONE);

                h.cb.setOnCheckedChangeListener(null);
                h.cb.setChecked(selectedIds.contains(task.getId()));
                h.cb.setOnCheckedChangeListener((btn, checked) -> {
                    if (checked) selectedIds.add(task.getId());
                    else selectedIds.remove(task.getId());
                    refresh.run();
                });

                h.itemView.setOnClickListener(view -> {
                    if (!selectedIds.isEmpty()) { // already in select mode -> toggle
                        h.cb.setChecked(!h.cb.isChecked());
                        return;
                    }
                    PopupMenu m = new PopupMenu(activity, view);
                    m.getMenu().add(0, 1, 0, "Edit");
                    m.getMenu().add(0, 2, 1, "Duplicate");
                    m.getMenu().add(0, 3, 2, "Delete");
                    m.setOnMenuItemClickListener(item -> {
                        if (item.getItemId() == 1) {
                            Intent i = new Intent(activity, AddTaskActivity.class);
                            i.putExtra(AddTaskActivity.EXTRA_TASK_ID, task.getId());
                            activity.startActivity(i);
                            dialog.dismiss();
                        } else if (item.getItemId() == 2) {
                            confirmAction(activity, "Duplicate \"" + task.getName() + "\"?", () -> {
                                duplicateTask(activity, dao, task);
                                Toast.makeText(activity, "Duplicated", Toast.LENGTH_SHORT).show();
                                dialog.dismiss();
                                if (onChanged != null) onChanged.onChanged();
                            });
                            // single delete (Edit/Duplicate/Delete popup menu, item id 3):
                        } else if (item.getItemId() == 3) {
                            confirmAction(activity, "Delete \"" + task.getName() + "\"?", () -> {
                                com.expenseos.scheduler.TaskAlarmScheduler.cancelForTask(activity, task.getId());
                                com.expenseos.sync.GoogleCalendarSyncManager.delete(activity, task.getGoogleEventId(), (ok, msg) -> {
                                });
                                dao.delete(task.getId());
                                Toast.makeText(activity, "Deleted", Toast.LENGTH_SHORT).show();
                                dialog.dismiss();
                                if (onChanged != null) onChanged.onChanged();
                            });
                        }
                        return true;
                    });
                    m.show();
                });
            }

            @Override
            public int getItemCount() {
                return tasks.size();
            }
        });

        v.findViewById(R.id.btnMultiEdit).setOnClickListener(view -> {
            if (selectedIds.size() == 1) {
                Intent i = new Intent(activity, AddTaskActivity.class);
                i.putExtra(AddTaskActivity.EXTRA_TASK_ID, selectedIds.iterator().next());
                activity.startActivity(i);
                dialog.dismiss();
            }
        });

        v.findViewById(R.id.btnMultiDuplicate).setOnClickListener(view ->
                confirmAction(activity, "Duplicate " + selectedIds.size() + " task(s)?", () -> {
                    for (Task t : tasks)
                        if (selectedIds.contains(t.getId())) duplicateTask(activity, dao, t);
                    Toast.makeText(activity, "Duplicated", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                    if (onChanged != null) onChanged.onChanged();
                }));

        // multi-delete button:
        v.findViewById(R.id.btnMultiDelete).setOnClickListener(view ->
                confirmAction(activity, "Delete " + selectedIds.size() + " task(s)?", () -> {
                    for (Task t : tasks) {
                        if (selectedIds.contains(t.getId())) {
                            com.expenseos.scheduler.TaskAlarmScheduler.cancelForTask(activity, t.getId());
                            com.expenseos.sync.GoogleCalendarSyncManager.delete(activity, t.getGoogleEventId(), (ok, msg) -> {
                            });
                            dao.delete(t.getId());
                        }
                    }
                    Toast.makeText(activity, "Deleted", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                    if (onChanged != null) onChanged.onChanged();
                }));

        dialog.show();
    }

    private static void duplicateTask(Activity activity, TaskDao dao, Task original) {
        Task copy = new Task();
        copy.setName(original.getName() + " (Copy)");
        copy.setTaskDateTime(original.getTaskDateTime());
        copy.setDescription(original.getDescription());
        copy.setColor(original.getColor());
        List<Long> eventIds = dao.findEventIds(original.getId());
        copy.setEventIds(eventIds);
        long newId = dao.insert(copy);
        com.expenseos.scheduler.TaskAlarmScheduler.scheduleForTask(activity, newId, copy.getTaskDateTime(), eventIds);
    }

    private static void confirmAction(Activity activity, String message, Runnable onYes) {
        new AlertDialog.Builder(activity)
                .setTitle("Are you sure?")
                .setMessage(message)
                .setPositiveButton("Yes", (d, w) -> onYes.run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static class RowVH extends RecyclerView.ViewHolder {
        TextView tvName, tvTime;
        CheckBox cb;
        View colorDot;

        RowVH(View v) {
            super(v);
            tvName = v.findViewById(R.id.tvDayTaskName);
            tvTime = v.findViewById(R.id.tvDayTaskTime);
            cb = v.findViewById(R.id.cbDayTask);
            colorDot = v.findViewById(R.id.viewColorDot);
        }
    }
}