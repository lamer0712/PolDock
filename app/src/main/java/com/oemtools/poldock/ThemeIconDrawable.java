package com.oemtools.poldock;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** 위젯의 라이트/다크 테마 상태 아이콘. */
final class ThemeIconDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean dark;

    ThemeIconDrawable(boolean dark) {
        this.dark = dark;
        paint.setColor(0xFFFFFFFF);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float unit = Math.min(b.width(), b.height()) / 24f;
        float cx = b.exactCenterX();
        float cy = b.exactCenterY();
        if (dark) {
            Path moon = new Path();
            moon.addCircle(cx - unit, cy, 9f * unit, Path.Direction.CW);
            Path cutout = new Path();
            cutout.addCircle(cx + 4f * unit, cy - 3f * unit,
                    8f * unit, Path.Direction.CW);
            moon.op(cutout, Path.Op.DIFFERENCE);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawPath(moon, paint);
            return;
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, 1.8f * unit));
        canvas.drawCircle(cx, cy, 5f * unit, paint);
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI * i / 4.0;
            float sx = cx + (float) Math.cos(angle) * 8f * unit;
            float sy = cy + (float) Math.sin(angle) * 8f * unit;
            float ex = cx + (float) Math.cos(angle) * 11f * unit;
            float ey = cy + (float) Math.sin(angle) * 11f * unit;
            canvas.drawLine(sx, sy, ex, ey, paint);
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
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return 24;
    }

    @Override
    public int getIntrinsicHeight() {
        return 24;
    }
}
