package com.expenseos.sync;

import java.util.Arrays;
import java.util.Base64;

import javax.crypto.SecretKey;

/**
 * Sets up the cloud encryption key. The salt and a small "check" value live in the cloud table sync_meta, so a NEW
 * phone that enters the same passphrase derives the same key, and a WRONG passphrase is detected before anything is
 * written or decrypted. The passphrase itself never goes to the cloud.
 */
public final class CloudKeyring {

    public interface Meta {
        String get(String key) throws Exception;

        void putIfAbsent(String key, String value) throws Exception;
    }

    private static final String CHECK_PLAIN = "ExpenseOS-OK";
    private static volatile String lastError;
    private static char[] cachedPass;
    private static String cachedSalt;
    private static SecretKey cachedKey;

    private CloudKeyring() {
    }

    public static String lastError() {
        return lastError;
    }

    /**
     * Returns null when encryption is ready, otherwise a user-facing reason (encryption stays OFF).
     */
    public static synchronized String prepare(Meta meta, char[] pass) {
        CloudCrypto.setKey(null);
        lastError = null;
        if (pass == null || pass.length == 0) return fail("Cloud encryption passphrase is not set");
        try {
            String saltB64 = meta.get("enc_salt");
            if (saltB64 == null) {
                meta.putIfAbsent("enc_salt", Base64.getEncoder().encodeToString(CloudCrypto.newSalt()));
                saltB64 = meta.get("enc_salt");          // re-read: if two phones raced, both use the winner's salt
            }
            SecretKey key = (cachedKey != null && saltB64.equals(cachedSalt) && Arrays.equals(pass, cachedPass))
                    ? cachedKey
                    : CloudCrypto.deriveKey(pass, Base64.getDecoder().decode(saltB64));
            cachedKey = key;
            cachedSalt = saltB64;
            cachedPass = pass.clone();
            CloudCrypto.setKey(key);

            String check = meta.get("enc_check");
            if (check == null) {
                meta.putIfAbsent("enc_check", CloudCrypto.enc(CHECK_PLAIN));
                check = meta.get("enc_check");
            }
            if (!CHECK_PLAIN.equals(CloudCrypto.dec(check)))
                return fail("Wrong cloud encryption passphrase");
            return null;
        } catch (IllegalStateException e) {
            return fail("Wrong cloud encryption passphrase");
        } catch (Exception e) {
            return fail("Cloud encryption setup failed: " + e.getMessage());
        }
    }

    private static String fail(String msg) {
        CloudCrypto.setKey(null);
        lastError = msg;
        return msg;
    }
}