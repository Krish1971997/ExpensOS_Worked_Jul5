package com.expenseos.model;

/**
 * One row in the global search result list.
 * <p>
 * A single adapter renders both the group headers ("Screens", "Masters",
 * "Data · Transactions") and the result rows themselves, so the search
 * screen can present one flat, grouped, scrollable list.
 */
public class SearchResult {

    public static final int TYPE_HEADER = 0;
    public static final int TYPE_ITEM = 1;

    public final int viewType;
    public final String group;     // header text, e.g. "Screens"
    public final String title;
    public final String subtitle;
    public final String badge;     // short left-hand tag, e.g. "SCREEN"
    public final String action;    // registry key used for navigation
    public final int refId;        // transaction id / book id / -1

    private SearchResult(int viewType, String group, String title, String subtitle,
                         String badge, String action, int refId) {
        this.viewType = viewType;
        this.group = group;
        this.title = title;
        this.subtitle = subtitle;
        this.badge = badge;
        this.action = action;
        this.refId = refId;
    }

    public static SearchResult header(String group) {
        return new SearchResult(TYPE_HEADER, group, group, null, null, null, -1);
    }

    public static SearchResult item(String group, String title, String subtitle,
                                    String badge, String action, int refId) {
        return new SearchResult(TYPE_ITEM, group, title, subtitle, badge, action, refId);
    }
}
