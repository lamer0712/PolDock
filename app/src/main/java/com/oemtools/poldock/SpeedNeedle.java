package com.oemtools.poldock;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;

/** 속도계 바늘. 위(북쪽)를 가리키게 그리고, 뷰 회전으로 방향을 돌린다. */
public class SpeedNeedle extends Drawable {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Override
    public void draw(Canvas c) {
        int w = getBounds().width(), h = getBounds().height();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) * 0.40f;
        float wid = r * 0.055f;                  // 더 가늘게
        float tip = wid * 0.7f;                  // 끝을 뾰족하지 않게 살짝 평평하게

        p.setStyle(Paint.Style.FILL);
        p.setPathEffect(new android.graphics.CornerPathEffect(wid * 0.8f));  // 모서리 살짝 둥글게
        p.setColor(0xFFFF4500);                 // 붉은빛 오렌지 바늘
        Path path = new Path();
        path.moveTo(cx - tip, cy - r);           // 끝(위) — 평평한 면
        path.lineTo(cx - wid, cy);
        path.lineTo(cx - wid * 0.6f, cy + r * 0.28f);  // 짧은 꼬리
        path.lineTo(cx + wid * 0.6f, cy + r * 0.28f);
        path.lineTo(cx + wid, cy);
        path.lineTo(cx + tip, cy - r);
        path.close();
        c.drawPath(path, p);
        // 중심 핀(허브)은 가운데 글씨와 겹쳐서 제거함
    }

    @Override
    public void setAlpha(int alpha) {
        p.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(android.graphics.ColorFilter cf) {
        p.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return android.graphics.PixelFormat.TRANSLUCENT;
    }
}
