package com.expenseos.model;

public class Category {
    private int id;
    private String name;
    private String type;   // INCOME | EXPENSE

    public Category() {
    }

    public Category(int id, String name, String type) {
        this.id = id;
        this.name = name;
        this.type = type;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String n) {
        this.name = n;
    }

    public String getType() {
        return type;
    }

    public void setType(String t) {
        this.type = t;
    }

    @Override
    public String toString() {
        return name;
    }
}
