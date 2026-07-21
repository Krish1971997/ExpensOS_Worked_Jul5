package com.expenseos.model;

import java.time.LocalDateTime;

public class SchedulerConfig {

    private int id;
    private String name;
    private String displayName;
    private boolean enabled;
    private String repeatType; // DAILY, WEEKLY, MONTHLY
    private String repeatDays; // WEEKLY: "MON,THU" | MONTHLY: "1"
    private int runHour;
    private int runMinute;
    private LocalDateTime lastRunAt;
    private String lastRunStatus; // SUCCESS, FAILED, RUNNING
    private String lastRunMsg;
    private LocalDateTime nextRunAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Computed helper — next run display string
    public String getNextRunDisplay() {
        if (!enabled)
            return "Disabled";
        if (nextRunAt == null)
            return "Not scheduled";
        return nextRunAt.toString().replace("T", " ").substring(0, 16);
    }

    public String getLastRunDisplay() {
        if (lastRunAt == null)
            return "Never";
        return lastRunAt.toString().replace("T", " ").substring(0, 16);
    }

    public String getRepeatDescription() {
        switch (repeatType) {
            case "HOURLY":
                return "Every hour at " + pad(runHour) + ":" + pad(runMinute);
            case "DAILY":
                return "Every Day at " + pad(runHour) + ":" + pad(runMinute);
            case "WEEKLY":
                return "Weekly on " + repeatDays + " at " + pad(runHour) + ":" + pad(runMinute);
            case "MONTHLY":
                return "Monthly on day " + repeatDays + " at " + pad(runHour) + ":" + pad(runMinute);
            default:
                return repeatType;
        }
    }

    private String pad(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    // ── Getters / Setters ──────────────────────────────────────────
    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRepeatType() {
        return repeatType;
    }

    public void setRepeatType(String repeatType) {
        this.repeatType = repeatType;
    }

    public String getRepeatDays() {
        return repeatDays;
    }

    public void setRepeatDays(String repeatDays) {
        this.repeatDays = repeatDays;
    }

    public int getRunHour() {
        return runHour;
    }

    public void setRunHour(int runHour) {
        this.runHour = runHour;
    }

    public int getRunMinute() {
        return runMinute;
    }

    public void setRunMinute(int runMinute) {
        this.runMinute = runMinute;
    }

    public LocalDateTime getLastRunAt() {
        return lastRunAt;
    }

    public void setLastRunAt(LocalDateTime lastRunAt) {
        this.lastRunAt = lastRunAt;
    }

    public String getLastRunStatus() {
        return lastRunStatus;
    }

    public void setLastRunStatus(String lastRunStatus) {
        this.lastRunStatus = lastRunStatus;
    }

    public String getLastRunMsg() {
        return lastRunMsg;
    }

    public void setLastRunMsg(String lastRunMsg) {
        this.lastRunMsg = lastRunMsg;
    }

    public LocalDateTime getNextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(LocalDateTime nextRunAt) {
        this.nextRunAt = nextRunAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}