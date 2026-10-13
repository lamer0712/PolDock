package com.oemtools.poldock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.view.View;

import java.util.Calendar;

/** 간단한 아날로그 시계 (시/분/초 바늘 + 눈금). 1초마다 자동 갱신. */
public class AnalogClockView extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int handColor = 0xFF1C1C1E;
    private int tickColor = 0xFF9AA0AA;
    private int accent = 0xFFFF4500;   // 초침
    private Handler handler;
    private final Runnable ticker = new Runnable() {
        public void run() {
            invalidate();
            if (handler != null) handler.postDelayed(this, 1000);
        }
    };

    public AnalogClockView(Context c) {
        super(c);
    }

    void setColors(int hand, int tick) {
        handColor = hand;
        tickColor = tick;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler = getHandler();
        if (handler == null) handler = new Handler();
        handler.post(ticker);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (handler != null) handler.removeCallbacks(ticker);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f * 0.92f;

        // 눈금 (12개)
        p.setColor(tickColor);
        for (int i = 0; i < 12; i++) {
            double a = Math.toRadians(i * 30);
            float sin = (float) Math.sin(a), cos = (float) -Math.cos(a);
            boolean major = (i % 3 == 0);
            float inner = r * (major ? 0.80f : 0.88f);
            p.setStrokeWidth(Math.max(2f, r * (major ? 0.06f : 0.035f)));
            p.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(cx + sin * inner, cy + cos * inner, cx + sin * r, cy + cos * r, p);
        }

        Calendar cal = Calendar.getInstance();
        float sec = cal.get(Calendar.SECOND);
        float min = cal.get(Calendar.MINUTE) + sec / 60f;
        float hour = (cal.get(Calendar.HOUR_OF_DAY) % 12) + min / 60f;

        // 시침
        p.setColor(handColor);
        p.setStrokeCap(Paint.Cap.ROUND);
        drawHand(c, cx, cy, hour * 30, r * 0.52f, Math.max(3f, r * 0.08f));
        // 분침
        drawHand(c, cx, cy, min * 6, r * 0.78f, Math.max(2.5f, r * 0.055f));
        // 초침
        p.setColor(accent);
        drawHand(c, cx, cy, sec * 6, r * 0.84f, Math.max(1.5f, r * 0.03f));

        // 중심점
        p.setColor(handColor);
        c.drawCircle(cx, cy, Math.max(2.5f, r * 0.05f), p);
    }

    private void drawHand(Canvas c, float cx, float cy, float deg, float len, float width) {
        double a = Math.toRadians(deg);
        float sin = (float) Math.sin(a), cos = (float) -Math.cos(a);
        p.setStrokeWidth(width);
        c.drawLine(cx - sin * len * 0.18f, cy - cos * len * 0.18f,
                cx + sin * len, cy + cos * len, p);
    }
}
