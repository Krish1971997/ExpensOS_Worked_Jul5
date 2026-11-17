package com.expenseos.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.expenseos.sync.CloudRestore;

import java.util.function.Consumer;

/**
 * Restore-from-cloud with a progress bar and percentage.
 */
public final class RestoreProgressDialog {

    private RestoreProgressDialog() {
    }

    public static void run(Activity act, Consumer<Boolean> onFinished) {
        int pad = (int) (24 * act.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad / 2);

        TextView tvMsg = new TextView(act);
        tvMsg.setText("Starting...");
        ProgressBar bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setIndeterminate(false);
        TextView tvPct = new TextView(act);
        tvPct.setText("0%");
        tvPct.setGravity(Gravity.END);
        tvPct.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvPct.setTypeface(null, Typeface.BOLD);

        box.addView(tvMsg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad / 2;
        box.addView(bar, lp);
        box.addView(tvPct);

        AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("Restoring from cloud")
                .setView(box)
                .setCancelable(false)
                .create();
        dlg.show();

        CloudRestore.restoreAll(act.getApplicationContext(), new CloudRestore.Listener() {
            @Override
            public void onProgress(int percent, String message) {
                if (act.isFinishing()) return;
                bar.setProgress(percent);
                tvPct.setText(percent + "%");
                tvMsg.setText(message);
            }

            @Override
            public void onDone(boolean ok, String summary) {
                if (!act.isFinishing() && dlg.isShowing()) dlg.dismiss();
                Toast.makeText(act.getApplicationContext(), (ok ? "✔ " : "✘ ") + summary, Toast.LENGTH_LONG).show();
                if (onFinished != null) onFinished.accept(ok);
            }
        });
    }
}