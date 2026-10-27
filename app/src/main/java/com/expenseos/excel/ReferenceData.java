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
    /**
     * category name (case-insensitive) -> its subcategories; only categories that HAVE subcategories.
     */
    public final java.util.Map<String, List<String>> subsByCategory = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    /**
     * Zoho dropdown labels: "Category › Subcategory", sorted by category.
     */
    public final List<String> subLabels = new ArrayList<>();
    public static final String SUB_SEP = " \u203A ";
}