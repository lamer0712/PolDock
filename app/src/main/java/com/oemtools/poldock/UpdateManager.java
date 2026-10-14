package com.oemtools.poldock;

import android.Manifest;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

final class UpdateManager {

    static final String EXTRA_VERSION = "update_version";
    static final String EXTRA_URL = "update_url";
    static final String EXTRA_ASSET = "update_asset";

    static final String KEY_DOWNLOAD_ID = "ota_download_id";
    static final String KEY_DOWNLOAD_PATH = "ota_download_path";
    static final String KEY_DOWNLOAD_VERSION = "ota_download_version";
    static final String KEY_UPDATE_AVAILABLE = "ota_update_available";
    static final String KEY_LAST_CHECK = "ota_last_check";

    private static final String TAG = "PolDockUpdate";
    private static final String CHANNEL = "poldock_updates";
    private static final int AVAILABLE_NOTIFICATION = 2401;
    private static final int RESULT_NOTIFICATION = 2402;
    private static final AtomicBoolean CHECKING = new AtomicBoolean(false);
    private static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;

    private UpdateManager() {
    }

    static void prepareNotifications(Context context) {
        ensureChannel(context);
    }

    static boolean canNotify(Context context) {
        return Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 자동 실행 경로용: 마지막 성공 검사 후 하루가 지났을 때만 검사한다. */
    static void checkForUpdateIfDue(Context context) {
        long last = prefs(context).getLong(KEY_LAST_CHECK, 0L);
        long elapsed = System.currentTimeMillis() - last;
        if (last > 0 && elapsed >= 0 && elapsed < CHECK_INTERVAL_MS) return;
        if (!canNotify(context)) return;
        prepareNotifications(context);
        checkForUpdate(context);
    }

    static void checkForUpdate(Context context) {
        String repository = BuildConfig.GITHUB_REPOSITORY.trim();
        if (repository.isEmpty() || !repository.matches("[^/]+/[^/]+")) return;
        if (!CHECKING.compareAndSet(false, true)) return;

        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                Release release = fetchLatestRelease(app, repository);
                prefs(app).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
                if (release != null) {
                    boolean available = compareVersions(
                            release.version, BuildConfig.VERSION_NAME) > 0;
                    prefs(app).edit().putBoolean(KEY_UPDATE_AVAILABLE, available).apply();
                    if (available) notifyAvailable(app, release);
                }
            } catch (Exception e) {
                Log.w(TAG, "Update check failed", e);
            } finally {
                CHECKING.set(false);
            }
        }, "poldock-update-check").start();
    }

    private static Release fetchLatestRelease(Context context, String repository) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "https://api.github.com/repos/" + repository + "/releases/latest").openConnection();
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(8_000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "PolDock/" + BuildConfig.VERSION_NAME);

        SharedPreferences prefs = prefs(context);
        String etag = prefs.getString("ota_release_etag", "");
        if (!etag.isEmpty()) connection.setRequestProperty("If-None-Match", etag);

        int status = connection.getResponseCode();
        if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
            return releaseFromCache(prefs);
        }
        if (status != HttpURLConnection.HTTP_OK) {
            throw new IllegalStateException("GitHub returned HTTP " + status);
        }

        JSONObject json;
        String responseEtag;
        try (InputStream input = connection.getInputStream()) {
            json = new JSONObject(readText(input));
            responseEtag = connection.getHeaderField("ETag");
        } finally {
            connection.disconnect();
        }

        String tag = json.optString("tag_name", "");
        JSONArray assets = json.optJSONArray("assets");
        if (TextUtils.isEmpty(tag) || assets == null) return null;

        String apkUrl = null;
        String apkName = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            if (!name.toLowerCase(Locale.US).endsWith(".apk")) continue;
            apkName = name;
            apkUrl = asset.optString("browser_download_url", "");
            if (name.toLowerCase(Locale.US).contains("release")) break;
        }
        if (TextUtils.isEmpty(apkUrl)) return null;

        Release release = new Release(tag, apkName, apkUrl);
        prefs.edit()
                .putString("ota_release_etag", responseEtag == null ? "" : responseEtag)
                .putString("ota_release_version", release.version)
                .putString("ota_release_asset", release.assetName)
                .putString("ota_release_url", release.url)
                .apply();
        return release;
    }

    private static Release releaseFromCache(SharedPreferences prefs) {
        String version = prefs.getString("ota_release_version", "");
        String asset = prefs.getString("ota_release_asset", "");
        String url = prefs.getString("ota_release_url", "");
        return version.isEmpty() || url.isEmpty() ? null : new Release(version, asset, url);
    }

    static boolean isUpdateAvailable(Context context) {
        return prefs(context).getBoolean(KEY_UPDATE_AVAILABLE, false);
    }

    static boolean isInternalPreference(String key) {
        return key != null && key.startsWith("ota_") && !KEY_UPDATE_AVAILABLE.equals(key);
    }

    static void openUpdatePrompt(Context context) {
        Release release = releaseFromCache(prefs(context));
        if (release == null) return;
        context.startActivity(updateIntent(context, release)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static String readText(InputStream input) throws Exception {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private static void notifyAvailable(Context context, Release release) {
        ensureChannel(context);
        Intent intent = updateIntent(context, release);
        PendingIntent action = PendingIntent.getActivity(context, AVAILABLE_NOTIFICATION, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());

        Notification.Builder builder = builder(context)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("PolDock 업데이트")
                .setContentText(release.version + " 버전을 다운로드할 수 있습니다.")
                .setContentIntent(action)
                .setAutoCancel(true);
        notificationManager(context).notify(AVAILABLE_NOTIFICATION, builder.build());
    }

    private static Intent updateIntent(Context context, Release release) {
        return new Intent(context, UpdateActivity.class)
                .putExtra(EXTRA_VERSION, release.version)
                .putExtra(EXTRA_ASSET, release.assetName)
                .putExtra(EXTRA_URL, release.url);
    }

    static long enqueueDownload(Context context, String version, String url) {
        String safeVersion = version.replaceAll("[^0-9A-Za-z._-]", "_");
        File target = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "PolDock-" + safeVersion + ".apk");
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("기존 업데이트 파일을 지울 수 없습니다.");
        }

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url))
                // 다운로드 폴더 선택기는 title을 파일명처럼 보여주므로 확장자까지 포함한다.
                .setTitle(target.getName())
                .setDescription("PolDock " + version + " 업데이트 다운로드 중")
                .setMimeType(APK_MIME)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                        target.getName());
        DownloadManager manager = (DownloadManager) context.getSystemService(
                Context.DOWNLOAD_SERVICE);
        if (manager == null) throw new IllegalStateException("다운로드 서비스를 사용할 수 없습니다.");
        long id = manager.enqueue(request);
        prefs(context).edit()
                .putLong(KEY_DOWNLOAD_ID, id)
                .putString(KEY_DOWNLOAD_PATH, target.getAbsolutePath())
                .putString(KEY_DOWNLOAD_VERSION, version)
                .putBoolean(KEY_UPDATE_AVAILABLE, false)
                .apply();
        notificationManager(context).cancel(AVAILABLE_NOTIFICATION);
        return id;
    }

    static void notifyResult(Context context, String title, String message, Intent actionIntent) {
        ensureChannel(context);
        Notification.Builder builder = builder(context)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(message)
                .setAutoCancel(true);
        if (actionIntent != null) {
            PendingIntent action = PendingIntent.getActivity(context, RESULT_NOTIFICATION,
                    actionIntent, PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
            builder.setContentIntent(action);
        }
        notificationManager(context).notify(RESULT_NOTIFICATION, builder.build());
    }

    static final String APK_MIME = "application/vnd.android.package-archive";
    private static final String DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents";

    /**
     * 다운로드 완료 후 열 화면. PolDock이 설치 출처로 허용돼 있으면 설치 화면을 바로 띄우고,
     * 아니면 다운로드 폴더를 파일 관리자(탐색) 모드로 연다. 차량 설정에는 출처 허용 화면이
     * 없어 설치 화면을 바로 띄우면 막히지만, 파일 관리자에서 누르면 정상 설치된다.
     * 선택기(OPEN_DOCUMENT)는 고른 결과를 받을 곳이 없어 마지막 수단으로만 쓴다.
     */
    static Intent installOrBrowseIntent(Context context, long downloadId) {
        DownloadManager manager = (DownloadManager) context.getSystemService(
                Context.DOWNLOAD_SERVICE);
        Uri apk = manager == null ? null : manager.getUriForDownloadedFile(downloadId);
        if (apk != null && canInstallDirectly(context)) {
            Intent install = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(apk, APK_MIME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (resolves(context, install)) return install;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            Intent browse = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(DocumentsContract.buildRootUri(DOWNLOADS_AUTHORITY, "downloads"),
                            DocumentsContract.Root.MIME_TYPE_ITEM)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (resolves(context, browse)) return browse;
        }
        return fileExplorerIntent();
    }

    /**
     * 설치 화면이 PolDock을 출처로 받아줄지 확인한다. targetSdk 22라 canRequestPackageInstalls()는
     * 항상 false이므로, 설치 관리자가 실제로 보는 app op를 직접 확인한다.
     */
    private static boolean canInstallDirectly(Context context) {
        if (Build.VERSION.SDK_INT < 26) return true;
        try {
            android.app.AppOpsManager ops = (android.app.AppOpsManager)
                    context.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return false;
            int uid = android.os.Process.myUid();
            String op = "android:request_install_packages";
            int mode = Build.VERSION.SDK_INT >= 29
                    ? ops.unsafeCheckOpNoThrow(op, uid, context.getPackageName())
                    : ops.checkOpNoThrow(op, uid, context.getPackageName());
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean resolves(Context context, Intent intent) {
        return context.getPackageManager().resolveActivity(intent, 0) != null;
    }

    static Intent fileExplorerIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(APK_MIME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 26) {
            intent.putExtra("android.provider.extra.INITIAL_URI", Uri.parse(
                    "content://com.android.providers.downloads.documents/root/downloads"));
        }
        return intent;
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(ShortcutStore.PREF, Context.MODE_PRIVATE);
    }

    static int compareVersions(String left, String right) {
        List<Integer> a = versionParts(left);
        List<Integer> b = versionParts(right);
        int count = Math.max(a.size(), b.size());
        for (int i = 0; i < count; i++) {
            int av = i < a.size() ? a.get(i) : 0;
            int bv = i < b.size() ? b.get(i) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static List<Integer> versionParts(String version) {
        ArrayList<Integer> parts = new ArrayList<>();
        if (version == null) return parts;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+").matcher(version);
        while (matcher.find()) {
            try {
                parts.add(Integer.parseInt(matcher.group()));
            } catch (NumberFormatException ignored) {
                parts.add(Integer.MAX_VALUE);
            }
        }
        return parts;
    }

    private static Notification.Builder builder(Context context) {
        if (Build.VERSION.SDK_INT >= 26) return new Notification.Builder(context, CHANNEL);
        return new Notification.Builder(context);
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "PolDock 업데이트",
                NotificationManager.IMPORTANCE_DEFAULT);
        notificationManager(context).createNotificationChannel(channel);
    }

    private static NotificationManager notificationManager(Context context) {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static int immutableFlag() {
        return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    private static final class Release {
        final String version;
        final String assetName;
        final String url;

        Release(String version, String assetName, String url) {
            this.version = version;
            this.assetName = assetName;
            this.url = url;
        }
    }
}
