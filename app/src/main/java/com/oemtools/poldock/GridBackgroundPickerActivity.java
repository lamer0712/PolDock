package com.oemtools.poldock;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** 사진 선택 결과를 앱 내부 파일로 복사한 뒤 그리드 배경으로 적용한다. */
public class GridBackgroundPickerActivity extends Activity {
    private static final int REQUEST_IMAGE = 701;
    private static final String ACTION_PICK_IMAGES = "android.provider.action.PICK_IMAGES";
    private static final String CAR_FRAMEWORK_STUBS = "com.android.car.frameworkpackagestubs";
    static final String PHOTO_FILE = "grid_background_image";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state == null) openPicker();
    }

    private void openPicker() {
        if (launchPicker(new Intent(ACTION_PICK_IMAGES).setType("image/*"))) return;

        if (launchPicker(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*"))) return;

        if (launchPicker(new Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*"))) return;

        Toast.makeText(this, "사진 선택 앱을 찾을 수 없습니다", Toast.LENGTH_LONG).show();
        returnToEditor();
        finish();
    }

    private boolean launchPicker(Intent intent) {
        ResolveInfo resolved = getPackageManager().resolveActivity(
                intent, PackageManager.MATCH_DEFAULT_ONLY);
        ActivityInfo activity = resolved != null ? resolved.activityInfo : null;
        if (activity == null || CAR_FRAMEWORK_STUBS.equals(activity.packageName)
                || activity.name == null || activity.name.contains("Stub")) {
            return false;
        }
        intent.setComponent(new ComponentName(activity.packageName, activity.name));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_IMAGE);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK || data == null) {
            returnToEditor();
            finish();
            return;
        }
        Uri uri = data.getData();
        if (uri == null || !copyImage(uri)) {
            Toast.makeText(this, "사진을 불러오지 못했습니다", Toast.LENGTH_LONG).show();
            returnToEditor();
            finish();
            return;
        }
        getSharedPreferences(ShortcutStore.PREF, MODE_PRIVATE).edit()
                .putString(ShortcutStore.KEY_GRID_BG_MODE, "photo")
                .apply();
        returnToEditor();
        finish();
    }

    private void returnToEditor() {
        sendBroadcast(new Intent(OverlayService.ACTION_GRID_BACKGROUND_CHANGED)
                .setPackage(getPackageName()));
    }

    private boolean copyImage(Uri uri) {
        File target = new File(getFilesDir(), PHOTO_FILE);
        File pending = new File(getFilesDir(), PHOTO_FILE + ".pending");
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(pending)) {
            if (in == null) return false;
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (read > 0) out.write(buffer, 0, read);
            }
            out.getFD().sync();
            if (target.exists() && !target.delete()) return false;
            return pending.renameTo(target);
        } catch (Throwable ignored) {
            pending.delete();
            return false;
        }
    }
}
