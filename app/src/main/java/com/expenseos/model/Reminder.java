package com.expenseos.model;

public class Reminder {
    private long id;
    private String name;
    private int offsetValue;      // e.g. 1
    private String offsetUnit;    // DAY / WEEK
    private int timeHour;
    private int timeMinute;

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

    public int getOffsetValue() {
        return offsetValue;
    }

    public void setOffsetValue(int v) {
        this.offsetValue = v;
    }

    public String getOffsetUnit() {
        return offsetUnit;
    }

    public void setOffsetUnit(String u) {
        this.offsetUnit = u;
    }

    public int getTimeHour() {
        return timeHour;
    }

    public void setTimeHour(int h) {
        this.timeHour = h;
    }

    public int getTimeMinute() {
        return timeMinute;
    }

    public void setTimeMinute(int m) {
        this.timeMinute = m;
    }

    /**
     * Total offset expressed in days — WEEK converts ×7.
     */
    public int offsetInDays() {
        return "WEEK".equals(offsetUnit) ? offsetValue * 7 : offsetValue;
    }

    public String getSummary() {
        String unit = offsetValue == 1 ? offsetUnit.toLowerCase() : offsetUnit.toLowerCase() + "s";
        String when = offsetValue == 0 ? "On the day" : offsetValue + " " + unit + " before";
        return when + " at " + String.format("%02d:%02d", timeHour, timeMinute);
    }

    @Override
    public String toString() {
        return name;
    } // for Spinner display
}