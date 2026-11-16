package com.expenseos.sync;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM encryption of text fields pushed to the cloud DB: "enc:v1:" + Base64(iv | ciphertext | tag).
 * dec() returns values without the prefix unchanged (old plaintext rows). The key is derived from a passphrase
 * (PBKDF2, 200k iterations) and never leaves the device.
 */
public final class CloudCrypto {

    private static final String PREFIX = "enc:v1:";
    private static final int IV_LEN = 12, TAG_BITS = 128, PBKDF2_ITER = 200_000;
    private static final SecureRandom RNG = new SecureRandom();
    private static volatile SecretKey key;               // null = encryption OFF

    private CloudCrypto() {
    }

    public static boolean isEnabled() {
        return key != null;
    }

    public static void setKey(SecretKey k) {
        key = k;
    }

    public static byte[] newSalt() {
        byte[] s = new byte[16];
        RNG.nextBytes(s);
        return s;
    }

    public static SecretKey deriveKey(char[] passphrase, byte[] salt) throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] k = f.generateSecret(new PBEKeySpec(passphrase, salt, PBKDF2_ITER, 256)).getEncoded();
        return new SecretKeySpec(k, "AES");
    }

    public static boolean isEncrypted(String s) {
        return s != null && s.startsWith(PREFIX);
    }

    /**
     * Returns the input unchanged when null or when encryption is OFF; callers that must never push plaintext check isEnabled() first.
     */
    public static String enc(String plain) {
        if (plain == null || key == null) return plain;
        try {
            byte[] iv = new byte[IV_LEN];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LEN + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(ct, 0, out, IV_LEN, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed: " + e.getMessage(), e);
        }
    }

    public static String dec(String s) {
        if (!isEncrypted(s)) return s;
        if (key == null)
            throw new IllegalStateException("Cloud data is encrypted — set the cloud encryption passphrase in Config.");
        try {
            byte[] in = Base64.getDecoder().decode(s.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_LEN));
            return new String(c.doFinal(in, IV_LEN, in.length - IV_LEN), StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new IllegalStateException("Wrong cloud encryption passphrase, or the cloud data was modified.");
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed: " + e.getMessage(), e);
        }
    }
}