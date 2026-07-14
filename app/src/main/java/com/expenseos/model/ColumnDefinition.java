package com.expenseos.model;

import java.time.LocalDateTime;

public class ColumnDefinition {
    private int id;
    private String colName; // display name
    private String colKey; // snake_case key
    private String type; // INCOME | EXPENSE
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public ColumnDefinition() {
    }

    public ColumnDefinition(int id, String colName, String colKey, String type) {
        this.id = id;
        this.colName = colName;
        this.colKey = colKey;
        this.type = type;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getColName() {
        return colName;
    }

    public void setColName(String colName) {
        this.colName = colName;
    }

    public String getColKey() {
        return colKey;
    }

    public void setColKey(String colKey) {
        this.colKey = colKey;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
