package com.expenseos.model;

import java.math.BigDecimal;

public class Transaction {

    public enum Type {INCOME, EXPENSE}

    private int id;
    private Type type;
    private String txnDatetime;   // stored as ISO string locally
    private BigDecimal amount;
    private int categoryId;
    private String categoryName;
    private int subCategoryId;
    private String subCategoryName;
    private String note;
    private int bookId;
    private boolean synced;        // local-only flag

    // ── Getters / Setters ────────────────────────────────────
    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public String getTxnDatetime() {
        return txnDatetime;
    }

    public void setTxnDatetime(String d) {
        this.txnDatetime = d;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal a) {
        this.amount = a;
    }

    public int getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(int c) {
        this.categoryId = c;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String s) {
        this.categoryName = s;
    }

    public int getSubCategoryId() {
        return subCategoryId;
    }

    public void setSubCategoryId(int s) {
        this.subCategoryId = s;
    }

    public String getSubCategoryName() {
        return subCategoryName;
    }

    public void setSubCategoryName(String s) {
        this.subCategoryName = s;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String n) {
        this.note = n;
    }

    public int getBookId() {
        return bookId;
    }

    public void setBookId(int b) {
        this.bookId = b;
    }

    public boolean isSynced() {
        return synced;
    }

    public void setSynced(boolean s) {
        this.synced = s;
    }

    public String getAmountFormatted() {
        if (amount == null) return "₹0.00";
        String sign = (type == Type.INCOME) ? "+" : "-";
        return sign + "₹" + amount.toPlainString();
    }
}
