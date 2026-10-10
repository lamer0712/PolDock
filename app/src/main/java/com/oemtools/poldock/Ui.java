package com.oemtools.poldock;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 공통 다크 테마 스타일 헬퍼. 차량 화면용 큰 터치 타깃. */
public class Ui {

    public static final int BG = 0xFF0E0F12;
    public static final int CARD = 0xFF1A1C22;
    public static final int FIELD = 0xFF262933;
    public static final int TEXT = 0xFFECEDEF;
    public static final int DIM = 0xFF9AA0AA;
    public static final int ACCENT = 0xFF3B82F6;
    public static final int GREEN = 0xFF2E7D32;
    public static final int GREY = 0xFF3A3D46;
    public static final int DANGER = 0xFF7F2A24;

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable round(int color, int radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** 눌림 상태가 보이는 둥근 배경 */
    static StateListDrawable pressable(int base, int pressed, int radiusPx) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, round(pressed, radiusPx));
        s.addState(new int[]{}, round(base, radiusPx));
        return s;
    }

    static TextView title(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    static TextView sectionLabel(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setAllCaps(true);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(c, 4), dp(c, 6), 0, dp(c, 8));
        return t;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(CARD, dp(c, 18)));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 14);
        l.setLayoutParams(lp);
        return l;
    }

    /** 채워진 버튼 */
    static TextView button(Context c, String text, int color, View.OnClickListener cl) {
        TextView b = new TextView(c);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 20), dp(c, 14), dp(c, 20), dp(c, 14));
        b.setBackground(pressable(color, blend(color), dp(c, 14)));
        b.setOnClickListener(cl);
        return b;
    }

    /** 작은 아이콘 버튼 (편집/삭제/순서) */
    static TextView chip(Context c, String text, int color, View.OnClickListener cl) {
        TextView b = new TextView(c);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));
        b.setBackground(pressable(color, blend(color), dp(c, 12)));
        b.setOnClickListener(cl);
        return b;
    }

    static EditText field(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextColor(TEXT);
        e.setHintTextColor(DIM);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setBackground(round(FIELD, dp(c, 12)));
        e.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 6);
        lp.bottomMargin = dp(c, 4);
        e.setLayoutParams(lp);
        return e;
    }

    static TextView fieldLabel(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setPadding(dp(c, 2), dp(c, 10), 0, 0);
        return t;
    }

    static LinearLayout rowGap(Context c, View... views) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < views.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(c, 10);
            r.addView(views[i], lp);
        }
        return r;
    }

    private static int blend(int color) {
        // 눌림 색: 살짝 밝게
        float f = 0.18f;
        int r = (int) (Color.red(color) + (255 - Color.red(color)) * f);
        int g = (int) (Color.green(color) + (255 - Color.green(color)) * f);
        int b = (int) (Color.blue(color) + (255 - Color.blue(color)) * f);
        return Color.rgb(r, g, b);
    }

    static boolean empty(String s) {
        return TextUtils.isEmpty(s);
    }
}
