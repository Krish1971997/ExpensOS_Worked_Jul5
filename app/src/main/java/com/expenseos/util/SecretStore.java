package com.expenseos.util;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts small secrets (passwords, tokens, API keys) with an AES-256-GCM key that lives in the Android Keystore.
 * Stored form: "ks1:" + Base64(iv | ciphertext | tag). Values without the prefix are old plaintext and pass through,
 * so existing installs migrate without breaking.
 */
public final class SecretStore {

    private static final String PREFIX = "ks1:";
    private static final String TAG = "SecretStore";
    private static volatile SecretKey cached;

    private SecretStore() {
    }

    public static boolean isEncrypted(String s) {
        return s != null && s.startsWith(PREFIX);
    }

    /**
     * If the Keystore is unusable on this device the value is stored as-is (and logged) rather than breaking the app.
     */
    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty() || isEncrypted(plain)) return plain;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, getKey());            // the Keystore picks a fresh random IV
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            Log.w(TAG, "Keystore encrypt failed, storing value unencrypted: " + e.getMessage());
            return plain;
        }
    }

    /**
     * Old plaintext passes through. If the key is gone (data cleared / keystore reset) returns "" so the user re-enters it.
     */
    public static String decrypt(String stored) {
        if (!isEncrypted(stored)) return stored;
        try {
            byte[] in = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, in, 0, 12));
            return new String(c.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "Keystore decrypt failed (key lost?): " + e.getMessage());
            return "";
        }
    }

    private static SecretKey getKey() throws Exception {
        SecretKey k = cached;
        if (k != null) return k;
        synchronized (SecretStore.class) {
            if (cached != null) return cached;
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            Key existing = ks.getKey("expenseos_secret_key_v1", null);
            if (existing instanceof SecretKey) {
                cached = (SecretKey) existing;
                return cached;
            }
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder("expenseos_secret_key_v1",
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            cached = kg.generateKey();
            return cached;
        }
    }
}