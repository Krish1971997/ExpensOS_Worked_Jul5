package com.expenseos.model;

public class Reminder {
    private long id;
    private String name;

    public Reminder() {
    }

    public Reminder(long id, String name) {
        this.id = id;
        this.name = name;
    }

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

    @Override
    public String toString() {
        return name;
    } // for Spinner display
}