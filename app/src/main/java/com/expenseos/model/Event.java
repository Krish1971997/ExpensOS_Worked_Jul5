package com.expenseos.model;

public class Event {
    private long id;
    private String name;
    private String offsetDirection; // BEFORE / AFTER
    private int offsetDays;
    private String header;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getOffsetDirection() {
        return offsetDirection;
    }

    public void setOffsetDirection(String d) {
        this.offsetDirection = d;
    }

    public int getOffsetDays() {
        return offsetDays;
    }

    public void setOffsetDays(int d) {
        this.offsetDays = d;
    }

    public String getHeader() {
        return header;
    }

    public void setHeader(String h) {
        this.header = h;
    }

    public String getSummary() {
        return offsetDirection + " " + offsetDays + " day(s)" + (header != null && !header.isEmpty() ? " — " + header : "");
    }
}