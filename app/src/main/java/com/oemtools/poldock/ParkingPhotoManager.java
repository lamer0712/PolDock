package com.oemtools.poldock;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** D/R → P 전환 시 Camera2 ID 0을 촬영해 MYBOX로 전송한다. */
final class ParkingPhotoManager {
    static final String KEY_STATUS = "parking_photo_status";
    private static final String TAG = "ParkingPhoto";
    private static final String CAMERA_ID = "0";
    private static final long CAPTURE_TIMEOUT_MS = 12_000L;
    private static final long MAX_PREFERRED_PIXELS = 4_000_000L;

    private final Context context;
    private final AtomicBoolean busy = new AtomicBoolean();
    private HandlerThread thread;
    private Handler handler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader imageReader;
    private boolean imageHandled;

    ParkingPhotoManager(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean isBusy() {
        return busy.get();
    }

    void captureAndUpload() {
        if (!busy.compareAndSet(false, true)) return;
        ensureThread();
        handler.post(this::beginCapture);
    }

    void stop() {
        Handler localHandler = handler;
        HandlerThread localThread = thread;
        handler = null;
        thread = null;
        if (localHandler != null) localHandler.post(this::closeCameraResources);
        if (localThread != null) localThread.quitSafely();
        busy.set(false);
    }

    static boolean isInternalPreference(String key) {
        return KEY_STATUS.equals(key) || SecureTokenStore.isInternalPreference(key);
    }

    static boolean isParkingTransition(String previousGear, String currentGear) {
        return "P".equals(currentGear)
                && ("D".equals(previousGear) || "R".equals(previousGear));
    }

    static String captureFileName(Date capturedAt) {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(capturedAt) + ".png";
    }

    private synchronized void ensureThread() {
        if (thread != null && thread.isAlive() && handler != null) return;
        thread = new HandlerThread("parking-photo");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @SuppressLint("MissingPermission")
    private void beginCapture() {
        String token = SecureTokenStore.get(context);
        if (token.isEmpty()) {
            fail("MYBOX 토큰을 먼저 저장하세요", null);
            return;
        }
        if (context.checkCallingOrSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            fail("카메라 권한이 없습니다", null);
            return;
        }
        updateStatus("카메라 준비 중…");
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null || !Arrays.asList(manager.getCameraIdList()).contains(CAMERA_ID)) {
                startScreenCaptureFallback();
                return;
            }
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(CAMERA_ID);
            android.hardware.camera2.params.StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.JPEG);
            if (sizes == null || sizes.length == 0) {
                startScreenCaptureFallback();
                return;
            }
            Size size = chooseSize(sizes);
            imageReader = ImageReader.newInstance(
                    size.getWidth(), size.getHeight(), ImageFormat.JPEG, 2);
            imageHandled = false;
            imageReader.setOnImageAvailableListener(this::onImageAvailable, handler);
            manager.openCamera(CAMERA_ID, cameraCallback, handler);
            handler.postDelayed(timeout, CAPTURE_TIMEOUT_MS);
        } catch (Exception e) {
            fail("카메라를 열 수 없습니다", e);
        }
    }

    /** 카메라가 없는 에뮬레이터에서는 사용자 승인 후 현재 화면을 대신 전송한다. */
    private void startScreenCaptureFallback() {
        closeCameraResources();
        busy.set(false);
        updateStatus("카메라 없음 · 화면 캡처 권한 대기…");
        try {
            if (!ScreenCaptureActivity.request(context)) {
                updateStatus("화면 캡처 권한 창이 이미 열려 있습니다");
            }
        } catch (RuntimeException e) {
            fail("화면 캡처를 시작할 수 없습니다", e);
        }
    }

    private final CameraDevice.StateCallback cameraCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice opened) {
            camera = opened;
            try {
                opened.createCaptureSession(
                        java.util.Collections.singletonList(imageReader.getSurface()),
                        sessionCallback, handler);
            } catch (Exception e) {
                fail("카메라 촬영 준비에 실패했습니다", e);
            }
        }

        @Override
        public void onDisconnected(CameraDevice disconnected) {
            disconnected.close();
            if (camera == disconnected) camera = null;
            fail("카메라 연결이 끊겼습니다", null);
        }

        @Override
        public void onError(CameraDevice failedCamera, int error) {
            failedCamera.close();
            if (camera == failedCamera) camera = null;
            fail("카메라 오류 " + error, null);
        }
    };

    private final CameraCaptureSession.StateCallback sessionCallback =
            new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession configured) {
                    if (!busy.get() || camera == null || imageReader == null) {
                        configured.close();
                        return;
                    }
                    session = configured;
                    try {
                        CaptureRequest.Builder request =
                                camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                        request.addTarget(imageReader.getSurface());
                        request.set(CaptureRequest.CONTROL_MODE,
                                CaptureRequest.CONTROL_MODE_AUTO);
                        updateStatus("촬영 중…");
                        configured.capture(request.build(), null, handler);
                    } catch (CameraAccessException e) {
                        fail("사진 촬영에 실패했습니다", e);
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession failedSession) {
                    fail("Camera ID 0 촬영 세션을 만들 수 없습니다", null);
                }
            };

    private final Runnable timeout = () -> {
        if (busy.get() && !imageHandled) fail("카메라 응답 시간이 초과됐습니다", null);
    };

    private void onImageAvailable(ImageReader reader) {
        if (imageHandled || !busy.get()) {
            discardLatest(reader);
            return;
        }
        Image image = null;
        File photo = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            imageHandled = true;
            if (handler != null) handler.removeCallbacks(timeout);
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] jpeg = new byte[buffer.remaining()];
            buffer.get(jpeg);
            image.close();
            image = null;
            String fileName = captureFileName(new Date());
            File directory = new File(context.getCacheDir(), "parking-photos");
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IllegalStateException("임시 폴더 생성 실패");
            }
            photo = new File(directory, fileName);
            try (FileOutputStream output = new FileOutputStream(photo)) {
                Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                try {
                    if (bitmap == null
                            || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw new IllegalStateException("PNG 변환 실패");
                    }
                } finally {
                    if (bitmap != null) bitmap.recycle();
                }
            }
            closeCameraResources();
            updateStatus("MYBOX 전송 중…");
            MyBoxUploader.upload(photo, fileName, SecureTokenStore.get(context));
            updateStatus("전송 완료 · " + new SimpleDateFormat(
                    "M/d HH:mm", Locale.KOREA).format(new Date()));
            busy.set(false);
        } catch (Exception e) {
            fail(messageOf(e), e);
        } finally {
            if (image != null) image.close();
            if (photo != null && photo.exists() && !photo.delete()) {
                Log.w(TAG, "임시 주차 사진을 삭제하지 못했습니다: " + photo.getName());
            }
        }
    }

    private void fail(String message, Throwable error) {
        if (handler != null) handler.removeCallbacks(timeout);
        closeCameraResources();
        busy.set(false);
        updateStatus("실패 · " + message);
        if (error != null) Log.w(TAG, message, error);
        else Log.w(TAG, message);
    }

    private void updateStatus(String status) {
        setStatus(context, status);
    }

    static void setStatus(Context context, String status) {
        context.getSharedPreferences(ShortcutStore.PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY_STATUS, status).apply();
    }

    private void closeCameraResources() {
        if (session != null) {
            try { session.close(); } catch (Exception ignored) { }
            session = null;
        }
        if (camera != null) {
            try { camera.close(); } catch (Exception ignored) { }
            camera = null;
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Exception ignored) { }
            imageReader = null;
        }
    }

    private static Size chooseSize(Size[] sizes) {
        Size best = null;
        Size smallest = null;
        for (Size size : sizes) {
            long pixels = (long) size.getWidth() * size.getHeight();
            if (smallest == null || pixels < (long) smallest.getWidth() * smallest.getHeight()) {
                smallest = size;
            }
            if (pixels <= MAX_PREFERRED_PIXELS
                    && (best == null || pixels > (long) best.getWidth() * best.getHeight())) {
                best = size;
            }
        }
        return best != null ? best : smallest;
    }

    private static void discardLatest(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
        } catch (Exception ignored) {
        } finally {
            if (image != null) image.close();
        }
    }

    private static String messageOf(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return "알 수 없는 오류";
        return message.length() > 100 ? message.substring(0, 100) : message;
    }
}
