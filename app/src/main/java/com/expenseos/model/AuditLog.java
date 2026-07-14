package com.expenseos.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class AuditLog {

    private int id;
    private int transactionId;
    private String action; // CREATE | UPDATE | DELETE
    private String changedBy;
    private LocalDateTime changedAt;
    private String fieldName;
    private String oldValue;
    private String newValue;
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Extra context from JOIN (used in global audit view)
    private BigDecimal txnAmount;
    private String txnCategoryName;
    private String txnDate;

    public AuditLog() {
    }

    public String getFormattedChangedAt() {
        if (changedAt == null)
            return "";
        return changedAt.format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss"));
    }

    public String getFieldDisplay() {
        if (fieldName == null)
            return "";
        return switch (fieldName) {
            case "amount" -> "Amount";
            case "note" -> "Note";
            case "category" -> "Category";
            case "subcategory" -> "Sub Category";
            case "datetime" -> "Date & Time";
            case "type" -> "Type";
            case "book" -> "Book";
            default -> fieldName;
        };
    }

    // Getters / Setters
    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(int transactionId) {
        this.transactionId = transactionId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public void setChangedBy(String changedBy) {
        this.changedBy = changedBy;
    }

    public LocalDateTime getChangedAt() {
        return changedAt;
    }

    public void setChangedAt(LocalDateTime changedAt) {
        this.changedAt = changedAt;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getOldValue() {
        return oldValue;
    }

    public void setOldValue(String oldValue) {
        this.oldValue = oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public void setNewValue(String newValue) {
        this.newValue = newValue;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public BigDecimal getTxnAmount() {
        return txnAmount;
    }

    public void setTxnAmount(BigDecimal txnAmount) {
        this.txnAmount = txnAmount;
    }

    public String getTxnCategoryName() {
        return txnCategoryName;
    }

    public void setTxnCategoryName(String txnCategoryName) {
        this.txnCategoryName = txnCategoryName;
    }

    public String getTxnDate() {
        return txnDate;
    }

    public void setTxnDate(String txnDate) {
        this.txnDate = txnDate;
    }
}
