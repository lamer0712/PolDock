package com.oemtools.poldock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** 다운로드 완료 알림에서 설치 출처 권한과 APK 설치 화면을 순서대로 연다. */
public class UpdateInstallActivity extends Activity {

    private static final int REQUEST_INSTALL_SOURCE = 401;
    private long downloadId;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        downloadId = getIntent().getLongExtra(UpdateManager.EXTRA_DOWNLOAD_ID, -1L);
        if (downloadId < 0) {
            finish();
            return;
        }
        continueInstall();
    }

    private void continueInstall() {
        if (!UpdateManager.canInstallDirectly(this)) {
            Intent settings = UpdateManager.unknownSourcesIntent(this);
            if (settings != null) {
                startActivityForResult(settings, REQUEST_INSTALL_SOURCE);
                return;
            }
            showUnsupported();
            return;
        }
        Intent install = UpdateManager.directInstallIntent(this, downloadId);
        if (install == null) {
            Toast.makeText(this, "다운로드한 APK를 열 수 없습니다.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        startActivity(install);
        finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_INSTALL_SOURCE) return;
        if (UpdateManager.canInstallDirectly(this)) continueInstall();
        else {
            Toast.makeText(this, "PolDock의 앱 설치 허용이 필요합니다.",
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void showUnsupported() {
        new AlertDialog.Builder(this)
                .setTitle("설치 권한 화면 없음")
                .setMessage("차량에서 PolDock의 앱 설치 허용 화면을 제공하지 않습니다.")
                .setPositiveButton("확인", (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .show();
    }
}
