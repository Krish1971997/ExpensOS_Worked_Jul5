package com.expenseos.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.expenseos.R;
import com.expenseos.model.Transaction;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * "Share Bill" bottom sheet: bill preview card + WhatsApp share (as a PNG).
 */
public final class ShareBillSheet {

    private ShareBillSheet() {
    }

    public static void show(Activity a, Transaction t, String bookName) {
        final float dp = a.getResources().getDisplayMetrics().density;
        boolean income = t.getType() == Transaction.Type.INCOME;
        int amountColor = a.getColor(income ? R.color.green : R.color.red);

        LocalDateTime dt = t.getDateTime();
        String billDate = dt == null ? "" : ordinal(dt.getDayOfMonth()) + " "
                                            + dt.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH));

        String note = t.getNote() != null ? t.getNote().trim() : "";
        String cat = t.getCategoryName() != null ? t.getCategoryName() : "";
        String sub = t.getSubCategoryName() != null ? t.getSubCategoryName() : "";
        String pay = t.getPaymentType() != null ? t.getPaymentType() : "";
        // bill-la description / cashbook illa: category (▸ sub) headline, keezha payment type
        String title = !cat.isEmpty() ? (sub.isEmpty() ? cat : cat + " ▸ " + sub) : "Entry";

        StringBuilder meta = new StringBuilder(pay);

        BottomSheetDialog sheet = new BottomSheetDialog(a);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // header: ✕  Share Bill
        LinearLayout header = new LinearLayout(a);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding((int) (16 * dp), (int) (14 * dp), (int) (16 * dp), (int) (14 * dp));
        TextView close = text(a, "✕", 22, 0xFF212121, false);
        close.setOnClickListener(v -> sheet.dismiss());
        header.addView(close);
        TextView hTitle = text(a, "Share Bill", 18, 0xFF212121, true);
        hTitle.setPadding((int) (20 * dp), 0, 0, 0);
        header.addView(hTitle);
        root.addView(header);

        // capture area (coloured frame + bill card) — this is what gets shared
        FrameLayout frame = new FrameLayout(a);
        frame.setBackgroundColor(a.getColor(R.color.primary));
        int fp = (int) (16 * dp);
        frame.setPadding(fp, fp, fp, fp);

        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(fp, fp, fp, fp);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFFF5F5F5);
        cardBg.setCornerRadius(10 * dp);
        card.setBackground(cardBg);

        LinearLayout r1 = new LinearLayout(a);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r1.addView(text(a, "Bill", 20, 0xFF212121, true), weight());
        r1.addView(text(a, "Bill Date: " + billDate, 13, 0xFF616161, false));
        card.addView(r1);

        LinearLayout r2 = new LinearLayout(a);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        r2.setPadding(0, (int) (18 * dp), 0, (int) (8 * dp));
        r2.addView(text(a, "Details", 13, 0xFF757575, false), weight());
        r2.addView(text(a, "Amount", 13, 0xFF757575, false));
        card.addView(r2);

        View line = new View(a);
        line.setBackgroundColor(0x22000000);
        card.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) dp));

        LinearLayout r3 = new LinearLayout(a);
        r3.setOrientation(LinearLayout.HORIZONTAL);
        r3.setPadding(0, (int) (14 * dp), 0, (int) (14 * dp));
        LinearLayout left = new LinearLayout(a);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(text(a, title, 16, 0xFF212121, false));
        if (meta.length() > 0) left.addView(text(a, meta.toString(), 12, 0xFF757575, false));
        r3.addView(left, weight());
        r3.addView(text(a, t.getFormattedAmount(), 20, amountColor, true));
        card.addView(r3);

        // footer
        LinearLayout footer = new LinearLayout(a);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        footer.setPadding(0, (int) (18 * dp), 0, 0);
        TextView createdBy = text(a, "Created by", 12, 0xFF757575, false);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cbLp.gravity = Gravity.CENTER_HORIZONTAL;
        footer.addView(createdBy, cbLp);
        LinearLayout brand = new LinearLayout(a);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setPadding(0, (int) (4 * dp), 0, 0);
        ImageView logo = new ImageView(a);
        logo.setImageResource(R.mipmap.ic_launcher);
        brand.addView(logo, new LinearLayout.LayoutParams((int) (24 * dp), (int) (24 * dp)));
        TextView appName = text(a, "ExpenseOS", 14, a.getColor(R.color.primary), true);
        appName.setPadding((int) (6 * dp), 0, 0, 0);
        brand.addView(appName);
        LinearLayout.LayoutParams brandLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brandLp.gravity = Gravity.CENTER_HORIZONTAL;
        footer.addView(brand, brandLp);
        card.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        frame.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(frame);

        // WhatsApp share button
        FrameLayout btnBox = new FrameLayout(a);
        btnBox.setPadding(fp, fp, fp, fp);
        MaterialButton btn = new MaterialButton(a);
        btn.setText("Share on WhatsApp");
        btn.setAllCaps(false);
        btn.setTextSize(16);
        btn.setTextColor(Color.WHITE);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setCornerRadius((int) (14 * dp));
        btn.setBackgroundTintList(ColorStateList.valueOf(0xFF16A34A));
        btn.setOnClickListener(v -> share(a, frame, t, title));
        btnBox.addView(btn, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (52 * dp)));
        root.addView(btnBox);

        sheet.setContentView(root);
        sheet.setOnShowListener(d -> sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED));
        sheet.show();
    }

    private static void share(Activity a, View capture, Transaction t, String title) {
        try {
            Bitmap bmp = Bitmap.createBitmap(capture.getWidth(), capture.getHeight(), Bitmap.Config.ARGB_8888);
            capture.draw(new Canvas(bmp));

            File dir = new File(a.getCacheDir(), "receipts"); // same folder the FileProvider already exposes
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "bill_" + t.getId() + ".png");
            try (FileOutputStream out = new FileOutputStream(f)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            Uri uri = FileProvider.getUriForFile(a, a.getPackageName() + ".fileprovider", f);

            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("image/png");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_TEXT, title + " — " + t.getFormattedAmount());
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                send.setPackage("com.whatsapp");
                a.startActivity(send);
            } catch (ActivityNotFoundException e) {
                send.setPackage(null); // no WhatsApp → normal share sheet
                a.startActivity(Intent.createChooser(send, "Share bill"));
            }
        } catch (Exception e) {
            Toast.makeText(a, "Share failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static TextView text(Activity a, String s, float sp, int color, boolean bold) {
        TextView tv = new TextView(a);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private static String ordinal(int d) {
        if (d >= 11 && d <= 13) return d + "th";
        switch (d % 10) {
            case 1:
                return d + "st";
            case 2:
                return d + "nd";
            case 3:
                return d + "rd";
            default:
                return d + "th";
        }
    }
}