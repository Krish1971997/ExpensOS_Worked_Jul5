package com.expenseos.sync;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.expenseos.db.LocalDB;
import com.expenseos.util.SecretStore;

/**
 * Cloud/passbook encryption passphrase, kept on this device only (Keystore-encrypted), plus the shared salt.
 * Use the SAME passphrase on every phone.
 */
public final class CloudLock {

    private static final String PREF = "expenseos_cloud_lock";
    private static final String K_PASS = "pass";
    private static final String K_SALT = "salt";

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
        prefs(c).edit().remove(K_PASS).remove(K_SALT).apply();
    }

    /**
     * null when not set (or when the Keystore key was lost: the user must enter it again).
     */
    public static char[] get(Context c) {
        String p = SecretStore.decrypt(prefs(c).getString(K_PASS, ""));
        return p == null || p.isEmpty() ? null : p.toCharArray();
    }

    public static String getSalt(Context c) {
        String s = prefs(c).getString(K_SALT, "");
        return s.isEmpty() ? null : s;
    }

    public static void setSalt(Context c, String salt) {
        prefs(c).edit().putString(K_SALT, salt).apply();
    }

    /**
     * Offline: loads the key from the saved passphrase + salt (no cloud call). The first call takes ~0.5 s, so call it off the UI thread.
     */
    public static boolean unlockLocal(Context c) {
        if (CloudCrypto.isEnabled()) return true;
        char[] p = get(c);
        String s = getSalt(c);
        if (p == null || s == null) return false;
        try {
            CloudCrypto.setKey(CloudKeyring.keyFor(p, s));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void showDialog(Activity act, Runnable onDone) {
        if (isSet(act)) showRemoveDialog(act, onDone);
        else showSetDialog(act, onDone);
    }

    private static void showSetDialog(Activity act, Runnable onDone) {
        int pad = (int) (20 * act.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView warn = new TextView(act);
        warn.setText("Bank SMS data (passbook entries) is stored encrypted on this phone and in the cloud. "
                + "Use the SAME passphrase on every phone. It cannot be recovered: if you forget it, the cloud copy "
                + "cannot be read (the phone can re-read your SMS). At least 8 characters. Needs internet once.");
        EditText p1 = field(act, "Passphrase"), p2 = field(act, "Repeat passphrase");
        box.addView(warn);
        box.addView(p1);
        box.addView(p2);

        AlertDialog d = new AlertDialog.Builder(act)
                .setTitle("Cloud encryption passphrase")
                .setView(box)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnShowListener(x -> {
            Button save = d.getButton(AlertDialog.BUTTON_POSITIVE);
            save.setOnClickListener(v -> {
                String a = p1.getText().toString(), c2 = p2.getText().toString();
                if (a.length() < 8) {
                    p1.setError("At least 8 characters");
                    return;
                }
                if (!a.equals(c2)) {
                    p2.setError("Does not match");
                    return;
                }
                save.setEnabled(false);
                save.setText("Checking…");
                Context app = act.getApplicationContext();
                new Thread(() -> {
                    String err = SyncManager.establishCloudKey(app, a.toCharArray());   // salt + passphrase check in the cloud
                    if (err == null) {
                        set(app, a);
                        PassbookVault.encryptPending(LocalDB.getInstance(app).getWritableDatabase());
                    }
                    act.runOnUiThread(() -> {
                        if (err != null) {
                            save.setEnabled(true);
                            save.setText("Save");
                            p1.setError(err);
                            return;
                        }
                        Toast.makeText(act, "Saved. Passbook data is now encrypted.", Toast.LENGTH_LONG).show();
                        d.dismiss();
                        if (onDone != null) onDone.run();
                    });
                }).start();
            });
        });
        d.show();
    }

    private static void showRemoveDialog(Activity act, Runnable onDone) {
        new AlertDialog.Builder(act)
                .setTitle("Cloud encryption is ON")
                .setMessage("Passbook entries are encrypted on this phone and in the cloud.\n\n"
                        + "Remove: decrypts the entries on this phone and stops syncing passbook data. "
                        + "The cloud copy stays encrypted. (To use a NEW passphrase later, reset the cloud encryption.)")
                .setPositiveButton("Close", null)
                .setNegativeButton("Remove", (dlg, w) -> {
                    Context app = act.getApplicationContext();
                    new Thread(() -> {
                        boolean unlocked = unlockLocal(app);
                        if (unlocked)
                            PassbookVault.decryptAll(LocalDB.getInstance(app).getWritableDatabase());
                        clear(app);
                        CloudCrypto.setKey(null);
                        act.runOnUiThread(() -> {
                            Toast.makeText(act, unlocked ? "Removed. Entries on this phone are readable again."
                                            : "Removed, but the key was not available, so existing entries stay encrypted.",
                                    Toast.LENGTH_LONG).show();
                            if (onDone != null) onDone.run();
                        });
                    }).start();
                })
                .show();
    }

    private static EditText field(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }
}