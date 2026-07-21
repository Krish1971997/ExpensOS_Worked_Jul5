package com.expenseos.model;

import java.time.LocalDateTime;

public class SchedulerLog {
    private int id;
    private int schedulerId;
    private String schedulerName;
    private String status;
    private String message;
    private int rowsSynced;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getSchedulerId() {
        return schedulerId;
    }

    public void setSchedulerId(int schedulerId) {
        this.schedulerId = schedulerId;
    }

    public String getSchedulerName() {
        return schedulerName;
    }

    public void setSchedulerName(String schedulerName) {
        this.schedulerName = schedulerName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getRowsSynced() {
        return rowsSynced;
    }

    public void setRowsSynced(int rowsSynced) {
        this.rowsSynced = rowsSynced;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }
}