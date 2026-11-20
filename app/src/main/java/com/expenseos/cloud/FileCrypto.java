package com.expenseos.cloud;

import android.content.Context;

import com.expenseos.sync.CloudLock;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Client-side AES-256-GCM for attachments. Supabase Storage and the local cache only ever hold
 * ciphertext; plaintext exists only in memory while it is displayed (and in a short-lived temp
 * file when the user presses Open / Share).
 *
 * <p>Layout: "EOF1" | IV (12 bytes) | ciphertext + GCM tag. The object path is bound as AAD, so
 * one receipt's ciphertext cannot be swapped with another's.
 *
 * <p>Key: the existing cloud passphrase ({@link CloudLock}) + the shared salt (cloud_salt)
 * through PBKDF2-SHA256 (200k iterations). No extra passphrase is needed — restoring a backup on
 * a fresh install and entering the same passphrase is enough to decrypt every attachment.
 */
public final class FileCrypto {

    public static class NotReadyException extends IOException {
        public NotReadyException() {
            super("Cloud passphrase is not set. Set it under Config > Cloud encryption before using attachments.");
        }
    }

    private static final byte[] MAGIC = {'E', 'O', 'F', '1'};
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int PBKDF2_ITERATIONS = 200_000;
    private static final byte[] LABEL = "expenseos-files-v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RNG = new SecureRandom();

    private static byte[] cachedFingerprint;   // avoids re-running PBKDF2 on every file
    private static SecretKey cachedKey;

    private FileCrypto() {
    }

    /** True when the passphrase and the shared salt are both available on this phone. */
    public static boolean isReady(Context ctx) {
        return passphrase(ctx) != null && salt(ctx) != null;
    }

    public static byte[] encrypt(Context ctx, byte[] plain, String aad) throws IOException {
        try {
            byte[] iv = new byte[IV_LEN];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(ctx), new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[MAGIC.length + IV_LEN + ct.length];
            System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
            System.arraycopy(iv, 0, out, MAGIC.length, IV_LEN);
            System.arraycopy(ct, 0, out, MAGIC.length + IV_LEN, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IOException("Encryption failed: " + e.getMessage(), e);
        }
    }

    public static byte[] decrypt(Context ctx, byte[] data, String aad) throws IOException {
        if (!hasMagic(data)) return data; // object written before encryption existed: returned as-is
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(ctx),
                    new GCMParameterSpec(TAG_BITS, data, MAGIC.length, IV_LEN));
            c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            int off = MAGIC.length + IV_LEN;
            return c.doFinal(data, off, data.length - off);
        } catch (AEADBadTagException e) {
            throw new IOException("Cannot decrypt attachment: wrong cloud passphrase or corrupted file.");
        } catch (GeneralSecurityException e) {
            throw new IOException("Decryption failed: " + e.getMessage(), e);
        }
    }

    /**
     * Deletes decrypted temp files (camera shots, "Open / Share" copies) older than 10 minutes.
     */
    public static void purgePlainTemp(Context ctx) {
        File[] files = new File(ctx.getCacheDir(), "receipts").listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 10 * 60 * 1000L;
        for (File f : files) if (f.lastModified() < cutoff) f.delete();
    }

    private static boolean hasMagic(byte[] d) {
        if (d == null || d.length < MAGIC.length + IV_LEN + 16) return false;
        for (int i = 0; i < MAGIC.length; i++) if (d[i] != MAGIC[i]) return false;
        return true;
    }

    private static synchronized SecretKey key(Context ctx) throws IOException {
        char[] pass = passphrase(ctx);
        byte[] salt = salt(ctx);
        if (pass == null || salt == null) throw new NotReadyException();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(new String(pass).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(salt);
            byte[] fp = md.digest();
            if (cachedKey != null && MessageDigest.isEqual(fp, cachedFingerprint)) return cachedKey;

            byte[] s = new byte[salt.length + LABEL.length];
            System.arraycopy(salt, 0, s, 0, salt.length);
            System.arraycopy(LABEL, 0, s, salt.length, LABEL.length);
            byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec(pass, s, PBKDF2_ITERATIONS, 256)).getEncoded();
            cachedKey = new SecretKeySpec(raw, "AES");
            cachedFingerprint = fp;
            return cachedKey;
        } catch (GeneralSecurityException e) {
            throw new IOException("Key derivation failed: " + e.getMessage(), e);
        }
    }

    private static char[] passphrase(Context ctx) {
        try {
            char[] p = CloudLock.get(ctx);
            return p == null || p.length == 0 ? null : p;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] salt(Context ctx) {
        try {
            Object s = CloudLock.getSalt(ctx);
            if (s == null) return null;
            return s instanceof byte[] b ? b : String.valueOf(s).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
