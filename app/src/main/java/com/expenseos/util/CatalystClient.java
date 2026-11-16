package com.expenseos.util;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * HTTPS client for the Catalyst function. Call from a BACKGROUND thread only.
 */
public final class CatalystClient {

    // Catalyst console -> Functions -> expenseos_api -> function URL (no trailing slash)
    private static final String BASE_URL = "https://project-rainfall-841028177.development.catalystserverless.com/server/expenseos_api";

    private CatalystClient() {
    }

    public static JSONObject get(Context ctx, String path) throws Exception {
        return call(ctx, "GET", path, null);
    }

    public static JSONObject post(Context ctx, String path, JSONObject body) throws Exception {
        return call(ctx, "POST", path, body);
    }

    private static JSONObject call(Context ctx, String method, String path, JSONObject body) throws Exception {
        String token = AuthManager.getAccessToken(ctx);
        if (token == null || token.isEmpty()) throw new IOException("Not signed in to Zoho");

        HttpURLConnection c = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15_000);
        c.setReadTimeout(40_000);   // function max is 30s
        c.setRequestProperty("X-Zoho-Token", token);
        c.setRequestProperty("Accept", "application/json");
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }

        int status = c.getResponseCode();
        InputStream is = status < 400 ? c.getInputStream() : c.getErrorStream();
        String resp = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);

        JSONObject json;
        try {
            json = new JSONObject(resp);
        } catch (JSONException e) {
            throw new IOException("Server [HTTP " + status + "]: " + resp);
        }
        if (status >= 400 || !json.optBoolean("ok", false))
            throw new IOException("Server [HTTP " + status + "]: " + json.optString("error", resp));
        return json;
    }
}