package com.expenseos.model;

import java.math.BigDecimal;

public class CashBook {
    private int id;
    private String name;
    private String description;
    private String createdAt;
    private BigDecimal totalIncome = BigDecimal.ZERO;
    private BigDecimal totalExpense = BigDecimal.ZERO;

    private boolean isActive;

    public CashBook(){

    }
    public CashBook(int id1, String name1, String description1) {
        this.id=id1;
        this.name=name1;
        this.description=description1;

    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String n) {
        this.name = n;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String d) {
        this.description = d;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String c) {
        this.createdAt = c;
    }

    public BigDecimal getTotalIncome() {
        return totalIncome;
    }

    public void setTotalIncome(BigDecimal b) {
        this.totalIncome = b;
    }

    public BigDecimal getTotalExpense() {
        return totalExpense;
    }

    public void setTotalExpense(BigDecimal b) {
        this.totalExpense = b;
    }

    @Override
    public String toString() {
        return name;
    }

    public void setActive(boolean i){
       this.isActive=i;
    }

    public boolean getActive(){
        return isActive;
    }
}
