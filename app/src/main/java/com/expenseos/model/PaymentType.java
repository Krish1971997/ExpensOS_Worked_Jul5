package com.expenseos.model;

public class PaymentType {
    private int id;
    private String name;
    private boolean isDefault;   // ← new field


    public PaymentType() {
    }

    public PaymentType(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public PaymentType(int id, String name, boolean isDefault) {   // ← new constructor
        this.id = id;
        this.name = name;
        this.isDefault = isDefault;
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

    public void setName(String name) {
        this.name = name;
    }

    public boolean isDefault() {          // ← new getter
        return isDefault;
    }

    public void setDefault(boolean isDefault) {   // ← new setter
        this.isDefault = isDefault;
    }

    @Override
    public String toString() {
        return name;
    } // so it displays correctly in a plain ArrayAdapter<PaymentType>
}