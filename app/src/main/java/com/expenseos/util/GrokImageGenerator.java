package com.expenseos.util;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Calls xAI's dedicated image-generation endpoint (separate model/endpoint
 * from Grok's chat model — see /v1/images/generations). Always uses the
 * Grok-slot API key regardless of which provider is doing the chatting, so
 * "draw me a picture of X" works even mid-conversation with another
 * provider, as long as a Grok key is configured in Config.
 */
public class GrokImageGenerator {

    private static final String ENDPOINT = "https://api.x.ai/v1/images/generations";
    private static final String MODEL = "grok-imagine-image-quality";

    public static String generate(Context ctx, String prompt) {
        String apiKey = AppConfig.get(ctx).getAiKey(AppConfig.PROVIDER_GROK);
        if (apiKey == null || apiKey.isBlank()) {
            return errorJson("Grok API key not configured — add it under the \"grok\" provider in Config to enable image generation.");
        }
        if (prompt == null || prompt.isBlank()) {
            return errorJson("No prompt given for image generation.");
        }

        try {
            JSONObject body = new JSONObject();
            body.put("model", MODEL);
            body.put("prompt", prompt);

            URL url = new URL(ENDPOINT);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000); // image gen is slower than chat

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            InputStream is = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
            String responseStr = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            if (status < 200 || status >= 300) {
                return errorJson("Grok image generation failed (" + status + "): " + responseStr);
            }

            JSONObject response = new JSONObject(responseStr);
            JSONArray data = response.optJSONArray("data");
            if (data == null || data.length() == 0) {
                return errorJson("Grok returned no image data.");
            }
            JSONObject first = data.getJSONObject(0);

            byte[] imageBytes;
            if (first.has("b64_json")) {
                imageBytes = android.util.Base64.decode(first.getString("b64_json"), android.util.Base64.DEFAULT);
            } else if (first.has("url")) {
                imageBytes = downloadBytes(first.getString("url"));
            } else {
                return errorJson("Unrecognized image response format from Grok.");
            }

            File dir = new File(ctx.getCacheDir(), "ai_images");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "grok_img_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(imageBytes);
            }

            JSONObject result = new JSONObject();
            result.put("status", "image generated");
            result.put("image_path", out.getAbsolutePath());
            return result.toString();
        } catch (Exception e) {
            return errorJson("Grok image generation error: " + e.getMessage());
        }
    }

    private static byte[] downloadBytes(String imageUrl) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(imageUrl).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        try (InputStream is = conn.getInputStream()) {
            return is.readAllBytes();
        }
    }

    private static String errorJson(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("error", msg);
        } catch (Exception ignored) {
        }
        return o.toString();
    }
}