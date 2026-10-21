package com.expenseos.scheduler;

import android.content.Context;

import com.expenseos.dao.TaskDao;
import com.expenseos.model.Task;

import java.util.List;

/**
 * Recomputes and reschedules the AlarmManager entries for every task.
 * <p>
 * Needed whenever the alarm graph may have drifted from the config:
 * <ul>
 *   <li>after a reboot — AlarmManager alarms are cleared on shutdown;</li>
 *   <li>after editing/deleting a Reminder or Event — tasks already scheduled
 *       against the old offsets/times must be recomputed.</li>
 * </ul>
 * {@link #scheduleForTask} cancels each task's old alarms first, so calling
 * this repeatedly is safe — no duplicate alarms, no missed cancels.
 */
public final class TaskAlarmRescheduler {

    private TaskAlarmRescheduler() {
    }

    /** Reschedules every task's alarms. Blocking — call off the main thread. */
    public static void rescheduleAll(Context ctx) {
        try {
            TaskDao taskDao = new TaskDao(ctx);
            List<Task> all = taskDao.findAll();
            for (Task t : all) {
                List<Long> eventIds = taskDao.findEventIds(t.getId());
                if (eventIds.isEmpty()) continue; // no events → no alarms to schedule
                TaskAlarmScheduler.scheduleForTask(ctx, t.getId(), t.getTaskDateTime(), eventIds);
            }
        } catch (Exception ignored) {
            // never crash the caller (boot receiver / activity resume) over a reschedule failure
        }
    }

    /** Fire-and-forget variant for call sites already on the UI thread. */
    public static void rescheduleAllAsync(Context ctx) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> rescheduleAll(app), "task-alarm-reschedule").start();
    }
}
