package com.expenseos.model;

public class SubCategory {
    private int id;
    private String name;
    private int parentCategoryId;
    private String parentCategoryName;

    public SubCategory() {
    }

    public SubCategory(int id, String name, int parentCategoryId) {
        this.id = id;
        this.name = name;
        this.parentCategoryId = parentCategoryId;
    }

    public SubCategory(int id, String name, int parentCategoryId, String parentName) {
        this.id = id;
        this.name = name;
        this.parentCategoryId = parentCategoryId;
        this.parentCategoryName = parentName;
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

    public int getParentCategoryId() {
        return parentCategoryId;
    }

    public void setParentCategoryId(int p) {
        this.parentCategoryId = p;
    }

    public String getParentCategoryName() {
        return parentCategoryName;
    }

    public void setParentCategoryName(String s) {
        this.parentCategoryName = s;
    }

    @Override
    public String toString() {
        return name;
    }
}
