package com.expenseos.model;

import java.util.List;

public class Task {
    private long id;
    private String name;
    private String taskDateTime; // "yyyy-MM-dd HH:mm"
    private String description;
    private String color;
    private String googleEventId;
    private List<Long> eventIds; // linked events (transient, set/read by DAO)

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String n) {
        this.name = n;
    }

    public String getTaskDateTime() {
        return taskDateTime;
    }

    public void setTaskDateTime(String d) {
        this.taskDateTime = d;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String d) {
        this.description = d;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String c) {
        this.color = c;
    }

    public String getGoogleEventId() {
        return googleEventId;
    }

    public void setGoogleEventId(String g) {
        this.googleEventId = g;
    }

    public List<Long> getEventIds() {
        return eventIds;
    }

    public void setEventIds(List<Long> e) {
        this.eventIds = e;
    }
}