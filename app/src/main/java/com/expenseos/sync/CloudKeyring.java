package com.expenseos.sync;

import java.util.Arrays;
import java.util.Base64;

import javax.crypto.SecretKey;

/**
 * Key management for the encrypted passbook data. ONE random salt per user lives in the cloud table sync_meta (so a
 * new phone derives the same key from the same passphrase) and is also kept on every phone, so the phone can unlock
 * its local data offline. The passphrase never goes to the cloud; a small encrypted "check" value detects a wrong one.
 */
public final class CloudKeyring {

    public interface Meta {
        String get(String key) throws Exception;

        void putIfAbsent(String key, String value) throws Exception;
    }

    private static final String CHECK_PLAIN = "ExpenseOS-OK";
    private static volatile String lastError;
    private static volatile String lastSalt;
    private static volatile boolean cloudReady;
    private static char[] cachedPass;
    private static String cachedSalt;
    private static SecretKey cachedKey;

    private CloudKeyring() {
    }

    public static String lastError() {
        return lastError;
    }

    /**
     * Salt the last successful prepare() agreed with the cloud (the caller stores it on the phone).
     */
    public static String lastSalt() {
        return lastSalt;
    }

    /**
     * True only after prepare() verified this passphrase + salt against the cloud: the gate for push/pull.
     */
    public static boolean isCloudReady() {
        return cloudReady;
    }

    /**
     * PBKDF2 runs once per (passphrase, salt) per app start.
     */
    public static synchronized SecretKey keyFor(char[] pass, String saltB64) throws Exception {
        if (cachedKey != null && saltB64.equals(cachedSalt) && Arrays.equals(pass, cachedPass))
            return cachedKey;
        cachedKey = CloudCrypto.deriveKey(pass, Base64.getDecoder().decode(saltB64));
        cachedSalt = saltB64;
        cachedPass = pass.clone();
        return cachedKey;
    }

    /**
     * Talks to the cloud: reads (or creates) the shared salt, verifies the passphrase. On success loads the key
     * and returns null. On failure returns a reason; a key that was already unlocked locally is left untouched.
     * localSalt = the salt this phone already uses (null on a phone that has none yet).
     */
    public static synchronized String prepare(Meta meta, char[] pass, String localSalt) {
        cloudReady = false;
        lastError = null;
        if (pass == null || pass.length == 0) return fail("Cloud encryption passphrase is not set");
        try {
            String saltB64 = meta.get("enc_salt");
            if (saltB64 == null) {
                // first phone ever: its own salt becomes the cloud salt (so already-encrypted local rows stay valid)
                meta.putIfAbsent("enc_salt", localSalt != null ? localSalt
                        : Base64.getEncoder().encodeToString(CloudCrypto.newSalt()));
                saltB64 = meta.get("enc_salt");              // re-read: if two phones raced, both use the winner's salt
            }
            if (localSalt != null && !localSalt.equals(saltB64))
                return fail("This phone was set up with a different salt than the cloud. Reset the cloud encryption (see notes).");
            SecretKey key = keyFor(pass, saltB64);

            String check = meta.get("enc_check");
            if (check == null) {
                meta.putIfAbsent("enc_check", CloudCrypto.encWith(key, CHECK_PLAIN));
                check = meta.get("enc_check");
            }
            if (!CHECK_PLAIN.equals(CloudCrypto.decWith(key, check)))
                return fail("Wrong cloud encryption passphrase");

            CloudCrypto.setKey(key);
            lastSalt = saltB64;
            cloudReady = true;
            return null;
        } catch (IllegalStateException e) {
            return fail("Wrong cloud encryption passphrase");
        } catch (Exception e) {
            return fail("Cloud encryption setup failed: " + e.getMessage());
        }
    }

    private static String fail(String msg) {
        cloudReady = false;
        lastError = msg;
        return msg;
    }
}