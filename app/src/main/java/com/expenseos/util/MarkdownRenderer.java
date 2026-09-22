package com.expenseos.util;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.style.StyleSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.expenseos.R;

/**
 * Renders a small markdown subset the AI Assistant actually produces:
 *   ### / ## headings, **bold**, *italic*, `code`, - bullet lists, --- dividers
 * and inline newlines. Spits a stack of TextViews / divider Views into the
 * bubble column. Handles every render call safely (no exceptions escape —
 * falls back to a single plain TextView if the input is weird).
 */
public final class MarkdownRenderer {

    private MarkdownRenderer() {}

    public static void render(Context ctx, LinearLayout col, String text) {
        if (text == null || text.isEmpty()) return;
        try {
            String[] lines = text.replace("\r\n", "\n").split("\n");
            boolean inList = false;
            for (String raw : lines) {
                String line = raw == null ? "" : raw.trim();
                if (line.isEmpty()) {
                    addSpacer(col, 4);
                    continue;
                }
                if (line.startsWith("### ")) {
                    addHeading(ctx, col, line.substring(4), 18, true);
                } else if (line.startsWith("## ")) {
                    addHeading(ctx, col, line.substring(3), 16, true);
                } else if (line.startsWith("# ")) {
                    addHeading(ctx, col, line.substring(2), 20, true);
                } else if (line.equals("---")) {
                    addDivider(col);
                } else if (line.startsWith("- ") || line.startsWith("* ")) {
                    addBullet(ctx, col, line.substring(2));
                } else {
                    addBody(ctx, col, line);
                }
            }
        } catch (Exception e) {
            // Defensive fallback — never show empty bubble if parsing blows up
            TextView t = plainText(ctx, text);
            t.setTextSize(14);
            t.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));
            t.setBackgroundResource(R.drawable.bg_chat_bubble_bot);
            col.addView(t);
        }
    }

    private static void addHeading(Context ctx, LinearLayout col, String text, float sp, boolean bold) {
        TextView t = plainText(ctx, applyInlineSpans(text));
        t.setTextSize(sp);
        t.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        t.setTextColor(ContextCompat.getColor(ctx, R.color.primary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 6);
        lp.bottomMargin = dp(ctx, 2);
        t.setLayoutParams(lp);
        col.addView(t);
    }

    private static void addBody(Context ctx, LinearLayout col, String text) {
        TextView t = plainText(ctx, applyInlineSpans(text));
        t.setTextSize(14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 2);
        t.setLayoutParams(lp);
        col.addView(t);
    }

    private static void addBullet(Context ctx, LinearLayout col, String text) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView dot = new TextView(ctx);
        dot.setText("•  ");
        dot.setTextSize(15);
        dot.setTextColor(ContextCompat.getColor(ctx, R.color.primary));
        dot.setPadding(dp(ctx, 4), 0, 0, 0);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dotLp.topMargin = dp(ctx, 2);
        row.addView(dot, dotLp);

        TextView body = plainText(ctx, applyInlineSpans(text));
        body.setTextSize(14);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = dp(ctx, 2);
        row.addView(body, bodyLp);
        col.addView(row);
    }

    private static void addDivider(LinearLayout col) {
        View v = new View(col.getContext());
        v.setBackgroundColor(0xFFD1D5DB);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(col.getContext(), 1));
        lp.topMargin = dp(col.getContext(), 6);
        lp.bottomMargin = dp(col.getContext(), 6);
        v.setLayoutParams(lp);
        col.addView(v);
    }

    private static void addSpacer(LinearLayout col, int dpVal) {
        View v = new View(col.getContext());
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(col.getContext(), dpVal)));
        col.addView(v);
    }

    /**
     * Handles **bold** / *italic* / `code` spans inside a paragraph.
     * Non-greedy so **foo**bar** does not eat the trailing text.
     * Exposed as a public static helper so user bubbles rendered outside
     * MarkdownRenderer (e.g. ChatActivity.textBubble) can inline-format too.
     */
    public static CharSequence applyInlineSpansStatic(String text) {
        return applyInlineSpans(text);
    }

    private static CharSequence applyInlineSpans(String text) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|`([^`]+)`")
                .matcher(text);
        int last = 0;
        Context tmpCtx = null; // foreground color resolution deferred below
        while (m.find()) {
            sb.append(text, last, m.start());
            int start = sb.length();
            if (m.group(1) != null) {
                sb.append(m.group(1));
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(2) != null) {
                sb.append(m.group(2));
                sb.setSpan(new StyleSpan(Typeface.ITALIC), start, sb.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(3) != null) {
                sb.append(m.group(3));
                sb.setSpan(new android.text.style.BackgroundColorSpan(0xFFF3F4F6), start, sb.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            last = m.end();
        }
        if (last < text.length()) sb.append(text, last, text.length());
        return sb;
    }

    private static TextView plainText(Context ctx, CharSequence cs) {
        TextView t = new TextView(ctx);
        t.setText(cs);
        t.setTextIsSelectable(true);
        t.setTextColor(ContextCompat.getColor(ctx, R.color.text));
        return t;
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
