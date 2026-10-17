package com.expenseos.excel;

import java.util.ArrayList;
import java.util.List;

public class ReferenceData {
    public final List<String> cashbooks = new ArrayList<>();
    public final List<String> paymentTypes = new ArrayList<>();
    public final List<String> categories = new ArrayList<>();
    public final List<String> subCategories = new ArrayList<>();
    /**
     * {category, type, scope, "sub1, sub2"}
     */
    public final List<String[]> categoryInfo = new ArrayList<>();
}