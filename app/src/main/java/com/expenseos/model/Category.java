package com.expenseos.model;

public class Category {
    private int id;
    private String name;
    private String type;    // INCOME | EXPENSE
    private Integer bookId;  // null = common, non-null = book-specific

    public Category() {
    }

    public Category(int id, String name, String type, Integer bookId) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.bookId = bookId;
    }

    public Category(int id, String name, String type) {
        this.id = id;
        this.name = name;
        this.type = type;
    }

    public boolean isCommon() {
        return bookId == null;
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

    public Integer getBookId() {
        return bookId;
    }

    public void setBookId(Integer id) {
        this.bookId = id;
    }

    // Spinner display — show "(Custom)" tag for book-specific categories
    @Override
    public String toString() {
        return bookId != null ? name + " ✦" : name;
    }
}
