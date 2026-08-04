package com.expenseos.model;

public class PaymentType {
    private int id;
    private String name;

    public PaymentType() {
    }

    public PaymentType(int id, String name) {
        this.id = id;
        this.name = name;
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

    @Override
    public String toString() {
        return name;
    } // so it displays correctly in a plain ArrayAdapter<PaymentType>
}