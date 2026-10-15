package com.oemtools.poldock;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.os.Build;

import java.io.File;

public class UpdateDownloadReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
            return;
        }

        SharedPreferences prefs = UpdateManager.prefs(context);
        long expectedId = prefs.getLong(UpdateManager.KEY_DOWNLOAD_ID, -1L);
        long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
        if (expectedId < 0 || completedId != expectedId) return;

        DownloadManager manager = (DownloadManager) context.getSystemService(
                Context.DOWNLOAD_SERVICE);
        if (manager == null || !downloadSucceeded(manager, completedId)) {
            prefs.edit().putBoolean(UpdateManager.KEY_UPDATE_AVAILABLE, true).apply();
            clearDownload(prefs);
            UpdateManager.notifyResult(context, "PolDock 업데이트 실패",
                    "APK를 다운로드하지 못했습니다.", null);
            return;
        }

        String path = prefs.getString(UpdateManager.KEY_DOWNLOAD_PATH, "");
        String error = verifyApk(context, new File(path));
        if (error != null) {
            new File(path).delete();
            prefs.edit().putBoolean(UpdateManager.KEY_UPDATE_AVAILABLE, true).apply();
            clearDownload(prefs);
            UpdateManager.notifyResult(context, "PolDock 업데이트 검증 실패", error, null);
            return;
        }

        clearDownload(prefs);
        // 백그라운드에서 화면을 강제로 띄우지 않고, 알림을 누르면 권한 확인 후 설치로 이어진다.
        Intent install = UpdateManager.installOrBrowseIntent(context, completedId);
        UpdateManager.notifyResult(context, "PolDock 다운로드 완료",
                "눌러서 설치를 계속하세요.", install);
    }

    private static boolean downloadSucceeded(DownloadManager manager, long id) {
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) return false;
            int index = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            return index >= 0 && cursor.getInt(index) == DownloadManager.STATUS_SUCCESSFUL;
        }
    }

    private static String verifyApk(Context context, File apk) {
        if (!apk.isFile() || apk.length() == 0) return "다운로드한 APK 파일이 없습니다.";
        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (archive == null) return "APK 파일을 읽을 수 없습니다.";
        if (!context.getPackageName().equals(archive.packageName)) {
            return "PolDock APK가 아니어서 파일을 삭제했습니다.";
        }

        try {
            PackageInfo installed = pm.getPackageInfo(context.getPackageName(), flags);
            if (!sameSigner(installed, archive)) {
                return "현재 앱과 서명이 달라 파일을 삭제했습니다.";
            }
            long installedCode = versionCode(installed);
            long archiveCode = versionCode(archive);
            if (archiveCode <= installedCode
                    && UpdateManager.compareVersions(archive.versionName,
                    installed.versionName) <= 0) {
                return "설치된 버전보다 새로운 APK가 아닙니다.";
            }
        } catch (PackageManager.NameNotFoundException e) {
            return "현재 PolDock 설치 정보를 확인할 수 없습니다.";
        }
        return null;
    }

    private static boolean sameSigner(PackageInfo installed, PackageInfo archive) {
        Signature[] current = signatures(installed);
        Signature[] candidate = signatures(archive);
        if (current.length == 0 || candidate.length == 0) return false;
        for (Signature left : current) {
            for (Signature right : candidate) {
                if (left.equals(right)) return true;
            }
        }
        return false;
    }

    private static Signature[] signatures(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            return info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        }
        return info.signatures == null ? new Signature[0] : info.signatures;
    }

    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private static void clearDownload(SharedPreferences prefs) {
        prefs.edit()
                .remove(UpdateManager.KEY_DOWNLOAD_ID)
                .remove(UpdateManager.KEY_DOWNLOAD_PATH)
                .remove(UpdateManager.KEY_DOWNLOAD_VERSION)
                .apply();
    }
}
