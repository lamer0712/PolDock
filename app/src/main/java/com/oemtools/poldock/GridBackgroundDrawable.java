package com.oemtools.poldock;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import java.io.File;

/** 큰 사진을 적당히 축소해 읽고 그리드 전체에 중앙 크롭으로 그린다. */
final class GridBackgroundDrawable extends Drawable {
    private static final int MAX_DECODE_SIDE = 2048;
    private final Bitmap bitmap;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect source = new Rect();
    private final RectF destination = new RectF();

    private GridBackgroundDrawable(Bitmap bitmap) {
        this.bitmap = bitmap;
    }

    static GridBackgroundDrawable load(File file) {
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
        return bitmap == null ? null : new GridBackgroundDrawable(bitmap);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;
        canvas.drawColor(ShortcutStore.DEFAULT_GRID_BG_COLOR);

        int sourceSide = Math.min(bitmap.getWidth(), bitmap.getHeight());
        if (bitmap.getWidth() > bitmap.getHeight()) {
            int left = (bitmap.getWidth() - sourceSide) / 2;
            source.set(left, 0, left + sourceSide, sourceSide);
        } else {
            int top = (bitmap.getHeight() - sourceSide) / 2;
            source.set(0, top, sourceSide, top + sourceSide);
        }

        int side = Math.min(bounds.width(), bounds.height());
        float left = bounds.left + (bounds.width() - side) / 2f;
        destination.set(left, bounds.bottom - side, left + side, bounds.bottom);
        canvas.drawBitmap(bitmap, source, destination, paint);
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
