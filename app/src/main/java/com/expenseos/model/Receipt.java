package com.expenseos.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

public class Receipt {
    private int id;
    private int transactionId;
    private String fileName;
    private String fileType;
    private byte[] fileData;
    private int fileSize;
    private LocalDateTime uploadedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Receipt() {
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

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public byte[] getFileData() {
        return fileData;
    }

    public void setFileData(byte[] fileData) {
        this.fileData = fileData;
    }

    public int getFileSize() {
        return fileSize;
    }

    public void setFileSize(int fileSize) {
        this.fileSize = fileSize;
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(LocalDateTime uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    /** Base64 data URI for inline display in JSP */
    public String getBase64DataUri() {
        if (fileData == null)
            return "";
        return "data:" + fileType + ";base64," + Base64.getEncoder().encodeToString(fileData);
    }

    public String getFileSizeDisplay() {
        if (fileSize < 1024)
            return fileSize + " B";
        if (fileSize < 1048576)
            return String.format("%.1f KB", fileSize / 1024.0);
        return String.format("%.1f MB", fileSize / 1048576.0);
    }

    public boolean isImage() {
        return fileType != null && fileType.startsWith("image/");
    }

    public String getFormattedUploadedAt() {
        if (uploadedAt == null)
            return "";
        return uploadedAt.format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm"));
    }
}
