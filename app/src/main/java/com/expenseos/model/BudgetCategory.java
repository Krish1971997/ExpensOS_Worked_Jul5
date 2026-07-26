package com.expenseos.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class BudgetCategory {
	private int id;
	private int budgetId;
	private int categoryId;
	private String categoryName;
	private BigDecimal catLimit;
	private int alertPct; // alert threshold %
	private LocalDateTime createdAt;
	private LocalDateTime updatedAt;

	// Computed
	private BigDecimal spent; // actual spent this month in this category
	private BigDecimal remaining;

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public int getBudgetId() {
		return budgetId;
	}

	public void setBudgetId(int budgetId) {
		this.budgetId = budgetId;
	}

	public int getCategoryId() {
		return categoryId;
	}

	public void setCategoryId(int categoryId) {
		this.categoryId = categoryId;
	}

	public String getCategoryName() {
		return categoryName;
	}

	public void setCategoryName(String categoryName) {
		this.categoryName = categoryName;
	}

	public BigDecimal getCatLimit() {
		return catLimit;
	}

	public void setCatLimit(BigDecimal catLimit) {
		this.catLimit = catLimit;
	}

	public int getAlertPct() {
		return alertPct;
	}

	public void setAlertPct(int alertPct) {
		this.alertPct = alertPct;
	}

	public BigDecimal getSpent() {
		return spent;
	}

	public void setSpent(BigDecimal spent) {
		this.spent = spent;
	}

	public BigDecimal getRemaining() {
		return remaining;
	}

	public void setRemaining(BigDecimal remaining) {
		this.remaining = remaining;
	}

	public int getUsedPct() {
		if (catLimit == null || catLimit.compareTo(BigDecimal.ZERO) == 0)
			return 0;
		if (spent == null || spent.compareTo(BigDecimal.ZERO) == 0)
			return 0;
		return spent.multiply(BigDecimal.valueOf(100)).divide(catLimit, 0, java.math.RoundingMode.HALF_UP)
				.min(BigDecimal.valueOf(100)).intValue();
	}

	public boolean isAlertTriggered() {
		return getUsedPct() >= alertPct;
	}

	public boolean isExceeded() {
		return spent != null && catLimit != null && spent.compareTo(catLimit) > 0;
	}

	public BigDecimal getSpentSafe() {
		return spent != null ? spent : BigDecimal.ZERO;
	}

	public BigDecimal getRemainingSafe() {
		return remaining != null ? remaining : (catLimit != null ? catLimit : BigDecimal.ZERO);
	}
}
