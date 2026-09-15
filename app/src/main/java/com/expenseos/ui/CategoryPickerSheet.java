package com.expenseos.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.expenseos.R;
import com.expenseos.dao.SubCategoryDao;
import com.expenseos.model.Category;
import com.expenseos.model.SubCategory;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.List;

/**
 * Two-step bottom-sheet picker: Category list -> (drill down if it has
 * sub-categories) -> Sub-Category list, back arrow between steps. Themed
 * explicitly (white surface, primary-colored header) instead of relying
 * on BottomSheetDialog's default Material theme, which otherwise renders
 * a dark header that doesn't match the rest of the app.
 */
public class CategoryPickerSheet {

    public interface OnPicked {
        void onPicked(Category category, SubCategory subCategory); // subCategory null = none / not applicable
    }

    public static void show(Activity activity, List<Category> categories, SubCategoryDao subCatDao,
                            int bookId, OnPicked callback) {
        BottomSheetDialog sheet = new BottomSheetDialog(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE); // overrides the theme's default dark sheet background

        root.addView(buildHeader(activity, "Select category", sheet, () -> {
            sheet.dismiss();
            Intent i = new Intent(activity, SettingsActivity.class);
            i.putExtra("bookScoped", true);
            i.putExtra("bookId", bookId);
            i.putExtra("startTab", 0); // Categories tab
            activity.startActivity(i);
        }));

        LinearLayout listContainer = new LinearLayout(activity);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setBackgroundColor(Color.WHITE);
        root.addView(listContainer);

        renderCategoryList(activity, listContainer, categories, subCatDao, sheet, callback);

        sheet.setContentView(root);
        sheet.show();
    }

    // Header: title on the left, ✎ edit (jump to Settings) + ✕ close on the right —
    // white background, primary-colored accents, matching the rest of the app.
    private static View buildHeader(Activity activity, String title, BottomSheetDialog sheet, Runnable onEdit) {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(Color.WHITE);
        header.setPadding(dp(activity, 20), dp(activity, 14), dp(activity, 8), dp(activity, 14));

        TextView tvTitle = new TextView(activity);
        tvTitle.setText(title);
        tvTitle.setTextSize(17);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(activity.getColor(R.color.text_primary));
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(tvTitle);

        TextView btnEdit = new TextView(activity);
        btnEdit.setText("✎");
        btnEdit.setTextSize(18);
        btnEdit.setTextColor(activity.getColor(R.color.primary));
        btnEdit.setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 10), dp(activity, 6));
        btnEdit.setBackgroundResource(selectableBg(activity));
        btnEdit.setOnClickListener(v -> onEdit.run());
        header.addView(btnEdit);

        TextView btnClose = new TextView(activity);
        btnClose.setText("✕");
        btnClose.setTextSize(18);
        btnClose.setTextColor(activity.getColor(R.color.text_muted));
        btnClose.setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 10), dp(activity, 6));
        btnClose.setBackgroundResource(selectableBg(activity));
        btnClose.setOnClickListener(v -> sheet.dismiss());
        header.addView(btnClose);

        View divider = new View(activity);
        divider.setBackgroundColor(0xFFE5E7EB);

        LinearLayout wrap = new LinearLayout(activity);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(header);
        View bottomLine = new View(activity);
        bottomLine.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 1)));
        bottomLine.setBackgroundColor(0xFFE5E7EB);
        wrap.addView(bottomLine);
        return wrap;
    }

    private static void renderCategoryList(Activity activity, LinearLayout container, List<Category> categories,
                                           SubCategoryDao subCatDao, BottomSheetDialog sheet, OnPicked callback) {
        container.removeAllViews();
        for (Category c : categories) {
            List<SubCategory> subs = subCatDao.findByCategoryId(c.getId());
            boolean hasSubs = !subs.isEmpty();
            container.addView(row(activity, c.getName(), hasSubs, () -> {
                if (hasSubs) {
                    renderSubCategoryList(activity, container, categories, c, subs, subCatDao, sheet, callback);
                } else {
                    callback.onPicked(c, null);
                    sheet.dismiss();
                }
            }));
        }
    }

    private static void renderSubCategoryList(Activity activity, LinearLayout container, List<Category> allCategories,
                                              Category category, List<SubCategory> subs, SubCategoryDao subCatDao,
                                              BottomSheetDialog sheet, OnPicked callback) {
        container.removeAllViews();

        LinearLayout backRow = new LinearLayout(activity);
        backRow.setOrientation(LinearLayout.HORIZONTAL);
        backRow.setGravity(Gravity.CENTER_VERTICAL);
        backRow.setPadding(dp(activity, 20), dp(activity, 14), dp(activity, 20), dp(activity, 14));
        backRow.setClickable(true);
        backRow.setFocusable(true);
        backRow.setBackgroundResource(selectableBg(activity));
        TextView back = new TextView(activity);
        back.setText("← " + category.getName());
        back.setTextSize(14);
        back.setTypeface(null, Typeface.BOLD);
        back.setTextColor(activity.getColor(R.color.primary));
        backRow.addView(back);
        backRow.setOnClickListener(v -> renderCategoryList(activity, container, allCategories, subCatDao, sheet, callback));
        container.addView(backRow);

        container.addView(row(activity, "No sub-category", false, () -> {
            callback.onPicked(category, null);
            sheet.dismiss();
        }));
        for (SubCategory sc : subs) {
            container.addView(row(activity, sc.getName(), false, () -> {
                callback.onPicked(category, sc);
                sheet.dismiss();
            }));
        }
    }

    private static View row(Activity activity, String label, boolean showChevron, Runnable onClick) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(Color.WHITE);
        row.setPadding(dp(activity, 20), dp(activity, 14), dp(activity, 20), dp(activity, 14));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackgroundResource(selectableBg(activity));

        TextView tvLabel = new TextView(activity);
        tvLabel.setText(label);
        tvLabel.setTextSize(15);
        tvLabel.setTextColor(activity.getColor(R.color.text_primary));
        tvLabel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvLabel);

        if (showChevron) {
            TextView chevron = new TextView(activity);
            chevron.setText("›");
            chevron.setTextSize(18);
            chevron.setTextColor(activity.getColor(R.color.text_muted));
            row.addView(chevron);
        }
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private static int selectableBg(Activity activity) {
        TypedValue outValue = new TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        return outValue.resourceId;
    }

    private static int dp(Activity activity, int v) {
        return (int) (v * activity.getResources().getDisplayMetrics().density);
    }
}