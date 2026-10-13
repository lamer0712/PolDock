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
                        + "다운로드가 끝나면 파일 탐색기에서 APK를 직접 선택해 설치합니다.")
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
        startDownload();
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
            startDownload();
        } else {
            Toast.makeText(this, "다운로드 폴더 권한이 필요합니다.", Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}
