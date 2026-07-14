package com.expenseos.model;

import java.math.BigDecimal;

/**
 * Android model for a cash book.
 * <p>
 * Note: createdAt is a String (SQLite stores it as TEXT, "yyyy-MM-dd HH:mm:ss"),
 * unlike the web version's LocalDateTime — this matches how BooksFragment
 * already reads/writes it via raw SQLite queries.
 */
public class CashBook {

    private int id;
    private String name;
    private String description;
    private String createdAt;
    private boolean active = true;

    // Populated separately (per-book summary query), not always present
    private BigDecimal totalIncome;
    private BigDecimal totalExpense;

    public CashBook() {
    }

    public CashBook(int id, String name, String description, String createdAt, boolean active) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.createdAt = createdAt;
        this.active = active;
    }

    /**
     * "2026-07-11" out of "2026-07-11 14:32:00" — mirrors the web view's book.formattedDate
     */
    public String getFormattedDate() {
        if (createdAt == null || createdAt.isEmpty())
            return "";
        return createdAt.substring(0, Math.min(10, createdAt.length()));
    }

    public BigDecimal getNetBalance() {
        BigDecimal inc = totalIncome != null ? totalIncome : BigDecimal.ZERO;
        BigDecimal exp = totalExpense != null ? totalExpense : BigDecimal.ZERO;
        return inc.subtract(exp);
    }

    // Getters / Setters
    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public BigDecimal getTotalIncome() {
        return totalIncome;
    }

    public void setTotalIncome(BigDecimal totalIncome) {
        this.totalIncome = totalIncome;
    }

    public BigDecimal getTotalExpense() {
        return totalExpense;
    }

    public void setTotalExpense(BigDecimal totalExpense) {
        this.totalExpense = totalExpense;
    }
}
