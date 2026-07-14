package com.expenseos.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class TransactionFilter {

    private String type; // INCOME | EXPENSE | null = all
    private Integer bookId;
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private List<Integer> categoryIds; // multi-select
    private List<Integer> subCategoryIds;
    private String amountOp1; // =, >, >=, <, <=
    private BigDecimal amount1;
    private String amountOp2;
    private BigDecimal amount2;
    private String noteSearch;
    private int page = 1;
    private int pageSize = 15;
    private String sortBy = "date"; // date | type | category | subcategory | amount | note
    private String sortDir = "desc"; // asc | desc

    public boolean isFiltered() {
        return dateFrom != null || dateTo != null || (categoryIds != null && !categoryIds.isEmpty())
                || (subCategoryIds != null && !subCategoryIds.isEmpty()) || amount1 != null
                || (noteSearch != null && !noteSearch.isBlank());
    }

    public static String safeOp(String op) {
        return switch (op != null ? op.trim() : "") {
            case ">=" -> ">=";
            case "<=" -> "<=";
            case ">" -> ">";
            case "<" -> "<";
            default -> "=";
        };
    }

    // Getters / Setters
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Integer getBookId() {
        return bookId;
    }

    public void setBookId(Integer bookId) {
        this.bookId = bookId;
    }

    public LocalDate getDateFrom() {
        return dateFrom;
    }

    public void setDateFrom(LocalDate dateFrom) {
        this.dateFrom = dateFrom;
    }

    public LocalDate getDateTo() {
        return dateTo;
    }

    public void setDateTo(LocalDate dateTo) {
        this.dateTo = dateTo;
    }

    public List<Integer> getCategoryIds() {
        return categoryIds;
    }

    public void setCategoryIds(List<Integer> categoryIds) {
        this.categoryIds = categoryIds;
    }

    public List<Integer> getSubCategoryIds() {
        return subCategoryIds;
    }

    public void setSubCategoryIds(List<Integer> subCategoryIds) {
        this.subCategoryIds = subCategoryIds;
    }

    public String getAmountOp1() {
        return amountOp1;
    }

    public void setAmountOp1(String amountOp1) {
        this.amountOp1 = amountOp1;
    }

    public BigDecimal getAmount1() {
        return amount1;
    }

    public void setAmount1(BigDecimal amount1) {
        this.amount1 = amount1;
    }

    public String getAmountOp2() {
        return amountOp2;
    }

    public void setAmountOp2(String amountOp2) {
        this.amountOp2 = amountOp2;
    }

    public BigDecimal getAmount2() {
        return amount2;
    }

    public void setAmount2(BigDecimal amount2) {
        this.amount2 = amount2;
    }

    public String getNoteSearch() {
        return noteSearch;
    }

    public void setNoteSearch(String noteSearch) {
        this.noteSearch = noteSearch;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public String getSortBy() {
        return sortBy;
    }

    public void setSortBy(String sortBy) {
        this.sortBy = sortBy;
    }

    public String getSortDir() {
        return sortDir;
    }

    public void setSortDir(String sortDir) {
        this.sortDir = sortDir;
    }
}

