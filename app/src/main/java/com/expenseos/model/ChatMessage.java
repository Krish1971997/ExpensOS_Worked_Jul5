package com.expenseos.model;

public class ChatMessage {
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    private int id;
    private String role;
    private String content;
    private String attachmentPath;
    private String attachmentName;
    private String chartPath;
    private String provider;
    private String createdAt;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getAttachmentPath() {
        return attachmentPath;
    }

    public void setAttachmentPath(String v) {
        this.attachmentPath = v;
    }

    public String getAttachmentName() {
        return attachmentName;
    }

    public void setAttachmentName(String v) {
        this.attachmentName = v;
    }

    public String getChartPath() {
        return chartPath;
    }

    public void setChartPath(String v) {
        this.chartPath = v;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String v) {
        this.provider = v;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String v) {
        this.createdAt = v;
    }

    public boolean isUser() {
        return ROLE_USER.equals(role);
    }
}