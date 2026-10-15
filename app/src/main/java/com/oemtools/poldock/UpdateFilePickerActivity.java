package com.oemtools.poldock;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

/** 첨부 파일 선택 화면에서 APK를 고른 뒤 시스템 설치 화면으로 넘긴다. */
public class UpdateFilePickerActivity extends Activity {
    private static final int REQUEST_APK = 501;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state == null) openFilePicker();
    }

    private void openFilePicker() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(UpdateManager.APK_MIME);
        try {
            startActivityForResult(picker, REQUEST_APK);
        } catch (RuntimeException first) {
            try {
                picker.setAction(Intent.ACTION_GET_CONTENT);
                startActivityForResult(picker, REQUEST_APK);
            } catch (RuntimeException second) {
                Toast.makeText(this, "파일 선택 앱을 찾을 수 없습니다.", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_APK) return;
        Uri apk = resultCode == RESULT_OK && data != null ? data.getData() : null;
        if (apk != null) {
            Intent install = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(apk, UpdateManager.APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(install);
            } catch (RuntimeException e) {
                Toast.makeText(this, "선택한 APK를 열 수 없습니다.", Toast.LENGTH_LONG).show();
            }
        }
        finish();
    }
}
