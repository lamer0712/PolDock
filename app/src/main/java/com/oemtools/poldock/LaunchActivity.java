package com.oemtools.poldock;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/**
 * 런처 진입점. 앱을 실행하면 설정 화면 대신 독을 띄운다.
 * 설정 화면은 독의 손잡이(≡)를 더블탭하면 열린다.
 *
 * 첫 실행 시: 위치 권한을 먼저 받고, 이어서 '알림 접근'(앨범아트용)을 켜도록 안내한다.
 */
public class LaunchActivity extends Activity {

    private static final String KEY_ONBOARDED = "onboarded";
    private static final int REQ_LOCATION = 101;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        // 독 띄우기 (오버레이 권한 없으면 설정 화면으로)
        if (OverlayService.canOverlay(this)) {
            OverlayService.start(this);
            Toast.makeText(this, "독을 띄웠습니다 · 손잡이(≡) 더블탭으로 설정",
                    Toast.LENGTH_SHORT).show();
        } else {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        SharedPreferences sp = getSharedPreferences(ShortcutStore.PREF, MODE_PRIVATE);
        UpdateManager.prepareNotifications(this);
        if (Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            UpdateManager.checkForUpdate(this);
        }
        continueOnboarding(sp);
    }

    private void continueOnboarding(SharedPreferences sp) {
        if (sp.getBoolean(KEY_ONBOARDED, false)) {
            finish();
            return;
        }

        // 1) 위치 권한 먼저
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;   // 결과를 받고 2단계로 이어감
        }
        // 이미 있으면 바로 2) 알림 접근 안내
        promptNotificationAccess(sp);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req == REQ_LOCATION) {
            promptNotificationAccess(getSharedPreferences(ShortcutStore.PREF, MODE_PRIVATE));
        } else {
            finish();
        }
    }

    /** 2) 앨범아트용 알림 접근 안내 (이미 켜져 있으면 생략) */
    private void promptNotificationAccess(SharedPreferences sp) {
        sp.edit().putBoolean(KEY_ONBOARDED, true).apply();
        if (notifAccessEnabled()) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("음악 앨범아트 표시")
                .setMessage("음악 위젯에 앨범 사진을 표시하려면 '알림 접근'을 켜야 해요.\n"
                        + "다음 화면에서 목록 중 'PolDock'을 찾아 켜주세요.")
                .setPositiveButton("켜러 가기", (d, w) -> {
                    try {
                        startActivity(new Intent(
                                "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception ignored) {
                    }
                    finish();
                })
                .setNegativeButton("나중에", (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    private boolean notifAccessEnabled() {
        try {
            String flat = Settings.Secure.getString(getContentResolver(),
                    "enabled_notification_listeners");
            return flat != null && flat.contains(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }
}
