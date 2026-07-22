package com.expenseos.sync;

import android.content.Context;

import com.expenseos.util.AppConfig;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Android port of the web ZohoTokenService — reads client id/secret/refresh token from AppConfig.
 */
public class ZohoTokenService {

    private static final String TOKEN_URL = "https://accounts.zoho.com/oauth/v2/token";
    private static final long EXPIRY_BUFFER_SECONDS = 60;

    private final AppConfig cfg;
    private volatile String accessToken;
    private volatile Instant expiresAt = Instant.EPOCH;

    public ZohoTokenService(Context ctx) {
        cfg = AppConfig.get(ctx);
    }

    public synchronized String getAccessToken() throws IOException, JSONException {
        if (accessToken == null || Instant.now().isAfter(expiresAt.minusSeconds(EXPIRY_BUFFER_SECONDS))) {
            refreshAccessToken();
        }
        return accessToken;
    }

    private void refreshAccessToken() throws IOException, JSONException {
        String body = "grant_type=refresh_token"
                + "&client_id=" + cfg.getZohoClientId()
                + "&client_secret=" + cfg.getZohoClientSecret()
                + "&refresh_token=" + cfg.getZohoRefreshToken();

        HttpURLConnection conn = (HttpURLConnection) new URL(TOKEN_URL).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        String response = new String(
                (status == 200 ? conn.getInputStream() : conn.getErrorStream()).readAllBytes(),
                StandardCharsets.UTF_8);

        if (status != 200)
            throw new IOException("Token refresh failed [HTTP " + status + "]: " + response);

        JSONObject json = new JSONObject(response);
        this.accessToken = json.getString("access_token");
        int expiresIn = json.optInt("expires_in", 3600);
        this.expiresAt = Instant.now().plusSeconds(expiresIn);
    }
}