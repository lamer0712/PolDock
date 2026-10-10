package com.oemtools.mirrorfold;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;

/** 원형 속도계 눈금. SW(225°)=0, N(360°)=100, SE(495°)=200. 회전하지 않음. */
public class SpeedGauge extends Drawable {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Override
    public void draw(Canvas c) {
        int w = getBounds().width(), h = getBounds().height();
        float cx = w / 2f, cy = h / 2f;
        float R = Math.min(w, h) * 0.46f;

        for (int t = 0; t <= 200; t += 20) {
            double bearing = Math.toRadians(225 + t * 1.35);   // N=0, 시계방향
            float sx = (float) Math.sin(bearing), cyv = (float) -Math.cos(bearing);
            boolean major = (t % 100 == 0);                    // 0/100/200
            float inner = R * (major ? 0.74f : 0.84f);
            p.setStrokeWidth(Math.max(2f, R * (major ? 0.07f : 0.04f)));
            p.setColor(major ? 0xFF555A63 : 0xFFAAB0BA);
            c.drawLine(cx + sx * inner, cy + cyv * inner,
                    cx + sx * R, cy + cyv * R, p);
        }
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
