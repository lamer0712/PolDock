package com.oemtools.poldock;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** 화면에서 그리드 독이 차지하는 영역을 보여주는 아이콘. */
final class GridPositionIconDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String position;

    GridPositionIconDrawable(String position) {
        this.position = position;
        paint.setColor(0xFFFFFFFF);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float unit = Math.min(b.width(), b.height()) / 28f;
        float left = b.centerX() - 14f * unit;
        float top = b.centerY() - 11f * unit;
        float right = b.centerX() + 14f * unit;
        float bottom = b.centerY() + 11f * unit;
        float centerX = (left + right) / 2f;
        float middle = (top + bottom) / 2f;
        float inset = 2.5f * unit;
        float dockLeft = centerX + inset;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, 1.6f * unit));
        canvas.drawRoundRect(left, top, right, bottom, 2f * unit, 2f * unit, paint);
        boolean topFull = "top_full".equals(position);
        boolean bottomFull = "bottom_full".equals(position);
        canvas.drawLine(centerX, topFull ? middle : top, centerX, bottomFull ? middle : bottom, paint);
        canvas.drawLine(left, middle, "right".equals(position) ? centerX : right, middle, paint);

        paint.setStyle(Paint.Style.FILL);
        if (topFull) {
            canvas.drawRoundRect(left + inset, top + inset, right - inset, middle - inset,
                    unit, unit, paint);
        } else if (bottomFull) {
            canvas.drawRoundRect(left + inset, middle + inset, right - inset, bottom - inset,
                    unit, unit, paint);
        } else if ("top".equals(position)) {
            canvas.drawRoundRect(dockLeft, top + inset, right - inset, middle - inset,
                    unit, unit, paint);
        } else if ("bottom".equals(position)) {
            canvas.drawRoundRect(dockLeft, middle + inset, right - inset, bottom - inset,
                    unit, unit, paint);
        } else {
            canvas.drawRoundRect(dockLeft, top + inset, right - inset, bottom - inset,
                    unit, unit, paint);
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
