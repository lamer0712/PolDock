package com.oemtools.poldock;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 떠다니는 도크. 모든 위젯을 iOS 기하(코너 22.37%)의 둥근 사각 타일로 아이콘화. */
public class OverlayService extends Service {

    private static final String CH_ID = "mirror_fold_overlay";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";
    static final String KEY_ORI = "dock_ori";
    private static final int TILE_DP = 52;
    // 런처 즐겨찾기 카드(타일) 크기: fav_card_width/height (OEM dimens)
    private static final int GRID_TILE_W_DP = 532;
    private static final int GRID_TILE_H_DP = 576;
    // 런처 카드 헤더 아이콘: card_icon_size=64dp, card_icon_margin_*=32dp
    private static final int CARD_ICON_DP = 64;
    private static final int CARD_ICON_MARGIN_DP = 32;
    // 타일 기본색 = 날짜(캘린더) 타일과 동일: 흰 바탕 + 어두운 내용
    private static final int TILE_BG = 0xFFFFFFFF;
    private static final int TILE_FG = 0xFF1C1C1E;
    private static final int TILE_LABEL = 0xFF4E5157;
    private float SCALE = 1.5f;

    /** macOS Dock 컨테이너 코너 ≈ 두께의 25% */
    private static final float MAC_DOCK_CORNER_RATIO = 0.25f;

    private WindowManager wm;
    private View dock;
    private GradientDrawable barBg;
    private LinearLayout shortcutRow;
    private TextView timeView, timeAmPm, tempView, dustView, dateWeekday, dateDay;
    private ImageView weatherIconView, musicArt, carSweep, speedNeedle, speedGauge;
    private TextView musicPh;
    private String prevGear;
    private View[] lampDots;
    private boolean hazardOn;
    private BatteryBar batteryBar;
    private boolean hasVehWidget;
    private boolean gridActive;
    private LinearLayout gridDots;   // 그리드 페이지 표시(헤더)
    private EditPanel editPanel;     // 독 위 편집 패널
    private String pendingSnapEdge;  // 재구성 후 벽에 붙일 위치 (top/bottom/left/right/gridTop/gridBottom)
    private static final int SNAP_EDGE_DP = 56;   // 벽 스냅 발동 범위 (손가락 두께 정도)

    /** 속도 → 바늘 회전각(나침반 방위). SW(225°)=0, N(360°)=100, SE(495°=135°)=200 */
    private float speedAngle(int kmh) {
        return 225f + kmh * 1.35f;
    }
    private final Map<String, TextView> vehValues = new LinkedHashMap<>();

    // 재생성 시 즉시 보여줄 마지막 값 캐시 (깜빡임 방지)
    private String cacheTemp;
    private WeatherIcon.Kind cacheKind;
    private int cachePm10 = -2, cachePm25 = -2;
    private TextView dustLabel;
    private boolean dustShowPm25;
    private final Runnable dustRevert = () -> {
        dustShowPm25 = false;
        renderDustCurrent();
    };
    private android.graphics.Bitmap cacheArt;
    private final Map<String, CharSequence> cacheVeh = new LinkedHashMap<>();

    static boolean isRunning;
    private WindowManager.LayoutParams lp;
    private SharedPreferences sp;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private VehicleInfo vehicle;
    private HandlerThread vehThread;
    private Handler vehHandler;
    private android.view.GestureDetector handleGesture;
    private java.util.Set<String> homePackages;
    private String lastFgPkg;
    private String mode = "default";   // 현재 배치 모드: default / home
    private boolean homeFgNow;

    /** 현재 모드의 pref 키 (home 모드는 _home 접미사) */
    private String mk(String base) {
        return "home".equals(mode) ? base + "_home" : base;
    }

    private String activeMode() {
        if (!sp.getBoolean(ShortcutStore.KEY_HOME_PROFILE, false)) return "default";
        String em = sp.getString(ShortcutStore.KEY_EDIT_MODE, "auto");
        if ("home".equals(em)) return "home";
        if ("default".equals(em)) return "default";
        return homeFgNow ? "home" : "default";   // auto: 포그라운드 따라
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (prefs, key) -> {
                if (key == null) return;
                if (ShortcutStore.KEY.equals(key)) {
                    if (gridActive) installDock(); else rebuildShortcuts();
                } else installDock();
            };

    private final Runnable clockTick = new Runnable() {
        public void run() {
            updateClock();
            ui.postDelayed(this, 1000);
        }
    };
    private final Runnable envTick = new Runnable() {
        public void run() {
            fetchEnv();
            ui.postDelayed(this, 15 * 60 * 1000L);
        }
    };
    private final Runnable musicTick = new Runnable() {
        public void run() {
            updateMusic();
            ui.postDelayed(this, 3000);
        }
    };
    private final Runnable hazardTick = new Runnable() {
        public void run() {
            if (lampDots == null || !"P".equals(prevGear)) return;
            hazardOn = !hazardOn;
            for (View d : lampDots) if (d != null) d.setAlpha(hazardOn ? 1f : 0.06f);
            ui.postDelayed(this, 450);
        }
    };
    private final Runnable fgTick = new Runnable() {
        public void run() {
            updateHomeMode();
            ui.postDelayed(this, 1200);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        sp = getSharedPreferences(ShortcutStore.PREF, MODE_PRIVATE);
        handleGesture = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        openApp();
                        return true;
                    }
                });
        startForegroundSafely();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = sp.getInt(KEY_X, d(24));
        lp.y = sp.getInt(KEY_Y, d(60));
        installDock();
        sp.registerOnSharedPreferenceChangeListener(prefListener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private boolean isVertical() {
        return "v".equals(sp.getString(mk(KEY_ORI), "h"));
    }

    private boolean on(String key) {
        return sp.getBoolean(key, false);
    }

    private int tileSide() {
        return d(TILE_DP);
    }

    // ---------------------------------------------------------------- 구성

    private void installDock() {
        ui.removeCallbacks(clockTick);
        ui.removeCallbacks(envTick);
        ui.removeCallbacks(musicTick);
        ui.removeCallbacks(hazardTick);
        stopVehicle();
        ui.removeCallbacks(fgTick);
        vehValues.clear();
        mode = activeMode();
        lp.x = sp.getInt(mk(KEY_X), d(24));
        lp.y = sp.getInt(mk(KEY_Y), d(60));
        int pct = sp.getInt(mk(ShortcutStore.KEY_SCALE), ShortcutStore.DEFAULT_SCALE);
        SCALE = Math.max(50, Math.min(200, pct)) / 100f;
        boolean vertical = isVertical();

        // 창은 유지하고 내용만 교체 → 창 제거/재생성 깜빡임 방지
        LinearLayout bar;
        if (dock == null) {
            bar = new LinearLayout(this);
            dock = bar;
            barBg = new GradientDrawable();
            barBg.setColor(0xDD111111);
            barBg.setStroke(d(1), 0x55FFFFFF);
            barBg.setCornerRadius(d(26));
            bar.setBackground(barBg);
            // 크기가 바뀔 때마다 코너 반경 재계산 (그리드↔가로 전환 시 묵은 크기로 계산되는 문제 방지)
            dock.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if ((r - l) != (or - ol) || (b - t) != (ob - ot)) applyDockCorner();
            });
            wm.addView(dock, lp);
        } else {
            bar = (LinearLayout) dock;
            bar.removeAllViews();
        }
        boolean grid = "g".equals(sp.getString(mk(KEY_ORI), "h"));
        gridActive = grid;
        bar.setOrientation(grid || vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        // 그리드: 런처 타일 크기로 강제 (각진 사각 박스), 비율 슬라이더 무시 / 그 외: 둥근 독
        if (grid) {
            float density = getResources().getDisplayMetrics().density;
            lp.width = Math.round(GRID_TILE_W_DP * density);
            lp.height = Math.round(GRID_TILE_H_DP * density);
            bar.setPadding(0, 0, 0, 0);
            // 셀 크기에 맞춰 위젯 스케일을 산출 → 슬라이더(SCALE) 대신 타일/격자 기준
            int gc = Math.max(1, Math.min(6, sp.getInt(ShortcutStore.KEY_GRID_C, ShortcutStore.DEFAULT_GRID)));
            int gr = Math.max(1, Math.min(8, sp.getInt(ShortcutStore.KEY_GRID_R, ShortcutStore.DEFAULT_GRID)));
            float gridH = lp.height - px(CARD_ICON_DP) - px(CARD_ICON_MARGIN_DP) * 2;  // 헤더(상+하 여백) 제외
            float cell = Math.min(lp.width / (float) gc, gridH / gr);
            float tilePx = cell * 0.82f;                 // 셀 안에 여백을 두고 채움
            SCALE = tilePx / (TILE_DP * density);
            SCALE = Math.max(0.5f, Math.min(4f, SCALE)); // 과대/과소 방지
        } else {
            lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            bar.setPadding(d(8), d(8), d(8), d(8));
        }

        TextView handle = new TextView(this);
        handle.setText("≡");
        handle.setTextColor(0xFFCCCCCC);
        handle.setGravity(Gravity.CENTER);
        handle.setOnTouchListener(new DragHandle());
        if (grid) {
            // 그리드: 핸들은 아래 헤더(우상단 편집 버튼 자리)에서 배치
            handle.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(CARD_ICON_DP) * 0.72f);
        } else {
            handle.setTextSize(TypedValue.COMPLEX_UNIT_PX, tileSide() * 0.5f);
            handle.setPadding(d(12), d(8), d(12), d(8));
            bar.addView(handle);
        }

        // 위젯을 저장된 순서대로 수집 (켜진 것만)
        final java.util.List<View> items = new java.util.ArrayList<>();
        weatherIconView = null;
        tempView = null;
        dustView = null;
        dustLabel = null;
        dustShowPm25 = false;
        ui.removeCallbacks(dustRevert);
        dateWeekday = null;
        timeView = null;
        musicArt = null;
        musicPh = null;
        carSweep = null;
        lampDots = null;
        speedNeedle = null;
        speedGauge = null;
        batteryBar = null;
        hasVehWidget = false;
        for (String id : ShortcutStore.widgetOrder(this)) {
            if (!sp.getBoolean(ShortcutStore.widgetOnKey(id), ShortcutStore.widgetDefaultOn(id)))
                continue;
            View v = buildWidget(id);
            if (v != null) items.add(v);
        }
        if (dateWeekday != null || timeView != null) {
            updateClock();
            ui.post(clockTick);
        }
        if (weatherIconView != null || dustView != null) ui.post(envTick);
        if (musicArt != null) ui.post(musicTick);
        ui.removeCallbacks(fgTick);
        if (dock != null) dock.setVisibility(View.VISIBLE);
        // 홈 프로필 사용 + 자동(편집 아님) + 사용정보 접근 시 포그라운드 따라 모드 전환
        if (sp.getBoolean(ShortcutStore.KEY_HOME_PROFILE, false)
                && "auto".equals(sp.getString(ShortcutStore.KEY_EDIT_MODE, "auto"))
                && hasUsageAccess()) {
            ui.post(fgTick);
        }

        DisplayMetrics dm = realMetrics();
        if (grid) {
            shortcutRow = null;
            for (Shortcut s : ShortcutStore.load(this)) items.add(makeItem(s, false));
            int cols = Math.max(1, Math.min(6, sp.getInt(ShortcutStore.KEY_GRID_C, ShortcutStore.DEFAULT_GRID)));
            int rows = Math.max(1, Math.min(8, sp.getInt(ShortcutStore.KEY_GRID_R, ShortcutStore.DEFAULT_GRID)));
            int perPage = Math.max(1, cols * rows);
            final int pages = Math.max(1, (items.size() + perPage - 1) / perPage);

            // 런처 카드 헤더: 좌상단 앱 아이콘 + 우상단 핸들(편집 버튼 자리)
            int ic = px(CARD_ICON_DP), m = px(CARD_ICON_MARGIN_DP);
            FrameLayout header = new FrameLayout(this);
            header.setOnTouchListener(new DragHandle());   // 헤더 드래그로 독 이동
            TextView appName = new TextView(this);
            appName.setText("PolDock");
            appName.setTextColor(0xFFFFFFFF);
            appName.setTypeface(appName.getTypeface(), android.graphics.Typeface.BOLD);
            appName.setTextSize(TypedValue.COMPLEX_UNIT_PX, ic * 0.42f);
            appName.setGravity(Gravity.CENTER_VERTICAL);
            appName.setIncludeFontPadding(false);
            appName.setSingleLine(true);
            FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ic, Gravity.START | Gravity.TOP);
            alp.setMargins(m, m, 0, 0);
            header.addView(appName, alp);
            // 우상단: 런처 편집 버튼(ic_control_edit)과 동일한 아이콘/크기/위치 — 원클릭으로 설정 열기
            ImageView editBtn = new ImageView(this);
            editBtn.setImageResource(R.drawable.ic_control_edit);
            editBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
            editBtn.setPadding(px(8), px(8), px(8), px(8));
            editBtn.setClickable(true);
            editBtn.setOnClickListener(v -> openApp());
            FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(ic, ic, Gravity.END | Gravity.TOP);
            hlp.setMargins(0, m, m, 0);
            header.addView(editBtn, hlp);
            // 헤더 가운데: 페이지 표시 점 (여러 페이지일 때만)
            gridDots = null;
            if (pages > 1) {
                gridDots = new LinearLayout(this);
                gridDots.setOrientation(LinearLayout.HORIZONTAL);
                gridDots.setGravity(Gravity.CENTER);
                int dot = px(8), gap = px(5);
                for (int p = 0; p < pages; p++) {
                    View d = new View(this);
                    GradientDrawable dg = new GradientDrawable();
                    dg.setShape(GradientDrawable.OVAL);
                    dg.setColor(p == 0 ? 0xFFFFFFFF : 0x66FFFFFF);
                    d.setBackground(dg);
                    LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dot, dot);
                    dlp.leftMargin = p == 0 ? 0 : gap;
                    gridDots.addView(d, dlp);
                }
                FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ic, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
                glp.topMargin = m;
                header.addView(gridDots, glp);
            }
            // 아이콘 상단 여백(m)과 동일한 하단 여백을 둬 위젯과 간격 확보
            LinearLayout.LayoutParams headlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ic + m * 2);
            bar.addView(header, headlp);

            // 좌우 페이지: 각 페이지가 타일 폭을 꽉 채우는 cols×rows 격자
            final HorizontalScrollView pager = new HorizontalScrollView(this);
            pager.setHorizontalScrollBarEnabled(false);
            pager.setOverScrollMode(View.OVER_SCROLL_NEVER);
            LinearLayout pagesRow = new LinearLayout(this);
            pagesRow.setOrientation(LinearLayout.HORIZONTAL);
            final int pageW = lp.width;
            int idx = 0;
            for (int p = 0; p < pages; p++) {
                LinearLayout colv = new LinearLayout(this);
                colv.setOrientation(LinearLayout.VERTICAL);
                for (int r = 0; r < rows; r++) {
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    for (int ccol = 0; ccol < cols; ccol++) {
                        FrameLayout cell = new FrameLayout(this);
                        if (idx < items.size()) {
                            View w = items.get(idx);
                            int side = tileSide();
                            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(side, side, Gravity.CENTER);
                            clp.setMargins(d(4), d(4), d(4), d(4));
                            cell.addView(w, clp);
                        }
                        idx++;
                        row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
                    }
                    colv.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
                }
                pagesRow.addView(colv, new LinearLayout.LayoutParams(pageW, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            pager.addView(pagesRow, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // 손 떼면 가장 가까운 페이지로 스냅 + 점 갱신
            if (pages > 1) {
                pager.setOnTouchListener((v, e) -> {
                    if (e.getAction() == android.view.MotionEvent.ACTION_UP
                            || e.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                        pager.postDelayed(() -> snapPage(pager, pageW, pages), 130);
                    }
                    return false;
                });
            }
            bar.addView(pager, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            content.setGravity(Gravity.CENTER);
            for (View v : items) content.addView(v);
            shortcutRow = new LinearLayout(this);
            shortcutRow.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            shortcutRow.setGravity(Gravity.CENTER);
            content.addView(shortcutRow);

            FrameLayout scroll = vertical ? new ScrollView(this) : new HorizontalScrollView(this);
            scroll.addView(content);
            if (vertical) {
                ((ScrollView) scroll).setVerticalScrollBarEnabled(false);
                bar.addView(scroll);
                capHeight(scroll, dm.heightPixels - insetTop() - insetBottom() - d(70));
            } else {
                ((HorizontalScrollView) scroll).setHorizontalScrollBarEnabled(false);
                bar.addView(scroll);
                capWidth(scroll, dm.widthPixels - insetLeftRight() - d(70));
            }
            rebuildShortcuts();
        }
        if (barBg != null) {
            if (grid) {
                // 런처 타일처럼: 불투명 배경(card_background #141516), 테두리 없음, 각진 사각
                barBg.setColor(0xFF141516);
                barBg.setStroke(0, 0);
                barBg.setCornerRadius(0f);
            } else {
                barBg.setColor(0xDD111111);
                barBg.setStroke(d(1), 0x55FFFFFF);
            }
        }
        dock.post(() -> {
            applyDockCorner();
            if (pendingSnapEdge != null) {
                String e = pendingSnapEdge;
                pendingSnapEdge = null;
                applySnapEdge(e);        // 새 크기 기준으로 벽에 붙임
            } else {
                clamp();
                wm.updateViewLayout(dock, lp);
            }
        });

        if (hasVehWidget) startVehicle();
    }

    // ---------------------------------------------------------------- 타일 공통

    /** 둥근 사각 타일 컨테이너 (iOS 코너 비율). */
    private LinearLayout tileBox(int fillColor) {
        int side = tileSide();
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(fillColor);
        g.setCornerRadius(side * CalendarTile.CORNER_RATIO);
        l.setBackground(g);
        l.setPadding(d(3), d(2), d(3), d(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side);
        lp.setMargins(d(5), d(3), d(5), d(3));
        l.setLayoutParams(lp);
        return l;
    }

    /** 값 글씨: 한 줄 + 길면 자동 축소 (50km 등 줄바꿈 방지) */
    private TextView valueText(int side) {
        TextView t = tileText(side * 0.36f, TILE_FG, true);
        t.setMaxLines(1);
        if (Build.VERSION.SDK_INT >= 26) {
            int max = Math.round(side * 0.36f);
            int min = Math.round(side * 0.22f);
            int step = Math.max(1, Math.round(side * 0.02f));
            t.setAutoSizeTextTypeUniformWithConfiguration(min, max, step,
                    TypedValue.COMPLEX_UNIT_PX);
        }
        return t;
    }

    private TextView tileText(float pxSize, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, pxSize);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    // ---------------------------------------------------------------- 날짜/시간

    private View buildDateTile() {
        int side = tileSide();
        LinearLayout box = tileBox(TILE_BG);
        dateWeekday = tileText(side * 0.20f, TILE_LABEL, false);   // 요일 (위, 라벨)
        dateDay = valueText(side);                                 // 날짜 (아래, 값)
        box.addView(dateWeekday);
        box.addView(dateDay);
        return box;
    }

    private View buildTimeTile() {
        int side = tileSide();
        LinearLayout box = tileBox(TILE_BG);
        timeAmPm = tileText(side * 0.20f, TILE_LABEL, false);   // 오전/오후 (위, 작게)
        timeView = valueText(side);       // 시각 (아래, 크게)
        box.addView(timeAmPm);
        box.addView(timeView);
        return box;
    }

    private void updateClock() {
        if (dateWeekday != null) {
            Date now = new Date();
            dateWeekday.setText(new SimpleDateFormat("E", Locale.getDefault()).format(now));
            dateDay.setText(new SimpleDateFormat("d", Locale.getDefault()).format(now));
        }
        if (timeView != null) {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            int h = cal.get(java.util.Calendar.HOUR_OF_DAY);
            int m = cal.get(java.util.Calendar.MINUTE);
            int hr = h % 12;
            if (hr == 0) hr = 12;
            timeView.setText(String.format(Locale.US, "%d:%02d", hr, m));
            if (timeAmPm != null) {
                timeAmPm.setVisibility(View.VISIBLE);
                timeAmPm.setText(h < 12 ? "오전" : "오후");
            }
        }
    }

    // ---------------------------------------------------------------- 날씨/미세먼지

    private View buildWeatherTile() {
        int side = tileSide();
        LinearLayout box = tileBox(TILE_BG);
        tempView = tileText(side * 0.24f, TILE_LABEL, false);   // 온도 (°기호 보정으로 살짝 키움)
        tempView.setText(cacheTemp != null ? cacheTemp : "—");
        box.addView(tempView);
        weatherIconView = new ImageView(this);                  // 그림 (아래)
        int isz = Math.round(side * 0.40f);
        if (cacheKind != null) weatherIconView.setImageDrawable(new WeatherIcon(cacheKind));
        box.addView(weatherIconView, new LinearLayout.LayoutParams(isz, isz));
        return box;
    }

    private View buildDustTile() {
        int side = tileSide();
        LinearLayout box = tileBox(TILE_BG);
        dustLabel = tileText(side * 0.20f, TILE_LABEL, false);   // 라벨 (미세/초미세)
        dustLabel.setText("미세");
        dustView = valueText(side);   // 값 (아래)
        dustView.setText("…");
        box.addView(dustLabel);
        box.addView(dustView);
        box.setClickable(true);
        box.setOnClickListener(v -> toggleDust());
        renderDustCurrent();
        return box;
    }

    /** 탭: 미세→초미세(5초 후/재탭 시 미세로 복귀) */
    private void toggleDust() {
        ui.removeCallbacks(dustRevert);
        dustShowPm25 = !dustShowPm25;
        if (dustShowPm25) ui.postDelayed(dustRevert, 5000);
        renderDustCurrent();
    }

    private void renderDustCurrent() {
        if (dustView == null) return;
        int pm = dustShowPm25 ? cachePm25 : cachePm10;
        if (dustLabel != null) dustLabel.setText(dustShowPm25 ? "초미세" : "미세");
        if (pm < 0) {
            dustView.setText("—");
            dustView.setTextColor(TILE_FG);
            return;
        }
        dustView.setText(String.valueOf(pm));
        dustView.setTextColor(dustGradeColor(pm, dustShowPm25));
    }

    /** 한국 기준 등급색. 흰 바탕 가독용 어두운 색. */
    private int dustGradeColor(int pm, boolean pm25) {
        int good, moderate, bad;
        if (pm25) { good = 15; moderate = 35; bad = 75; }   // PM2.5
        else { good = 30; moderate = 80; bad = 150; }        // PM10
        if (pm <= good) return 0xFF1E8E3E;
        if (pm <= moderate) return 0xFFB8860B;
        if (pm <= bad) return 0xFFE0661A;
        return 0xFFD23B30;
    }

    // ---------------------------------------------------------------- 음악(앨범아트)

    private View buildMusicTile() {
        int side = tileSide();
        LinearLayout box = tileBox(TILE_BG);
        int isz = Math.round(side * 0.86f);
        final float radius = side * CalendarTile.CORNER_RATIO;

        FrameLayout fl = new FrameLayout(this);
        musicArt = new ImageView(this);
        musicArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        musicArt.setClipToOutline(true);
        musicArt.setOutlineProvider(new android.view.ViewOutlineProvider() {
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        if (cacheArt != null) musicArt.setImageBitmap(cacheArt);
        musicArt.setVisibility(cacheArt != null ? View.VISIBLE : View.GONE);
        fl.addView(musicArt, new FrameLayout.LayoutParams(isz, isz));

        musicPh = new TextView(this);          // 🎵 이모지 플레이스홀더
        musicPh.setText("🎵");
        musicPh.setGravity(Gravity.CENTER);
        musicPh.setTextSize(TypedValue.COMPLEX_UNIT_PX, isz * 0.60f);
        musicPh.setVisibility(cacheArt != null ? View.GONE : View.VISIBLE);
        fl.addView(musicPh, new FrameLayout.LayoutParams(isz, isz));

        box.addView(fl, new LinearLayout.LayoutParams(isz, isz));
        box.setOnClickListener(v -> {
            // 앨범아트 없으면 알림 접근 설정 열기
            try {
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
        });
        return box;
    }

    private void updateMusic() {
        if (musicArt == null) return;
        new Thread(() -> {
            final android.graphics.Bitmap art = fetchAlbumArt();
            ui.post(() -> {
                if (musicArt == null) return;
                if (art != null) {
                    cacheArt = art;
                    musicArt.setImageBitmap(art);
                    musicArt.setVisibility(View.VISIBLE);
                    if (musicPh != null) musicPh.setVisibility(View.GONE);
                } else {
                    cacheArt = null;
                    musicArt.setVisibility(View.GONE);
                    if (musicPh != null) musicPh.setVisibility(View.VISIBLE);
                }
            });
        }).start();
    }

    private android.graphics.Bitmap fetchAlbumArt() {
        try {
            android.media.session.MediaSessionManager msm =
                    (android.media.session.MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            java.util.List<android.media.session.MediaController> cs = msm.getActiveSessions(
                    new android.content.ComponentName(this, NotifListener.class));
            android.graphics.Bitmap any = null;
            for (android.media.session.MediaController mc : cs) {
                android.media.MediaMetadata md = mc.getMetadata();
                if (md == null) continue;
                android.graphics.Bitmap b = artFromMetadata(md);
                if (b == null) continue;
                android.media.session.PlaybackState ps = mc.getPlaybackState();
                boolean playing = ps != null
                        && ps.getState() == android.media.session.PlaybackState.STATE_PLAYING;
                if (playing) return b;   // 재생 중 세션 우선
                if (any == null) any = b;
            }
            return any;
        } catch (Throwable t) {
            return null;   // 알림 접근 권한 없음 등
        }
    }

    private android.graphics.Bitmap artFromMetadata(android.media.MediaMetadata md) {
        android.graphics.Bitmap b = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (b != null) return b;
        // 비트맵이 없으면 URI 로 로드
        String uri = md.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
        if (uri == null) uri = md.getString(android.media.MediaMetadata.METADATA_KEY_ART_URI);
        if (uri == null) uri = md.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
        if (uri == null) return null;
        try (java.io.InputStream is = getContentResolver()
                .openInputStream(android.net.Uri.parse(uri))) {
            return android.graphics.BitmapFactory.decodeStream(is);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressLint("MissingPermission")
    private void fetchEnv() {
        final boolean weather = weatherIconView != null;
        final boolean dust = dustView != null;
        if (!weather && !dust) return;
        new Thread(() -> {
            Location loc = lastLocation();
            if (loc == null) {
                if (weather && cacheTemp == null) post(tempView, "위치");
                return;
            }
            if (weather) {
                try {
                    String j = httpGet("https://api.open-meteo.com/v1/forecast?latitude="
                            + loc.getLatitude() + "&longitude=" + loc.getLongitude()
                            + "&current=temperature_2m,weather_code");
                    JSONObject cur = new JSONObject(j).getJSONObject("current");
                    final WeatherIcon.Kind kind = WeatherIcon.fromCode(cur.getInt("weather_code"));
                    final String tt = Math.round(cur.getDouble("temperature_2m")) + "°";
                    cacheTemp = tt;
                    cacheKind = kind;
                    ui.post(() -> {
                        if (weatherIconView != null)
                            weatherIconView.setImageDrawable(new WeatherIcon(kind));
                        if (tempView != null) tempView.setText(tt);
                    });
                } catch (Throwable t) {
                    if (cacheTemp == null) post(tempView, "—");  // 캐시 있으면 유지
                }
            }
            if (dust) {
                try {
                    String j = httpGet("https://air-quality-api.open-meteo.com/v1/air-quality?latitude="
                            + loc.getLatitude() + "&longitude=" + loc.getLongitude()
                            + "&current=pm2_5,pm10");
                    JSONObject cur = new JSONObject(j).getJSONObject("current");
                    cachePm25 = (int) Math.round(cur.getDouble("pm2_5"));
                    cachePm10 = (int) Math.round(cur.getDouble("pm10"));
                    ui.post(this::renderDustCurrent);
                } catch (Throwable ignored) {
                }
            }
        }).start();
    }

    @SuppressLint("MissingPermission")
    private Location lastLocation() {
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location best = null;
            for (String p : new String[]{LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                try {
                    Location l = lm.getLastKnownLocation(p);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                } catch (Throwable ignored) {
                }
            }
            if (best != null) return best;
            // 캐시된 위치가 없으면 한 번 즉시 요청 (부팅 직후/에뮬 대응)
            if (Build.VERSION.SDK_INT >= 30) {
                for (String p : new String[]{LocationManager.GPS_PROVIDER,
                        LocationManager.NETWORK_PROVIDER}) {
                    if (!lm.isProviderEnabled(p)) continue;
                    final java.util.concurrent.CountDownLatch latch =
                            new java.util.concurrent.CountDownLatch(1);
                    final Location[] out = new Location[1];
                    lm.getCurrentLocation(p, null, getMainExecutor(), loc -> {
                        out[0] = loc;
                        latch.countDown();
                    });
                    latch.await(8, java.util.concurrent.TimeUnit.SECONDS);
                    if (out[0] != null) return out[0];
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private void post(TextView v, String text) {
        ui.post(() -> {
            if (v != null) v.setText(text);
        });
    }

    private String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- 차량 타일

    /** 위젯 id → 뷰 */
    private View buildWidget(String id) {
        switch (id) {
            case "date": return buildDateTile();
            case "time": return buildTimeTile();
            case "weather": return buildWeatherTile();
            case "dust": return buildDustTile();
            case "music": return buildMusicTile();
            case "v_gear": return buildVehTile(ShortcutStore.KEY_V_GEAR, "기어");
            case "v_battery": return buildVehTile(ShortcutStore.KEY_V_BATTERY, "배터리");
            case "v_range": return buildVehTile(ShortcutStore.KEY_V_RANGE, "주행거리");
            case "v_speed": return buildVehTile(ShortcutStore.KEY_V_SPEED, "속도");
            case "v_eff": return buildVehTile(ShortcutStore.KEY_V_EFF, "전비");
            case "v_temp": return buildVehTile(ShortcutStore.KEY_V_TEMP, "외기온도");
        }
        return null;
    }

    private View buildVehTile(String key, String label) {
        int side = tileSide();
        hasVehWidget = true;
        boolean gear = ShortcutStore.KEY_V_GEAR.equals(key);
        boolean speed = ShortcutStore.KEY_V_SPEED.equals(key);
        LinearLayout box = tileBox(TILE_BG);
        if (gear || speed) box.setBackground(null);   // 배경은 FrameLayout에서 처리
        TextView lab = tileText(side * 0.20f, TILE_LABEL, false);   // 라벨 (위)
        lab.setText(label);
        TextView val = valueText(side);   // 값 (아래)
        CharSequence cached = cacheVeh.get(key);
        val.setText(cached != null ? cached : "—");   // 재생성 시 마지막 값 유지
        if (gear && cached != null) val.setTextColor(gearColor(cached.toString()));
        box.addView(lab);
        box.addView(val);
        vehValues.put(key, val);

        if (speed) return buildSpeedTile(box, cached);
        if (!gear) return box;

        // 기어 타일: 흰 배경 + (뒤) 폴스타 차량 애니메이션 + (앞) 라벨/값
        final float radius = side * CalendarTile.CORNER_RATIO;
        FrameLayout tile = new FrameLayout(this);
        tile.setClipToOutline(true);
        tile.setOutlineProvider(new android.view.ViewOutlineProvider() {
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        tile.setBackgroundColor(TILE_BG);
        carSweep = new ImageView(this);
        int carRes = getResources().getIdentifier("polestar", "drawable", getPackageName());
        if (carRes != 0) carSweep.setImageResource(carRes);
        carSweep.setScaleType(ImageView.ScaleType.FIT_CENTER);
        carSweep.setVisibility(View.GONE);
        tile.addView(carSweep, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tile.addView(box, new FrameLayout.LayoutParams(side, side));

        // 재생성 시 현재 P면 중앙 주차 상태 유지
        if (cached != null && "P".equals(cached.toString())) {
            carSweep.setTranslationY(0);
            carSweep.setAlpha(0.3f);
            carSweep.setVisibility(View.VISIBLE);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side);
        lp.setMargins(d(5), d(3), d(5), d(3));
        tile.setLayoutParams(lp);
        return tile;
    }

    /** 배터리 타일: 흰 배경 + (뒤) 5칸 배터리 막대 + (앞) 라벨/퍼센트 */
    private View buildBatteryTile(String label) {
        int side = tileSide();
        final float radius = side * CalendarTile.CORNER_RATIO;
        FrameLayout tile = new FrameLayout(this);
        tile.setClipToOutline(true);
        tile.setOutlineProvider(new android.view.ViewOutlineProvider() {
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        tile.setBackgroundColor(TILE_BG);

        batteryBar = new BatteryBar(this);            // 배경
        batteryBar.setAlpha(0.42f);
        int bw = Math.round(side * 0.6f), bh = Math.round(side * 0.82f);
        FrameLayout.LayoutParams bbp = new FrameLayout.LayoutParams(bw, bh);
        bbp.gravity = Gravity.CENTER;
        tile.addView(batteryBar, bbp);

        LinearLayout box = tileBox(TILE_BG);
        box.setBackground(null);
        TextView lab = tileText(side * 0.20f, TILE_LABEL, false);
        lab.setText(label);
        TextView val = valueText(side);
        CharSequence cached = cacheVeh.get(ShortcutStore.KEY_V_BATTERY);
        val.setText(cached != null ? cached : "—");
        box.addView(lab);
        box.addView(val);
        vehValues.put(ShortcutStore.KEY_V_BATTERY, val);   // % 텍스트 갱신
        tile.addView(box, new FrameLayout.LayoutParams(side, side));

        int pct = parsePct(cached);
        if (pct >= 0) batteryBar.setPercent(pct);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side);
        lp.setMargins(d(5), d(3), d(5), d(3));
        tile.setLayoutParams(lp);
        return tile;
    }

    /** "81%" 등에서 숫자만 추출 */
    private int parsePct(CharSequence s) {
        if (s == null) return -1;
        String digits = s.toString().replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return -1;
        try {
            return Integer.parseInt(digits);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 속도 타일: 흰 배경 + (뒤) 속도계 바늘 + (앞) 라벨/값 */
    private View buildSpeedTile(LinearLayout box, CharSequence cached) {
        int side = tileSide();
        final float radius = side * CalendarTile.CORNER_RATIO;
        FrameLayout tile = new FrameLayout(this);
        tile.setClipToOutline(true);
        tile.setOutlineProvider(new android.view.ViewOutlineProvider() {
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        tile.setBackgroundColor(TILE_BG);
        speedGauge = new ImageView(this);                 // 원형 눈금 (고정)
        speedGauge.setImageDrawable(new SpeedGauge());
        speedGauge.setVisibility(View.GONE);
        tile.addView(speedGauge, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        speedNeedle = new ImageView(this);                // 바늘 (회전)
        speedNeedle.setImageDrawable(new SpeedNeedle());
        speedNeedle.setVisibility(View.GONE);
        tile.addView(speedNeedle, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tile.addView(box, new FrameLayout.LayoutParams(side, side));

        // 재생성 시 현재 속도 반영
        int spd = parseIntSafe(cached);
        if (spd >= 1) {
            speedNeedle.setRotation(speedAngle(spd));
            speedNeedle.setVisibility(View.VISIBLE);
            speedGauge.setVisibility(View.VISIBLE);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side);
        lp.setMargins(d(5), d(3), d(5), d(3));
        tile.setLayoutParams(lp);
        return tile;
    }

    private int parseIntSafe(CharSequence s) {
        if (s == null) return -1;
        try {
            return Integer.parseInt(s.toString().trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private void updateSpeedNeedle(float kmh) {
        if (speedNeedle == null) return;
        boolean show = kmh >= 1f;
        speedNeedle.setVisibility(show ? View.VISIBLE : View.GONE);
        if (speedGauge != null) speedGauge.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            speedNeedle.animate().rotation(speedAngle(Math.round(kmh))).setDuration(180)
                    .setInterpolator(new android.view.animation.LinearInterpolator()).start();
        }
    }

    private void startVehicle() {
        vehicle = new VehicleInfo();
        vehThread = new HandlerThread("veh");
        vehThread.start();
        vehHandler = new Handler(vehThread.getLooper());
        // 속도 바늘 빠른 갱신 (~4.5Hz): 속도 위젯이 있을 때만
        if (speedNeedle != null) {
            vehHandler.post(new Runnable() {
                public void run() {
                    if (vehicle != null && vehicle.isConnected()) {
                        Float mps = vehicle.speedMps();
                        if (mps != null) {
                            final float kmh = Math.abs(mps) * 3.6f;
                            ui.post(() -> {
                                updateSpeedNeedle(kmh);
                                TextView tv = vehValues.get(ShortcutStore.KEY_V_SPEED);
                                if (tv != null) tv.setText(String.valueOf(Math.round(kmh)));
                            });
                        }
                    }
                    if (vehHandler != null) vehHandler.postDelayed(this, 220);
                }
            });
        }
        vehHandler.post(new Runnable() {
            public void run() {
                if (!vehicle.isConnected()) vehicle.connect(getApplicationContext());
                final Map<String, String> vals = readVehicle();
                ui.post(() -> {
                    for (Map.Entry<String, TextView> e : vehValues.entrySet()) {
                        String v = vals.get(e.getKey());
                        CharSequence cs;
                        if (v == null) {
                            CharSequence old = cacheVeh.get(e.getKey());
                            cs = old != null ? old : "—";   // 일시적 null 이면 이전 값 유지
                        } else if (ShortcutStore.KEY_V_RANGE.equals(e.getKey())) {
                            cs = withUnit(v, "km");
                        } else if (ShortcutStore.KEY_V_BATTERY.equals(e.getKey())) {
                            cs = withUnit(v, "%");
                            if (batteryBar != null) batteryBar.setPercent(parseIntSafe(v));
                        } else if (ShortcutStore.KEY_V_GEAR.equals(e.getKey())) {
                            cs = v;
                            e.getValue().setTextColor(gearColor(v));   // PRND 색상
                            if (v != null && prevGear != null && !v.equals(prevGear)) {
                                if ("D".equals(v)) sweepCar(true);        // 전진: 아래→위
                                else if ("R".equals(v)) sweepCar(false);  // 후진: 위→아래
                                else if ("P".equals(v)) parkCar();        // 주차: 중앙 정지+비상등
                                else hideCar();                           // N 등: 숨김
                            }
                            prevGear = v;
                        } else if (ShortcutStore.KEY_V_SPEED.equals(e.getKey())) {
                            cs = v;
                            updateSpeedNeedle(parseIntSafe(v));
                        } else {
                            cs = v;
                        }
                        cacheVeh.put(e.getKey(), cs);
                        e.getValue().setText(cs);
                    }
                });
                if (vehHandler != null) vehHandler.postDelayed(this, 1000);
            }
        });
    }

    /** 기어 전환 시 폴스타 차량이 독을 세로로 지나감 (D 아래→위, R 위→아래) */
    private void sweepCar(boolean up) {
        if (carSweep == null) return;
        final ImageView c = carSweep;
        int h = tileSide();   // 타일 크기 고정 (레이아웃 전 getHeight()=0 문제 방지)
        c.animate().cancel();
        c.setTranslationY(up ? h : -h);
        c.setAlpha(0.55f);
        c.setVisibility(View.VISIBLE);
        c.animate().translationY(up ? -h : h).setDuration(1600)
                .setInterpolator(new android.view.animation.LinearInterpolator())
                .withEndAction(() -> c.setVisibility(View.GONE)).start();
    }

    /** P: 하단에서 올라와 중앙에 정지 (깜빡임 없음) */
    private void parkCar() {
        if (carSweep == null) return;
        final ImageView c = carSweep;
        int h = tileSide();
        c.animate().cancel();
        c.setTranslationY(h);
        c.setAlpha(0.3f);
        c.setVisibility(View.VISIBLE);
        c.animate().translationY(0).setDuration(900)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    private void startHazard() {
        ui.removeCallbacks(hazardTick);
        if (lampDots != null) for (View d : lampDots) if (d != null) d.setVisibility(View.VISIBLE);
        hazardOn = false;
        ui.post(hazardTick);
    }

    private void stopHazard() {
        ui.removeCallbacks(hazardTick);
        if (lampDots != null) for (View d : lampDots) if (d != null) d.setVisibility(View.GONE);
    }

    private void hideCar() {
        stopHazard();
        if (carSweep == null) return;
        carSweep.animate().cancel();
        carSweep.setVisibility(View.GONE);
    }

    /** 기어 색상: P 검정 · D 다크그린 · R 다크레드 · N 오렌지 */
    private int gearColor(String g) {
        if (g == null) return TILE_FG;
        switch (g) {
            case "P": return 0xFF1C1C1E;
            case "D": return 0xFF1E7E34;
            case "R": return 0xFFB3261E;
            case "N": return 0xFFE0661A;
        }
        return TILE_FG;
    }

    /** 숫자 + 작은 단위 (예: 500 + 작은 km) */
    private CharSequence withUnit(String number, String unit) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(number);
        int start = sb.length();
        sb.append(unit);
        sb.setSpan(new android.text.style.RelativeSizeSpan(0.5f), start, sb.length(),
                android.text.Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.ForegroundColorSpan(TILE_LABEL), start, sb.length(),
                android.text.Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private Map<String, String> readVehicle() {
        Map<String, String> m = new LinkedHashMap<>();
        if (vehicle == null || !vehicle.isConnected()) return m;
        if (vehValues.containsKey(ShortcutStore.KEY_V_GEAR)) {
            String g = vehicle.gear();
            if (g != null) m.put(ShortcutStore.KEY_V_GEAR, g);
        }
        if (vehValues.containsKey(ShortcutStore.KEY_V_BATTERY)) {
            Integer b = vehicle.batteryPercent();
            if (b != null) m.put(ShortcutStore.KEY_V_BATTERY, String.valueOf(b));
        }
        if (vehValues.containsKey(ShortcutStore.KEY_V_RANGE)) {
            Integer r = vehicle.rangeKm();
            if (r != null) m.put(ShortcutStore.KEY_V_RANGE, String.valueOf(r));
        }
        if (vehValues.containsKey(ShortcutStore.KEY_V_SPEED)) {
            Integer s = vehicle.speedKmh();
            if (s != null) m.put(ShortcutStore.KEY_V_SPEED, String.valueOf(s));
        }
        if (vehValues.containsKey(ShortcutStore.KEY_V_TEMP)) {
            Integer t = vehicle.outsideTemp();
            if (t != null) m.put(ShortcutStore.KEY_V_TEMP, t + "°");
        }
        // 전비: 속도 적분(거리) + 배터리 Wh 변화(에너지) 누적 → km/kWh
        if (vehValues.containsKey(ShortcutStore.KEY_V_EFF)) {
            accumulateEff();
            if (effDist > 0.3f && effEnergy > 0.05f)
                m.put(ShortcutStore.KEY_V_EFF,
                        String.format(Locale.US, "%.1f", effDist / effEnergy));
        }
        return m;
    }

    private float effPrevWh = -1f, effDist = 0f, effEnergy = 0f;
    private long effLastMs = 0L;

    /** 1초마다 호출 (vehThread). 속도로 거리, 배터리 소비로 에너지 누적. */
    private void accumulateEff() {
        Float mps = vehicle.speedMps();
        Float wh = vehicle.batteryWh();
        long now = System.currentTimeMillis();
        if (mps != null && wh != null) {
            if (effLastMs > 0 && effPrevWh > 0) {
                float dtH = (now - effLastMs) / 3_600_000f;
                effDist += Math.abs(mps) * 3.6f * dtH;      // km
                float used = effPrevWh - wh;                 // Wh 소비(양수만)
                if (used > 0) effEnergy += used / 1000f;     // kWh
            }
            effLastMs = now;
            effPrevWh = wh;
        }
    }

    private void stopVehicle() {
        if (vehHandler != null) {
            vehHandler.removeCallbacksAndMessages(null);
            vehHandler = null;
        }
        if (vehThread != null) {
            vehThread.quitSafely();
            vehThread = null;
        }
        if (vehicle != null) {
            final VehicleInfo v = vehicle;
            new Thread(v::close).start();
            vehicle = null;
        }
    }

    // ---------------------------------------------------------------- 바로가기

    private void rebuildShortcuts() {
        if (shortcutRow == null) return;
        final boolean vertical = isVertical();
        ui.post(() -> {
            shortcutRow.removeAllViews();
            for (Shortcut s : ShortcutStore.load(this)) shortcutRow.addView(makeItem(s, vertical));
        });
    }

    private View makeItem(Shortcut s, boolean vertical) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(d(6), d(4), d(6), d(4));

        Drawable icon = IconLoader.load(this, s);
        int side = tileSide();
        if (icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            item.addView(iv, new LinearLayout.LayoutParams(side, side));
        } else {
            TextView t = new TextView(this);
            t.setText(s.label.isEmpty() ? "?" : s.label);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(TILE_FG);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, side * 0.30f);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(TILE_BG);
            bg.setCornerRadius(side * CalendarTile.CORNER_RATIO);
            t.setBackground(bg);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(side, side);
            t.setLayoutParams(tl);
            item.addView(t);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (vertical) lp.topMargin = d(4);
        else lp.leftMargin = d(4);
        item.setLayoutParams(lp);
        item.setOnClickListener(v -> launch(s));
        return item;
    }

    private void launch(Shortcut s) {
        Intent i = s.toIntent(this);
        if (i == null) {
            toast("실행 대상을 못 찾음: " + s.label);
            return;
        }
        try {
            startActivity(i);
        } catch (Exception e) {
            toast(s.label + " 실행 실패: " + e.getClass().getSimpleName());
        }
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }

    /** 독 위에서 바로 편집하는 패널 열기 (별도 설정 화면 없이) */
    private void openApp() {
        if (editPanel == null) editPanel = new EditPanel(this);
        editPanel.show();
    }

    // ---------------------------------------------------------------- 홈(런처)에서만 표시

    private java.util.Set<String> homePackages() {
        if (homePackages != null) return homePackages;
        java.util.Set<String> s = new java.util.HashSet<>();
        s.add("com.ecarx.car.launcher");
        s.add("com.android.car.carlauncher");
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (android.content.pm.ResolveInfo ri :
                    getPackageManager().queryIntentActivities(home, 0)) {
                if (ri.activityInfo != null) s.add(ri.activityInfo.packageName);
            }
        } catch (Throwable ignored) {
        }
        homePackages = s;
        return s;
    }

    boolean hasUsageAccess() {
        try {
            android.app.AppOpsManager ao =
                    (android.app.AppOpsManager) getSystemService(APP_OPS_SERVICE);
            int mode = ao.checkOpNoThrow("android:get_usage_stats",
                    android.os.Process.myUid(), getPackageName());
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    private String foregroundPackage() {
        try {
            android.app.usage.UsageStatsManager usm =
                    (android.app.usage.UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            android.app.usage.UsageEvents ev = usm.queryEvents(now - 10000, now);
            android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
            String top = null;
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                int t = e.getEventType();
                if (t == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND
                        || t == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                    top = e.getPackageName();
                }
            }
            return top;
        } catch (Throwable t) {
            return null;
        }
    }

    private void updateHomeMode() {
        String top = foregroundPackage();
        if (top != null) lastFgPkg = top;
        homeFgNow = lastFgPkg != null && homePackages().contains(lastFgPkg);
        String want = activeMode();
        if (!want.equals(mode)) {
            // 그리드 상태에서 홈을 벗어나면: 세로형 + 우측 센터로 전환
            boolean leavingHomeFromGrid = "home".equals(mode) && "default".equals(want) && gridActive;
            installDock();   // 모드 바뀌면 그 모드의 배치로 재구성 (mode=want로 갱신됨)
            if (leavingHomeFromGrid) {
                if (!"v".equals(sp.getString(mk(KEY_ORI), "h")))
                    sp.edit().putString(mk(KEY_ORI), "v").apply();   // default 배치=세로
                moveToRightCenter();
            }
        }
    }

    // ---------------------------------------------------------------- 위치 제한

    private void clamp() {
        if (dock == null) return;
        DisplayMetrics dm = realMetrics();
        int dockW = dock.getWidth(), dockH = dock.getHeight();
        int minX = d(2), minY = insetTop();
        int maxX = dm.widthPixels - dockW - d(2);
        int maxY = dm.heightPixels - insetBottom() - dockH - d(2);
        if (maxX < minX) maxX = minX;
        if (maxY < minY) maxY = minY;
        if (lp.x < minX) lp.x = minX;
        if (lp.x > maxX) lp.x = maxX;
        if (lp.y < minY) lp.y = minY;
        if (lp.y > maxY) lp.y = maxY;
    }

    // ---------------------------------------------------------------- 드래그 스냅(자석 배치)

    /** 손을 뗀 위치에 따라 배치(가로/세로/그리드)를 바꾸고 벽/타일에 붙인다. */
    private void snapAfterDrag() {
        if (dock == null) return;
        DisplayMetrics dm = realMetrics();
        int sw = dm.widthPixels, sh = dm.heightPixels;
        int w = dock.getWidth(), h = dock.getHeight();
        int it = insetTop(), ib = insetBottom();
        int cx = lp.x + w / 2, cy = lp.y + h / 2;

        String curOri = sp.getString(mk(KEY_ORI), "h");
        String target = curOri;
        String edge = null;

        // 1) 홈 화면 + 우측 타일 영역 → 그리드
        if (homeFgNow && "home".equals(mode) && cx > sw * 0.58f) {
            target = "g";
            edge = (cy < sh / 2) ? "gridTop" : "gridBottom";
        } else {
            // 2) 가장 가까운 벽: 상/하 = 가로, 좌/우 = 세로
            int dTop = lp.y - it;
            int dBottom = (sh - ib) - (lp.y + h);
            int dLeft = lp.x;
            int dRight = sw - (lp.x + w);
            int min = Math.min(Math.min(dTop, dBottom), Math.min(dLeft, dRight));
            int thresh = px(SNAP_EDGE_DP);   // 벽 발동 범위 ≈ 성인 손가락 두께
            if (min <= thresh) {
                if (min == dTop) { target = "h"; edge = "top"; }
                else if (min == dBottom) { target = "h"; edge = "bottom"; }
                else if (min == dLeft) { target = "v"; edge = "left"; }
                else { target = "v"; edge = "right"; }
            }
        }

        if (!target.equals(curOri)) {
            // 배치가 바뀌면 재구성 후 새 크기 기준으로 붙인다
            pendingSnapEdge = edge;
            sp.edit().putString(mk(KEY_ORI), target).apply();   // -> prefListener -> installDock
        } else if (edge != null) {
            applySnapEdge(edge);   // 배치 그대로면 즉시 붙임
        } else {
            sp.edit().putInt(mk(KEY_X), lp.x).putInt(mk(KEY_Y), lp.y).apply();
        }
    }

    /** 현재 크기 기준으로 지정한 벽/타일에 붙이고 위치 저장. */
    private void applySnapEdge(String edge) {
        if (dock == null || edge == null) return;
        DisplayMetrics dm = realMetrics();
        int sw = dm.widthPixels, sh = dm.heightPixels;
        int w = dock.getWidth(), h = dock.getHeight();
        int it = insetTop(), ib = insetBottom();
        switch (edge) {
            case "top":    lp.y = it + d(2); break;
            case "bottom": lp.y = sh - ib - h - d(2); break;
            case "left":   lp.x = d(2); break;
            case "right":  lp.x = sw - w - d(2); break;
            case "gridTop":    lp.x = sw - w - d(4); lp.y = it + d(4); break;
            case "gridBottom": lp.x = sw - w - d(4); lp.y = sh - ib - h - d(4); break;
        }
        clamp();
        wm.updateViewLayout(dock, lp);
        sp.edit().putInt(mk(KEY_X), lp.x).putInt(mk(KEY_Y), lp.y).apply();
    }

    /** 세로형으로 화면 우측 세로 중앙에 배치. */
    private void moveToRightCenter() {
        if (dock == null) return;
        dock.post(() -> {
            DisplayMetrics dm = realMetrics();
            int w = dock.getWidth(), h = dock.getHeight();
            lp.x = dm.widthPixels - w - d(4);
            lp.y = (dm.heightPixels - h) / 2;
            clamp();
            wm.updateViewLayout(dock, lp);
            sp.edit().putInt(mk(KEY_X), lp.x).putInt(mk(KEY_Y), lp.y).apply();
        });
    }

    private int insetTop() {
        return systemBar(0);
    }

    private int insetBottom() {
        return systemBar(1);
    }

    private int insetLeftRight() {
        return systemBar(2) + systemBar(3);
    }

    /** 0=top 1=bottom 2=left 3=right */
    private int systemBar(int which) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                android.view.WindowMetrics m = wm.getCurrentWindowMetrics();
                android.graphics.Insets in = m.getWindowInsets()
                        .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
                switch (which) {
                    case 0: return in.top;
                    case 1: return in.bottom;
                    case 2: return in.left;
                    default: return in.right;
                }
            }
        } catch (Throwable ignored) {
        }
        if (which == 0) {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
        }
        return 0;
    }

    private DisplayMetrics realMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm;
    }

    private void capWidth(View v, int maxW) {
        v.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (v.getWidth() > maxW && v.getLayoutParams().width != maxW) {
                v.getLayoutParams().width = maxW;
                v.requestLayout();
            }
        });
    }

    private void capHeight(View v, int maxH) {
        v.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (v.getHeight() > maxH && v.getLayoutParams().height != maxH) {
                v.getLayoutParams().height = maxH;
                v.requestLayout();
            }
        });
    }

    private class DragHandle implements View.OnTouchListener {
        private int startX, startY;
        private float touchX, touchY;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (handleGesture != null) handleGesture.onTouchEvent(e);  // 더블탭 = 앱 열기
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = lp.x;
                    startY = lp.y;
                    touchX = e.getRawX();
                    touchY = e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lp.x = startX + (int) (e.getRawX() - touchX);
                    lp.y = startY + (int) (e.getRawY() - touchY);
                    clamp();
                    wm.updateViewLayout(dock, lp);
                    return true;
                case MotionEvent.ACTION_UP:
                    clamp();
                    wm.updateViewLayout(dock, lp);
                    snapAfterDrag();   // 벽/영역에 따라 배치 전환 + 스냅
                    return true;
            }
            return false;
        }
    }

    // ---------------------------------------------------------------- 전경 서비스

    private void startForegroundSafely() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "독", NotificationManager.IMPORTANCE_MIN);
            nm.createNotificationChannel(ch);
        }
        Notification n = new Notification.Builder(this, CH_ID)
                .setContentTitle("독 실행 중")
                .setSmallIcon(android.R.drawable.ic_menu_rotate)
                .setOngoing(true)
                .build();
        startForeground(1, n);
    }

    private int d(int v) {
        return Math.round(v * SCALE * getResources().getDisplayMetrics().density);
    }

    /** SCALE 무시한 순수 dp→px (런처 치수 그대로 맞출 때 사용) */
    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    /** 현재 모드/크기에 맞춰 독 코너 반경 적용 (그리드=각진 사각, 그 외=macOS 독 비율) */
    private void applyDockCorner() {
        if (barBg == null || dock == null) return;
        if (gridActive) {
            barBg.setCornerRadius(0f);
        } else {
            int thickness = isVertical() ? dock.getWidth() : dock.getHeight();
            if (thickness > 0) barBg.setCornerRadius(thickness * MAC_DOCK_CORNER_RATIO);
        }
        dock.invalidate();
    }

    /** 그리드 페이지: 가장 가까운 페이지로 스냅하고 헤더 점을 갱신 */
    private void snapPage(HorizontalScrollView pager, int pageW, int pages) {
        if (pageW <= 0) return;
        int page = Math.round(pager.getScrollX() / (float) pageW);
        page = Math.max(0, Math.min(pages - 1, page));
        pager.smoothScrollTo(page * pageW, 0);
        if (gridDots != null) {
            for (int i = 0; i < gridDots.getChildCount(); i++) {
                View d = gridDots.getChildAt(i);
                if (d.getBackground() instanceof GradientDrawable) {
                    ((GradientDrawable) d.getBackground())
                            .setColor(i == page ? 0xFFFFFFFF : 0x66FFFFFF);
                }
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        ui.removeCallbacks(clockTick);
        ui.removeCallbacks(envTick);
        ui.removeCallbacks(musicTick);
        ui.removeCallbacks(hazardTick);
        stopVehicle();
        ui.removeCallbacks(fgTick);
        if (sp != null) sp.unregisterOnSharedPreferenceChangeListener(prefListener);
        if (editPanel != null) editPanel.hide();
        if (wm != null && dock != null) {
            try {
                wm.removeView(dock);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static boolean canOverlay(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return android.provider.Settings.canDrawOverlays(ctx);
    }
}
