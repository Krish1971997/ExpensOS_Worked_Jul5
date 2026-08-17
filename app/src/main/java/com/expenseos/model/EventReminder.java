package com.expenseos.model;

public class EventReminder {
    private long id;
    private long eventId;
    private long reminderId;
    private String reminderName; // joined, for display
    private String type;         // NOTIFICATION / ALARM

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getEventId() {
        return eventId;
    }

    public void setEventId(long e) {
        this.eventId = e;
    }

    public long getReminderId() {
        return reminderId;
    }

    public void setReminderId(long r) {
        this.reminderId = r;
    }

    public String getReminderName() {
        return reminderName;
    }

    public void setReminderName(String n) {
        this.reminderName = n;
    }

    public String getType() {
        return type;
    }

    public void setType(String t) {
        this.type = t;
    }
}