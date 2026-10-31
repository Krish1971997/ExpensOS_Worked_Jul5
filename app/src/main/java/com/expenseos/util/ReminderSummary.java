package com.expenseos.util;

import android.content.Context;

import com.expenseos.dao.EventDao;
import com.expenseos.dao.ReminderDao;
import com.expenseos.model.Event;
import com.expenseos.model.EventReminder;
import com.expenseos.model.Reminder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Human readable "when will I be notified / alarmed" text for a task.
 * Used by AddTaskActivity (live summary) and TaskPreviewActivity.
 *
 * Maths (same as the Reminder/Event labels):
 *   eventDate = taskDate -/+ event.offsetDays   (BEFORE / AFTER)
 *   fireAt    = eventDate - reminder.offset (days or weeks) at reminder.hour:minute
 * If TaskAlarmScheduler computes it differently, change fireTime() below
 * (or better: call the scheduler's own calculator from here) so both agree.
 */
public final class ReminderSummary {
    private ReminderSummary() {
    }

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy · hh:mm a", Locale.ENGLISH);

    public static LocalDateTime fireTime(LocalDate taskDate, Event e, Reminder r) {
        LocalDate eventDate = "AFTER".equals(e.getOffsetDirection())
                ? taskDate.plusDays(e.getOffsetDays())
                : taskDate.minusDays(e.getOffsetDays());
        // same maths as TaskAlarmScheduler.scheduleType: base.minusDays(offsetInDays) at reminder time
        return LocalDateTime.of(eventDate.minusDays(r.offsetInDays()),
                LocalTime.of(r.getTimeHour(), r.getTimeMinute()));
    }

    /** Events are listed in the order of eventIds, reminders in the order they were added. */
    public static String build(Context ctx, LocalDate taskDate, List<Long> eventIds) {
        if (eventIds == null || eventIds.isEmpty())
            return "Select event(s) to see when notifications / alarms will fire";

        EventDao eventDao = new EventDao(ctx);
        ReminderDao reminderDao = new ReminderDao(ctx);
        List<Event> allEvents = eventDao.findAll();
        LocalDateTime now = LocalDateTime.now();
        StringBuilder sb = new StringBuilder();

        for (Long eid : eventIds) {
            Event ev = null;
            for (Event e : allEvents) if (e.getId() == eid) ev = e;
            if (ev == null) continue;

            if (sb.length() > 0) sb.append("\n\n");
            sb.append("📌 ").append(ev.getName()).append(" (")
                    .append(ev.getOffsetDays()).append(" day(s) ")
                    .append("AFTER".equals(ev.getOffsetDirection()) ? "after" : "before")
                    .append(")");

            boolean any = false;
            any |= appendType(sb, eventDao, reminderDao, ev, taskDate, now, "NOTIFICATION", "🔔 Notification");
            any |= appendType(sb, eventDao, reminderDao, ev, taskDate, now, "ALARM", "⏰ Alarm");
            if (!any) sb.append("\n   No reminders set");
        }
        return sb.toString();
    }

    private static boolean appendType(StringBuilder sb, EventDao eventDao, ReminderDao reminderDao,
                                      Event ev, LocalDate taskDate, LocalDateTime now,
                                      String type, String label) {
        boolean any = false;
        for (EventReminder er : eventDao.findReminders(ev.getId(), type)) {
            Reminder r = reminderDao.findById(er.getReminderId());
            if (r == null) continue;
            LocalDateTime at = fireTime(taskDate, ev, r);
            sb.append("\n   ").append(label).append(" – ").append(at.format(FMT));
            if (at.isBefore(now)) sb.append("  (already passed)");
            any = true;
        }
        return any;
    }

    /** One reminder line, for UIs that draw their own rows (task preview). */
    public static final class Row {
        public final boolean alarm;
        public final String eventName;
        public final String when;
        public final boolean passed;

        Row(boolean alarm, String eventName, String when, boolean passed) {
            this.alarm = alarm;
            this.eventName = eventName;
            this.when = when;
            this.passed = passed;
        }
    }

    /** Same order as build(): per event, notifications then alarms, reminders in added order. */
    public static List<Row> rows(Context ctx, LocalDate taskDate, List<Long> eventIds) {
        List<Row> out = new ArrayList<>();
        if (eventIds == null) return out;
        EventDao eventDao = new EventDao(ctx);
        ReminderDao reminderDao = new ReminderDao(ctx);
        List<Event> allEvents = eventDao.findAll();
        LocalDateTime now = LocalDateTime.now();
        for (Long eid : eventIds) {
            Event ev = null;
            for (Event e : allEvents) if (e.getId() == eid) ev = e;
            if (ev == null) continue;
            for (String type : new String[]{"NOTIFICATION", "ALARM"}) {
                for (EventReminder er : eventDao.findReminders(ev.getId(), type)) {
                    Reminder r = reminderDao.findById(er.getReminderId());
                    if (r == null) continue;
                    LocalDateTime at = fireTime(taskDate, ev, r);
                    out.add(new Row("ALARM".equals(type), ev.getName(), at.format(FMT), at.isBefore(now)));
                }
            }
        }
        return out;
    }
}
