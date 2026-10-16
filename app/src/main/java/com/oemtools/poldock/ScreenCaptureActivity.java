package com.oemtools.poldock;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** 카메라가 없는 기기에서 사용자 승인 후 현재 화면을 PNG로 캡처해 MYBOX로 전송한다. */
public class ScreenCaptureActivity extends Activity {
    private static final int REQUEST_SCREEN_CAPTURE = 601;
    private static final long CAPTURE_TIMEOUT_MS = 8_000L;
    private static final AtomicBoolean REQUEST_ACTIVE = new AtomicBoolean(false);

    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private HandlerThread workerThread;
    private Handler workerHandler;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;

    static boolean request(Context context) {
        if (!REQUEST_ACTIVE.compareAndSet(false, true)) return false;
        try {
            context.startActivity(new Intent(context, ScreenCaptureActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            REQUEST_ACTIVE.set(false);
            throw e;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            fail("화면 캡처 권한을 다시 요청해 주세요", null);
            return;
        }
        MediaProjectionManager manager = (MediaProjectionManager)
                getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            fail("화면 캡처 서비스를 사용할 수 없습니다", null);
            return;
        }
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE);
        } catch (RuntimeException e) {
            fail("화면 캡처 권한 창을 열 수 없습니다", e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SCREEN_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            fail("화면 캡처 권한이 취소됐습니다", null);
            return;
        }
        MediaProjectionManager manager = (MediaProjectionManager)
                getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = manager == null ? null : manager.getMediaProjection(resultCode, data);
        if (projection == null) {
            fail("화면 캡처 권한을 사용할 수 없습니다", null);
            return;
        }
        startCapture();
    }

    @SuppressWarnings("deprecation")
    private void startCapture() {
        Display display = getWindowManager().getDefaultDisplay();
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        int width = Math.max(1, metrics.widthPixels);
        int height = Math.max(1, metrics.heightPixels);

        workerThread = new HandlerThread("parking-screen-capture");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(
                reader -> handleImage(reader, width, height), workerHandler);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                if (!completed.get()) fail("화면 캡처가 중단됐습니다", null);
            }
        }, mainHandler);
        virtualDisplay = projection.createVirtualDisplay(
                "PolDock parking screenshot", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, workerHandler);
        ParkingPhotoManager.setStatus(this, "화면 캡처 중…");
        mainHandler.postDelayed(timeout, CAPTURE_TIMEOUT_MS);
    }

    private final Runnable timeout = () -> fail("화면 캡처 응답 시간이 초과됐습니다", null);

    private void handleImage(ImageReader reader, int width, int height) {
        if (completed.get()) {
            discardLatest(reader);
            return;
        }
        Image image = null;
        Bitmap padded = null;
        Bitmap screenshot = null;
        File photo = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int paddedWidth = width + Math.max(0, rowStride - pixelStride * width) / pixelStride;
            padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            screenshot = Bitmap.createBitmap(padded, 0, 0, width, height);

            String fileName = ParkingPhotoManager.captureFileName(new Date());
            File directory = new File(getCacheDir(), "parking-photos");
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IllegalStateException("임시 폴더 생성 실패");
            }
            photo = new File(directory, fileName);
            try (FileOutputStream output = new FileOutputStream(photo)) {
                if (!screenshot.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IllegalStateException("PNG 저장 실패");
                }
            }
            ParkingPhotoManager.setStatus(this, "MYBOX 전송 중…");
            MyBoxUploader.upload(photo, fileName, SecureTokenStore.get(this));
            complete("전송 완료 · " + new SimpleDateFormat(
                    "M/d HH:mm", Locale.KOREA).format(new Date()));
        } catch (Exception e) {
            fail(messageOf(e), e);
        } finally {
            if (image != null) image.close();
            if (padded != null) padded.recycle();
            if (screenshot != null) screenshot.recycle();
            if (photo != null && photo.exists()) photo.delete();
        }
    }

    private void complete(String status) {
        if (!completed.compareAndSet(false, true)) return;
        mainHandler.removeCallbacks(timeout);
        ParkingPhotoManager.setStatus(this, status);
        cleanup();
        mainHandler.post(this::finish);
    }

    private void fail(String message, Throwable error) {
        if (!completed.compareAndSet(false, true)) return;
        mainHandler.removeCallbacks(timeout);
        ParkingPhotoManager.setStatus(this, "실패 · " + message);
        if (error != null) android.util.Log.w("ParkingPhoto", message, error);
        else android.util.Log.w("ParkingPhoto", message);
        cleanup();
        mainHandler.post(this::finish);
    }

    private synchronized void cleanup() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
            workerHandler = null;
        }
        REQUEST_ACTIVE.set(false);
    }

    private static void discardLatest(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
        } catch (RuntimeException ignored) {
        } finally {
            if (image != null) image.close();
        }
    }

    private static String messageOf(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return "알 수 없는 오류";
        return message.length() > 100 ? message.substring(0, 100) : message;
    }

    @Override
    protected void onDestroy() {
        if (isFinishing() && !completed.get()) fail("화면 캡처가 중단됐습니다", null);
        super.onDestroy();
    }
}
