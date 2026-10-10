package com.oemtools.mirrorfold;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * 원본 컬러 날씨 그림. 흰 타일 위에서 또렷하게: 맑음=노란 해, 흐림=회색 구름,
 * 비=파란 빗방울, 눈=하늘색 눈송이, 안개=회색 줄, 뇌우=구름+노란 번개.
 */
public class WeatherIcon extends Drawable {

    public enum Kind {CLEAR, PARTLY, CLOUD, RAIN, SNOW, FOG, STORM}

    private static final int SUN = 0xFFFFC400;
    private static final int CLOUD = 0xFFC2C8D0;
    private static final int RAIN = 0xFF3B82F6;
    private static final int SNOW = 0xFF8FD3F4;
    private static final int FOG = 0xFFAAB2BD;

    private final Kind kind;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    public WeatherIcon(Kind kind) {
        this.kind = kind;
    }

    public WeatherIcon(Kind kind, int ignoredColor) {
        this(kind);
    }

    public static Kind fromCode(int code) {
        if (code == 0) return Kind.CLEAR;
        if (code <= 2) return Kind.PARTLY;
        if (code == 3) return Kind.CLOUD;
        if (code >= 45 && code <= 48) return Kind.FOG;
        if (code >= 51 && code <= 67) return Kind.RAIN;
        if (code >= 71 && code <= 77) return Kind.SNOW;
        if (code >= 80 && code <= 82) return Kind.RAIN;
        if (code >= 95) return Kind.STORM;
        return Kind.CLOUD;
    }

    @Override
    public void draw(Canvas c) {
        int w = getBounds().width(), h = getBounds().height();
        float u = Math.min(w, h);
        float cx = w / 2f, cy = h / 2f;

        switch (kind) {
            case CLEAR:
                sun(c, cx, cy, u * 0.22f, u * 0.44f);
                break;
            case PARTLY:
                sun(c, cx - u * 0.16f, cy - u * 0.18f, u * 0.16f, u * 0.30f);
                cloud(c, cx + u * 0.08f, cy + u * 0.10f, u * 0.56f);
                break;
            case CLOUD:
                cloud(c, cx, cy, u * 0.66f);
                break;
            case RAIN:
                cloud(c, cx, cy - u * 0.12f, u * 0.60f);
                drops(c, cx, cy + u * 0.26f, u, RAIN);
                break;
            case SNOW:
                cloud(c, cx, cy - u * 0.12f, u * 0.60f);
                flakes(c, cx, cy + u * 0.28f, u);
                break;
            case FOG:
                fogLines(c, cx, cy, u);
                break;
            case STORM:
                cloud(c, cx, cy - u * 0.12f, u * 0.60f);
                bolt(c, cx, cy + u * 0.22f, u);
                break;
        }
    }

    private void sun(Canvas c, float cx, float cy, float r, float rayOuter) {
        p.setColor(SUN);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(r * 0.32f);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float x1 = cx + (float) Math.cos(a) * (r + rayOuter * 0.30f);
            float y1 = cy + (float) Math.sin(a) * (r + rayOuter * 0.30f);
            float x2 = cx + (float) Math.cos(a) * rayOuter;
            float y2 = cy + (float) Math.sin(a) * rayOuter;
            c.drawLine(x1, y1, x2, y2, p);
        }
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, r, p);
    }

    private void cloud(Canvas c, float cx, float cy, float width) {
        float r = width * 0.30f;
        p.setColor(CLOUD);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx - r * 0.95f, cy, r * 0.82f, p);
        c.drawCircle(cx + r * 0.78f, cy, r * 0.72f, p);
        c.drawCircle(cx - r * 0.02f, cy - r * 0.58f, r, p);
        RectF base = new RectF(cx - r * 1.65f, cy - r * 0.1f, cx + r * 1.5f, cy + r * 0.95f);
        c.drawRoundRect(base, r * 0.5f, r * 0.5f, p);
    }

    private void drops(Canvas c, float cx, float cy, float u, int color) {
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(u * 0.06f);
        for (int i = -1; i <= 1; i++) {
            float x = cx + i * u * 0.22f;
            c.drawLine(x + u * 0.03f, cy, x - u * 0.05f, cy + u * 0.17f, p);
        }
    }

    private void flakes(Canvas c, float cx, float cy, float u) {
        p.setColor(SNOW);
        p.setStyle(Paint.Style.FILL);
        for (int i = -1; i <= 1; i++) {
            c.drawCircle(cx + i * u * 0.22f, cy + u * 0.03f, u * 0.055f, p);
        }
    }

    private void fogLines(Canvas c, float cx, float cy, float u) {
        p.setColor(FOG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(u * 0.09f);
        for (int i = -1; i <= 2; i++) {
            float y = cy + i * u * 0.19f;
            c.drawLine(cx - u * 0.36f, y, cx + u * 0.36f, y, p);
        }
    }

    private void bolt(Canvas c, float cx, float cy, float u) {
        p.setColor(SUN);
        p.setStyle(Paint.Style.FILL);
        Path path = new Path();
        path.moveTo(cx + u * 0.05f, cy - u * 0.04f);
        path.lineTo(cx - u * 0.11f, cy + u * 0.16f);
        path.lineTo(cx + u * 0.0f, cy + u * 0.16f);
        path.lineTo(cx - u * 0.07f, cy + u * 0.34f);
        path.lineTo(cx + u * 0.15f, cy + u * 0.08f);
        path.lineTo(cx + u * 0.03f, cy + u * 0.08f);
        path.close();
        c.drawPath(path, p);
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
