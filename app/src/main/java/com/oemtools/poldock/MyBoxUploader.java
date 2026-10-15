package com.oemtools.poldock;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** NAVER MYBOX 공식 Open API의 업로드 URL 발급 + 파일 업로드 흐름. */
final class MyBoxUploader {
    private static final String CREATE_UPLOAD_URL =
            "https://open-api.mybox.naver.com/v1/drive/files";
    private static final int TIMEOUT_MS = 20_000;

    private MyBoxUploader() {
    }

    static void upload(File file, String remoteName, String token) throws Exception {
        if (file == null || !file.isFile()) throw new IllegalArgumentException("사진 파일 없음");
        if (token == null || token.trim().isEmpty()) throw new IllegalArgumentException("MYBOX 토큰 없음");
        String uploadUrl = requestUploadUrl(file, remoteName, token.trim());
        uploadFile(file, uploadUrl);
    }

    private static String requestUploadUrl(File file, String remoteName, String token)
            throws Exception {
        HttpURLConnection connection = open(new URL(CREATE_UPLOAD_URL));
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setDoOutput(true);
            JSONObject body = new JSONObject()
                    .put("fileName", remoteName)
                    .put("fileSize", file.length())
                    .put("isOverwrite", false);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            String response = readResponse(connection, status);
            if (status != HttpURLConnection.HTTP_CREATED) {
                throw new IllegalStateException("MYBOX 업로드 준비 실패 (HTTP " + status
                        + responseSuffix(response) + ")");
            }
            String uploadUrl = new JSONObject(response).optString("uploadUrl", "");
            if (!uploadUrl.startsWith("https://")) {
                throw new IllegalStateException("MYBOX 업로드 주소가 올바르지 않음");
            }
            return uploadUrl;
        } finally {
            connection.disconnect();
        }
    }

    private static void uploadFile(File file, String uploadUrl) throws Exception {
        String boundary = "----PolDock" + UUID.randomUUID().toString().replace("-", "");
        String disposition = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"Filedata\"; filename=\""
                + safeFileName(file.getName()) + "\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n";
        String ending = "\r\n--" + boundary + "--\r\n";
        byte[] prefix = disposition.getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ending.getBytes(StandardCharsets.UTF_8);

        HttpURLConnection connection = open(new URL(uploadUrl));
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type",
                    "multipart/form-data; boundary=" + boundary);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(prefix.length + file.length() + suffix.length);
            try (OutputStream output = connection.getOutputStream();
                 InputStream input = new BufferedInputStream(new FileInputStream(file))) {
                output.write(prefix);
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
                output.write(suffix);
            }
            int status = connection.getResponseCode();
            String response = readResponse(connection, status);
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("MYBOX 전송 실패 (HTTP " + status
                        + responseSuffix(response) + ")");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(URL url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);
        return connection;
    }

    private static String readResponse(HttpURLConnection connection, int status) {
        InputStream stream = null;
        try {
            stream = status >= 200 && status < 400
                    ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) return "";
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && text.length() < 4096) text.append(line);
            return text.toString();
        } catch (Exception ignored) {
            return "";
        } finally {
            if (stream != null) try { stream.close(); } catch (Exception ignored) { }
        }
    }

    private static String responseSuffix(String response) {
        if (response == null || response.isEmpty()) return "";
        try {
            JSONObject error = new JSONObject(response);
            String code = error.optString("code", "");
            if (!code.isEmpty()) return ", " + code;
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String safeFileName(String name) {
        return name.replace("\\", "_").replace("\"", "_")
                .replace("\r", "_").replace("\n", "_");
    }
}
