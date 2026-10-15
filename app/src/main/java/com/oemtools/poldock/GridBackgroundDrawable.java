package com.oemtools.poldock;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import java.io.File;

/** 그리드 사진의 크기, 정렬, 여백 배경을 설정에 맞춰 그린다. */
final class GridBackgroundDrawable extends Drawable {
    private static final int MAX_DECODE_SIDE = 2048;
    private static final int MAX_BLUR_SIDE = 128;
    private final Bitmap bitmap;
    private final int originalWidth;
    private final int originalHeight;
    private final String sizeMode;
    private final String alignX;
    private final String alignY;
    private final String backdrop;
    private final int backgroundColor;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect source = new Rect();
    private final RectF destination = new RectF();
    private Bitmap blurredBitmap;

    private GridBackgroundDrawable(Bitmap bitmap, int originalWidth, int originalHeight,
                                   String sizeMode, String alignX, String alignY,
                                   String backdrop, int backgroundColor) {
        this.bitmap = bitmap;
        this.originalWidth = originalWidth;
        this.originalHeight = originalHeight;
        this.sizeMode = sizeMode;
        this.alignX = alignX;
        this.alignY = alignY;
        this.backdrop = backdrop;
        this.backgroundColor = backgroundColor;
    }

    static GridBackgroundDrawable load(File file, String sizeMode, String alignX,
                                       String alignY, String backdrop, int backgroundColor,
                                       int rotation) {
        if (file == null || !file.isFile()) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample)
                > MAX_DECODE_SIDE) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        int originalWidth = bounds.outWidth;
        int originalHeight = bounds.outHeight;
        int normalizedRotation = ((rotation % 360) + 360) % 360;
        if (bitmap != null && normalizedRotation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(normalizedRotation);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) bitmap.recycle();
            bitmap = rotated;
            if (normalizedRotation == 90 || normalizedRotation == 270) {
                int swap = originalWidth;
                originalWidth = originalHeight;
                originalHeight = swap;
            }
        }
        return bitmap == null ? null : new GridBackgroundDrawable(bitmap,
                originalWidth, originalHeight, sizeMode, alignX, alignY,
                backdrop, backgroundColor);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;
        int save = canvas.save();
        canvas.clipRect(bounds);
        canvas.drawColor(backgroundColor);
        if ("blur".equals(backdrop)) {
            Bitmap blurred = getBlurredBitmap();
            if (blurred != null) {
                drawScaled(canvas, blurred, bounds, "fill", "center", "center",
                        blurred.getWidth(), blurred.getHeight());
            }
        }
        drawScaled(canvas, bitmap, bounds, sizeMode, alignX, alignY,
                originalWidth, originalHeight);
        canvas.restoreToCount(save);
    }

    private void drawScaled(Canvas canvas, Bitmap image, Rect bounds, String mode,
                            String horizontal, String vertical, int naturalWidth,
                            int naturalHeight) {
        source.set(0, 0, image.getWidth(), image.getHeight());
        float width;
        float height;
        if ("original".equals(mode)) {
            width = naturalWidth;
            height = naturalHeight;
        } else {
            float scaleX = bounds.width() / (float) naturalWidth;
            float scaleY = bounds.height() / (float) naturalHeight;
            float scale = "fill".equals(mode)
                    ? Math.max(scaleX, scaleY) : Math.min(scaleX, scaleY);
            width = naturalWidth * scale;
            height = naturalHeight * scale;
        }
        float left = aligned(bounds.left, bounds.width(), width, horizontal,
                "left", "right");
        float top = aligned(bounds.top, bounds.height(), height, vertical,
                "top", "bottom");
        destination.set(left, top, left + width, top + height);
        canvas.drawBitmap(image, source, destination, paint);
    }

    private static float aligned(float start, float available, float content, String alignment,
                                 String startValue, String endValue) {
        if (startValue.equals(alignment)) return start;
        if (endValue.equals(alignment)) return start + available - content;
        return start + (available - content) / 2f;
    }

    private Bitmap getBlurredBitmap() {
        if (blurredBitmap != null) return blurredBitmap;
        int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
        float scale = Math.min(1f, MAX_BLUR_SIDE / (float) longest);
        int width = Math.max(1, Math.round(bitmap.getWidth() * scale));
        int height = Math.max(1, Math.round(bitmap.getHeight() * scale));
        Bitmap small = Bitmap.createScaledBitmap(bitmap, width, height, true);
        Bitmap mutable = small.copy(Bitmap.Config.ARGB_8888, true);
        if (small != bitmap && small != mutable) small.recycle();
        if (mutable == null) return null;

        int[] pixels = new int[width * height];
        int[] output = new int[pixels.length];
        mutable.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int pass = 0; pass < 2; pass++) {
            boxBlur(pixels, output, width, height, 5);
            int[] swap = pixels;
            pixels = output;
            output = swap;
        }
        mutable.setPixels(pixels, 0, width, 0, 0, width, height);
        blurredBitmap = mutable;
        return blurredBitmap;
    }

    private static void boxBlur(int[] input, int[] output, int width, int height, int radius) {
        for (int y = 0; y < height; y++) {
            int minY = Math.max(0, y - radius);
            int maxY = Math.min(height - 1, y + radius);
            for (int x = 0; x < width; x++) {
                int minX = Math.max(0, x - radius);
                int maxX = Math.min(width - 1, x + radius);
                long a = 0, r = 0, g = 0, b = 0;
                int count = 0;
                for (int yy = minY; yy <= maxY; yy++) {
                    int offset = yy * width;
                    for (int xx = minX; xx <= maxX; xx++) {
                        int color = input[offset + xx];
                        a += color >>> 24;
                        r += (color >>> 16) & 0xFF;
                        g += (color >>> 8) & 0xFF;
                        b += color & 0xFF;
                        count++;
                    }
                }
                output[y * width + x] = ((int) (a / count) << 24)
                        | ((int) (r / count) << 16)
                        | ((int) (g / count) << 8)
                        | (int) (b / count);
            }
        }
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
