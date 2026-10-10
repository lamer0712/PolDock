package com.oemtools.mirrorfold;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** 5칸 수직 배터리 막대. 칸 수에 따라 색: 5=초록 … 1=빨강. */
public class BatteryBar extends View {

    private static final int[] FILL = {
            0xFFE0E0E0, // 0 (없음)
            0xFFD32F2F, // 1 빨강
            0xFFF57C00, // 2 주황
            0xFFF5C518, // 3 노랑
            0xFF7CB342, // 4 연두
            0xFF2E9E3E, // 5 초록
    };
    private static final int EMPTY = 0xFFE4E6EA;
    private static final int OUTLINE = 0xFF9AA0AA;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int percent = -1;

    public BatteryBar(Context c) {
        super(c);
    }

    public void setPercent(int pct) {
        if (pct == percent) return;
        percent = pct;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float nub = h * 0.06f;                 // 상단 단자
        float bodyTop = nub;
        RectF body = new RectF(w * 0.14f, bodyTop, w * 0.86f, h);
        float r = w * 0.10f;

        // 단자
        p.setStyle(Paint.Style.FILL);
        p.setColor(OUTLINE);
        c.drawRoundRect(new RectF(w * 0.36f, 0, w * 0.64f, nub + r), r * 0.5f, r * 0.5f, p);

        int filled = percent < 0 ? 0 : Math.max(0, Math.min(5, Math.round(percent / 20f)));
        int color = FILL[filled];

        float gap = (body.height()) * 0.03f;
        float cellH = (body.height() - gap * 4) / 5f;
        float pad = w * 0.04f;
        for (int i = 0; i < 5; i++) {
            // i=0 맨 아래
            float top = body.bottom - (i + 1) * cellH - i * gap;
            float bottom = top + cellH;
            RectF cell = new RectF(body.left + pad, top, body.right - pad, bottom);
            p.setColor(i < filled ? color : EMPTY);
            float cr = r * 0.5f;
            c.drawRoundRect(cell, cr, cr, p);
        }

        // 테두리
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, w * 0.03f));
        p.setColor(OUTLINE);
        c.drawRoundRect(body, r, r, p);
    }
}
