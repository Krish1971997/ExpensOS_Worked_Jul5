package com.expenseos.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class Budget {
	private int id;
	private int bookId;
	private int year;
	private int month;
	private BigDecimal overallLimit;
	private LocalDateTime createdAt;
	private LocalDateTime updatedAt;

	// Loaded separately
	private List<BudgetCategory> categories = new ArrayList<>();

	// Computed fields (filled by DAO)
	private BigDecimal totalSpent; // actual expense this month
	private BigDecimal remainingAmount; // overallLimit - totalSpent

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public int getBookId() {
		return bookId;
	}

	public void setBookId(int bookId) {
		this.bookId = bookId;
	}

	public int getYear() {
		return year;
	}

	public void setYear(int year) {
		this.year = year;
	}

	public int getMonth() {
		return month;
	}

	public void setMonth(int month) {
		this.month = month;
	}

	public BigDecimal getOverallLimit() {
		return overallLimit;
	}

	public void setOverallLimit(BigDecimal overallLimit) {
		this.overallLimit = overallLimit;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(LocalDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}

	public List<BudgetCategory> getCategories() {
		return categories;
	}

	public void setCategories(List<BudgetCategory> categories) {
		this.categories = categories;
	}

	public BigDecimal getTotalSpent() {
		return totalSpent;
	}

	public void setTotalSpent(BigDecimal totalSpent) {
		this.totalSpent = totalSpent;
	}

	public BigDecimal getRemainingAmount() {
		return remainingAmount;
	}

	public void setRemainingAmount(BigDecimal remainingAmount) {
		this.remainingAmount = remainingAmount;
	}

	/** 0-100 usage percentage */
	public int getUsedPct() {
		if (overallLimit == null || overallLimit.compareTo(BigDecimal.ZERO) == 0)
			return 0;
		if (totalSpent == null)
			return 0;
		return totalSpent.multiply(BigDecimal.valueOf(100)).divide(overallLimit, 0, java.math.RoundingMode.HALF_UP)
				.min(BigDecimal.valueOf(100)).intValue();
	}

	public boolean isRemainingPositive() {
		if (remainingAmount == null)
			return true;
		return remainingAmount.compareTo(BigDecimal.ZERO) >= 0;
	}

	public String getMonthName() {
		return java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH);
	}
}
