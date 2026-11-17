package com.expenseos.sync;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.expenseos.util.SecretStore;

/**
 * Cloud encryption passphrase, kept on this device only (Keystore-encrypted). Use the SAME passphrase on every phone.
 */
public final class CloudLock {

    private static final String PREF = "expenseos_cloud_lock";
    private static final String K_PASS = "pass";

    private CloudLock() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static boolean isSet(Context c) {
        return !prefs(c).getString(K_PASS, "").isEmpty();
    }

    public static void set(Context c, String pass) {
        prefs(c).edit().putString(K_PASS, SecretStore.encrypt(pass)).apply();
    }

    public static void clear(Context c) {
        prefs(c).edit().remove(K_PASS).apply();
    }

    /**
     * null when not set (or when the Keystore key was lost: the user must enter it again).
     */
    public static char[] get(Context c) {
        String p = SecretStore.decrypt(prefs(c).getString(K_PASS, ""));
        return p == null || p.isEmpty() ? null : p.toCharArray();
    }

    public static void showDialog(Activity act, Runnable onDone) {
        int pad = (int) (20 * act.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView warn = new TextView(act);
        warn.setText("Bank SMS data (passbook entries) is encrypted with this passphrase before it is uploaded. "
                + "Use the SAME passphrase on every phone. It cannot be recovered: if you forget it, the cloud copy of "
                + "those entries cannot be read. At least 8 characters.");
        EditText p1 = field(act, "Passphrase"), p2 = field(act, "Repeat passphrase");
        box.addView(warn);
        box.addView(p1);
        box.addView(p2);

        AlertDialog.Builder b = new AlertDialog.Builder(act)
                .setTitle(isSet(act) ? "Change cloud passphrase" : "Cloud encryption passphrase")
                .setView(box)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null);
        if (isSet(act))
            b.setNeutralButton("Remove", (d, w) -> {
                clear(act);
                Toast.makeText(act, "Passbook entries will NOT sync until a passphrase is set", Toast.LENGTH_LONG).show();
                if (onDone != null) onDone.run();
            });
        AlertDialog d = b.create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String a = p1.getText().toString(), c2 = p2.getText().toString();
            if (a.length() < 8) {
                p1.setError("At least 8 characters");
            } else if (!a.equals(c2)) {
                p2.setError("Does not match");
            } else {
                set(act, a);
                Toast.makeText(act, "Saved. Passbook entries will sync encrypted.", Toast.LENGTH_LONG).show();
                d.dismiss();
                if (onDone != null) onDone.run();
            }
        }));
        d.show();
    }

    private static EditText field(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }
}