package com.oemtools.mirrorfold;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** PolDock 설정 화면 — 다크 테마, 카드, 큰 터치 버튼. */
public class MainActivity extends Activity {

    private TextView status;
    private LinearLayout listBox, widgetBox;
    private TextView oriBtn, dockBtn, homeProfBtn, editDefBtn, editHomeBtn, sizeLabel, gridLabel;
    private android.widget.SeekBar sizeSeek;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(Ui.BG);
        sc.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 18), pad, Ui.dp(this, 28));
        sc.addView(root);

        root.addView(Ui.title(this, "PolDock"));

        status = new TextView(this);
        status.setTextColor(Ui.DIM);
        status.setTextSize(13);
        status.setPadding(Ui.dp(this, 2), Ui.dp(this, 6), 0, Ui.dp(this, 14));
        root.addView(status);

        root.addView(buildDockCard());
        root.addView(buildWidgetCard());
        root.addView(buildShortcutCard());

        setContentView(sc);
    }

    // ---------------------------------------------------------------- 카드: 독

    private View buildDockCard() {
        LinearLayout c = Ui.card(this);
        c.addView(Ui.sectionLabel(this, "독"));
        dockBtn = Ui.button(this, "독 켜기", Ui.ACCENT, v -> toggleDock());
        oriBtn = Ui.button(this, "배치: 가로", Ui.GREY, v -> toggleOrientation());
        c.addView(Ui.rowGap(this, dockBtn, oriBtn));

        // 독 크기 슬라이더 50~200% (편집 대상 모드에 적용)
        int pct = prefs().getInt(scaleKeyE(), ShortcutStore.DEFAULT_SCALE);
        sizeLabel = Ui.fieldLabel(this, "독 크기: " + pct + "%");
        c.addView(sizeLabel);
        sizeSeek = new android.widget.SeekBar(this);
        sizeSeek.setMax(150);
        sizeSeek.setProgress(Math.max(0, Math.min(150, pct - 50)));
        sizeSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar s, int p, boolean u) {
                int v = p + 50;
                sizeLabel.setText("독 크기: " + v + "%");
                if (prefs().getInt(scaleKeyE(), ShortcutStore.DEFAULT_SCALE) != v)
                    prefs().edit().putInt(scaleKeyE(), v).apply();
            }
            public void onStartTrackingTouch(android.widget.SeekBar s) {
            }
            public void onStopTrackingTouch(android.widget.SeekBar s) {
                status.setText("독 크기 " + (s.getProgress() + 50) + "%");
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(this, 6);
        c.addView(sizeSeek, slp);

        // 홈(런처) 전용 배치
        c.addView(Ui.sectionLabel(this, "홈 화면 연동"));
        homeProfBtn = Ui.button(this, "홈 전용 배치", Ui.GREY, v -> toggleHomeProfile());
        c.addView(Ui.rowGap(this, homeProfBtn,
                Ui.button(this, "사용정보 접근", Ui.GREY, v -> openUsageAccess())));
        editDefBtn = Ui.button(this, "일반 편집", Ui.GREY, v -> setEditMode("default"));
        editHomeBtn = Ui.button(this, "홈 편집", Ui.GREY, v -> setEditMode("home"));
        c.addView(Ui.rowGap(this, editDefBtn, editHomeBtn));

        // 그리드 (배치가 '그리드'일 때)
        gridLabel = Ui.fieldLabel(this, "그리드 열×행");
        c.addView(gridLabel);
        c.addView(Ui.rowGap(this,
                Ui.button(this, "열 −", Ui.GREY, v -> stepGrid(ShortcutStore.KEY_GRID_C, -1)),
                Ui.button(this, "열 ＋", Ui.GREY, v -> stepGrid(ShortcutStore.KEY_GRID_C, 1)),
                Ui.button(this, "행 −", Ui.GREY, v -> stepGrid(ShortcutStore.KEY_GRID_R, -1)),
                Ui.button(this, "행 ＋", Ui.GREY, v -> stepGrid(ShortcutStore.KEY_GRID_R, 1))));

        TextView hnote = new TextView(this);
        hnote.setText("홈 전용 배치: OEM 런처 화면에선 '홈' 배치, 다른 앱 위에선 '일반' 배치로 자동 전환. "
                + "각 모드는 [편집] 선택 후 독을 드래그·크기조절해 맞추세요. (사용정보 접근 필요)");
        hnote.setTextColor(Ui.DIM);
        hnote.setTextSize(11);
        hnote.setPadding(Ui.dp(this, 2), Ui.dp(this, 8), 0, 0);
        c.addView(hnote);
        return c;
    }

    private String editSuffix() {
        if (!prefs().getBoolean(ShortcutStore.KEY_HOME_PROFILE, false)) return "";
        return "home".equals(prefs().getString(ShortcutStore.KEY_EDIT_MODE, "auto")) ? "_home" : "";
    }

    private String scaleKeyE() {
        return ShortcutStore.KEY_SCALE + editSuffix();
    }

    private String oriKeyE() {
        return OverlayService.KEY_ORI + editSuffix();
    }

    private void toggleHomeProfile() {
        boolean on = !prefs().getBoolean(ShortcutStore.KEY_HOME_PROFILE, false);
        prefs().edit().putBoolean(ShortcutStore.KEY_HOME_PROFILE, on)
                .putString(ShortcutStore.KEY_EDIT_MODE, on ? "default" : "auto").apply();
        refreshDockControls();
        status.setText(on ? "홈 전용 배치 켬 · [홈 편집]으로 홈 배치를 맞추세요"
                : "홈 전용 배치 끔");
    }

    private void setEditMode(String m) {
        if (!prefs().getBoolean(ShortcutStore.KEY_HOME_PROFILE, false)) {
            status.setText("먼저 [홈 전용 배치]를 켜세요.");
            return;
        }
        prefs().edit().putString(ShortcutStore.KEY_EDIT_MODE, m).apply();
        refreshDockControls();
        status.setText(("home".equals(m) ? "홈" : "일반") + " 배치 편집 중 · 독을 드래그·크기조절하세요");
    }

    private void openUsageAccess() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    private void refreshDockControls() {
        boolean hp = prefs().getBoolean(ShortcutStore.KEY_HOME_PROFILE, false);
        String em = prefs().getString(ShortcutStore.KEY_EDIT_MODE, "auto");
        homeProfBtn.setText(hp ? "홈 전용 배치  ●" : "홈 전용 배치  ○");
        homeProfBtn.setBackground(Ui.pressable(hp ? Ui.GREEN : Ui.GREY,
                hp ? Ui.GREEN : Ui.GREY, Ui.dp(this, 14)));
        boolean editHome = hp && "home".equals(em);
        boolean editDef = hp && "default".equals(em);
        editDefBtn.setBackground(Ui.pressable(editDef ? Ui.ACCENT : Ui.GREY,
                editDef ? Ui.ACCENT : Ui.GREY, Ui.dp(this, 14)));
        editHomeBtn.setBackground(Ui.pressable(editHome ? Ui.ACCENT : Ui.GREY,
                editHome ? Ui.ACCENT : Ui.GREY, Ui.dp(this, 14)));
        // 슬라이더/방향을 편집 모드 값으로 동기화
        int pct = prefs().getInt(scaleKeyE(), ShortcutStore.DEFAULT_SCALE);
        if (sizeSeek != null) sizeSeek.setProgress(Math.max(0, Math.min(150, pct - 50)));
        if (sizeLabel != null) sizeLabel.setText("독 크기: " + pct + "%");
        if (oriBtn != null) {
            String o = prefs().getString(oriKeyE(), "h");
            oriBtn.setText("배치: " + ("v".equals(o) ? "세로" : "g".equals(o) ? "그리드" : "가로"));
            if (gridLabel != null) {
                int cols = prefs().getInt(ShortcutStore.KEY_GRID_C, ShortcutStore.DEFAULT_GRID);
                int rows = prefs().getInt(ShortcutStore.KEY_GRID_R, ShortcutStore.DEFAULT_GRID);
                gridLabel.setText("그리드 " + cols + "열 × " + rows + "행 (배치=그리드일 때 적용)");
            }
        }
    }

    private void toggleDock() {
        if (OverlayService.isRunning) {
            stopService(new Intent(this, OverlayService.class));
            status.setText("독을 숨겼습니다.");
            dockBtn.postDelayed(this::updateDockBtn, 200);
        } else {
            if (!OverlayService.canOverlay(this)) {
                status.setText("오버레이 권한이 없습니다. 앱을 재설치하면 자동 부여됩니다.");
                return;
            }
            startService(new Intent(this, OverlayService.class));
            status.setText("독을 띄웠습니다 · 손잡이(≡)로 이동, 아이콘 탭으로 실행");
            dockBtn.postDelayed(this::updateDockBtn, 200);
        }
    }

    private void updateDockBtn() {
        boolean on = OverlayService.isRunning;
        dockBtn.setText(on ? "독 끄기" : "독 켜기");
        dockBtn.setBackground(Ui.pressable(on ? Ui.GREY : Ui.ACCENT,
                on ? Ui.GREY : Ui.ACCENT, Ui.dp(this, 14)));
    }

    // ---------------------------------------------------------------- 카드: 위젯 (순서/표시)

    private View buildWidgetCard() {
        LinearLayout c = Ui.card(this);
        c.addView(Ui.sectionLabel(this, "위젯 (↑↓ 순서 · 탭하여 켜고 끄기)"));
        widgetBox = new LinearLayout(this);
        widgetBox.setOrientation(LinearLayout.VERTICAL);
        c.addView(widgetBox);
        TextView note = new TextView(this);
        note.setText("※ 음악은 '알림 접근' 허용 필요 · 타이어공기압·전비는 차량이 권한을 막아 불가");
        note.setTextColor(Ui.DIM);
        note.setTextSize(11);
        note.setPadding(Ui.dp(this, 2), Ui.dp(this, 10), 0, 0);
        c.addView(note);
        return c;
    }

    private void buildWidgetList() {
        widgetBox.removeAllViews();
        final List<String> order = ShortcutStore.widgetOrder(this);
        for (int i = 0; i < order.size(); i++) {
            widgetBox.addView(widgetRow(order, i));
        }
    }

    private View widgetRow(List<String> order, int idx) {
        final String id = order.get(idx);
        boolean on = prefs().getBoolean(ShortcutStore.widgetOnKey(id),
                ShortcutStore.widgetDefaultOn(id));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.round(Ui.FIELD, Ui.dp(this, 14)));
        int p = Ui.dp(this, 8);
        row.setPadding(Ui.dp(this, 12), p, p, p);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Ui.dp(this, 8);
        row.setLayoutParams(rlp);

        TextView name = new TextView(this);
        name.setText((on ? "● " : "○ ") + ShortcutStore.widgetLabel(id));
        name.setTextColor(on ? Ui.TEXT : Ui.DIM);
        name.setTextSize(16);
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // 이름 탭 = 켜기/끄기
        name.setOnClickListener(v -> {
            boolean cur = prefs().getBoolean(ShortcutStore.widgetOnKey(id),
                    ShortcutStore.widgetDefaultOn(id));
            prefs().edit().putBoolean(ShortcutStore.widgetOnKey(id), !cur).apply();
            buildWidgetList();
        });

        final int fi = idx;
        row.addView(spacedChip("↑", Ui.GREY, v -> moveWidget(fi, -1)));
        row.addView(spacedChip("↓", Ui.GREY, v -> moveWidget(fi, 1)));
        return row;
    }

    private void moveWidget(int idx, int dir) {
        List<String> order = ShortcutStore.widgetOrder(this);
        int j = idx + dir;
        if (j < 0 || j >= order.size()) return;
        String tmp = order.get(idx);
        order.set(idx, order.get(j));
        order.set(j, tmp);
        ShortcutStore.saveWidgetOrder(this, order);
        buildWidgetList();
    }

    // ---------------------------------------------------------------- 카드: 바로가기

    private View buildShortcutCard() {
        LinearLayout c = Ui.card(this);
        c.addView(Ui.sectionLabel(this, "바로가기"));
        c.addView(Ui.rowGap(this,
                Ui.button(this, "+ 추가", Ui.ACCENT, v -> edit(-1)),
                Ui.button(this, "앱 추가", Ui.GREY,
                        v -> startActivity(new Intent(this, AppPickerActivity.class)))));
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 10);
        listBox.setLayoutParams(lp);
        c.addView(listBox);
        return c;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        updateDockBtn();
        buildWidgetList();
        buildList();
    }

    private void refresh() {
        status.setText(OverlayService.canOverlay(this)
                ? "준비됨 · [독 켜기]로 띄우세요"
                : "오버레이 권한 없음 · 앱 재설치 시 자동 부여");
        refreshDockControls();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 설정을 벗어나면 편집 모드를 자동(포그라운드 전환)으로 복귀
        if (prefs().getBoolean(ShortcutStore.KEY_HOME_PROFILE, false)) {
            prefs().edit().putString(ShortcutStore.KEY_EDIT_MODE, "auto").apply();
        }
    }

    private void buildList() {
        listBox.removeAllViews();
        final List<Shortcut> list = ShortcutStore.load(this);
        if (list.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("비어 있음 — [+ 추가] 또는 [앱 추가]");
            t.setTextColor(Ui.DIM);
            listBox.addView(t);
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            listBox.addView(shortcutRow(list.get(i), i));
        }
    }

    private View shortcutRow(Shortcut s, int idx) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.round(Ui.FIELD, Ui.dp(this, 14)));
        int p = Ui.dp(this, 10);
        row.setPadding(p, p, p, p);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Ui.dp(this, 8);
        row.setLayoutParams(rlp);

        ImageView iv = new ImageView(this);
        Drawable icon = IconLoader.load(this, s);
        if (icon != null) iv.setImageDrawable(icon);
        int sz = Ui.dp(this, 36);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(sz, sz);
        ilp.rightMargin = Ui.dp(this, 12);
        row.addView(iv, ilp);

        TextView name = new TextView(this);
        name.setText(s.label.isEmpty() ? "(이름없음)" : s.label);
        name.setTextColor(Ui.TEXT);
        name.setTextSize(16);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final int fi = idx;
        row.addView(spacedChip("↑", Ui.GREY, v -> move(fi, -1)));
        row.addView(spacedChip("↓", Ui.GREY, v -> move(fi, 1)));
        row.addView(spacedChip("편집", Ui.ACCENT, v -> edit(fi)));
        row.addView(spacedChip("삭제", Ui.DANGER, v -> delete(fi)));
        return row;
    }

    private View spacedChip(String text, int color, View.OnClickListener cl) {
        TextView c = Ui.chip(this, text, color, cl);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 6);
        c.setLayoutParams(lp);
        return c;
    }

    // ---------------------------------------------------------------- 동작

    private void toggleOrientation() {
        String cur = prefs().getString(oriKeyE(), "h");
        String next = "h".equals(cur) ? "v" : "v".equals(cur) ? "g" : "h";
        prefs().edit().putString(oriKeyE(), next).apply();
        refreshDockControls();
    }

    private void stepGrid(String key, int delta) {
        int v = prefs().getInt(key, ShortcutStore.DEFAULT_GRID) + delta;
        v = Math.max(1, Math.min(key.equals(ShortcutStore.KEY_GRID_C) ? 6 : 8, v));
        prefs().edit().putInt(key, v).apply();
        refreshDockControls();
    }

    private void move(int idx, int dir) {
        List<Shortcut> list = ShortcutStore.load(this);
        int j = idx + dir;
        if (j < 0 || j >= list.size()) return;
        Shortcut tmp = list.get(idx);
        list.set(idx, list.get(j));
        list.set(j, tmp);
        ShortcutStore.save(this, list);
        buildList();
    }

    private void delete(int idx) {
        List<Shortcut> list = ShortcutStore.load(this);
        if (idx < 0 || idx >= list.size()) return;
        new AlertDialog.Builder(this)
                .setMessage("삭제할까요?  " + list.get(idx).label)
                .setPositiveButton("삭제", (d, w) -> {
                    list.remove(idx);
                    ShortcutStore.save(this, list);
                    buildList();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void edit(int idx) {
        Intent i = new Intent(this, EditActivity.class);
        i.putExtra(EditActivity.EXTRA_INDEX, idx);
        startActivity(i);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(ShortcutStore.PREF, MODE_PRIVATE);
    }
}
