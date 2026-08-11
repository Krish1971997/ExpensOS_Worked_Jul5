package com.expenseos.util;

import java.util.LinkedHashMap;
import java.util.Map;

public class TaskColors {
    // name -> hex, Google-Calendar-style palette
    public static final Map<String, String> PALETTE = new LinkedHashMap<>();

    static {
        PALETTE.put("Tomato", "#D50000");
        PALETTE.put("Flamingo", "#E67C73");
        PALETTE.put("Tangerine", "#F4511E");
        PALETTE.put("Banana", "#F6BF26");
        PALETTE.put("Sage", "#33B679");
        PALETTE.put("Basil", "#0B8043");
        PALETTE.put("Peacock", "#039BE5");
        PALETTE.put("Blueberry", "#3F51B5");
        PALETTE.put("Lavender", "#7986CB");
        PALETTE.put("Grape", "#8E24AA");
        PALETTE.put("Graphite", "#616161");
        PALETTE.put("None", "");
    }

    public static String[] names() {
        return PALETTE.keySet().toArray(new String[0]);
    }

    public static String hexFor(String name) {
        return PALETTE.getOrDefault(name, "");
    }

    public static String nameFor(String hex) {
        for (Map.Entry<String, String> e : PALETTE.entrySet())
            if (e.getValue().equalsIgnoreCase(hex)) return e.getKey();
        return "None";
    }
}