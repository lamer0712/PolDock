package com.oemtools.poldock;

import android.animation.LayoutTransition;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.DragEvent;
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

import java.util.ArrayList;
import java.util.List;

/**
 * 독 위에서 바로 편집하는 좌측 패널 (별도 설정 Activity 없이 오버레이로 동작).
 * 설정은 OverlayService 와 같은 SharedPreferences 를 쓰며, 변경 즉시 뒤의 독이 갱신된다.
 */
public class EditPanel {

    private final OverlayService svc;
    private final WindowManager wm;
    private final SharedPreferences sp;

    private View root;          // 전체 스크림 + 시트
    private ScrollView sheet;
    private LinearLayout body;  // 다시 채우는 내용 컨테이너
    private boolean showing;
    private boolean scrollToBottomOnShow;
    private int photoPickerScrollY = -1;

    private static final class WidgetDragData {
        final String id;
        final View row;
        final LinearLayout container;
        final List<String> order;
        boolean saved;

        WidgetDragData(String id, View row, LinearLayout container, List<String> order) {
            this.id = id;
            this.row = row;
            this.container = container;
            this.order = new ArrayList<>(order);
        }
    }

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

        sheet = new ScrollView(c);
        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(Ui.BG);
        int rad = Ui.dp(c, 26);
        sheetBg.setCornerRadii(new float[]{0, 0, rad, rad, rad, rad, 0, 0});
        sheet.setBackground(sheetBg);
        sheet.setClickable(true);                 // 시트 탭은 닫히지 않게 소비
        sheet.setVerticalScrollBarEnabled(false);

        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(c, 18);
        body.setPadding(pad, Ui.dp(c, 12), pad, Ui.dp(c, 24));
        sheet.addView(body);

        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        scrim.addView(sheet, slp);

        root = scrim;
        // 편집 중엔 구체적 모드로 고정 (auto면 '다른 앱일 때'부터 시작)
        if ("auto".equals(sp.getString(ShortcutStore.KEY_EDIT_MODE, "auto")))
            sp.edit().putString(ShortcutStore.KEY_EDIT_MODE, "default").apply();
        populate();

        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(
                Math.max(1, dm.widthPixels / 2),
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT);
        wlp.gravity = Gravity.LEFT | Gravity.TOP;
        wlp.dimAmount = 0.55f;
        wlp.windowAnimations = android.R.style.Animation_InputMethod;
        try {
            wm.addView(root, wlp);
            showing = true;
            root.requestFocus();
            sp.registerOnSharedPreferenceChangeListener(lis);
            if (scrollToBottomOnShow) {
                scrollToBottomOnShow = false;
                scrollToBottom();
            }
        } catch (Exception ignored) {
        }
    }

    /** 앱 선택 완료 뒤에는 새로 추가된 바로가기 쪽으로 복귀한다. */
    void showAtBottom() {
        if (showing) {
            scrollToBottom();
            return;
        }
        scrollToBottomOnShow = true;
        show();
    }

    private void scrollToBottom() {
        final ScrollView target = sheet;
        if (target == null) return;
        target.post(() -> {
            if (target == sheet) target.fullScroll(View.FOCUS_DOWN);
        });
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
        sheet = null;
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

    /** 사진 선택기가 오버레이에 가리지 않도록 기존 편집창만 잠시 숨긴다. */
    private void launchPhotoPicker() {
        photoPickerScrollY = sheet != null ? sheet.getScrollY() : -1;
        if (root != null) {
            root.clearFocus();
            root.setVisibility(View.GONE);
        }
        try {
            svc.startActivity(new Intent(svc, GridBackgroundPickerActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
            restoreAfterPhotoPicker();
        }
    }

    /** 사진 선택 전의 편집창과 스크롤 위치를 그대로 복원한다. */
    void restoreAfterPhotoPicker() {
        if (!showing || root == null) {
            show();
            return;
        }
        root.setVisibility(View.VISIBLE);
        root.requestFocus();
        if (sheet != null && photoPickerScrollY >= 0) {
            final ScrollView target = sheet;
            final int savedY = photoPickerScrollY;
            target.post(() -> {
                if (target == sheet) target.scrollTo(0, savedY);
            });
        }
        photoPickerScrollY = -1;
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

        body.addView(buildHomeCard());
        body.addView(buildLayoutCard());
        body.addView(buildWidgetCard());
        body.addView(buildShortcutCard());

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
        card.addView(Ui.tabs(c,
                Ui.tab(c, "가로", "h".equals(ori),
                        v -> sp.edit().putString(oriKeyE(), "h").apply()),
                Ui.tab(c, "세로", "v".equals(ori),
                        v -> sp.edit().putString(oriKeyE(), "v").apply()),
                Ui.tab(c, "그리드", "g".equals(ori),
                        v -> sp.edit().putString(oriKeyE(), "g").apply())));
        LinearLayout tabContent = Ui.tabContent(c);
        card.addView(tabContent);

        if ("g".equals(ori)) {
            int cols = sp.getInt(ShortcutStore.KEY_GRID_C, ShortcutStore.DEFAULT_GRID);
            int rows = sp.getInt(ShortcutStore.KEY_GRID_R, ShortcutStore.DEFAULT_GRID);
            // 위치: 우상단 / 상하 통합 / 우하단 (드래그 불가, 여기서 선택)
            String gpos = sp.getString(ShortcutStore.KEY_GRID_POS, "top");
            tabContent.addView(Ui.fieldLabel(c, "위치"));
            tabContent.addView(Ui.rowGap(c,
                    gridPositionOption("우상단", "top", gpos),
                    gridPositionOption("우측 전체", "right", gpos),
                    gridPositionOption("우하단", "bottom", gpos)));
            tabContent.addView(gridStepper(c, "열 (가로 칸)", ShortcutStore.KEY_GRID_C, cols));
            tabContent.addView(gridStepper(c, "행 (세로 칸)", ShortcutStore.KEY_GRID_R, rows));
            addGridBackgroundControls(tabContent);
        } else {
            int pct = sp.getInt(scaleKeyE(), ShortcutStore.DEFAULT_SCALE);
            final TextView sizeLabel = Ui.fieldLabel(c, "독 크기: " + pct + "%");
            tabContent.addView(sizeLabel);
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
            tabContent.addView(seek, lp);
        }

        // 투명도 (가로/세로 전용 — 그리드는 항상 불투명 고정)
        if ("g".equals(ori)) {
            tabContent.addView(Ui.fieldLabel(c, "그리드는 항상 불투명으로 고정됩니다."));
            return card;
        }
        int apct = sp.getInt(ShortcutStore.KEY_ALPHA, ShortcutStore.DEFAULT_ALPHA);
        final TextView alphaLabel = Ui.fieldLabel(c, "독 불투명도: " + apct + "%");
        tabContent.addView(alphaLabel);
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
        tabContent.addView(aseek, alp);
        return card;
    }

    private View gridPositionOption(String description, String value, String current) {
        return Ui.iconButton(svc, new GridPositionIconDrawable(value), description,
                value.equals(current) ? Ui.ACCENT : Ui.GREY,
                v -> sp.edit().putString(ShortcutStore.KEY_GRID_POS, value).apply());
    }

    private void addGridBackgroundControls(LinearLayout card) {
        Context c = svc;
        String mode = sp.getString(ShortcutStore.KEY_GRID_BG_MODE, "default");
        int color = sp.getInt(ShortcutStore.KEY_GRID_BG_COLOR,
                ShortcutStore.DEFAULT_GRID_BG_COLOR) | 0xFF000000;
        card.addView(Ui.fieldLabel(c, "배경"));
        card.addView(Ui.tabs(c,
                Ui.tab(c, "기본", "default".equals(mode),
                        v -> sp.edit().putString(
                                ShortcutStore.KEY_GRID_BG_MODE, "default").apply()),
                Ui.tab(c, "단색", "color".equals(mode),
                        v -> sp.edit().putString(
                                ShortcutStore.KEY_GRID_BG_MODE, "color").apply()),
                Ui.tab(c, "사진", "photo".equals(mode),
                        v -> selectPhotoBackground())));

        if ("color".equals(mode)) {
            LinearLayout details = Ui.tabContent(c);
            TextView colorButton = Ui.button(c, "배경색 변경", color,
                    v -> showGridColorDialog(true));
            double luminance = Color.red(color) * 0.299
                    + Color.green(color) * 0.587 + Color.blue(color) * 0.114;
            colorButton.setTextColor(luminance > 170 ? Color.BLACK : Color.WHITE);
            details.addView(colorButton);
            card.addView(details);
        } else if ("photo".equals(mode)) {
            LinearLayout details = Ui.tabContent(c);
            addGridPhotoControls(details, color);
            card.addView(details);
        }
    }

    private void selectPhotoBackground() {
        java.io.File photo = new java.io.File(svc.getFilesDir(),
                GridBackgroundPickerActivity.PHOTO_FILE);
        if (photo.isFile()) {
            sp.edit().putString(ShortcutStore.KEY_GRID_BG_MODE, "photo").apply();
        } else {
            launchPhotoPicker();
        }
    }

    private void addGridPhotoControls(LinearLayout card, int color) {
        Context c = svc;
        String size = sp.getString(ShortcutStore.KEY_GRID_PHOTO_SIZE, "fit");
        String alignX = sp.getString(ShortcutStore.KEY_GRID_PHOTO_ALIGN_X, "center");
        String alignY = sp.getString(ShortcutStore.KEY_GRID_PHOTO_ALIGN_Y, "bottom");
        String backdrop = sp.getString(ShortcutStore.KEY_GRID_PHOTO_BACKDROP, "color");

        card.addView(Ui.button(c, "다른 사진 선택", Ui.GREY, v -> launchPhotoPicker()));
        card.addView(Ui.fieldLabel(c, "사진 표시 방식"));
        card.addView(Ui.rowGap(c,
                photoOption("맞춤", ShortcutStore.KEY_GRID_PHOTO_SIZE, "fit", size),
                photoOption("꽉 채우기", ShortcutStore.KEY_GRID_PHOTO_SIZE, "fill", size),
                photoOption("원본 크기", ShortcutStore.KEY_GRID_PHOTO_SIZE, "original", size)));

        card.addView(Ui.fieldLabel(c, "좌우 정렬"));
        card.addView(Ui.rowGap(c,
                photoAlignmentOption("왼쪽 정렬", "x", ShortcutStore.KEY_GRID_PHOTO_ALIGN_X,
                        "left", alignX),
                photoAlignmentOption("가운데 정렬", "x", ShortcutStore.KEY_GRID_PHOTO_ALIGN_X,
                        "center", alignX),
                photoAlignmentOption("오른쪽 정렬", "x", ShortcutStore.KEY_GRID_PHOTO_ALIGN_X,
                        "right", alignX)));

        card.addView(Ui.fieldLabel(c, "상하 정렬"));
        card.addView(Ui.rowGap(c,
                photoAlignmentOption("위쪽 정렬", "y", ShortcutStore.KEY_GRID_PHOTO_ALIGN_Y,
                        "top", alignY),
                photoAlignmentOption("가운데 정렬", "y", ShortcutStore.KEY_GRID_PHOTO_ALIGN_Y,
                        "center", alignY),
                photoAlignmentOption("아래쪽 정렬", "y", ShortcutStore.KEY_GRID_PHOTO_ALIGN_Y,
                        "bottom", alignY)));

        card.addView(Ui.fieldLabel(c, "사진 밖의 여백"));
        TextView backdropColor = Ui.button(c, "단색 여백",
                "color".equals(backdrop) ? color : Ui.GREY,
                v -> showGridColorDialog(false));
        if ("color".equals(backdrop)) {
            double luminance = Color.red(color) * 0.299
                    + Color.green(color) * 0.587 + Color.blue(color) * 0.114;
            backdropColor.setTextColor(luminance > 170 ? Color.BLACK : Color.WHITE);
        }
        card.addView(Ui.rowGap(c,
                backdropColor,
                photoOption("블러 여백", ShortcutStore.KEY_GRID_PHOTO_BACKDROP,
                        "blur", backdrop)));
    }

    private View photoOption(String label, String key, String value, String current) {
        return Ui.button(svc, label, value.equals(current) ? Ui.ACCENT : Ui.GREY,
                v -> sp.edit().putString(key, value).apply());
    }

    private View photoAlignmentOption(String description, String axis, String key,
                                      String value, String current) {
        return Ui.iconButton(svc, new AlignmentIconDrawable(axis, value), description,
                value.equals(current) ? Ui.ACCENT : Ui.GREY,
                v -> sp.edit().putString(key, value).apply());
    }

    private void showGridColorDialog(boolean selectColorMode) {
        Context c = svc;
        int initial = sp.getInt(ShortcutStore.KEY_GRID_BG_COLOR,
                ShortcutStore.DEFAULT_GRID_BG_COLOR) | 0xFF000000;
        int[] rgb = {Color.red(initial), Color.green(initial), Color.blue(initial)};

        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(c, 20);
        panel.setPadding(pad, Ui.dp(c, 8), pad, Ui.dp(c, 8));
        View preview = new View(c);
        preview.setBackground(Ui.round(initial, Ui.dp(c, 8)));
        panel.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 72)));

        TextView redLabel = Ui.fieldLabel(c, "R  " + rgb[0]);
        TextView greenLabel = Ui.fieldLabel(c, "G  " + rgb[1]);
        TextView blueLabel = Ui.fieldLabel(c, "B  " + rgb[2]);
        panel.addView(redLabel);
        SeekBar red = colorSeek(c, 255, rgb[0]);
        panel.addView(red);
        panel.addView(greenLabel);
        SeekBar green = colorSeek(c, 255, rgb[1]);
        panel.addView(green);
        panel.addView(blueLabel);
        SeekBar blue = colorSeek(c, 255, rgb[2]);
        panel.addView(blue);

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                rgb[0] = red.getProgress();
                rgb[1] = green.getProgress();
                rgb[2] = blue.getProgress();
                redLabel.setText("R  " + rgb[0]);
                greenLabel.setText("G  " + rgb[1]);
                blueLabel.setText("B  " + rgb[2]);
                preview.setBackground(Ui.round(
                        Color.rgb(rgb[0], rgb[1], rgb[2]), Ui.dp(c, 8)));
            }
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
        red.setOnSeekBarChangeListener(listener);
        green.setOnSeekBarChangeListener(listener);
        blue.setOnSeekBarChangeListener(listener);

        AlertDialog dialog = new AlertDialog.Builder(c)
                .setTitle(selectColorMode ? "그리드 배경색" : "사진 밖의 여백 색상")
                .setView(panel)
                .setPositiveButton("적용", (d, w) -> {
                    SharedPreferences.Editor editor = sp.edit()
                            .putInt(ShortcutStore.KEY_GRID_BG_COLOR,
                                    Color.rgb(rgb[0], rgb[1], rgb[2]));
                    if (selectColorMode) {
                        editor.putString(ShortcutStore.KEY_GRID_BG_MODE, "color");
                    } else {
                        editor.putString(ShortcutStore.KEY_GRID_BG_MODE, "photo")
                                .putString(ShortcutStore.KEY_GRID_PHOTO_BACKDROP, "color");
                    }
                    editor.apply();
                })
                .setNegativeButton("취소", null)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setType(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE);
        }
        dialog.show();
    }

    private SeekBar colorSeek(Context c, int max, int progress) {
        SeekBar seek = new SeekBar(c);
        seek.setMax(max);
        seek.setProgress(progress);
        return seek;
    }

    /** 큰 숫자 스테퍼 한 줄: [라벨]  [−]  N  [＋] */
    private View gridStepper(Context c, String label, String key, int value) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.round(Ui.FIELD, Ui.dp(c, 14)));
        row.setPadding(Ui.dp(c, 14), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Ui.dp(c, 8);
        row.setLayoutParams(rlp);

        TextView lab = new TextView(c);
        lab.setText(label);
        lab.setTextColor(Ui.TEXT);
        lab.setTextSize(16);
        row.addView(lab, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(stepBtn(c, "−", v -> stepGrid(key, -1)));

        TextView num = new TextView(c);
        num.setText(String.valueOf(value));
        num.setTextColor(Ui.TEXT);
        num.setTextSize(30);
        num.setTypeface(num.getTypeface(), android.graphics.Typeface.BOLD);
        num.setGravity(Gravity.CENTER);
        row.addView(num, new LinearLayout.LayoutParams(Ui.dp(c, 54),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        row.addView(stepBtn(c, "＋", v -> stepGrid(key, 1)));
        return row;
    }

    private TextView stepBtn(Context c, String t, View.OnClickListener cl) {
        TextView b = new TextView(c);
        b.setText(t);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(24);
        b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.pressable(Ui.GREY, 0xFF50535C, Ui.dp(c, 12)));
        b.setOnClickListener(cl);
        int sz = Ui.dp(c, 50);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
        lp.leftMargin = Ui.dp(c, 6);
        b.setLayoutParams(lp);
        return b;
    }

    // 위젯
    private View buildWidgetCard() {
        Context c = svc;
        LinearLayout card = Ui.card(c);
        card.addView(Ui.sectionLabel(c, "위젯 (탭하여 켜고 끄기 · 손잡이를 길게 눌러 순서 변경)"));
        boolean dark = sp.getBoolean(ShortcutStore.KEY_DARK, false);
        View themeButton = Ui.button(c,
                dark ? "🌙 위젯 다크 모드  ●" : "☀️ 위젯 라이트 모드  ○",
                dark ? Ui.GREEN : Ui.GREY,
                v -> sp.edit().putBoolean(ShortcutStore.KEY_DARK, !dark).apply());
        LinearLayout.LayoutParams themeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        themeLp.bottomMargin = Ui.dp(c, 8);
        card.addView(themeButton, themeLp);
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
            row.setOnClickListener(v -> {
                boolean cur = sp.getBoolean(ShortcutStore.widgetOnKey(id), ShortcutStore.widgetDefaultOn(id));
                sp.edit().putBoolean(ShortcutStore.widgetOnKey(id), !cur).apply();
            });
            View handle = chipGap("≡", Ui.GREY, v -> { });
            handle.setContentDescription("순서 변경");
            handle.setOnLongClickListener(v -> startWidgetDrag(row, id, card, order));
            row.addView(handle);
            row.setOnDragListener((target, event) -> handleWidgetDrag(row, id, event));
            card.addView(row);
        }
        TextView note = Ui.fieldLabel(c, "※ 음악은 '알림 접근' 허용 필요");
        card.addView(note);
        LayoutTransition transition = new LayoutTransition();
        transition.setDuration(120);
        card.setLayoutTransition(transition);
        return card;
    }

    private boolean startWidgetDrag(View row, String id, LinearLayout container,
                                    List<String> order) {
        WidgetDragData local = new WidgetDragData(id, row, container, order);
        View.DragShadowBuilder shadow = new View.DragShadowBuilder(row);
        boolean started;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            started = row.startDragAndDrop(
                    ClipData.newPlainText("widget", id), shadow, local, 0);
        } else {
            started = row.startDrag(
                    ClipData.newPlainText("widget", id), shadow, local, 0);
        }
        if (started) row.setAlpha(0.45f);
        return started;
    }

    private boolean handleWidgetDrag(View row, String targetId, DragEvent event) {
        if (!(event.getLocalState() instanceof WidgetDragData)) return false;
        WidgetDragData dragged = (WidgetDragData) event.getLocalState();
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return true;
            case DragEvent.ACTION_DRAG_ENTERED:
                if (dragged.row != row) {
                    row.setBackground(Ui.round(0xFF343A46, Ui.dp(svc, 14)));
                    previewWidgetOrder(dragged, row, targetId);
                }
                return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                autoScrollWidgetDrag(row, event.getY());
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                row.setBackground(Ui.round(Ui.FIELD, Ui.dp(svc, 14)));
                return true;
            case DragEvent.ACTION_DROP:
                row.setBackground(Ui.round(Ui.FIELD, Ui.dp(svc, 14)));
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                dragged.row.setAlpha(1f);
                row.setBackground(Ui.round(Ui.FIELD, Ui.dp(svc, 14)));
                saveWidgetDragOrder(dragged);
                return true;
            default:
                return true;
        }
    }

    private void previewWidgetOrder(WidgetDragData dragged, View target, String targetId) {
        int sourceViewIndex = dragged.container.indexOfChild(dragged.row);
        int targetViewIndex = dragged.container.indexOfChild(target);
        int sourceOrderIndex = dragged.order.indexOf(dragged.id);
        int targetOrderIndex = dragged.order.indexOf(targetId);
        if (sourceViewIndex < 0 || targetViewIndex < 0
                || sourceOrderIndex < 0 || targetOrderIndex < 0
                || sourceOrderIndex == targetOrderIndex) return;

        ViewGroup.LayoutParams params = dragged.row.getLayoutParams();
        dragged.container.removeView(dragged.row);
        dragged.container.addView(dragged.row,
                Math.min(targetViewIndex, dragged.container.getChildCount()), params);

        dragged.order.remove(sourceOrderIndex);
        dragged.order.add(Math.min(targetOrderIndex, dragged.order.size()), dragged.id);
    }

    private void saveWidgetDragOrder(WidgetDragData dragged) {
        if (dragged.saved) return;
        dragged.saved = true;
        List<String> result = new ArrayList<>(dragged.order);
        dragged.container.post(() -> ShortcutStore.saveWidgetOrder(svc, result));
    }

    private void autoScrollWidgetDrag(View target, float localY) {
        if (sheet == null) return;
        int[] targetPos = new int[2];
        int[] sheetPos = new int[2];
        target.getLocationOnScreen(targetPos);
        sheet.getLocationOnScreen(sheetPos);
        float screenY = targetPos[1] + localY;
        int edge = Ui.dp(svc, 72);
        if (screenY < sheetPos[1] + edge) {
            sheet.scrollBy(0, -Ui.dp(svc, 18));
        } else if (screenY > sheetPos[1] + sheet.getHeight() - edge) {
            sheet.scrollBy(0, Ui.dp(svc, 18));
        }
    }

    // 바로가기
    private View buildShortcutCard() {
        Context c = svc;
        LinearLayout card = Ui.card(c);
        card.addView(Ui.sectionLabel(c, "바로가기"));
        card.addView(Ui.rowGap(c,
                Ui.button(c, "+ 추가", Ui.ACCENT, v -> openEdit(-1)),
                Ui.button(c, "앱 추가", Ui.GREY,
                        v -> launchAndClose(new Intent(c, AppPickerActivity.class)
                                .putExtra(AppPickerActivity.EXTRA_RETURN_TO_DOCK_EDITOR, true)))));

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
        card.addView(Ui.sectionLabel(c, "지금 편집하는 화면"));
        boolean home = "home".equals(sp.getString(ShortcutStore.KEY_EDIT_MODE, "default"));

        // 일반/홈 편집을 토글 하나로 합침
        card.addView(Ui.button(c,
                home ? "🏠 독이 홈 화면 위에 있을 때 (탭하면 전환)"
                        : "📱 독이 다른 앱 위에 있을 때 (탭하면 전환)",
                home ? Ui.GREEN : Ui.ACCENT,
                v -> setEditMode(home ? "default" : "home")));

        // 고등학생도 이해할 쉬운 설명
        TextView n = new TextView(c);
        n.setText("독은 놓인 화면에 따라 두 가지 모습으로 바뀌어요.\n\n"
                + "• 📱 독이 다른 앱 위에 있을 때 — 지도·음악 같은 앱 위의 독 모습\n"
                + "• 🏠 독이 홈 화면 위에 있을 때 — 차 홈(앱 목록) 위의 독 모습\n\n"
                + "지금은 " + (home ? "‘🏠 독이 홈 화면 위에 있을 때’"
                        : "‘📱 독이 다른 앱 위에 있을 때’")
                + " 모습을 편집하는 중이에요.");
        n.setTextColor(Ui.DIM);
        n.setTextSize(13);
        n.setLineSpacing(Ui.dp(c, 3), 1f);
        n.setPadding(Ui.dp(c, 2), Ui.dp(c, 10), Ui.dp(c, 2), 0);
        card.addView(n);

        return card;
    }

    private void setEditMode(String m) {
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
