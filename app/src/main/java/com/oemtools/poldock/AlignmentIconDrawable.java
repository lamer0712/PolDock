package com.oemtools.poldock;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** 사진이 프레임 안에서 정렬되는 위치를 보여주는 아이콘. */
final class AlignmentIconDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String axis;
    private final String alignment;

    AlignmentIconDrawable(String axis, String alignment) {
        this.axis = axis;
        this.alignment = alignment;
        paint.setColor(0xFFFFFFFF);
        paint.setStrokeWidth(2f);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float unit = Math.min(b.width(), b.height()) / 28f;
        float left = b.centerX() - 14f * unit;
        float top = b.centerY() - 11f * unit;
        float right = b.centerX() + 14f * unit;
        float bottom = b.centerY() + 11f * unit;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, 1.6f * unit));
        canvas.drawRoundRect(left, top, right, bottom, 2f * unit, 2f * unit, paint);

        paint.setStyle(Paint.Style.FILL);
        if ("x".equals(axis)) {
            float blockWidth = 7f * unit;
            float x = left + 3f * unit;
            if ("center".equals(alignment)) x = b.centerX() - blockWidth / 2f;
            if ("right".equals(alignment)) x = right - 3f * unit - blockWidth;
            canvas.drawRoundRect(x, top + 4f * unit, x + blockWidth,
                    bottom - 4f * unit, 1.5f * unit, 1.5f * unit, paint);
        } else {
            float blockHeight = 6f * unit;
            float y = top + 3f * unit;
            if ("center".equals(alignment)) y = b.centerY() - blockHeight / 2f;
            if ("bottom".equals(alignment)) y = bottom - 3f * unit - blockHeight;
            canvas.drawRoundRect(left + 5f * unit, y, right - 5f * unit,
                    y + blockHeight, 1.5f * unit, 1.5f * unit, paint);
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
        return 32;
    }

    @Override
    public int getIntrinsicHeight() {
        return 28;
    }
}
