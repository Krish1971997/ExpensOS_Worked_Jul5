package com.expenseos.util;

import android.content.Context;

import androidx.fragment.app.FragmentManager;

import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

public class TimePickerHelper {

    public interface OnTimePicked {
        void onPicked(int hour, int minute);
    }

    /**
     * Shows the Google-Calendar-style circular clock dial for time selection.
     */
    public static void show(Context ctx, FragmentManager fm, int initialHour, int initialMinute, OnTimePicked callback) {
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_12H)
                .setHour(initialHour)
                .setMinute(initialMinute)
                .setTitleText("Select time")
                .build();

        picker.addOnPositiveButtonClickListener(v ->
                callback.onPicked(picker.getHour(), picker.getMinute()));

        picker.show(fm, "time_picker");
    }
}