package com.oemtools.poldock;

import org.json.JSONArray;
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
    private static final String API_ROOT = "https://open-api.mybox.naver.com/v1/drive";
    private static final String CREATE_UPLOAD_URL =
            API_ROOT + "/files";
    private static final String ROOT_RESOURCES_URL =
            API_ROOT + "/resources?sort=name%2Casc&count=1000";
    private static final String CREATE_FOLDER_URL = API_ROOT + "/folders";
    private static final String FOLDER_NAME = "PolDock";
    private static final String LATEST_NAME = "latest.png";
    private static final int TIMEOUT_MS = 20_000;

    private MyBoxUploader() {
    }

    static void upload(File file, String remoteName, String token) throws Exception {
        if (file == null || !file.isFile()) throw new IllegalArgumentException("사진 파일 없음");
        if (token == null || token.trim().isEmpty()) throw new IllegalArgumentException("MYBOX 토큰 없음");
        token = token.trim();
        String folderId = ensureFolder(token);
        String uploadUrl = requestUploadUrl(file, remoteName, folderId, token);
        uploadFile(file, uploadUrl);
        String resourceId = findResourceId(folderId, remoteName, token);
        copyAsLatest(resourceId, folderId, token);
    }

    private static String ensureFolder(String token) throws Exception {
        String existing = findFolderId(token);
        if (!existing.isEmpty()) return existing;

        HttpURLConnection connection = open(new URL(CREATE_FOLDER_URL));
        try {
            connection.setRequestMethod("POST");
            authorize(connection, token);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setDoOutput(true);
            byte[] bytes = new JSONObject().put("folderName", FOLDER_NAME)
                    .toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            String response = readResponse(connection, status);
            if (status == HttpURLConnection.HTTP_CREATED) {
                String id = new JSONObject(response).optString("resourceId", "");
                if (!id.isEmpty()) return id;
            }
            // 다른 PolDock 인스턴스가 동시에 폴더를 만든 경우 다시 조회한다.
            if (status == HttpURLConnection.HTTP_CONFLICT) {
                String raced = findFolderId(token);
                if (!raced.isEmpty()) return raced;
            }
            throw new IllegalStateException("PolDock 폴더 생성 실패 (HTTP " + status
                    + responseSuffix(response) + ")");
        } finally {
            connection.disconnect();
        }
    }

    private static String findFolderId(String token) throws Exception {
        HttpURLConnection connection = open(new URL(ROOT_RESOURCES_URL));
        try {
            connection.setRequestMethod("GET");
            authorize(connection, token);
            int status = connection.getResponseCode();
            String response = readResponse(connection, status);
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("MYBOX 폴더 조회 실패 (HTTP " + status
                        + responseSuffix(response) + ")");
            }
            JSONArray resources = new JSONObject(response).optJSONArray("resources");
            if (resources == null) return "";
            for (int i = 0; i < resources.length(); i++) {
                JSONObject resource = resources.optJSONObject(i);
                if (resource != null && FOLDER_NAME.equals(resource.optString("name"))
                        && "folder".equals(resource.optString("type"))) {
                    return resource.optString("resourceId", "");
                }
            }
            return "";
        } finally {
            connection.disconnect();
        }
    }

    private static String requestUploadUrl(File file, String remoteName, String folderId,
                                           String token) throws Exception {
        HttpURLConnection connection = open(new URL(CREATE_UPLOAD_URL));
        try {
            connection.setRequestMethod("POST");
            authorize(connection, token);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setDoOutput(true);
            JSONObject body = new JSONObject()
                    .put("fileName", remoteName)
                    .put("fileSize", file.length())
                    .put("isOverwrite", false)
                    .put("parentId", folderId);
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
                + "Content-Type: image/png\r\n\r\n";
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

    private static String findResourceId(String folderId, String remoteName, String token)
            throws Exception {
        String listUrl = API_ROOT + "/folders/" + folderId
                + "/resources?sort=createdAt%2Cdesc&count=100";
        for (int attempt = 0; attempt < 4; attempt++) {
            HttpURLConnection connection = open(new URL(listUrl));
            try {
                connection.setRequestMethod("GET");
                authorize(connection, token);
                int status = connection.getResponseCode();
                String response = readResponse(connection, status);
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new IllegalStateException("업로드 파일 조회 실패 (HTTP " + status
                            + responseSuffix(response) + ")");
                }
                JSONArray resources = new JSONObject(response).optJSONArray("resources");
                if (resources != null) {
                    for (int i = 0; i < resources.length(); i++) {
                        JSONObject resource = resources.optJSONObject(i);
                        if (resource != null && remoteName.equals(resource.optString("name"))
                                && "file".equals(resource.optString("type"))) {
                            String id = resource.optString("resourceId", "");
                            if (!id.isEmpty()) return id;
                        }
                    }
                }
            } finally {
                connection.disconnect();
            }
            Thread.sleep(300L);
        }
        throw new IllegalStateException("업로드 파일 ID를 찾을 수 없습니다");
    }

    private static void copyAsLatest(String resourceId, String folderId, String token)
            throws Exception {
        HttpURLConnection connection = open(new URL(
                API_ROOT + "/resources/" + resourceId + "/copy"));
        try {
            connection.setRequestMethod("POST");
            authorize(connection, token);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setDoOutput(true);
            byte[] bytes = new JSONObject()
                    .put("parentId", folderId)
                    .put("name", LATEST_NAME)
                    .put("isOverwrite", true)
                    .toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            String response = readResponse(connection, status);
            if (status != HttpURLConnection.HTTP_CREATED) {
                throw new IllegalStateException("latest.png 갱신 실패 (HTTP " + status
                        + responseSuffix(response) + ")");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void authorize(HttpURLConnection connection, String token) {
        connection.setRequestProperty("Authorization", "Bearer " + token);
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
