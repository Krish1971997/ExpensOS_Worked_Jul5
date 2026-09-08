package com.expenseos.adapter;

import android.content.Context;
import android.graphics.Color;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.List;
import java.util.Locale;

/**
 * Note-suggestion dropdown adapter — matched substring (case-insensitive,
 * ellame occurrence) highlight color-la kaatum, screenshot-oda style
 * matching pannradhukku. TransactionEntryActivity + TransactionDetailActivity
 * rendayum ivai use pannudhu.
 */
public class NoteSuggestionAdapter extends ArrayAdapter<String> {

    // App-oda accent/orange-red-ku match aagura color — venumna adjust pannunga.
    private static final int HIGHLIGHT_COLOR = Color.parseColor("#E8563A");

    private String query = "";

    public NoteSuggestionAdapter(Context ctx, List<String> items) {
        super(ctx, android.R.layout.simple_list_item_1, items);
    }

    /**
     * Ovvoru showNoteSuggestions() call-lyum, notifyDataSetChanged()-ku munnadi call pannunga.
     */
    public void setQuery(String query) {
        this.query = query != null ? query : "";
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        View view = convertView != null ? convertView
                : LayoutInflater.from(getContext()).inflate(android.R.layout.simple_list_item_1, parent, false);
        TextView tv = view.findViewById(android.R.id.text1);
        tv.setText(highlight(getItem(position), query));
        return view;
    }

    private CharSequence highlight(String text, String q) {
        if (text == null) return "";
        if (q == null || q.isEmpty()) return text;

        SpannableString spannable = new SpannableString(text);
        String lowerText = text.toLowerCase(Locale.ROOT);
        String lowerQuery = q.toLowerCase(Locale.ROOT);

        int start = 0;
        while (true) {
            int idx = lowerText.indexOf(lowerQuery, start);
            if (idx < 0) break;
            spannable.setSpan(new ForegroundColorSpan(HIGHLIGHT_COLOR), idx, idx + lowerQuery.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            start = idx + lowerQuery.length();
        }
        return spannable;
    }
}