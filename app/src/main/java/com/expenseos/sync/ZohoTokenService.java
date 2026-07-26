package com.expenseos.sync;

import android.content.Context;

import com.expenseos.util.AppConfig;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
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
                + "&client_id=" + urlEncode(cfg.getZohoClientId())
                + "&client_secret=" + urlEncode(cfg.getZohoClientSecret())
                + "&refresh_token=" + urlEncode(cfg.getZohoRefreshToken());

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

        JSONObject json;
        try {
            json = new JSONObject(response);
        } catch (JSONException e) {
            throw new IOException("Zoho token refresh returned a non-JSON response [HTTP " + status + "]: " + response);
        }

        // IMPORTANT: Zoho's OAuth token endpoint frequently returns HTTP 200
        // even when the request failed, with an "error" field instead of
        // access_token (e.g. {"error":"invalid_client"}). Checking only
        // `status != 200` misses this entirely and the code falls through to
        // json.getString("access_token"), which throws a generic, unhelpful
        // "No value for access_token" — that's what was showing up in the UI.
        // Check for "error" FIRST, regardless of HTTP status.
        if (json.has("error")) {
            String zohoError = json.optString("error", "unknown_error");
            throw new IOException("Zoho token refresh failed: " + zohoError + " — " + hintFor(zohoError));
        }

        if (status != 200 || !json.has("access_token")) {
            throw new IOException("Token refresh failed [HTTP " + status + "]: " + response);
        }

        this.accessToken = json.getString("access_token");
        int expiresIn = json.optInt("expires_in", 3600);
        this.expiresAt = Instant.now().plusSeconds(expiresIn);
    }

    /**
     * Human-readable hint for Zoho's common OAuth error codes.
     */
    private String hintFor(String zohoError) {
        switch (zohoError) {
            case "invalid_client":
                return "Client ID or Client Secret is wrong — recheck them in Config";
            case "invalid_code":
            case "invalid_grant":
                return "Refresh Token is invalid, expired, or was already used/revoked — generate a new one in Zoho API Console";
            case "access_denied":
                return "This app isn't authorized for the requested scope — recheck the WorkDrive scopes granted when the refresh token was created";
            default:
                return "see Zoho's OAuth error code reference for \"" + zohoError + "\"";
        }
    }

    private String urlEncode(String s) {
        try {
            return URLEncoder.encode(s != null ? s : "", StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s != null ? s : "";
        }
    }
}