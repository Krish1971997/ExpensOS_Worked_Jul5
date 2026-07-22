package com.expenseos.model;

import java.time.LocalDateTime;

public class BackupMetadata {

    public enum BackupType {
        MANUAL, SCHEDULED, AUTO_BEFORE_RESTORE
    }

    public enum BackupStatus {
        PENDING, SUCCESS, FAILED, RESTORING, RESTORED
    }

    public enum BackupMode {
        OFFLINE, ONLINE
    }

    private int id;
    private String fileName;
    private String filePath;
    private long fileSizeBytes;
    private BackupType backupType;
    private BackupStatus status;
    private String description;
    private String errorMessage;
    private int incomeCount;
    private int expenseCount;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private BackupMode mode;
    private String external_ID;
    private LocalDateTime updatedAt;

    public BackupMetadata() {
    }

    public String getFileSizeFormatted() {
        if (fileSizeBytes < 1024)
            return fileSizeBytes + " B";
        if (fileSizeBytes < 1024 * 1024)
            return String.format("%.1f KB", fileSizeBytes / 1024.0);
        return String.format("%.2f MB", fileSizeBytes / (1024.0 * 1024));
    }

    public boolean isRestorable() {
        return status == BackupStatus.SUCCESS || status == BackupStatus.RESTORED;
    }

    public int getId() {
        return id;
    }

    public void setId(int v) {
        id = v;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String v) {
        fileName = v;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String v) {
        filePath = v;
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public void setFileSizeBytes(long v) {
        fileSizeBytes = v;
    }

    public BackupType getBackupType() {
        return backupType;
    }

    public void setBackupType(BackupType v) {
        backupType = v;
    }

    public BackupMode getMode() {
        return mode;
    }

    public void setMode(BackupMode v) {
        mode = v;
    }

    public BackupStatus getStatus() {
        return status;
    }

    public void setStatus(BackupStatus v) {
        status = v;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String v) {
        description = v;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String v) {
        errorMessage = v;
    }

    public int getIncomeCount() {
        return incomeCount;
    }

    public void setIncomeCount(int v) {
        incomeCount = v;
    }

    public int getExpenseCount() {
        return expenseCount;
    }

    public void setExpenseCount(int v) {
        expenseCount = v;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime v) {
        createdAt = v;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime v) {
        completedAt = v;
    }

    public String getExternal_ID() {
        return external_ID;
    }

    public void setExternal_ID(String external_ID) {
        this.external_ID = external_ID;
    }
}
