package com.oemtools.poldock;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Compact cloud-download mark used when a newer PolDock release is available. */
final class UpdateIconDrawable extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    UpdateIconDrawable() {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float size = Math.min(b.width(), b.height());
        float left = b.centerX() - size / 2f;
        float top = b.centerY() - size / 2f;

        paint.setColor(0xFF67B7FF);
        paint.setStrokeWidth(size * 0.075f);
        path.reset();
        path.moveTo(left + size * 0.25f, top + size * 0.67f);
        path.cubicTo(left + size * 0.10f, top + size * 0.67f,
                left + size * 0.08f, top + size * 0.43f,
                left + size * 0.27f, top + size * 0.39f);
        path.cubicTo(left + size * 0.34f, top + size * 0.16f,
                left + size * 0.68f, top + size * 0.16f,
                left + size * 0.74f, top + size * 0.42f);
        path.cubicTo(left + size * 0.94f, top + size * 0.44f,
                left + size * 0.94f, top + size * 0.67f,
                left + size * 0.76f, top + size * 0.67f);
        canvas.drawPath(path, paint);

        paint.setColor(0xFFFFFFFF);
        paint.setStrokeWidth(size * 0.08f);
        float cx = left + size * 0.51f;
        canvas.drawLine(cx, top + size * 0.43f, cx, top + size * 0.83f, paint);
        canvas.drawLine(cx, top + size * 0.83f,
                left + size * 0.37f, top + size * 0.68f, paint);
        canvas.drawLine(cx, top + size * 0.83f,
                left + size * 0.65f, top + size * 0.68f, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
