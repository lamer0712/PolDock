package com.oemtools.poldock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** 작은 위젯 안에 현재 월과 날짜를 표시하는 7열 달력. */
final class MonthCalendarView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Calendar shown = Calendar.getInstance();
    private int foreground;
    private int label;
    private int accent;

    MonthCalendarView(Context context) {
        super(context);
        paint.setTextAlign(Paint.Align.CENTER);
    }

    void setColors(int foreground, int label, int accent) {
        this.foreground = foreground;
        this.label = label;
        this.accent = accent;
        invalidate();
    }

    void setDate(Date date) {
        shown.setTime(date);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0f || height <= 0f) return;

        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setColor(foreground);
        paint.setTextSize(height * 0.16f);
        paint.setTextAlign(Paint.Align.CENTER);
        String month = new SimpleDateFormat("M월", Locale.getDefault())
                .format(shown.getTime());
        drawCentered(canvas, month, width / 2f, height * 0.13f, paint);

        Calendar first = (Calendar) shown.clone();
        first.set(Calendar.DAY_OF_MONTH, 1);
        int startColumn = first.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY;
        int days = first.getActualMaximum(Calendar.DAY_OF_MONTH);
        int today = shown.get(Calendar.DAY_OF_MONTH);
        float gridTop = height * 0.25f;
        float cellWidth = width / 7f;
        float cellHeight = (height - gridTop) / 6f;
        paint.setTextSize(Math.min(cellWidth * 0.52f, cellHeight * 0.58f));

        for (int day = 1; day <= days; day++) {
            int index = startColumn + day - 1;
            int column = index % 7;
            int row = index / 7;
            float cx = (column + 0.5f) * cellWidth;
            float cy = gridTop + (row + 0.5f) * cellHeight;

            if (day == today) {
                paint.setColor(accent);
                float radius = Math.min(cellWidth, cellHeight) * 0.43f;
                canvas.drawOval(new RectF(cx - radius, cy - radius,
                        cx + radius, cy + radius), paint);
                paint.setColor(0xFFFFFFFF);
            } else {
                paint.setColor(column == 0 ? 0xFFFF5A52 : (column == 6 ? accent : label));
            }
            drawCentered(canvas, String.valueOf(day), cx, cy, paint);
        }
    }

    private static void drawCentered(Canvas canvas, String text, float x, float y, Paint paint) {
        canvas.drawText(text, x, y - (paint.ascent() + paint.descent()) / 2f, paint);
    }
}
