package com.expenseos.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.expenseos.dao.SubCategoryDao;
import com.expenseos.model.Category;
import com.expenseos.model.SubCategory;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.List;

/**
 * Two-pane category/sub-category picker — left column lists categories
 * (chevron = has sub-categories), right column shows the highlighted
 * category's sub-categories, updating live as you tap the left side.
 * Categories with no sub-categories select immediately on tap.
 * <p>
 * Replaces the old Spinner pair: a BottomSheetDialog isn't anchored near
 * the keyboard, so the list is never pushed off-screen or hidden behind it.
 */
public class CategoryPickerSheet {

    public interface OnPicked {
        void onPicked(Category category, SubCategory subCategory); // subCategory null = none
    }

    public static void show(Activity activity, List<Category> categories, SubCategoryDao subCatDao,
                            Integer preselectCategoryId, OnPicked callback) {
        BottomSheetDialog sheet = new BottomSheetDialog(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFFFFFFF);

        // Header
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(0xFF1F1F1F);
        header.setPadding(dp(activity, 20), dp(activity, 16), dp(activity, 16), dp(activity, 16));

        TextView title = new TextView(activity);
        title.setText("Category");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);

        TextView close = new TextView(activity);
        close.setText("✕");
        close.setTextColor(0xFFFFFFFF);
        close.setTextSize(20);
        close.setPadding(dp(activity, 16), 0, 0, 0);
        close.setOnClickListener(v -> sheet.dismiss());
        header.addView(close);
        root.addView(header);

        // Two-column body
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 460)));

        ScrollView leftScroll = new ScrollView(activity);
        LinearLayout leftList = new LinearLayout(activity);
        leftList.setOrientation(LinearLayout.VERTICAL);
        leftScroll.addView(leftList);
        leftScroll.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.42f));

        ScrollView rightScroll = new ScrollView(activity);
        LinearLayout rightList = new LinearLayout(activity);
        rightList.setOrientation(LinearLayout.VERTICAL);
        rightScroll.setBackgroundColor(0xFFFAFAFA);
        rightScroll.addView(rightList);
        rightScroll.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.58f));

        body.addView(leftScroll);
        body.addView(rightScroll);
        root.addView(body);
        sheet.setContentView(root);

        View[] highlighted = new View[1];

        for (Category c : categories) {
            List<SubCategory> subs = subCatDao.findByCategoryId(c.getId());
            boolean hasSubs = !subs.isEmpty();
            boolean preselected = preselectCategoryId != null && c.getId() == preselectCategoryId;

            LinearLayout row = leftRow(activity, c.getName(), hasSubs, preselected);
            if (preselected) highlighted[0] = row;
            row.setOnClickListener(v -> {
                if (highlighted[0] != null) highlighted[0].setBackgroundColor(0x00000000);
                row.setBackgroundColor(0xFFFCE4EC);
                highlighted[0] = row;

                if (hasSubs) {
                    populateRight(activity, rightList, c, subs, sheet, callback);
                } else {
                    rightList.removeAllViews();
                    callback.onPicked(c, null);
                    sheet.dismiss();
                }
            });
            leftList.addView(row);

            if (preselected && hasSubs)
                populateRight(activity, rightList, c, subs, sheet, callback);
        }

        sheet.show();
    }

    private static LinearLayout leftRow(Activity activity, String name, boolean hasSubs, boolean selected) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 8), dp(activity, 14));
        row.setClickable(true);
        row.setFocusable(true);
        if (selected) row.setBackgroundColor(0xFFFCE4EC);

        TextView label = new TextView(activity);
        label.setText(name);
        label.setTextSize(15);
        label.setTextColor(selected ? 0xFFD84315 : 0xFF212121);
        label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);

        if (hasSubs) {
            TextView chevron = new TextView(activity);
            chevron.setText("›");
            chevron.setTextSize(18);
            chevron.setTextColor(0xFF9E9E9E);
            row.addView(chevron);
        }
        return row;
    }

    private static void populateRight(Activity activity, LinearLayout rightList, Category category,
                                      List<SubCategory> subs, BottomSheetDialog sheet, OnPicked callback) {
        rightList.removeAllViews();
        for (SubCategory sc : subs) {
            TextView row = new TextView(activity);
            row.setText(sc.getName());
            row.setTextSize(15);
            row.setTextColor(0xFF212121);
            row.setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 14));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> {
                callback.onPicked(category, sc);
                sheet.dismiss();
            });
            rightList.addView(row);
        }
    }

    private static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density);
    }
}