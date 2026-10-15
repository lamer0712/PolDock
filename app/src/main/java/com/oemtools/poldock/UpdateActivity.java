package com.oemtools.poldock;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

public class UpdateActivity extends Activity {

    private static final int REQUEST_STORAGE = 301;
    private static final int REQUEST_INSTALL_SOURCE = 302;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String version = getIntent().getStringExtra(UpdateManager.EXTRA_VERSION);
        String url = getIntent().getStringExtra(UpdateManager.EXTRA_URL);
        if (version == null || url == null) {
            finish();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("PolDock 업데이트")
                .setMessage(version + " 버전을 다운로드할까요?\n\n"
                        + "처음 한 번은 PolDock의 앱 설치 허용이 필요합니다.")
                .setPositiveButton("다운로드", (dialog, which) -> requestDownload())
                .setNegativeButton("취소", (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    private void requestDownload() {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_STORAGE);
            return;
        }
        requestInstallSource();
    }

    private void requestInstallSource() {
        if (UpdateManager.canInstallDirectly(this)) {
            startDownload();
            return;
        }
        android.content.Intent settings = UpdateManager.unknownSourcesIntent(this);
        if (settings != null) {
            startActivityForResult(settings, REQUEST_INSTALL_SOURCE);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("설치 권한 화면 없음")
                .setMessage("차량에서 PolDock의 앱 설치 허용 화면을 제공하지 않습니다.")
                .setPositiveButton("확인", (dialog, which) -> finish())
                .show();
    }

    private void startDownload() {
        try {
            UpdateManager.enqueueDownload(this,
                    getIntent().getStringExtra(UpdateManager.EXTRA_VERSION),
                    getIntent().getStringExtra(UpdateManager.EXTRA_URL));
            Toast.makeText(this, "업데이트를 다운로드합니다.", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            new AlertDialog.Builder(this)
                    .setTitle("다운로드 실패")
                    .setMessage(e.getMessage())
                    .setPositiveButton("확인", (dialog, which) -> finish())
                    .show();
            return;
        }
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            requestInstallSource();
        } else {
            Toast.makeText(this, "다운로드 폴더 권한이 필요합니다.", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_INSTALL_SOURCE) return;
        if (UpdateManager.canInstallDirectly(this)) {
            startDownload();
        } else {
            Toast.makeText(this, "PolDock의 앱 설치 허용이 필요합니다.",
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }
}
