package com.expenseos.ui;

import android.content.Context;
import android.widget.TextView;

import com.expenseos.R;
import com.github.mikephil.charting.components.MarkerView;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.utils.MPPointF;

public class CustomMarkerView extends MarkerView {

    private final TextView tvContent;

    public CustomMarkerView(Context context, int layoutResource) {
        super(context, layoutResource);
        tvContent = findViewById(R.id.tvMarkerContent);
    }

    @Override
    public void refreshContent(Entry e, Highlight highlight) {
        // Point-ஐ click செய்யும்போது தொகையை ₹1,295 வடிவில் காட்டும்
        tvContent.setText(String.format("₹%,.0f", e.getY()));
        super.refreshContent(e, highlight);
    }

    @Override
    public MPPointF getOffset() {
        // Marker Popup புள்ளிக்கு மேலே சரியாக நிற்க Center Alignment Offset
        return new MPPointF(-(getWidth() / 2f), -getHeight() - 10);
    }
}