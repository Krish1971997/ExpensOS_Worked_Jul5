package com.expenseos.sync;

import android.content.Context;

import com.expenseos.util.AppConfig;
import com.expenseos.util.ConsoleLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class WorkDriveApiService {

    private final ConsoleLogger log = ConsoleLogger.get();
    private final ZohoTokenService token;
    private final String folderId;

    public WorkDriveApiService(Context ctx) {
        token = new ZohoTokenService(ctx);
        folderId = AppConfig.get(ctx).getWorkdriveFolderId();
    }

    /**
     * Uploads a local file to WorkDrive, returns resource_id or null on failure.
     */
    public String uploadFile(File file) throws Exception {
        String url = "https://workdrive.zoho.com/api/v1/upload";
        String boundary = "Boundary-" + UUID.randomUUID().toString().replace("-", "");

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("Authorization", "Zoho-oauthtoken " + token.getAccessToken());
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (OutputStream os = conn.getOutputStream();
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8), true)) {

            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"parent_id\"\r\n\r\n");
            writer.append(folderId).append("\r\n");

            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"override-name-exist\"\r\n\r\n");
            writer.append("true\r\n");

            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"content\"; filename=\"")
                    .append(file.getName()).append("\"\r\n");
            writer.append("Content-Type: application/zip\r\n\r\n");
            writer.flush();

            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = fis.read(buf)) > 0) os.write(buf, 0, len);
            }
            writer.append("\r\n--").append(boundary).append("--\r\n");
        }

        int status = conn.getResponseCode();
        String resp = new String(
                (status < 400 ? conn.getInputStream() : conn.getErrorStream()).readAllBytes(),
                StandardCharsets.UTF_8);

        if (status == 200 || status == 201) {
            JSONObject json = new JSONObject(resp);
            JSONObject fileData = json.getJSONArray("data").getJSONObject(0);
            return fileData.getJSONObject("attributes").optString("resource_id");
        }
        throw new IOException("Upload failed [HTTP " + status + "]: " + resp);
    }

    /**
     * Downloads a WorkDrive file's bytes by resource_id.
     */
    public byte[] downloadFile(String fileId) throws Exception {
        String url = "https://download-accl.zoho.com/v1/workdrive/download/" + fileId;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("Authorization", "Zoho-oauthtoken " + token.getAccessToken());

        int status = conn.getResponseCode();
        if (status != 200) {
            String err = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("Download failed [HTTP " + status + "]: " + err);
        }
        try (InputStream is = conn.getInputStream(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) bos.write(buf, 0, len);
            return bos.toByteArray();
        }
    }

    public boolean deleteFile(String fileId) throws Exception {
        String url = "https://www.zohoapis.com/workdrive/api/v1/files/" + fileId;
        String body = "{\"data\":{\"attributes\":{\"status\":\"51\"},\"type\":\"files\"}}";

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("PATCH");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("Authorization", "Zoho-oauthtoken " + token.getAccessToken());
        conn.setRequestProperty("Content-Type", "application/vnd.api+json");
        conn.setRequestProperty("Accept", "application/vnd.api+json");   // <-- add this, matches web

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = conn.getResponseCode();
        String resp = new String(
                (status < 400 ? conn.getInputStream() : conn.getErrorStream()).readAllBytes(),
                StandardCharsets.UTF_8);

        // Always throw with full detail instead of silently returning false —
        // BackupManager's catch block will log this to Console.
        if (status == 200 || status == 204)
            return true;
        throw new IOException("Delete failed [HTTP " + status + "] fileId=" + fileId + ": " + resp);
    }

    public List<WorkDriveFileInfo> listFiles() throws Exception {
        List<WorkDriveFileInfo> allFiles = new ArrayList<>();
        int offset = 0;
        int limit = 50;

        while (true) {
            String url = "https://workdrive.zoho.com/api/v1/files/" + folderId + "/files"
                    + "?page%5Blimit%5D=" + limit + "&page%5Boffset%5D=" + offset;

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("Authorization", "Zoho-oauthtoken " + token.getAccessToken());
            conn.setRequestProperty("Accept", "application/vnd.api+json");

            int status = conn.getResponseCode();
            String resp = new String(
                    (status < 400 ? conn.getInputStream() : conn.getErrorStream()).readAllBytes(),
                    StandardCharsets.UTF_8);
            if (status != 200) throw new IOException("List failed [HTTP " + status + "]: " + resp);

            JSONObject json = new JSONObject(resp);
            JSONArray data = json.optJSONArray("data");
            if (data == null || data.length() == 0) break;   // no more pages

            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.getJSONObject(i);
                JSONObject attrs = item.optJSONObject("attributes");
                if (attrs == null || attrs.optBoolean("is_folder", false))
                    continue;   // skip the "errors" subfolder etc.

                WorkDriveFileInfo f = new WorkDriveFileInfo();
                f.id = item.optString("id");
                f.name = attrs.optString("name");
                f.modifiedTimeMillis = attrs.optLong("modified_time_in_millisecond");
                allFiles.add(f);
            }

            if (data.length() < limit) break;   // last page reached
            offset += limit;
        }
        return allFiles;
    }

    public static class WorkDriveFileInfo {
        public String id;
        public String name;
        public long modifiedTimeMillis;
    }

}