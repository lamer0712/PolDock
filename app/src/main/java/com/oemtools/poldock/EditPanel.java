package com.oemtools.poldock;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.List;

/**
 * 독 위에서 바로 편집하는 바텀시트 패널 (별도 설정 Activity 없이 오버레이로 동작).
 * 설정은 OverlayService 와 같은 SharedPreferences 를 쓰며, 변경 즉시 뒤의 독이 갱신된다.
 */
public class EditPanel {

    private final OverlayService svc;
    private final WindowManager wm;
    private final SharedPreferences sp;

    private View root;          // 전체 스크림 + 시트
    private LinearLayout body;  // 다시 채우는 내용 컨테이너
    private boolean showing;

    private final SharedPreferences.OnSharedPreferenceChangeListener lis =
            (p, k) -> {
                if (k == null || !showing || body == null) return;
                // 드래그 좌표/크기 슬라이더는 패널을 다시 그리지 않음 (슬라이더 끊김 방지)
                if ("x".equals(k) || "y".equals(k)
                        || k.startsWith(ShortcutStore.KEY_SCALE)
                        || ShortcutStore.KEY_ALPHA.equals(k)) return;
                populate();
            };

    EditPanel(OverlayService svc) {
        this.svc = svc;
        wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
        sp = svc.getSharedPreferences(ShortcutStore.PREF, Context.MODE_PRIVATE);
    }

    boolean isShowing() {
        return showing;
    }

    // ------------------------------------------------------------ 표시/숨김

    void show() {
        if (showing) return;
        Context c = svc;

        FrameLayout scrim = new FrameLayout(c);
        scrim.setFocusableInTouchMode(true);
        scrim.setOnClickListener(v -> hide());   // 바깥 탭 = 닫기
        scrim.setOnKeyListener((v, code, e) -> {
            if (code == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) {
                hide();
                return true;
            }
            return false;
        });

        ScrollView sheet = new ScrollView(c);
        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(Ui.BG);
        int rad = Ui.dp(c, 26);
        sheetBg.setCornerRadii(new float[]{rad, rad, rad, rad, 0, 0, 0, 0});
        sheet.setBackground(sheetBg);
        sheet.setClickable(true);                 // 시트 탭은 닫히지 않게 소비
        sheet.setVerticalScrollBarEnabled(false);

        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(c, 18);
        body.setPadding(pad, Ui.dp(c, 12), pad, Ui.dp(c, 24));
        sheet.addView(body);

        int screenH = c.getResources().getDisplayMetrics().heightPixels;
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(screenH * 0.82f), Gravity.BOTTOM);
        scrim.addView(sheet, slp);

        root = scrim;
        populate();

        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_DIM_BEHIND
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        wlp.dimAmount = 0.55f;
        wlp.windowAnimations = android.R.style.Animation_InputMethod;
        try {
            wm.addView(root, wlp);
            showing = true;
            root.requestFocus();
            sp.registerOnSharedPreferenceChangeListener(lis);
        } catch (Exception ignored) {
        }
    }

    void hide() {
        if (!showing) return;
        showing = false;
        try {
            sp.unregisterOnSharedPreferenceChangeListener(lis);
        } catch (Exception ignored) {
        }
        try {
            wm.removeView(root);
        } catch (Exception ignored) {
        }
        root = null;
        body = null;
        // 홈 전용 배치 중이면 편집 모드를 자동(포그라운드 전환)으로 복귀
        if (sp.getBoolean(ShortcutStore.KEY_HOME_PROFILE, false)) {
            sp.edit().putString(ShortcutStore.KEY_EDIT_MODE, "auto").apply();
        }
    }

    /** 다른 화면(상세 편집/앱 추가/권한)으로 나갈 때: 패널이 위에 떠서 가리므로 먼저 닫는다. */
    private void launchAndClose(Intent i) {
        hide();
        try {
            svc.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ 내용 구성

    private void populate() {
        if (body == null) return;
        body.removeAllViews();
        Context c = svc;

        // 헤더: 제목 + 닫기
        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = Ui.title(c, "독 편집");
        header.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(Ui.chip(c, "닫기", Ui.GREY, v -> hide()));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = Ui.dp(c, 10);
        body.addView(header, hlp);

        body.addView(buildLayoutCard());
        body.addView(buildWidgetCard());
        body.addView(buildShortcutCard());
        body.addView(buildHomeCard());

        // 독 끄기
        body.addView(Ui.button(c, "독 끄기", Ui.DANGER, v -> {
            hide();
            svc.stopSelf();
        }));
    }

    // 배치 + 크기/그리드
    private View buildLayoutCard() {
        Context c = svc;
        LinearLayout card = Ui.card(c);
        card.addView(Ui.sectionLabel(c, "배치"));

        String ori = sp.getString(oriKeyE(), "h");
        card.addView(Ui.rowGap(c,
                segBtn("가로", "h", ori),
                segBtn("세로", "v", ori),
                segBtn("그리드", "g", ori)));

        if ("g".equals(ori)) {
            int cols = sp.getInt(ShortcutStore.KEY_GRID_C, ShortcutStore.DEFAULT_GRID);
            int rows = sp.getInt(ShortcutStore.KEY_GRID_R, ShortcutStore.DEFAULT_GRID);
            card.addView(Ui.fieldLabel(c, "그리드  " + cols + "열 × " + rows + "행"));
            card.addView(Ui.rowGap(c,
                    Ui.button(c, "열 −", Ui.GREY, v -> stepGrid(ShortcutStore.KEY_GRID_C, -1)),
                    Ui.button(c, "열 ＋", Ui.GREY, v -> stepGrid(ShortcutStore.KEY_GRID_C, 1)),
                    Ui.button(c, "행 −", Ui.GREY, v -> stepGrid(ShortcutStore.KEY_GRID_R, -1)),
                    Ui.button(c, "행 ＋", Ui.GREY, v -> stepGrid(ShortcutStore.KEY_GRID_R, 1))));
            TextView n = Ui.fieldLabel(c, "그리드는 런처 타일 크기로 고정됩니다 (크기 조절 없음).");
            card.addView(n);
        } else {
            int pct = sp.getInt(scaleKeyE(), ShortcutStore.DEFAULT_SCALE);
            final TextView sizeLabel = Ui.fieldLabel(c, "독 크기: " + pct + "%");
            card.addView(sizeLabel);
            SeekBar seek = new SeekBar(c);
            seek.setMax(150);
            seek.setProgress(Math.max(0, Math.min(150, pct - 50)));
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar s, int p, boolean u) {
                    int v = p + 50;
                    sizeLabel.setText("독 크기: " + v + "%");
                    if (sp.getInt(scaleKeyE(), ShortcutStore.DEFAULT_SCALE) != v)
                        sp.edit().putInt(scaleKeyE(), v).apply();
                }
                public void onStartTrackingTouch(SeekBar s) {
                }
                public void onStopTrackingTouch(SeekBar s) {
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(c, 4);
            card.addView(seek, lp);
        }

        // 투명도 (가로/세로 전용 — 그리드는 항상 불투명 고정)
        if ("g".equals(ori)) {
            card.addView(Ui.fieldLabel(c, "그리드는 항상 불투명으로 고정됩니다."));
            return card;
        }
        int apct = sp.getInt(ShortcutStore.KEY_ALPHA, ShortcutStore.DEFAULT_ALPHA);
        final TextView alphaLabel = Ui.fieldLabel(c, "독 불투명도: " + apct + "%");
        card.addView(alphaLabel);
        SeekBar aseek = new SeekBar(c);
        aseek.setMax(80);                               // 20~100 → 0~80
        aseek.setProgress(Math.max(0, Math.min(80, apct - 20)));
        aseek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                int v = p + 20;
                alphaLabel.setText("독 불투명도: " + v + "%");
                if (sp.getInt(ShortcutStore.KEY_ALPHA, ShortcutStore.DEFAULT_ALPHA) != v)
                    sp.edit().putInt(ShortcutStore.KEY_ALPHA, v).apply();
            }
            public void onStartTrackingTouch(SeekBar s) {
            }
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = Ui.dp(c, 4);
        card.addView(aseek, alp);
        return card;
    }

    private TextView segBtn(String label, String val, String cur) {
        boolean active = val.equals(cur);
        return Ui.button(svc, label, active ? Ui.ACCENT : Ui.GREY, v -> {
            sp.edit().putString(oriKeyE(), val).apply();
        });
    }

    // 위젯
    private View buildWidgetCard() {
        Context c = svc;
        LinearLayout card = Ui.card(c);
        card.addView(Ui.sectionLabel(c, "위젯 (탭하여 켜고 끄기 · ↑↓ 순서)"));
        final List<String> order = ShortcutStore.widgetOrder(c);
        for (int i = 0; i < order.size(); i++) {
            final String id = order.get(i);
            boolean on = sp.getBoolean(ShortcutStore.widgetOnKey(id), ShortcutStore.widgetDefaultOn(id));

            LinearLayout row = listRow();
            TextView name = new TextView(c);
            name.setText((on ? "● " : "○ ") + ShortcutStore.widgetLabel(id));
            name.setTextColor(on ? Ui.TEXT : Ui.DIM);
            name.setTextSize(16);
            row.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            name.setOnClickListener(v -> {
                boolean cur = sp.getBoolean(ShortcutStore.widgetOnKey(id), ShortcutStore.widgetDefaultOn(id));
                sp.edit().putBoolean(ShortcutStore.widgetOnKey(id), !cur).apply();
            });
            final int fi = i;
            row.addView(chipGap("↑", Ui.GREY, v -> moveWidget(fi, -1)));
            row.addView(chipGap("↓", Ui.GREY, v -> moveWidget(fi, 1)));
            card.addView(row);
        }
        TextView note = Ui.fieldLabel(c, "※ 음악은 '알림 접근' 허용 필요");
        card.addView(note);
        return card;
    }

    private void moveWidget(int idx, int dir) {
        List<String> order = ShortcutStore.widgetOrder(svc);
        int j = idx + dir;
        if (j < 0 || j >= order.size()) return;
        String tmp = order.get(idx);
        order.set(idx, order.get(j));
        order.set(j, tmp);
        ShortcutStore.saveWidgetOrder(svc, order);  // -> lis -> populate
    }

    // 바로가기
    private View buildShortcutCard() {
        Context c = svc;
        LinearLayout card = Ui.card(c);
        card.addView(Ui.sectionLabel(c, "바로가기"));
        card.addView(Ui.rowGap(c,
                Ui.button(c, "+ 추가", Ui.ACCENT, v -> openEdit(-1)),
                Ui.button(c, "앱 추가", Ui.GREY,
                        v -> launchAndClose(new Intent(c, AppPickerActivity.class)))));

        final List<Shortcut> list = ShortcutStore.load(c);
        if (list.isEmpty()) {
            TextView e = Ui.fieldLabel(c, "비어 있음 — [+ 추가] 또는 [앱 추가]");
            card.addView(e);
            return card;
        }
        for (int i = 0; i < list.size(); i++) {
            final Shortcut s = list.get(i);
            final int fi = i;
            LinearLayout row = listRow();
            ImageView iv = new ImageView(c);
            Drawable icon = IconLoader.load(c, s);
            if (icon != null) iv.setImageDrawable(icon);
            int sz = Ui.dp(c, 34);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(sz, sz);
            ilp.rightMargin = Ui.dp(c, 10);
            row.addView(iv, ilp);

            TextView name = new TextView(c);
            name.setText(s.label.isEmpty() ? "(이름없음)" : s.label);
            name.setTextColor(Ui.TEXT);
            name.setTextSize(15);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            row.addView(chipGap("↑", Ui.GREY, v -> moveShortcut(fi, -1)));
            row.addView(chipGap("↓", Ui.GREY, v -> moveShortcut(fi, 1)));
            row.addView(chipGap("편집", Ui.ACCENT, v -> openEdit(fi)));
            row.addView(chipGap("삭제", Ui.DANGER, v -> confirmDelete(fi)));
            card.addView(row);
        }
        return card;
    }

    private void moveShortcut(int idx, int dir) {
        List<Shortcut> list = ShortcutStore.load(svc);
        int j = idx + dir;
        if (j < 0 || j >= list.size()) return;
        Shortcut tmp = list.get(idx);
        list.set(idx, list.get(j));
        list.set(j, tmp);
        ShortcutStore.save(svc, list);  // -> lis -> populate
    }

    private void confirmDelete(int idx) {
        final List<Shortcut> list = ShortcutStore.load(svc);
        if (idx < 0 || idx >= list.size()) return;
        AlertDialog dlg = new AlertDialog.Builder(svc)
                .setMessage("삭제할까요?  " + list.get(idx).label)
                .setPositiveButton("삭제", (d, w) -> {
                    list.remove(idx);
                    ShortcutStore.save(svc, list);
                })
                .setNegativeButton("취소", null)
                .create();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setType(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE);
        }
        dlg.show();
    }

    private void openEdit(int idx) {
        Intent i = new Intent(svc, EditActivity.class);
        i.putExtra(EditActivity.EXTRA_INDEX, idx);
        launchAndClose(i);
    }

    // 홈 화면 연동
    private View buildHomeCard() {
        Context c = svc;
        LinearLayout card = Ui.card(c);
        card.addView(Ui.sectionLabel(c, "홈 화면 연동"));
        boolean hp = sp.getBoolean(ShortcutStore.KEY_HOME_PROFILE, false);
        String em = sp.getString(ShortcutStore.KEY_EDIT_MODE, "auto");

        card.addView(Ui.rowGap(c,
                Ui.button(c, hp ? "홈 전용 배치  ●" : "홈 전용 배치  ○",
                        hp ? Ui.GREEN : Ui.GREY, v -> toggleHomeProfile()),
                Ui.button(c, "사용정보 접근", Ui.GREY,
                        v -> launchAndClose(new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)))));

        boolean editHome = hp && "home".equals(em);
        boolean editDef = hp && "default".equals(em);
        card.addView(Ui.rowGap(c,
                Ui.button(c, "일반 편집", editDef ? Ui.ACCENT : Ui.GREY, v -> setEditMode("default")),
                Ui.button(c, "홈 편집", editHome ? Ui.ACCENT : Ui.GREY, v -> setEditMode("home"))));

        TextView n = Ui.fieldLabel(c, "홈 전용 배치: OEM 런처에선 '홈' 배치, 다른 앱 위에선 '일반' 배치로 자동 전환. "
                + "각 모드를 [편집] 후 독을 드래그·조절하세요. (사용정보 접근 필요)");
        card.addView(n);
        return card;
    }

    private void toggleHomeProfile() {
        boolean on = !sp.getBoolean(ShortcutStore.KEY_HOME_PROFILE, false);
        sp.edit().putBoolean(ShortcutStore.KEY_HOME_PROFILE, on)
                .putString(ShortcutStore.KEY_EDIT_MODE, on ? "default" : "auto").apply();
    }

    private void setEditMode(String m) {
        if (!sp.getBoolean(ShortcutStore.KEY_HOME_PROFILE, false)) return;
        sp.edit().putString(ShortcutStore.KEY_EDIT_MODE, m).apply();
    }

    private void stepGrid(String key, int delta) {
        int v = sp.getInt(key, ShortcutStore.DEFAULT_GRID) + delta;
        v = Math.max(1, Math.min(key.equals(ShortcutStore.KEY_GRID_C) ? 6 : 8, v));
        sp.edit().putInt(key, v).apply();
    }

    // ------------------------------------------------------------ 공통

    private LinearLayout listRow() {
        Context c = svc;
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.round(Ui.FIELD, Ui.dp(c, 14)));
        int p = Ui.dp(c, 8);
        row.setPadding(Ui.dp(c, 12), p, p, p);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Ui.dp(c, 8);
        row.setLayoutParams(rlp);
        return row;
    }

    private View chipGap(String text, int color, View.OnClickListener cl) {
        TextView chip = Ui.chip(svc, text, color, cl);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(svc, 6);
        chip.setLayoutParams(lp);
        return chip;
    }

    private String editSuffix() {
        if (!sp.getBoolean(ShortcutStore.KEY_HOME_PROFILE, false)) return "";
        return "home".equals(sp.getString(ShortcutStore.KEY_EDIT_MODE, "auto")) ? "_home" : "";
    }

    private String oriKeyE() {
        return OverlayService.KEY_ORI + editSuffix();
    }

    private String scaleKeyE() {
        return ShortcutStore.KEY_SCALE + editSuffix();
    }
}
