package com.expenseos.scheduler;

import com.expenseos.model.SchedulerConfig;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Port of the web SchedulerEngine's calcNextRun()/isDue() logic, kept in one
 * place so the edit screen (which recalculates next_run_at on Save) and the
 * background Worker (which checks isDue on each tick) never drift apart.
 */
public class SchedulerTimeUtil {

    public static LocalDateTime calcNextRun(SchedulerConfig s) {
        LocalDate today = LocalDate.now();
        LocalTime runTime = LocalTime.of(s.getRunHour(), s.getRunMinute());

        switch (s.getRepeatType()) {
            case "HOURLY":
                return LocalDateTime.now().plusHours(1)
                        .withMinute(s.getRunMinute()).withSecond(0).withNano(0);
            case "WEEKLY": {
                LocalDate d = today.plusDays(1);
                for (int i = 0; i < 7; i++, d = d.plusDays(1)) {
                    String day = d.getDayOfWeek().name().substring(0, 3);
                    if (s.getRepeatDays() != null && s.getRepeatDays().toUpperCase().contains(day))
                        return LocalDateTime.of(d, runTime);
                }
                return LocalDateTime.of(today.plusDays(7), runTime);
            }
            case "MONTHLY": {
                int dom = 1;
                try {
                    dom = Integer.parseInt(s.getRepeatDays().trim());
                } catch (Exception ignored) {
                }
                LocalDate next = today.plusMonths(1).withDayOfMonth(Math.min(dom, 28));
                return LocalDateTime.of(next, runTime);
            }
            case "DAILY":
            default:
                return LocalDateTime.of(today.plusDays(1), runTime);
        }
    }

    /**
     * Due if enabled and next_run_at has arrived (or is missing/in the past).
     */
    public static boolean isDue(SchedulerConfig s, LocalDateTime now) {
        if (!s.isEnabled()) return false;
        if (s.getNextRunAt() == null) return true;
        return !now.isBefore(s.getNextRunAt());
    }
}
