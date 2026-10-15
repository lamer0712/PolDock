package com.oemtools.poldock;

import android.animation.LayoutTransition;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.DragEvent;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.EditText;
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

    /** 그리드 위치 상단/하단(런처 타일 한 줄) 버튼 노출 여부 — 나중에 쓰려고 숨겨 둠. */
    private static final boolean SHOW_TILE_ROW_POSITIONS = false;

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

    private static final class ShortcutDragData {
        final Shortcut shortcut;
        final View row;
        final LinearLayout container;
        final List<Shortcut> order;
        boolean saved;

        ShortcutDragData(Shortcut shortcut, View row, LinearLayout container,
                         List<Shortcut> order) {
            this.shortcut = shortcut;
            this.row = row;
            this.container = container;
            this.order = new ArrayList<>(order);
        }
    }

    private static final class WidgetDragShadow extends View.DragShadowBuilder {
        private final View row;
        private final View handle;

        WidgetDragShadow(View row, View handle) {
            super(row);
            this.row = row;
            this.handle = handle;
        }

        @Override
        public void onProvideShadowMetrics(Point size, Point touch) {
            size.set(row.getWidth(), row.getHeight());
            touch.set(handle.getLeft() + handle.getWidth() / 2,
                    handle.getTop() + handle.getHeight() / 2);
        }
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener lis =
            (p, k) -> {
                if (k == null || !showing || body == null) return;
                // 드래그 좌표/크기 슬라이더는 패널을 다시 그리지 않음 (슬라이더 끊김 방지)
                if ("x".equals(k) || "y".equals(k)
                        || k.startsWith(ShortcutStore.KEY_SCALE)
                        || ShortcutStore.KEY_ALPHA.equals(k)
                        || ShortcutStore.KEY_DOCK_BORDER_WIDTH.equals(k)) return;
                LinearLayout target = body;
                target.post(() -> {
                    if (showing && body == target) populate();
                });
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
        if (showing) {
            if (root != null && root.getVisibility() != View.VISIBLE) {
                restoreAfterPhotoPicker();
            }
            return;
        }
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
        // 편집 중엔 구체적 모드로 고정 (auto면 홈 화면부터 시작)
        if ("auto".equals(sp.getString(ShortcutStore.KEY_EDIT_MODE, "auto")))
            sp.edit().putString(ShortcutStore.KEY_EDIT_MODE, "home").apply();
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
        if (UpdateManager.isUpdateAvailable(c)) {
            ImageView update = new ImageView(c);
            update.setImageDrawable(new UpdateIconDrawable());
            update.setScaleType(ImageView.ScaleType.FIT_CENTER);
            update.setPadding(Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10));
            update.setContentDescription("업데이트 있음");
            update.setOnClickListener(v -> {
                hide();
                UpdateManager.openUpdatePrompt(c);
            });
            LinearLayout.LayoutParams updateLp = new LinearLayout.LayoutParams(
                    Ui.dp(c, 48), Ui.dp(c, 48));
            updateLp.rightMargin = Ui.dp(c, 6);
            header.addView(update, updateLp);
        }
        // 닫기: 업데이트 아이콘과 같은 48dp 정사각형, 대문자 X를 정중앙에
        TextView close = Ui.chip(c, "X", Ui.GREY, v -> hide());
        close.setTextSize(22);
        close.setPadding(0, 0, 0, 0);
        close.setIncludeFontPadding(false);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("닫기");
        header.addView(close, new LinearLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48)));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = Ui.dp(c, 10);
        body.addView(header, hlp);

        body.addView(buildLayoutCard());
        body.addView(buildWidgetCard());
        body.addView(buildParkingPhotoCard());
        body.addView(buildShortcutCard());

        // 독 끄기
        body.addView(Ui.button(c, "독 끄기", Ui.DANGER, v -> {
            hide();
            svc.stopSelf();
        }));

        TextView appInfo = new TextView(c);
        appInfo.setText("PolDock v" + BuildConfig.VERSION_NAME + "\n개발자 · 로로아빠");
        appInfo.setTextColor(Ui.DIM);
        appInfo.setTextSize(11);
        appInfo.setGravity(Gravity.CENTER);
        appInfo.setLetterSpacing(0f);
        appInfo.setLineSpacing(0, 1.15f);
        appInfo.setPadding(0, Ui.dp(c, 16), 0, Ui.dp(c, 6));
        body.addView(appInfo, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // 배치 + 크기/그리드
    private View buildLayoutCard() {
        Context c = svc;
        LinearLayout card = Ui.card(c);
        card.addView(Ui.sectionLabel(c, "화면"));
        boolean home = "home".equals(sp.getString(ShortcutStore.KEY_EDIT_MODE, "home"));
        card.addView(Ui.tabs(c,
                Ui.tab(c, "홈 화면", home, v -> setEditMode("home")),
                Ui.tab(c, "그 외", !home, v -> setEditMode("default"))));

        LinearLayout screenContent = Ui.tabContent(c);
        card.addView(screenContent);
        screenContent.addView(Ui.sectionLabel(c, "모드"));

        String ori = sp.getString(oriKeyE(), "h");
        screenContent.addView(Ui.tabs(c,
                Ui.tab(c, "가로", "h".equals(ori),
                        v -> sp.edit().putString(oriKeyE(), "h").apply()),
                Ui.tab(c, "세로", "v".equals(ori),
                        v -> sp.edit().putString(oriKeyE(), "v").apply()),
                Ui.tab(c, "그리드", "g".equals(ori),
                        v -> sp.edit().putString(oriKeyE(), "g").apply()),
                Ui.tab(c, "꺼짐", OverlayService.ORI_OFF.equals(ori),
                        v -> sp.edit().putString(oriKeyE(), OverlayService.ORI_OFF).apply())));
        LinearLayout tabContent = Ui.tabContent(c);
        screenContent.addView(tabContent);

        if ("g".equals(ori)) {
            int cols = sp.getInt(ShortcutStore.KEY_GRID_C, ShortcutStore.DEFAULT_GRID);
            int rows = sp.getInt(ShortcutStore.KEY_GRID_R, ShortcutStore.DEFAULT_GRID);
            // 위치: (상단) / 우상단 / 우측 / 우하단 / (하단) (드래그 불가, 여기서 선택)
            String gpos = sp.getString(ShortcutStore.KEY_GRID_POS, "top");
            // 숨김 상태여도 이미 상단/하단을 쓰는 중이면 선택 상태가 보이도록 노출
            boolean rowPositions = SHOW_TILE_ROW_POSITIONS
                    || "top_full".equals(gpos) || "bottom_full".equals(gpos);
            tabContent.addView(Ui.fieldLabel(c, "위치"));
            tabContent.addView(rowPositions
                    ? Ui.rowGap(c,
                            gridPositionOption("상단", "top_full", gpos),
                            gridPositionOption("우상단", "top", gpos),
                            gridPositionOption("우측", "right", gpos),
                            gridPositionOption("우하단", "bottom", gpos),
                            gridPositionOption("하단", "bottom_full", gpos))
                    : Ui.rowGap(c,
                            gridPositionOption("우상단", "top", gpos),
                            gridPositionOption("우측", "right", gpos),
                            gridPositionOption("우하단", "bottom", gpos)));
            tabContent.addView(Ui.fieldLabel(c, "배치"));
            tabContent.addView(gridStepper(c, "행", ShortcutStore.KEY_GRID_R, rows));
            tabContent.addView(gridStepper(c, "열", ShortcutStore.KEY_GRID_C, cols));
            addGridBackgroundControls(tabContent);
        } else if (OverlayService.ORI_OFF.equals(ori)) {
            tabContent.addView(Ui.fieldLabel(c,
                    "이 화면에서는 독을 표시하지 않습니다.\n"
                            + "독이 없을 때 PolDock 앱 아이콘을 누르면 이 편집 화면이 열립니다."));
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

            addDockAlphaControl(tabContent);
            addDockBackgroundControls(tabContent);
        }
        return card;
    }

    /** 가로·세로 독의 크기 바로 아래에 표시되는 불투명도 조절. */
    private void addDockAlphaControl(LinearLayout card) {
        Context c = svc;
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
    }

    /** 가로·세로 독의 컨테이너 배경색. 그리드 배경 설정과는 별도로 저장한다. */
    private void addDockBackgroundControls(LinearLayout card) {
        Context c = svc;
        String mode = sp.getString(ShortcutStore.KEY_DOCK_BG_MODE, "default");
        int color = sp.getInt(ShortcutStore.KEY_DOCK_BG_COLOR,
                ShortcutStore.DEFAULT_DOCK_BG_COLOR) | 0xFF000000;
        card.addView(Ui.fieldLabel(c, "배경"));
        card.addView(Ui.tabs(c,
                Ui.tab(c, "기본색", "default".equals(mode),
                        v -> sp.edit().putString(
                                ShortcutStore.KEY_DOCK_BG_MODE, "default").apply()),
                Ui.tab(c, "배경색", "color".equals(mode),
                        v -> showDockColorDialog(false))));

        if ("color".equals(mode)) {
            LinearLayout details = Ui.tabContent(c);
            TextView colorButton = Ui.button(c, "색상 변경", color,
                    v -> showDockColorDialog(false));
            setReadableButtonText(colorButton, color);
            details.addView(colorButton);
            card.addView(details);
        }

        int borderColor = sp.getInt(ShortcutStore.KEY_DOCK_BORDER_COLOR,
                ShortcutStore.DEFAULT_DOCK_BORDER_COLOR);
        TextView borderColorButton = Ui.button(c, "테두리색", borderColor,
                v -> showDockColorDialog(true));
        setReadableButtonText(borderColorButton, borderColor);
        LinearLayout borderDetails = Ui.tabContent(c);
        borderDetails.addView(borderColorButton);
        card.addView(borderDetails);

        int borderWidth = Math.max(0, Math.min(10,
                sp.getInt(ShortcutStore.KEY_DOCK_BORDER_WIDTH,
                        ShortcutStore.DEFAULT_DOCK_BORDER_WIDTH)));
        TextView borderWidthLabel = Ui.fieldLabel(c, borderWidth == 0
                ? "테두리 두께: 0 (없음)" : "테두리 두께: " + borderWidth);
        card.addView(borderWidthLabel);
        SeekBar borderWidthSeek = new SeekBar(c);
        borderWidthSeek.setMax(10);
        borderWidthSeek.setProgress(borderWidth);
        borderWidthSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                borderWidthLabel.setText(progress == 0
                        ? "테두리 두께: 0 (없음)" : "테두리 두께: " + progress);
                if (sp.getInt(ShortcutStore.KEY_DOCK_BORDER_WIDTH,
                        ShortcutStore.DEFAULT_DOCK_BORDER_WIDTH) != progress) {
                    sp.edit().putInt(ShortcutStore.KEY_DOCK_BORDER_WIDTH, progress).apply();
                }
            }
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        card.addView(borderWidthSeek);
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
            TextView colorButton = Ui.button(c, "색상 선택", color,
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

        LinearLayout photoActions = new LinearLayout(c);
        photoActions.setOrientation(LinearLayout.HORIZONTAL);
        photoActions.setGravity(Gravity.CENTER_VERTICAL);
        View changePhoto = Ui.button(c, "사진 변경", Ui.GREY, v -> launchPhotoPicker());
        photoActions.addView(changePhoto, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView rotatePhoto = Ui.button(c, "⟳", Ui.GREY,
                v -> rotateGridPhotoClockwise());
        rotatePhoto.setTextSize(28);
        rotatePhoto.setGravity(Gravity.CENTER);
        rotatePhoto.setIncludeFontPadding(false);
        rotatePhoto.setPadding(0, 0, 0, 0);
        rotatePhoto.setTypeface(android.graphics.Typeface.DEFAULT);
        rotatePhoto.setContentDescription("오른쪽으로 90도 회전");
        LinearLayout.LayoutParams rotateLp = new LinearLayout.LayoutParams(
                Ui.dp(c, 52), Ui.dp(c, 52));
        rotateLp.leftMargin = Ui.dp(c, 10);
        photoActions.addView(rotatePhoto, rotateLp);
        card.addView(photoActions);
        card.addView(Ui.fieldLabel(c, "크기"));
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

        card.addView(Ui.fieldLabel(c, "여백"));
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

    private void rotateGridPhotoClockwise() {
        int current = sp.getInt(ShortcutStore.KEY_GRID_PHOTO_ROTATION, 0);
        sp.edit().putInt(ShortcutStore.KEY_GRID_PHOTO_ROTATION,
                (current + 90) % 360).apply();
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
                .setTitle(selectColorMode ? "배경색" : "여백 색상")
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

    private void showDockColorDialog(boolean border) {
        Context c = svc;
        int initial = border
                ? sp.getInt(ShortcutStore.KEY_DOCK_BORDER_COLOR,
                        ShortcutStore.DEFAULT_DOCK_BORDER_COLOR)
                : sp.getInt(ShortcutStore.KEY_DOCK_BG_COLOR,
                        ShortcutStore.DEFAULT_DOCK_BG_COLOR);
        initial |= 0xFF000000;
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
        SeekBar red = colorSeek(c, 255, rgb[0]);
        SeekBar green = colorSeek(c, 255, rgb[1]);
        SeekBar blue = colorSeek(c, 255, rgb[2]);
        panel.addView(redLabel);
        panel.addView(red);
        panel.addView(greenLabel);
        panel.addView(green);
        panel.addView(blueLabel);
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
                .setTitle(border ? "독 테두리색" : "독 배경색")
                .setView(panel)
                .setPositiveButton("적용", (d, w) -> {
                    SharedPreferences.Editor editor = sp.edit();
                    int selected = Color.rgb(rgb[0], rgb[1], rgb[2]);
                    if (border) {
                        editor.putInt(ShortcutStore.KEY_DOCK_BORDER_COLOR, selected);
                    } else {
                        editor.putInt(ShortcutStore.KEY_DOCK_BG_COLOR, selected)
                                .putString(ShortcutStore.KEY_DOCK_BG_MODE, "color");
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

    private void setReadableButtonText(TextView button, int color) {
        double luminance = Color.red(color) * 0.299
                + Color.green(color) * 0.587 + Color.blue(color) * 0.114;
        button.setTextColor(luminance > 170 ? Color.BLACK : Color.WHITE);
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
        row.setBackground(Ui.round(Ui.FIELD, Ui.dp(c, 8)));
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
        boolean dark = sp.getBoolean(ShortcutStore.KEY_DARK, false);
        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.sectionLabel(c, "위젯"), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View themeButton = Ui.compactIconButton(c, new ThemeIconDrawable(dark),
                dark ? "다크 테마" : "라이트 테마", dark ? Ui.GREY : Ui.ACCENT,
                v -> sp.edit().putBoolean(ShortcutStore.KEY_DARK, !dark).apply());
        header.addView(themeButton, new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)));
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerLp.bottomMargin = Ui.dp(c, 8);
        card.addView(header, headerLp);

        boolean widgetHeaderOn = sp.getBoolean(ShortcutStore.KEY_WIDGET_HEADER_ON, true);
        LinearLayout headerOption = listRow();
        TextView headerOptionName = new TextView(c);
        headerOptionName.setText("헤더");
        headerOptionName.setTextColor(Ui.TEXT);
        headerOptionName.setTextSize(16);
        headerOption.addView(headerOptionName, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        headerOption.addView(Ui.toggleIndicator(c, widgetHeaderOn));
        headerOption.setOnClickListener(v -> sp.edit()
                .putBoolean(ShortcutStore.KEY_WIDGET_HEADER_ON, !widgetHeaderOn).apply());
        card.addView(headerOption);

        boolean widgetFooterOn = sp.getBoolean(ShortcutStore.KEY_WIDGET_FOOTER_ON, true);
        LinearLayout footerOption = listRow();
        TextView footerOptionName = new TextView(c);
        footerOptionName.setText("푸터");
        footerOptionName.setTextColor(Ui.TEXT);
        footerOptionName.setTextSize(16);
        footerOption.addView(footerOptionName, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        footerOption.addView(Ui.toggleIndicator(c, widgetFooterOn));
        footerOption.setOnClickListener(v -> sp.edit()
                .putBoolean(ShortcutStore.KEY_WIDGET_FOOTER_ON, !widgetFooterOn).apply());
        card.addView(footerOption);

        final List<String> order = ShortcutStore.widgetOrder(c);
        for (int i = 0; i < order.size(); i++) {
            final String id = order.get(i);
            boolean on = sp.getBoolean(ShortcutStore.widgetOnKey(id), ShortcutStore.widgetDefaultOn(id));

            LinearLayout row = listRow();
            View handle = Ui.chip(c, "≡", Ui.GREY, v -> { });
            handle.setContentDescription("순서 변경");
            handle.setOnLongClickListener(v -> startWidgetDrag(row, v, id, card, order));
            LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            handleLp.rightMargin = Ui.dp(c, 12);
            row.addView(handle, handleLp);
            TextView name = new TextView(c);
            name.setText(ShortcutStore.widgetLabel(id));
            name.setTextColor(on ? Ui.TEXT : Ui.DIM);
            name.setTextSize(16);
            row.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.setOnClickListener(v -> {
                boolean cur = sp.getBoolean(ShortcutStore.widgetOnKey(id), ShortcutStore.widgetDefaultOn(id));
                sp.edit().putBoolean(ShortcutStore.widgetOnKey(id), !cur).apply();
            });
            row.addView(Ui.toggleIndicator(c, on));
            row.setOnDragListener((target, event) -> handleWidgetDrag(row, id, event));
            card.addView(row);
        }
        LayoutTransition transition = new LayoutTransition();
        transition.setDuration(120);
        card.setLayoutTransition(transition);
        return card;
    }

    private View buildParkingPhotoCard() {
        Context c = svc;
        LinearLayout card = Ui.card(c);
        card.addView(Ui.sectionLabel(c, "주차 사진"));

        boolean supported = SecureTokenStore.isSupported();
        boolean tokenSaved = SecureTokenStore.hasToken(c);
        boolean enabled = sp.getBoolean(ShortcutStore.KEY_PARKING_PHOTO_ENABLED, false);

        LinearLayout enabledRow = listRow();
        TextView enabledName = new TextView(c);
        enabledName.setText("D/R → P 전환 시 촬영·전송");
        enabledName.setTextColor(supported ? Ui.TEXT : Ui.DIM);
        enabledName.setTextSize(16);
        enabledRow.addView(enabledName, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        enabledRow.addView(Ui.toggleIndicator(c, enabled));
        enabledRow.setOnClickListener(v -> {
            if (!supported) {
                android.widget.Toast.makeText(c, "Android 6 이상에서 사용할 수 있습니다.",
                        android.widget.Toast.LENGTH_LONG).show();
            } else if (!enabled && !SecureTokenStore.hasToken(c)) {
                showMyBoxTokenDialog(true);
            } else {
                sp.edit().putBoolean(ShortcutStore.KEY_PARKING_PHOTO_ENABLED, !enabled).apply();
            }
        });
        card.addView(enabledRow);

        card.addView(Ui.fieldLabel(c,
                "Camera ID 0으로 사진 한 장을 찍어 MYBOX의 PolDock 폴더에 전송합니다.\n"
                        + "날짜_시각.png로 보관하고 latest.png도 최신 사진으로 갱신합니다."));

        TextView tokenButton = Ui.button(c,
                tokenSaved ? "MYBOX 토큰 변경" : "MYBOX 토큰 입력",
                tokenSaved ? Ui.GREY : Ui.ACCENT,
                v -> showMyBoxTokenDialog(false));
        tokenButton.setEnabled(supported);
        tokenButton.setAlpha(supported ? 1f : 0.45f);
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonLp.topMargin = Ui.dp(c, 10);
        card.addView(tokenButton, buttonLp);

        TextView guide = Ui.button(c, "토큰 발급받기", Ui.GREY,
                v -> launchAndClose(new Intent(c, MyBoxTokenActivity.class)));
        LinearLayout.LayoutParams guideLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        guideLp.topMargin = Ui.dp(c, 8);
        card.addView(guide, guideLp);

        if (tokenSaved) {
            TextView test = Ui.button(c, "지금 촬영·전송", Ui.GREEN,
                    v -> svc.testParkingPhoto());
            LinearLayout.LayoutParams testLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            testLp.topMargin = Ui.dp(c, 8);
            card.addView(test, testLp);
        }

        String status = sp.getString(ParkingPhotoManager.KEY_STATUS, "아직 촬영 기록이 없습니다.");
        card.addView(Ui.fieldLabel(c, "상태 · " + status));
        return card;
    }

    private void showMyBoxTokenDialog(boolean enableAfterSave) {
        Context c = svc;
        if (!SecureTokenStore.isSupported()) {
            android.widget.Toast.makeText(c, "Android 6 이상에서 사용할 수 있습니다.",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        EditText token = Ui.field(c, "mbx_pat_…");
        token.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setSingleLine(true);

        AlertDialog.Builder builder = new AlertDialog.Builder(c)
                .setTitle("MYBOX 개인 액세스 토큰")
                .setMessage("토큰은 Android Keystore로 암호화해 이 기기에만 저장합니다.")
                .setView(token)
                .setPositiveButton("저장", (dialog, which) -> {
                    String value = token.getText().toString().trim();
                    if (value.isEmpty()) {
                        android.widget.Toast.makeText(c, "토큰을 입력하세요.",
                                android.widget.Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (SecureTokenStore.save(c, value)) {
                        SharedPreferences.Editor editor = sp.edit();
                        if (enableAfterSave) {
                            editor.putBoolean(ShortcutStore.KEY_PARKING_PHOTO_ENABLED, true);
                        }
                        editor.putString(ParkingPhotoManager.KEY_STATUS, "촬영 준비됨").apply();
                    } else {
                        android.widget.Toast.makeText(c, "토큰을 안전하게 저장하지 못했습니다.",
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("취소", null);
        if (SecureTokenStore.hasToken(c)) {
            builder.setNeutralButton("토큰 삭제", (dialog, which) -> {
                SecureTokenStore.clear(c);
                sp.edit().putBoolean(ShortcutStore.KEY_PARKING_PHOTO_ENABLED, false)
                        .putString(ParkingPhotoManager.KEY_STATUS, "MYBOX 토큰이 삭제됨")
                        .apply();
            });
        }
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setType(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE);
        }
        dialog.show();
    }

    private boolean startWidgetDrag(View row, View handle, String id, LinearLayout container,
                                    List<String> order) {
        WidgetDragData local = new WidgetDragData(id, row, container, order);
        View.DragShadowBuilder shadow = new WidgetDragShadow(row, handle);
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
                    row.setBackground(Ui.round(0xFF343A46, Ui.dp(svc, 8)));
                    previewWidgetOrder(dragged, row, targetId);
                }
                return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                autoScrollWidgetDrag(row, event.getY());
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                row.setBackground(Ui.round(Ui.FIELD, Ui.dp(svc, 8)));
                return true;
            case DragEvent.ACTION_DROP:
                row.setBackground(Ui.round(Ui.FIELD, Ui.dp(svc, 8)));
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                dragged.row.setAlpha(1f);
                row.setBackground(Ui.round(Ui.FIELD, Ui.dp(svc, 8)));
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
        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.sectionLabel(c, "바로가기"), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView addButton = new TextView(c);
        addButton.setText("+");
        addButton.setTextColor(Color.WHITE);
        addButton.setTextSize(30);
        addButton.setGravity(Gravity.CENTER);
        addButton.setIncludeFontPadding(false);
        addButton.setBackground(Ui.pressable(Ui.GREY, 0xFF50535C, Ui.dp(c, 8)));
        addButton.setOnClickListener(v -> launchAndClose(new Intent(c, AppPickerActivity.class)
                .putExtra(AppPickerActivity.EXTRA_RETURN_TO_DOCK_EDITOR, true)));
        addButton.setContentDescription("앱 추가");
        header.addView(addButton, new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)));
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerLp.bottomMargin = Ui.dp(c, 8);
        card.addView(header, headerLp);

        final List<Shortcut> list = ShortcutStore.load(c);
        if (list.isEmpty()) {
            TextView e = Ui.fieldLabel(c, "바로가기가 없습니다.");
            card.addView(e);
            return card;
        }
        for (int i = 0; i < list.size(); i++) {
            final Shortcut s = list.get(i);
            final int fi = i;
            LinearLayout row = listRow();
            View handle = Ui.chip(c, "≡", Ui.GREY, v -> { });
            handle.setContentDescription("순서 변경");
            handle.setOnLongClickListener(v -> startShortcutDrag(row, v, s, card, list));
            LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            handleLp.rightMargin = Ui.dp(c, 12);
            row.addView(handle, handleLp);

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

            View delete = Ui.compactIconButton(c, new TrashIconDrawable(), "삭제",
                    Ui.DANGER, v -> confirmDelete(fi));
            LinearLayout.LayoutParams deleteLp = new LinearLayout.LayoutParams(
                    Ui.dp(c, 40), Ui.dp(c, 40));
            deleteLp.leftMargin = Ui.dp(c, 8);
            row.addView(delete, deleteLp);
            row.setOnDragListener((target, event) -> handleShortcutDrag(row, s, event));
            card.addView(row);
        }
        LayoutTransition transition = new LayoutTransition();
        transition.setDuration(120);
        card.setLayoutTransition(transition);
        return card;
    }

    private boolean startShortcutDrag(View row, View handle, Shortcut shortcut,
                                      LinearLayout container, List<Shortcut> order) {
        ShortcutDragData local = new ShortcutDragData(shortcut, row, container, order);
        View.DragShadowBuilder shadow = new WidgetDragShadow(row, handle);
        boolean started;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            started = row.startDragAndDrop(
                    ClipData.newPlainText("shortcut", shortcut.label), shadow, local, 0);
        } else {
            started = row.startDrag(
                    ClipData.newPlainText("shortcut", shortcut.label), shadow, local, 0);
        }
        if (started) row.setAlpha(0.45f);
        return started;
    }

    private boolean handleShortcutDrag(View row, Shortcut targetShortcut, DragEvent event) {
        if (!(event.getLocalState() instanceof ShortcutDragData)) return false;
        ShortcutDragData dragged = (ShortcutDragData) event.getLocalState();
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return true;
            case DragEvent.ACTION_DRAG_ENTERED:
                if (dragged.row != row) {
                    row.setBackground(Ui.round(0xFF343A46, Ui.dp(svc, 8)));
                    previewShortcutOrder(dragged, row, targetShortcut);
                }
                return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                autoScrollWidgetDrag(row, event.getY());
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                row.setBackground(Ui.round(Ui.FIELD, Ui.dp(svc, 8)));
                return true;
            case DragEvent.ACTION_DROP:
                row.setBackground(Ui.round(Ui.FIELD, Ui.dp(svc, 8)));
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                dragged.row.setAlpha(1f);
                row.setBackground(Ui.round(Ui.FIELD, Ui.dp(svc, 8)));
                saveShortcutDragOrder(dragged);
                return true;
            default:
                return true;
        }
    }

    private void previewShortcutOrder(ShortcutDragData dragged, View target,
                                      Shortcut targetShortcut) {
        int sourceViewIndex = dragged.container.indexOfChild(dragged.row);
        int targetViewIndex = dragged.container.indexOfChild(target);
        int sourceOrderIndex = dragged.order.indexOf(dragged.shortcut);
        int targetOrderIndex = dragged.order.indexOf(targetShortcut);
        if (sourceViewIndex < 0 || targetViewIndex < 0
                || sourceOrderIndex < 0 || targetOrderIndex < 0
                || sourceOrderIndex == targetOrderIndex) return;

        ViewGroup.LayoutParams params = dragged.row.getLayoutParams();
        dragged.container.removeView(dragged.row);
        dragged.container.addView(dragged.row,
                Math.min(targetViewIndex, dragged.container.getChildCount()), params);
        dragged.order.remove(sourceOrderIndex);
        dragged.order.add(Math.min(targetOrderIndex, dragged.order.size()), dragged.shortcut);
    }

    private void saveShortcutDragOrder(ShortcutDragData dragged) {
        if (dragged.saved) return;
        dragged.saved = true;
        List<Shortcut> result = new ArrayList<>(dragged.order);
        dragged.container.post(() -> ShortcutStore.save(svc, result));
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
        row.setBackground(Ui.round(Ui.FIELD, Ui.dp(c, 8)));
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
