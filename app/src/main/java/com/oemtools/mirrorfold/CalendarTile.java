package com.oemtools.mirrorfold;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 원본 캘린더 타일. iOS 앱아이콘 기하(코너 반경 = 한 변의 22.37%)만 차용하고
 * 디자인은 자체 제작: 둥근 흰 타일 위에 요일(빨강, 상단) + 날짜 숫자(짙은색, 하단).
 */
public class CalendarTile extends Drawable {

    /** iOS 앱아이콘 코너 반경 비율 */
    public static final float CORNER_RATIO = 0.2237f;

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint weekPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String weekday = "";
    private String day = "";

    public CalendarTile() {
        bg.setColor(Color.WHITE);
        weekPaint.setColor(0xFFFF3B30);
        weekPaint.setTextAlign(Paint.Align.CENTER);
        weekPaint.setFakeBoldText(true);
        dayPaint.setColor(0xFF1C1C1E);
        dayPaint.setTextAlign(Paint.Align.CENTER);
        dayPaint.setFakeBoldText(true);
        setDate(new Date());
    }

    /** 날짜 변경 시 호출. 값이 바뀌면 true. */
    public boolean setDate(Date d) {
        String w = new SimpleDateFormat("E", Locale.getDefault()).format(d);
        String n = new SimpleDateFormat("d", Locale.getDefault()).format(d);
        if (w.equals(weekday) && n.equals(day)) return false;
        weekday = w;
        day = n;
        invalidateSelf();
        return true;
    }

    @Override
    public void draw(Canvas c) {
        RectF r = new RectF(getBounds());
        float side = Math.min(r.width(), r.height());
        float rad = side * CORNER_RATIO;
        c.drawRoundRect(r, rad, rad, bg);

        weekPaint.setTextSize(side * 0.30f);
        dayPaint.setTextSize(side * 0.56f);

        float cx = r.centerX();
        // 요일: 상단 약 30% 위치
        float wy = r.top + side * 0.32f - (weekPaint.ascent() + weekPaint.descent()) / 2f;
        c.drawText(weekday, cx, wy, weekPaint);
        // 날짜: 하단 약 65% 위치
        float dy = r.top + side * 0.66f - (dayPaint.ascent() + dayPaint.descent()) / 2f;
        c.drawText(day, cx, dy, dayPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        bg.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(android.graphics.ColorFilter cf) {
    }

    @Override
    public int getOpacity() {
        return android.graphics.PixelFormat.TRANSLUCENT;
    }
}
