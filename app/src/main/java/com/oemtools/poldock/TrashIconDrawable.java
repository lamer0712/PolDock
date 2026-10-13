package com.oemtools.poldock;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** 바로가기 삭제 버튼용 휴지통 아이콘. */
final class TrashIconDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    TrashIconDrawable() {
        paint.setColor(0xFFFFFFFF);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float unit = Math.min(b.width(), b.height()) / 24f;
        float cx = b.exactCenterX();
        float cy = b.exactCenterY();
        paint.setStrokeWidth(Math.max(1f, 1.8f * unit));
        canvas.drawLine(cx - 8f * unit, cy - 7f * unit,
                cx + 8f * unit, cy - 7f * unit, paint);
        canvas.drawLine(cx - 3f * unit, cy - 10f * unit,
                cx + 3f * unit, cy - 10f * unit, paint);
        canvas.drawRoundRect(cx - 6f * unit, cy - 5f * unit,
                cx + 6f * unit, cy + 10f * unit, 1.5f * unit, 1.5f * unit, paint);
        canvas.drawLine(cx - 2f * unit, cy - 2f * unit,
                cx - 2f * unit, cy + 6f * unit, paint);
        canvas.drawLine(cx + 2f * unit, cy - 2f * unit,
                cx + 2f * unit, cy + 6f * unit, paint);
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
