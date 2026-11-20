package com.expenseos.cloud;

import android.content.Context;

import com.expenseos.util.AppConfig;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Minimal Supabase Storage client (no extra dependency — plain HttpURLConnection).
 *
 * <p>All credentials come from {@link AppConfig} (Config > Supabase Storage), so nothing is
 * hard-coded in the APK. Because the app user's email + password are stored, the access token
 * can be refreshed — or the session re-created — silently whenever it expires, so there is no
 * login dialog anywhere in the UI.
 *
 * <p>Object bytes are always ciphertext: {@link FileCrypto} encrypts before
 * {@link #upload(Context, String, String, byte[])} is called, and decrypts after
 * {@link #download(Context, String)} returns.
 */
public final class SupabaseStorage {

    /** Thrown when the Supabase settings in Config are missing or a login/refresh failed. */
    public static class NotSignedInException extends IOException {
        public NotSignedInException() {
            super("Supabase is not configured or login failed. Check Config > Supabase Storage.");
        }
    }

    public static class HttpFail extends IOException {
        public final int code;

        public HttpFail(int code, String body) {
            super("HTTP " + code + ": " + body);
            this.code = code;
        }
    }

    private static String accessToken;   // memory only
    private static long accessExp;

    private SupabaseStorage() {
    }

    /** Stable object key for one attachment row. */
    public static String pathFor(int txnId, long receiptId) {
        return "receipts/" + txnId + "/" + receiptId;
    }

    /** True when URL + key + email + password are all saved in Config. */
    public static boolean isSignedIn(Context ctx) {
        return AppConfig.get(ctx).isSupabaseConfigured();
    }

    public static synchronized void reset() {
        accessToken = null;
        accessExp = 0;
    }

    public static void warmUp(Context ctx) throws IOException {
        token(ctx);
    }

    /** Config page "Test" button: login + list 1 object from the bucket. */
    public static void testConnection(Context ctx) throws IOException {
        reset();
        String t = token(ctx);
        HttpURLConnection c = open(ctx, "/storage/v1/object/list/" + bucket(ctx), "POST", t);
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write("{\"prefix\":\"\",\"limit\":1}".getBytes("UTF-8"));
        }
        int code = c.getResponseCode();
        String body = new String(readAll(code / 100 == 2 ? c.getInputStream() : c.getErrorStream()), "UTF-8");
        c.disconnect();
        if (code / 100 != 2) throw new HttpFail(code, body);
    }

    // ── Storage ops ───────────────────────────────────────

    public static void upload(Context ctx, String objectPath, String mime, byte[] data) throws IOException {
        HttpURLConnection c = open(ctx, objectUrl(ctx, objectPath), "POST", token(ctx));
        c.setRequestProperty("Content-Type", mime != null ? mime : "application/octet-stream");
        c.setRequestProperty("x-upsert", "true");
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(data.length);
        try (OutputStream os = c.getOutputStream()) {
            os.write(data);
        }
        int code = c.getResponseCode();
        if (code / 100 != 2) {
            String err = new String(readAll(c.getErrorStream()), "UTF-8");
            c.disconnect();
            throw new HttpFail(code, err);
        }
        c.disconnect();
    }

    public static byte[] download(Context ctx, String objectPath) throws IOException {
        HttpURLConnection c = open(ctx, "/storage/v1/object/authenticated/" + bucket(ctx) + "/"
                + enc(objectPath), "GET", token(ctx));
        int code = c.getResponseCode();
        if (code / 100 != 2) {
            String err = new String(readAll(c.getErrorStream()), "UTF-8");
            c.disconnect();
            throw new HttpFail(code, err);
        }
        try (InputStream in = c.getInputStream()) {
            return readAll(in);
        } finally {
            c.disconnect();
        }
    }

    public static void delete(Context ctx, String objectPath) throws IOException {
        HttpURLConnection c = open(ctx, objectUrl(ctx, objectPath), "DELETE", token(ctx));
        int code = c.getResponseCode();
        String err = code / 100 == 2 ? "" : new String(readAll(c.getErrorStream()), "UTF-8");
        c.disconnect();
        if (code / 100 != 2 && code != 404) throw new HttpFail(code, err);
    }

    // ── Auth / session ────────────────────────────────────

    private static synchronized String token(Context ctx) throws IOException {
        if (accessToken != null && System.currentTimeMillis() < accessExp - 60_000L) return accessToken;

        AppConfig cfg = AppConfig.get(ctx);
        if (!cfg.isSupabaseConfigured()) throw new NotSignedInException();

        // The app user's email + password are stored, so an expired token is re-created silently:
        // no login dialog is ever shown in the UI.
        try {
            JSONObject res = authCall(ctx, "/auth/v1/token?grant_type=password",
                    new JSONObject().put("email", cfg.getSupabaseEmail())
                            .put("password", cfg.getSupabasePassword()));
            saveSession(res);
            return res.getString("access_token");
        } catch (JSONException e) {
            throw new IOException(e);
        } catch (HttpFail f) {
            if (f.code == 400 || f.code == 401) {
                reset();
                throw new NotSignedInException();
            }
            throw f;
        }
    }

    private static void saveSession(JSONObject res) throws JSONException {
        accessToken = res.getString("access_token");
        accessExp = System.currentTimeMillis() + res.getLong("expires_in") * 1000L;
    }

    private static JSONObject authCall(Context ctx, String path, JSONObject body)
            throws IOException, JSONException {
        HttpURLConnection c = open(ctx, path, "POST", null);
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes("UTF-8"));
        }
        int code = c.getResponseCode();
        String resp = new String(readAll(code / 100 == 2 ? c.getInputStream() : c.getErrorStream()), "UTF-8");
        c.disconnect();
        if (code / 100 != 2) throw new HttpFail(code, resp);
        return new JSONObject(resp);
    }

    // ── helpers ───────────────────────────────────────────

    private static String baseUrl(Context ctx) {
        return AppConfig.get(ctx).getSupabaseUrl();
    }

    private static String bucket(Context ctx) {
        return AppConfig.get(ctx).getSupabaseBucket();
    }

    private static String objectUrl(Context ctx, String objectPath) {
        return "/storage/v1/object/" + bucket(ctx) + "/" + enc(objectPath);
    }

    private static HttpURLConnection open(Context ctx, String path, String method, String bearer)
            throws IOException {
        String base = baseUrl(ctx);
        if (base == null || base.isEmpty()) throw new NotSignedInException();
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15_000);
        c.setReadTimeout(60_000);
        c.setRequestProperty("apikey", AppConfig.get(ctx).getSupabaseAnonKey());
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        return c;
    }

    /** Encodes each path segment but keeps the "/" separators. */
    private static String enc(String objectPath) {
        StringBuilder sb = new StringBuilder();
        String[] parts = objectPath.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('/');
            try {
                sb.append(URLEncoder.encode(parts[i], "UTF-8").replace("+", "%20"));
            } catch (UnsupportedEncodingException e) {
                sb.append(parts[i]);
            }
        }
        return sb.toString();
    }

    public static byte[] readAll(InputStream in) throws IOException {
        if (in == null) return new byte[0];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
