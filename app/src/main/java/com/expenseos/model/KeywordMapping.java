package com.expenseos.model;

public class KeywordMapping {
    private int id;
    private String keyword;
    private String type;           // INCOME | EXPENSE
    private int categoryId;
    private Integer subCategoryId; // null = no sub-category
    private Integer bookId;        // null = common, non-null = book-specific

    // Display-only, filled by KeywordMappingDao's join query — not persisted.
    private String categoryName;
    private String subCategoryName;

    public KeywordMapping() {
    }

    public KeywordMapping(int id, String keyword, String type, int categoryId,
                          Integer subCategoryId, Integer bookId) {
        this.id = id;
        this.keyword = keyword;
        this.type = type;
        this.categoryId = categoryId;
        this.subCategoryId = subCategoryId;
        this.bookId = bookId;
    }

    public boolean isCommon() {
        return bookId == null;
    }

    public int getId() {
        return id;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getType() {
        return type;
    }

    public int getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(int categoryId) {
        this.categoryId = categoryId;
    }

    public Integer getSubCategoryId() {
        return subCategoryId;
    }

    public void setSubCategoryId(Integer subCategoryId) {
        this.subCategoryId = subCategoryId;
    }

    public Integer getBookId() {
        return bookId;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName) {
        this.categoryName = categoryName;
    }

    public String getSubCategoryName() {
        return subCategoryName;
    }

    public void setSubCategoryName(String subCategoryName) {
        this.subCategoryName = subCategoryName;
    }
}