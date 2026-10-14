package com.oemtools.poldock;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
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
    private static final String ECARX_DISPLAY_AREA_STATE = "com.ecarx.displayarea.STATE";
    private static final String ECARX_DISPLAY_AREA_UPDATE =
            "com.ecarx.displayarea.core.STATE_COMPONENT_UPDATE";
    private static final String ECARX_DISPLAY_AREA_EXTRA_STATE =
            "com.ecarx.displayarea.core.CURRENT_STATE";
    private static final long DISPLAY_AREA_POLL_MS = 2000L;
    static final String ACTION_SHORTCUT_ADDED =
            "com.oemtools.poldock.action.SHORTCUT_ADDED";
    static final String ACTION_GRID_BACKGROUND_CHANGED =
            "com.oemtools.poldock.action.GRID_BACKGROUND_CHANGED";
    private static final int TILE_DP = 52;
    // 런처 즐겨찾기 카드(타일) 크기: fav_card_width/height (OEM dimens)
    private static final int GRID_TILE_W_DP = 532;
    private static final int GRID_TILE_H_DP = 576;
    // 런처 카드 헤더 아이콘: card_icon_size=64dp, card_icon_margin_*=32dp
    private static final int CARD_ICON_DP = 64;
    private static final int CARD_ICON_MARGIN_DP = 32;
    // 바로가기 등 고정(라이트) 타일색
    private static final int SHORTCUT_BG = 0xFFFFFFFF;
    private static final int SHORTCUT_FG = 0xFF1C1C1E;
    private float SCALE = 1.5f;

    /** macOS Dock 컨테이너 코너 ≈ 두께의 25% */
    private static final float MAC_DOCK_CORNER_RATIO = 0.25f;

    private WindowManager wm;
    private View dock;
    private GradientDrawable barBg;
    private GridBackgroundDrawable gridPhotoBackground;
    private long gridPhotoModified;
    private String gridPhotoConfig;
    private LinearLayout shortcutRow;
    private TextView timeView, timeAmPm, tempView, dustView, dateWeekday, dateDay;
    private MonthCalendarView dateCalendarView;
    private TextView speedLabel;
    private final Map<String, TextView> effLabels = new LinkedHashMap<>();
    private ImageView weatherIconView, musicArt, carSweep, speedNeedle, speedGauge;
    private TextView musicPh;
    private String prevGear;
    private View[] lampDots;
    private boolean hazardOn;
    private BatteryBar batteryBar;
    private boolean hasVehWidget;
    private boolean gridActive;
    private LinearLayout gridDots;   // 그리드 페이지 표시(헤더)
    private int gridPage;            // 현재 그리드 페이지 (재구성 시 유지)
    private EditPanel editPanel;     // 독 위 편집 패널
    private String pendingSnapEdge;  // 재구성 후 벽에 붙일 위치 (top/bottom/left/right/gridTop/gridBottom)
    private static final int SNAP_EDGE_DP = 50;   // 벽 스냅 발동 범위 (손가락 두께 정도)

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
    /** 시동 직후에는 네트워크가 늦게 붙으므로 잠시 뒤 OTA를 확인한다. */
    private static final long UPDATE_CHECK_DELAY_MS = 60_000L;
    private final Runnable updateCheck = () -> UpdateManager.checkForUpdateIfDue(this);

    private VehicleInfo vehicle;
    private EfficiencyTracker efficiencyTracker;
    private HandlerThread vehThread;
    private Handler vehHandler;
    private android.view.GestureDetector handleGesture;
    private String displayAreaState;
    private android.database.ContentObserver displayAreaObserver;
    private android.content.BroadcastReceiver displayAreaReceiver;
    private android.content.BroadcastReceiver shortcutAddedReceiver;
    private FrameLayout dockScroll;
    private int pendingDockScroll = -1;
    private boolean pendingDockScrollVertical;
    private String pendingDockScrollMode;
    private String mode = "default";   // 현재 배치 모드: default / home
    private boolean homeFgNow;
    private final Runnable displayAreaPoll = new Runnable() {
        @Override
        public void run() {
            refreshDisplayAreaState();
            ui.postDelayed(this, DISPLAY_AREA_POLL_MS);
        }
    };

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
                // 위치 저장(x/y)은 이미 updateViewLayout 으로 반영됨 → 재구성 불필요 (전환 시 깜빡/이동 방지)
                if (KEY_X.equals(key) || KEY_Y.equals(key)
                        || (KEY_X + "_home").equals(key) || (KEY_Y + "_home").equals(key)) return;
                if (ShortcutStore.KEY_ALPHA.equals(key)) {   // 투명도는 재구성 없이 즉시 적용
                    if (dock != null) dock.setAlpha(dockAlpha());
                    return;
                }
                if (ShortcutStore.KEY_AUTOSTART.equals(key)) return;   // 재구성 불필요
                if (UpdateManager.isInternalPreference(key)) return;
                if (EfficiencyTracker.isInternalPreference(key)) return;
                if (ShortcutStore.KEY_DATE_CALENDAR.equals(key)) return; // 제자리 전환
                if (ShortcutStore.KEY_TIME_ANALOG.equals(key)) return; // 제자리 전환 (재구성 불필요)
                if (ShortcutStore.KEY_SPEED_MPH.equals(key)) {
                    updateSpeedUnitUi();
                    return;
                }
                if (ShortcutStore.KEY_EFF_WHKM.equals(key)) {
                    updateEffUnitUi();
                    return;
                }
                if (ShortcutStore.KEY.equals(key)) {
                    if (gridActive) installDock(); else rebuildShortcuts();
                } else installDock();
            };

    private boolean colonOn = true;   // 시계 콜론 깜빡임 상태
    private final Runnable clockTick = new Runnable() {
        public void run() {
            colonOn = !colonOn;        // 1초마다 콜론 토글 → 2초 주기 깜빡임
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
            ui.postDelayed(this, 4000);   // 콜백 백업용 느린 폴링
        }
    };

    // 미디어 세션 콜백 (메타데이터 변경 시 즉시 앨범아트 갱신)
    private android.media.session.MediaSessionManager mediaMgr;
    private android.media.session.MediaController mediaMc;
    private android.media.session.MediaController.Callback mediaCb;
    private android.media.session.MediaSessionManager.OnActiveSessionsChangedListener mediaSessions;
    private final Runnable hazardTick = new Runnable() {
        public void run() {
            if (lampDots == null || !"P".equals(prevGear)) return;
            hazardOn = !hazardOn;
            for (View d : lampDots) if (d != null) d.setAlpha(hazardOn ? 1f : 0.06f);
            ui.postDelayed(this, 450);
        }
    };
    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        sp = getSharedPreferences(ShortcutStore.PREF, MODE_PRIVATE);
        // 편집 중 강제한 모드가 전원 종료로 남아도 다음 실행은 실제 화면을 따라간다.
        sp.edit()
                .putBoolean(ShortcutStore.KEY_HOME_PROFILE, true)
                .putString(ShortcutStore.KEY_EDIT_MODE, "auto")
                .apply();
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
        lp.windowAnimations = 0;   // 창 전환 애니메이션 끔 (확인용)
        lp.x = sp.getInt(KEY_X, d(24));
        lp.y = sp.getInt(KEY_Y, d(60));
        startDisplayAreaTracking();
        startShortcutAddedTracking();
        installDock();
        sp.registerOnSharedPreferenceChangeListener(prefListener);
        ui.postDelayed(updateCheck, UPDATE_CHECK_DELAY_MS);
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
        stopMediaCallbacks();
        vehValues.clear();
        captureDockScroll();
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
            DisplayMetrics gdm = realMetrics();
            String gridPosition = sp.getString(ShortcutStore.KEY_GRID_POS, "top");
            lp.width = Math.round(GRID_TILE_W_DP * density);
            lp.height = "right".equals(gridPosition)
                    ? Math.max(1, gdm.heightPixels - insetTop() - insetBottom())
                    : Math.round(GRID_TILE_H_DP * density);
            bar.setPadding(0, 0, 0, 0);
            // 셀 크기에 맞춰 위젯 스케일을 산출 → 슬라이더(SCALE) 대신 타일/격자 기준
            int gc = Math.max(1, Math.min(6, sp.getInt(ShortcutStore.KEY_GRID_C, ShortcutStore.DEFAULT_GRID)));
            int gr = Math.max(1, Math.min(8, sp.getInt(ShortcutStore.KEY_GRID_R, ShortcutStore.DEFAULT_GRID)));
            float gridH = lp.height - px(CARD_ICON_DP) - px(CARD_ICON_MARGIN_DP) * 2;  // 헤더(상+하 여백) 제외
            float cell = Math.min(lp.width / (float) gc, gridH / gr);
            float tilePx = cell * 0.82f;                 // 셀 안에 여백을 두고 채움
            SCALE = tilePx / (TILE_DP * density);
            float maxGridScale = gc == 1 && gr == 1 ? 6f : 4f;
            SCALE = Math.max(0.5f, Math.min(maxGridScale, SCALE));
            // 그리드: 위치 고정 (드래그 불가) — 우측 통합은 상단바와 하단바 사이를 모두 사용
            lp.x = gdm.widthPixels - lp.width;
            if ("bottom".equals(gridPosition)) {
                lp.y = gdm.heightPixels - insetBottom() - lp.height;
            } else if ("right".equals(gridPosition)) {
                lp.y = insetTop();
            } else {
                lp.y = insetTop();   // 상단은 상태바 아래에 (너무 올라가지 않게)
            }
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
        dateCalendarView = null;
        timeView = null;
        musicArt = null;
        musicPh = null;
        carSweep = null;
        lampDots = null;
        speedNeedle = null;
        speedGauge = null;
        speedLabel = null;
        effLabels.clear();
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
        if (musicArt != null) {
            ui.post(musicTick);
            startMediaCallbacks();   // 즉시 갱신용 콜백 등록
        }
        if (dock != null) {
            dock.setVisibility(View.VISIBLE);
            dock.setAlpha(dockAlpha());
        }

        DisplayMetrics dm = realMetrics();
        if (grid) {
            shortcutRow = null;
            for (Shortcut s : ShortcutStore.load(this)) {
                View v = makeItem(s, false);
                v.setPadding(0, 0, 0, 0);   // 셀이 타일 크기로 고정되므로 패딩이 아이콘 가장자리를 잘라낸다
                items.add(v);
            }
            int cols = Math.max(1, Math.min(6, sp.getInt(ShortcutStore.KEY_GRID_C, ShortcutStore.DEFAULT_GRID)));
            int rows = Math.max(1, Math.min(8, sp.getInt(ShortcutStore.KEY_GRID_R, ShortcutStore.DEFAULT_GRID)));
            int perPage = Math.max(1, cols * rows);
            final int pages = Math.max(1, (items.size() + perPage - 1) / perPage);

            // 런처 카드 헤더: 좌상단 앱 아이콘 + 우상단 핸들(편집 버튼 자리)
            int ic = px(CARD_ICON_DP), m = px(CARD_ICON_MARGIN_DP);
            FrameLayout header = new FrameLayout(this);
            int headerBase = ShortcutStore.DEFAULT_GRID_BG_COLOR;
            GradientDrawable headerBackground = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{
                            Color.argb(51, Color.red(headerBase), Color.green(headerBase),
                                    Color.blue(headerBase)),
                            Color.argb(0, Color.red(headerBase), Color.green(headerBase),
                                    Color.blue(headerBase))
                    });
            header.setBackground(headerBackground);
            header.setOnTouchListener(new DragHandle());   // 헤더 드래그로 독 이동
            TextView appName = new TextView(this);
            appName.setText("PolDock");
            appName.setTextColor(0xFFFFFFFF);
            appName.setTextSize(TypedValue.COMPLEX_UNIT_PX, ic * 0.63f);
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
            // 재구성(위젯 토글 등) 후에도 보던 페이지 유지 — 그리기 직전에 스크롤 잡아 깜빡임 방지
            final int restorePage = Math.max(0, Math.min(pages - 1, gridPage));
            gridPage = restorePage;
            if (restorePage > 0) pager.setScrollX(restorePage * pageW);
            pager.getViewTreeObserver().addOnPreDrawListener(
                    new android.view.ViewTreeObserver.OnPreDrawListener() {
                        public boolean onPreDraw() {
                            pager.getViewTreeObserver().removeOnPreDrawListener(this);
                            pager.scrollTo(restorePage * pageW, 0);
                            if (gridDots != null) {
                                for (int i = 0; i < gridDots.getChildCount(); i++) {
                                    View d = gridDots.getChildAt(i);
                                    if (d.getBackground() instanceof GradientDrawable)
                                        ((GradientDrawable) d.getBackground())
                                                .setColor(i == restorePage ? 0xFFFFFFFF : 0x66FFFFFF);
                                }
                            }
                            return true;
                        }
                    });
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
            dockScroll = scroll;
            if (vertical) {
                ((ScrollView) scroll).setVerticalScrollBarEnabled(false);
                bar.addView(scroll);
                capHeight(scroll, dm.heightPixels - insetTop() - insetBottom() - d(70));
            } else {
                ((HorizontalScrollView) scroll).setHorizontalScrollBarEnabled(false);
                bar.addView(scroll);
                capWidth(scroll, dm.widthPixels - insetLeftRight() - d(70));
            }
            restoreDockScroll(scroll, vertical);
            rebuildShortcuts();
        }
        applyDockBackground(bar, grid);
        // 재구성 직후 측정값으로 '먼저' 클램프 → 전환 시 드롭위치에 그렸다가 화면 안으로 튀는 2-pass 제거
        if (pendingSnapEdge == null) {
            DisplayMetrics dmc = realMetrics();
            int wSpec = lp.width > 0
                    ? View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY)
                    : View.MeasureSpec.makeMeasureSpec(dmc.widthPixels, View.MeasureSpec.AT_MOST);
            int hSpec = lp.height > 0
                    ? View.MeasureSpec.makeMeasureSpec(lp.height, View.MeasureSpec.EXACTLY)
                    : View.MeasureSpec.makeMeasureSpec(dmc.heightPixels, View.MeasureSpec.AT_MOST);
            dock.measure(wSpec, hSpec);
            clampSize(dock.getMeasuredWidth(), dock.getMeasuredHeight());
            wm.updateViewLayout(dock, lp);
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

    /** 재구성되는 가로/세로 독의 스크롤 위치를 현재 모드 기준으로 보관한다. */
    private void captureDockScroll() {
        pendingDockScroll = -1;
        pendingDockScrollMode = null;
        if (dockScroll instanceof ScrollView) {
            pendingDockScroll = ((ScrollView) dockScroll).getScrollY();
            pendingDockScrollVertical = true;
            pendingDockScrollMode = mode;
        } else if (dockScroll instanceof HorizontalScrollView) {
            pendingDockScroll = ((HorizontalScrollView) dockScroll).getScrollX();
            pendingDockScrollVertical = false;
            pendingDockScrollMode = mode;
        }
        dockScroll = null;
    }

    /** 같은 홈/일반 모드와 같은 방향으로 다시 만들 때만 보던 지점으로 되돌린다. */
    private void restoreDockScroll(final FrameLayout scroll, boolean vertical) {
        if (pendingDockScroll < 0 || pendingDockScrollVertical != vertical
                || !mode.equals(pendingDockScrollMode)) return;
        final int position = pendingDockScroll;
        pendingDockScroll = -1;
        pendingDockScrollMode = null;
        scroll.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        scroll.getViewTreeObserver().removeOnPreDrawListener(this);
                        applyDockScroll(scroll, vertical, position);
                        scroll.post(() -> applyDockScroll(scroll, vertical, position));
                        return true;
                    }
                });
    }

    private void applyDockScroll(FrameLayout scroll, boolean vertical, int position) {
        if (scroll != dockScroll) return;
        if (vertical) ((ScrollView) scroll).scrollTo(0, position);
        else ((HorizontalScrollView) scroll).scrollTo(position, 0);
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
        TextView t = tileText(side * 0.36f, wFg(), true);
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
        final float radius = side * CalendarTile.CORNER_RATIO;
        FrameLayout tile = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(wBg());
        bg.setCornerRadius(radius);
        tile.setBackground(bg);
        tile.setClipToOutline(true);
        tile.setOutlineProvider(new android.view.ViewOutlineProvider() {
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });

        LinearLayout date = new LinearLayout(this);
        date.setOrientation(LinearLayout.VERTICAL);
        date.setGravity(Gravity.CENTER);
        dateWeekday = tileText(side * 0.20f, wLabel(), false);   // 요일 (위, 라벨)
        dateDay = valueText(side);                              // 날짜 (아래, 값)
        date.addView(dateWeekday);
        date.addView(dateDay);
        tile.addView(date, new FrameLayout.LayoutParams(side, side, Gravity.CENTER));

        dateCalendarView = new MonthCalendarView(this);
        dateCalendarView.setColors(wFg(), wLabel(), Ui.ACCENT);
        int inset = Math.max(1, Math.round(side * 0.04f));
        FrameLayout.LayoutParams calendarLp = new FrameLayout.LayoutParams(
                side - inset * 2, side - inset * 2, Gravity.CENTER);
        tile.addView(dateCalendarView, calendarLp);

        boolean calendar = sp.getBoolean(ShortcutStore.KEY_DATE_CALENDAR, false);
        date.setVisibility(calendar ? View.GONE : View.VISIBLE);
        dateCalendarView.setVisibility(calendar ? View.VISIBLE : View.GONE);
        tile.setClickable(true);
        tile.setOnClickListener(v -> {
            boolean show = !sp.getBoolean(ShortcutStore.KEY_DATE_CALENDAR, false);
            sp.edit().putBoolean(ShortcutStore.KEY_DATE_CALENDAR, show).apply();
            date.setVisibility(show ? View.GONE : View.VISIBLE);
            dateCalendarView.setVisibility(show ? View.VISIBLE : View.GONE);
        });

        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(side, side);
        layout.setMargins(d(5), d(3), d(5), d(3));
        tile.setLayoutParams(layout);
        return tile;
    }

    private View buildTimeTile() {
        int side = tileSide();
        final float radius = side * CalendarTile.CORNER_RATIO;

        FrameLayout tile = new FrameLayout(this);
        tile.setClipToOutline(true);
        tile.setOutlineProvider(new android.view.ViewOutlineProvider() {
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(wBg());
        bg.setCornerRadius(radius);
        tile.setBackground(bg);

        // 디지털
        final LinearLayout dig = new LinearLayout(this);
        dig.setOrientation(LinearLayout.VERTICAL);
        dig.setGravity(Gravity.CENTER);
        timeAmPm = tileText(side * 0.20f, wLabel(), false);   // 오전/오후 (위)
        timeView = valueText(side);                            // 시각 (아래)
        dig.addView(timeAmPm);
        dig.addView(timeView);
        tile.addView(dig, new FrameLayout.LayoutParams(side, side, Gravity.CENTER));

        // 아날로그
        final AnalogClockView analog = new AnalogClockView(this);
        analog.setColors(wFg(), wLabel());
        int inset = Math.round(side * 0.06f);
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(side, side, Gravity.CENTER);
        alp.setMargins(inset, inset, inset, inset);
        tile.addView(analog, alp);

        boolean isAnalog = sp.getBoolean(ShortcutStore.KEY_TIME_ANALOG, false);
        dig.setVisibility(isAnalog ? View.GONE : View.VISIBLE);
        analog.setVisibility(isAnalog ? View.VISIBLE : View.GONE);

        tile.setClickable(true);
        tile.setOnClickListener(v -> {
            boolean now = !sp.getBoolean(ShortcutStore.KEY_TIME_ANALOG, false);
            sp.edit().putBoolean(ShortcutStore.KEY_TIME_ANALOG, now).apply();
            dig.setVisibility(now ? View.GONE : View.VISIBLE);
            analog.setVisibility(now ? View.VISIBLE : View.GONE);   // 즉시 전환 (재구성 없음)
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side);
        lp.setMargins(d(5), d(3), d(5), d(3));
        tile.setLayoutParams(lp);
        return tile;
    }

    private void updateClock() {
        if (dateWeekday != null) {
            Date now = new Date();
            dateWeekday.setText(new SimpleDateFormat("E", Locale.getDefault()).format(now));
            dateDay.setText(new SimpleDateFormat("d", Locale.getDefault()).format(now));
            if (dateCalendarView != null) dateCalendarView.setDate(now);
        }
        if (timeView != null) {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            int h = cal.get(java.util.Calendar.HOUR_OF_DAY);
            int m = cal.get(java.util.Calendar.MINUTE);
            int hr = h % 12;
            if (hr == 0) hr = 12;
            String t = String.format(Locale.US, "%d:%02d", hr, m);
            int ci = t.indexOf(':');
            android.text.SpannableString ss = new android.text.SpannableString(t);
            if (!colonOn && ci >= 0)   // 콜론만 투명 처리 → 폭 유지하며 깜빡임
                ss.setSpan(new android.text.style.ForegroundColorSpan(0x00000000),
                        ci, ci + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            timeView.setText(ss);
            if (timeAmPm != null) {
                timeAmPm.setVisibility(View.VISIBLE);
                timeAmPm.setText(h < 12 ? "오전" : "오후");
            }
        }
    }

    // ---------------------------------------------------------------- 날씨/미세먼지

    private View buildWeatherTile() {
        int side = tileSide();
        LinearLayout box = tileBox(wBg());
        tempView = tileText(side * 0.24f, wLabel(), false);   // 온도 (°기호 보정으로 살짝 키움)
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
        LinearLayout box = tileBox(wBg());
        dustLabel = tileText(side * 0.20f, wLabel(), false);   // 라벨 (미세/초미세)
        dustLabel.setText("미세");
        dustView = valueText(side);   // 값 (아래)
        dustView.setText("…");
        box.addView(dustLabel);
        box.addView(dustView);
        box.setClickable(true);
        box.setOnClickListener(v -> {
            toggleDust();
        });
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
            dustView.setTextColor(wFg());
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
        LinearLayout box = tileBox(wBg());
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
        box.setClickable(true);
        box.setOnClickListener(v -> {
            if (notifAccessOn()) {
                mediaNext();   // 권한 있음 → 다음 곡
            } else {
                // 권한 없음 → 알림 접근 설정 열기
                try {
                    startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {
                }
            }
        });
        return box;
    }

    /** 알림 접근(미디어 세션 읽기) 허용 여부 */
    private boolean notifAccessOn() {
        try {
            String flat = android.provider.Settings.Secure.getString(
                    getContentResolver(), "enabled_notification_listeners");
            return flat != null && flat.contains(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 현재 재생 중인(없으면 아무) 미디어 세션에 다음 곡 명령 */
    private void mediaNext() {
        try {
            android.media.session.MediaSessionManager msm =
                    (android.media.session.MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            java.util.List<android.media.session.MediaController> cs = msm.getActiveSessions(
                    new android.content.ComponentName(this, NotifListener.class));
            android.media.session.MediaController target = null;
            for (android.media.session.MediaController mc : cs) {
                android.media.session.PlaybackState ps = mc.getPlaybackState();
                if (ps != null && ps.getState() == android.media.session.PlaybackState.STATE_PLAYING) {
                    target = mc;
                    break;
                }
                if (target == null) target = mc;
            }
            if (target != null) target.getTransportControls().skipToNext();
            // 앨범아트 갱신은 MediaController.Callback(onMetadataChanged)이 담당
        } catch (Throwable ignored) {
        }
    }

    /** 활성 미디어 세션에 콜백을 붙여 메타데이터 변경 시 즉시 갱신 */
    private void startMediaCallbacks() {
        if (!notifAccessOn()) return;
        try {
            mediaMgr = (android.media.session.MediaSessionManager)
                    getSystemService(MEDIA_SESSION_SERVICE);
            final android.content.ComponentName cn =
                    new android.content.ComponentName(this, NotifListener.class);
            mediaSessions = controllers -> attachMediaController(pickController(controllers));
            mediaMgr.addOnActiveSessionsChangedListener(mediaSessions, cn);
            attachMediaController(pickController(mediaMgr.getActiveSessions(cn)));
        } catch (Throwable ignored) {
        }
    }

    private android.media.session.MediaController pickController(
            java.util.List<android.media.session.MediaController> list) {
        if (list == null) return null;
        android.media.session.MediaController any = null;
        for (android.media.session.MediaController mc : list) {
            android.media.session.PlaybackState ps = mc.getPlaybackState();
            if (ps != null && ps.getState() == android.media.session.PlaybackState.STATE_PLAYING)
                return mc;
            if (any == null) any = mc;
        }
        return any;
    }

    private void attachMediaController(android.media.session.MediaController mc) {
        if (mc == mediaMc) {
            updateMusic();
            return;
        }
        if (mediaMc != null && mediaCb != null) {
            try { mediaMc.unregisterCallback(mediaCb); } catch (Throwable ignored) {}
        }
        mediaMc = mc;
        if (mc == null) {
            updateMusic();
            return;
        }
        mediaCb = new android.media.session.MediaController.Callback() {
            @Override public void onMetadataChanged(android.media.MediaMetadata m) { updateMusic(); }
            @Override public void onPlaybackStateChanged(android.media.session.PlaybackState s) { updateMusic(); }
            @Override public void onSessionDestroyed() { attachMediaController(null); }
        };
        try { mc.registerCallback(mediaCb, ui); } catch (Throwable ignored) {}
        updateMusic();
    }

    private void stopMediaCallbacks() {
        try {
            if (mediaMgr != null && mediaSessions != null)
                mediaMgr.removeOnActiveSessionsChangedListener(mediaSessions);
        } catch (Throwable ignored) {}
        if (mediaMc != null && mediaCb != null) {
            try { mediaMc.unregisterCallback(mediaCb); } catch (Throwable ignored) {}
        }
        mediaSessions = null;
        mediaMc = null;
        mediaCb = null;
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
        boolean eff = isEfficiencyKey(key);
        LinearLayout box = tileBox(wBg());
        if (gear || speed) box.setBackground(null);   // 배경은 FrameLayout에서 처리
        TextView lab = tileText(side * 0.20f, wLabel(), false);   // 라벨 (위)
        if (speed || eff) {
            lab.setText(vehicleUnitLabel(speed, label));
            lab.setMaxLines(1);
            if (Build.VERSION.SDK_INT >= 26) {
                int max = Math.round(side * 0.20f), min = Math.round(side * 0.10f);
                int step = Math.max(1, Math.round(side * 0.01f));
                lab.setAutoSizeTextTypeUniformWithConfiguration(min, max, step,
                        TypedValue.COMPLEX_UNIT_PX);
            }
        } else {
            lab.setText(label);
        }
        TextView val = valueText(side);   // 값 (아래)
        CharSequence cached = cacheVeh.get(key);
        if (speed) val.setText(cached != null ? speedNum(parseIntSafe(cached)) : "—");
        else if (eff) val.setText(cached != null ? effNum(parseFloatSafe(cached)) : "—");
        else val.setText(cached != null ? cached : "—");   // 재생성 시 마지막 값 유지
        if (gear && cached != null) val.setTextColor(gearColor(cached.toString()));
        if (eff) {
            box.setClickable(true);
            box.setOnClickListener(v -> toggleEffUnit());   // 탭 → km/kWh ↔ Wh/km
        }
        // 라벨 폭을 타일 폭에 맞춰 자동 축소가 한 줄로 동작하게
        box.addView(lab, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(val);
        vehValues.put(key, val);
        if (speed) speedLabel = lab;
        if (eff) effLabels.put(key, lab);

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
        tile.setBackgroundColor(wBg());
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
        tile.setBackgroundColor(wBg());

        batteryBar = new BatteryBar(this);            // 배경
        batteryBar.setAlpha(0.42f);
        int bw = Math.round(side * 0.6f), bh = Math.round(side * 0.82f);
        FrameLayout.LayoutParams bbp = new FrameLayout.LayoutParams(bw, bh);
        bbp.gravity = Gravity.CENTER;
        tile.addView(batteryBar, bbp);

        LinearLayout box = tileBox(wBg());
        box.setBackground(null);
        TextView lab = tileText(side * 0.20f, wLabel(), false);
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
        tile.setBackgroundColor(wBg());
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
        tile.setClickable(true);
        tile.setOnClickListener(v -> toggleSpeedUnit());   // 탭 → km/h ↔ mph
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
        final VehicleInfo sessionVehicle = new VehicleInfo();
        final EfficiencyTracker sessionTracker = new EfficiencyTracker(sp);
        vehicle = sessionVehicle;
        efficiencyTracker = sessionTracker;
        vehThread = new HandlerThread("veh");
        vehThread.start();
        final Handler sessionHandler = new Handler(vehThread.getLooper());
        vehHandler = sessionHandler;
        // 속도 바늘 빠른 갱신 (~4.5Hz): 속도 위젯이 있을 때만
        if (speedNeedle != null) {
            sessionHandler.post(new Runnable() {
                public void run() {
                    if (vehicle != sessionVehicle || vehHandler != sessionHandler) return;
                    if (sessionVehicle.isConnected()) {
                        Float mps = sessionVehicle.speedMps();
                        if (mps != null) {
                            final float kmh = Math.abs(mps) * 3.6f;
                            ui.post(() -> {
                                if (vehicle != sessionVehicle) return;
                                updateSpeedNeedle(kmh);
                                TextView tv = vehValues.get(ShortcutStore.KEY_V_SPEED);
                                if (tv != null) tv.setText(speedNum(Math.round(kmh)));
                                cacheVeh.put(ShortcutStore.KEY_V_SPEED, String.valueOf(Math.round(kmh)));
                            });
                        }
                    }
                    if (vehicle == sessionVehicle && vehHandler == sessionHandler)
                        sessionHandler.postDelayed(this, 220);
                }
            });
        }
        sessionHandler.post(new Runnable() {
            public void run() {
                if (vehicle != sessionVehicle || vehHandler != sessionHandler) return;
                if (!sessionVehicle.isConnected()) sessionVehicle.connect(getApplicationContext());
                final Map<String, String> vals = readVehicle(sessionVehicle, sessionTracker);
                ui.post(() -> {
                    if (vehicle != sessionVehicle) return;
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
                            int kmh = parseIntSafe(v);
                            updateSpeedNeedle(kmh);
                            cacheVeh.put(e.getKey(), v);              // 원본 km/h 캐시 (바늘/재생성용)
                            e.getValue().setText(speedNum(kmh));      // 단위 변환해 표시
                            continue;
                        } else if (isEfficiencyKey(e.getKey())) {
                            cacheVeh.put(e.getKey(), v);              // 원본 km/kWh 캐시
                            e.getValue().setText(effNum(parseFloatSafe(v)));
                            continue;
                        } else {
                            cs = v;
                        }
                        cacheVeh.put(e.getKey(), cs);
                        e.getValue().setText(cs);
                    }
                });
                if (vehicle == sessionVehicle && vehHandler == sessionHandler)
                    sessionHandler.postDelayed(this, 1000);
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
        if (g == null) return wFg();
        switch (g) {
            case "P": return wFg();   // 다크 모드에서도 보이게
            case "D": return 0xFF1E7E34;
            case "R": return 0xFFB3261E;
            case "N": return 0xFFE0661A;
        }
        return wFg();
    }

    /** 숫자 + 작은 단위 (예: 500 + 작은 km) */
    private CharSequence withUnit(String number, String unit) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(number);
        int start = sb.length();
        sb.append(unit);
        sb.setSpan(new android.text.style.RelativeSizeSpan(0.5f), start, sb.length(),
                android.text.Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.ForegroundColorSpan(wLabel()), start, sb.length(),
                android.text.Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private Map<String, String> readVehicle(VehicleInfo vehicle, EfficiencyTracker tracker) {
        Map<String, String> m = new LinkedHashMap<>();
        if (!vehicle.isConnected()) return m;
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
        if (vehValues.containsKey(ShortcutStore.KEY_V_EFF)) {
            VehicleInfo.FloatSample power = vehicle.chargeRateSample();
            tracker.sample(
                    SystemClock.elapsedRealtime(),
                    vehicle.gear(),
                    vehicle.speedMps(),
                    power.value,
                    power.timestampNanos,
                    vehicle.batteryWh(),
                    vehicle.wheelTicks(),
                    vehicle.wheelMicrometresPerTick());

            Float kwh100km = vehicle.tripAverageEfficiencyKwh100Km();
            Float efficiency;
            if (kwh100km != null) efficiency = 100f / kwh100km;
            else efficiency = tracker.efficiencyKmPerKwh();
            putEfficiency(m, ShortcutStore.KEY_V_EFF, efficiency);
        }
        return m;
    }

    private void putEfficiency(Map<String, String> values, String key, Float efficiency) {
        if (efficiency != null && Float.isFinite(efficiency) && efficiency > 0f) {
            values.put(key, String.format(Locale.US, "%.3f", efficiency));
        }
    }

    private void stopVehicle() {
        if (efficiencyTracker != null) efficiencyTracker.persist();
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
        efficiencyTracker = null;
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
            boolean settingsResource = ShortcutStore.SETTINGS_PKG.equals(s.iconPkg);
            boolean oemAppShortcut = isOemAppShortcut(s);
            boolean vehicleShortcut = settingsResource && !oemAppShortcut;
            if (vehicleShortcut || oemAppShortcut) {
                int inset = Math.round(side * (oemAppShortcut ? 0.15f : 0.25f));
                iv.setPadding(inset, inset, inset, inset);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(oemAppShortcut ? 0xFFF7EA48 : wBg());
                bg.setCornerRadius(side * CalendarTile.CORNER_RATIO);
                iv.setBackground(bg);
                if (vehicleShortcut && !"ic_car".equals(s.iconRes)) {
                    iv.setColorFilter(wFg());
                }
            } else {
                iv.setImageDrawable(roundVisibleCorners(icon, side));
            }
            item.addView(iv, new LinearLayout.LayoutParams(side, side));
        } else {
            TextView t = new TextView(this);
            t.setText(s.label.isEmpty() ? "?" : s.label);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(SHORTCUT_FG);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, side * 0.30f);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(SHORTCUT_BG);
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

    /** 아이콘의 보이는 영역(투명 여백 제외) 모서리만 둥글게 깎는다. 크기·위치는 그대로 둔다. */
    private Drawable roundVisibleCorners(Drawable icon, int size) {
        if (size <= 0) return icon;
        Bitmap src = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        icon.setBounds(0, 0, size, size);
        icon.draw(new Canvas(src));
        int[] px = new int[size * size];
        src.getPixels(px, 0, size, 0, 0, size, size);
        int left = size, top = size, right = -1, bottom = -1;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if ((px[y * size + x] >>> 24) < 16) continue;
                if (x < left) left = x;
                if (x > right) right = x;
                if (y < top) top = y;
                if (y > bottom) bottom = y;
            }
        }
        if (right < left || bottom < top) return icon;
        RectF r = new RectF(left, top, right + 1, bottom + 1);
        float radius = Math.min(r.width(), r.height()) * CalendarTile.CORNER_RATIO;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setShader(new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        new Canvas(out).drawRoundRect(r, radius, radius, paint);
        return new BitmapDrawable(getResources(), out);
    }

    private boolean isOemAppShortcut(Shortcut s) {
        if (s.iconRes.startsWith("ic_app_") || s.iconRes.startsWith("ic_launcher_app_")) {
            return true;
        }
        android.content.ComponentName component =
                android.content.ComponentName.unflattenFromString(s.component);
        if (component == null
                || !ShortcutStore.SETTINGS_PKG.equals(component.getPackageName())) return false;
        String cls = component.getClassName();
        return "android.settings.AMBIENCE".equals(cls)
                || "ecarx.settings.carstatus.ui.activity.CarStatusActivity".equals(cls)
                || "ecarx.settings.carstatus.ui.activity.TripMeterActivity".equals(cls);
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

    private void openAppAtBottom() {
        if (editPanel == null) editPanel = new EditPanel(this);
        editPanel.showAtBottom();
    }

    // ---------------------------------------------------------------- 홈(런처) 상태

    /** ECARX SystemUI가 기록하는 현재 디스플레이 영역 상태를 읽는다. */
    private String readDisplayAreaState() {
        try {
            return android.provider.Settings.Global.getString(
                    getContentResolver(), ECARX_DISPLAY_AREA_STATE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isDisplayAreaHome(String state) {
        return "Home".equals(state)
                || "HOME_RATIO_3".equals(state);
    }

    private void refreshDisplayAreaState() {
        String state = readDisplayAreaState();
        // SystemUI가 준비되는 동안 null이 잠시 반환돼도 마지막 유효 상태는 유지한다.
        if (state != null) displayAreaState = state;
        updateHomeMode();
    }

    /** Settings 변경과 SystemUI 브로드캐스트를 함께 감시해 상태 전환을 즉시 반영한다. */
    private void startDisplayAreaTracking() {
        // 첫 설치 전에 현재 상태를 반영한다. 이 시점에는 아직 독 뷰가 없으므로 재구성하지 않는다.
        displayAreaState = readDisplayAreaState();
        homeFgNow = isDisplayAreaHome(displayAreaState);

        displayAreaObserver = new android.database.ContentObserver(ui) {
            @Override
            public void onChange(boolean selfChange) {
                refreshDisplayAreaState();
            }
        };
        try {
            getContentResolver().registerContentObserver(
                    android.provider.Settings.Global.getUriFor(ECARX_DISPLAY_AREA_STATE),
                    false, displayAreaObserver);
        } catch (Throwable ignored) {
        }

        displayAreaReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !ECARX_DISPLAY_AREA_UPDATE.equals(intent.getAction())) return;
                String state = intent.getStringExtra(ECARX_DISPLAY_AREA_EXTRA_STATE);
                displayAreaState = state != null ? state : readDisplayAreaState();
                updateHomeMode();
            }
        };
        try {
            registerReceiver(displayAreaReceiver,
                    new android.content.IntentFilter(ECARX_DISPLAY_AREA_UPDATE));
        } catch (Throwable ignored) {
        }

        // 부팅 직후에는 Settings 값/브로드캐스트 등록보다 런처 준비가 늦을 수 있다.
        ui.removeCallbacks(displayAreaPoll);
        ui.postDelayed(displayAreaPoll, 500L);
    }

    private void stopDisplayAreaTracking() {
        ui.removeCallbacks(displayAreaPoll);
        if (displayAreaObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(displayAreaObserver);
            } catch (Throwable ignored) {
            }
            displayAreaObserver = null;
        }
        if (displayAreaReceiver != null) {
            try {
                unregisterReceiver(displayAreaReceiver);
            } catch (Throwable ignored) {
            }
            displayAreaReceiver = null;
        }
    }

    /** 앱 선택 화면을 닫은 뒤에만 인-독 편집 패널을 다시 연다. */
    private void startShortcutAddedTracking() {
        shortcutAddedReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                if (ACTION_GRID_BACKGROUND_CHANGED.equals(action)) {
                    gridPhotoBackground = null;
                    gridPhotoModified = 0L;
                    gridPhotoConfig = null;
                    ui.postDelayed(() -> {
                        if (!isRunning) return;
                        if (editPanel != null) editPanel.restoreAfterPhotoPicker();
                        else openApp();
                    }, 180);
                    return;
                }
                if (!ACTION_SHORTCUT_ADDED.equals(action)) return;
                ui.postDelayed(() -> {
                    if (isRunning) openAppAtBottom();
                }, 180);
            }
        };
        try {
            android.content.IntentFilter filter =
                    new android.content.IntentFilter(ACTION_SHORTCUT_ADDED);
            filter.addAction(ACTION_GRID_BACKGROUND_CHANGED);
            registerReceiver(shortcutAddedReceiver, filter);
        } catch (Throwable ignored) {
        }
    }

    private void stopShortcutAddedTracking() {
        if (shortcutAddedReceiver == null) return;
        try {
            unregisterReceiver(shortcutAddedReceiver);
        } catch (Throwable ignored) {
        }
        shortcutAddedReceiver = null;
    }

    private void updateHomeMode() {
        homeFgNow = isDisplayAreaHome(displayAreaState);
        String want = activeMode();
        if (!want.equals(mode)) {
            installDock();   // 모드 바뀌면 그 모드의 배치로 재구성 (mode=want로 갱신됨)
        }
    }

    // ---------------------------------------------------------------- 위치 제한

    private void clamp() {
        if (dock == null) return;
        clampSize(dock.getWidth(), dock.getHeight());
    }

    private void clampSize(int dockW, int dockH) {
        if (dock == null) return;
        DisplayMetrics dm = realMetrics();
        int m = gridActive ? 0 : d(2);   // 그리드는 좌우/하단 벽에 딱 붙게 여백 0
        int minX = m, minY = insetTop();   // 상단은 상태바 아래 유지
        int maxX = dm.widthPixels - dockW - m;
        int maxY = dm.heightPixels - insetBottom() - dockH - m;
        if (maxX < minX) maxX = minX;
        if (maxY < minY) maxY = minY;
        if (lp.x < minX) lp.x = minX;
        if (lp.x > maxX) lp.x = maxX;
        if (lp.y < minY) lp.y = minY;
        if (lp.y > maxY) lp.y = maxY;
    }

    // ---------------------------------------------------------------- 드래그 스냅(자석 배치)

    /** 손을 뗀 위치에 따라 배치(가로/세로/그리드)를 바꾸고 벽/타일에 붙인다. */
    /** 손 뗀 뒤 스냅 (그리드 타일에만). */
    private void snapAfterDrag() {
        applySnapFromPosition();
    }

    /**
     * 현재 독 위치로 배치/스냅 판정.
     * @param onlyOriChange true면 '배치가 실제로 바뀌는 경우(그리드 포함)'에만 발동 (드래그 중 실시간용).
     * @return 무언가 적용되면 true.
     */
    /**
     * 손 뗀 위치로 판정.
     * - 홈 런처 최우측 타일 헤더 → 그리드로 '위치 스냅'(타일에 붙임).
     * - 상단 밴드 → 가로, 좌측 밴드 → 세로로 '자동 전환'하되 위치는 제자리 피벗(벽 스냅 없음).
     * - 그 외 → 전환/스냅 없이 위치만 저장.
     */
    private void applySnapFromPosition() {
        if (dock == null) return;
        // 드래그는 위치 이동만 — 자동 전환/스냅 없음. 배치 전환은 편집 패널에서만.
        sp.edit().putInt(mk(KEY_X), lp.x).putInt(mk(KEY_Y), lp.y).apply();
    }

    /** 현재 크기 기준으로 지정한 벽/타일에 붙이고 위치 저장. */
    private void applySnapEdge(String edge) {
        if (dock == null || edge == null) return;
        DisplayMetrics dm = realMetrics();
        int sw = dm.widthPixels, sh = dm.heightPixels;
        int w = dock.getWidth(), h = dock.getHeight();
        int it = insetTop(), ib = insetBottom();
        switch (edge) {
            case "gridTop":    lp.x = sw - w - d(4); lp.y = it + d(4); break;
            case "gridRight":  lp.x = sw - w - d(4); lp.y = it + (sh - it - ib - h) / 2; break;
            case "gridBottom": lp.x = sw - w - d(4); lp.y = sh - ib - h - d(4); break;
        }
        clamp();
        wm.updateViewLayout(dock, lp);
        sp.edit().putInt(mk(KEY_X), lp.x).putInt(mk(KEY_Y), lp.y).apply();
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
                    if (gridActive) return true;   // 그리드는 위치 고정 (드래그 불가)
                    // 드래그 중엔 손가락만 따라감
                    lp.x = startX + (int) (e.getRawX() - touchX);
                    lp.y = startY + (int) (e.getRawY() - touchY);
                    clamp();
                    wm.updateViewLayout(dock, lp);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (gridActive) return true;   // 그리드는 위치 저장 안 함
                    clamp();
                    wm.updateViewLayout(dock, lp);
                    snapAfterDrag();   // 위치 저장
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

    // 위젯 타일 색 (다크 모드 토글) — 바로가기는 제외
    private boolean darkW() {
        return sp.getBoolean(ShortcutStore.KEY_DARK, false);
    }

    private int wBg() {
        return darkW() ? 0xFF2A2B2E : 0xFFFFFFFF;
    }

    private int wFg() {
        return darkW() ? 0xFFF2F3F5 : 0xFF1C1C1E;
    }

    private int wLabel() {
        return darkW() ? 0xFFB5BAC2 : 0xFF4E5157;
    }

    // 속도 단위 (km/h ↔ mph)
    private boolean speedMph() {
        return sp.getBoolean(ShortcutStore.KEY_SPEED_MPH, false);
    }

    private String speedUnit() {
        return speedMph() ? "mph" : "km/h";
    }

    private CharSequence vehicleUnitLabel(boolean speed, String efficiencyName) {
        String name = speed ? "속도" : efficiencyName;
        String unit = speed ? speedUnit() : effUnit();
        android.text.SpannableString text = new android.text.SpannableString(name + unit);
        text.setSpan(new android.text.style.RelativeSizeSpan(0.62f), name.length(), text.length(),
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    /** 속도 단위 전환은 독을 재구성하지 않고 라벨과 숫자만 바꾼다. */
    private void updateSpeedUnitUi() {
        if (speedLabel != null) speedLabel.setText(vehicleUnitLabel(true, ""));
        TextView value = vehValues.get(ShortcutStore.KEY_V_SPEED);
        CharSequence raw = cacheVeh.get(ShortcutStore.KEY_V_SPEED);
        if (value != null && raw != null) value.setText(speedNum(parseIntSafe(raw)));
    }

    /** km/h 정수를 현재 단위 문자열로 변환 */
    private String speedNum(int kmh) {
        if (kmh < 0) return "—";
        return String.valueOf(speedMph() ? Math.round(kmh * 0.621371f) : kmh);
    }

    private void toggleSpeedUnit() {
        sp.edit().putBoolean(ShortcutStore.KEY_SPEED_MPH, !speedMph()).apply();
    }

    // 전비 단위 (km/kWh ↔ Wh/km)
    private boolean effWhkm() {
        return sp.getBoolean(ShortcutStore.KEY_EFF_WHKM, false);
    }

    private String effUnit() {
        return effWhkm() ? "kWh/100km" : "km/kWh";
    }

    /** 전비 단위 전환은 독을 재구성하지 않고 라벨과 숫자만 바꾼다. */
    private void updateEffUnitUi() {
        for (Map.Entry<String, TextView> entry : effLabels.entrySet()) {
            entry.getValue().setText(vehicleUnitLabel(false, efficiencyName(entry.getKey())));
            TextView value = vehValues.get(entry.getKey());
            CharSequence raw = cacheVeh.get(entry.getKey());
            if (value != null && raw != null) value.setText(effNum(parseFloatSafe(raw)));
        }
    }

    private boolean isEfficiencyKey(String key) {
        return ShortcutStore.KEY_V_EFF.equals(key);
    }

    private String efficiencyName(String key) {
        return "전비";
    }

    /** km/kWh 실수를 현재 단위 문자열로 */
    private String effNum(float kmPerKwh) {
        if (kmPerKwh <= 0) return "—";
        return effWhkm()
                ? String.format(java.util.Locale.US, "%.1f", 100f / kmPerKwh)  // kWh/100km
                : String.format(java.util.Locale.US, "%.1f", kmPerKwh);
    }

    private float parseFloatSafe(CharSequence s) {
        if (s == null) return -1f;
        try {
            return Float.parseFloat(s.toString().trim());
        } catch (Exception e) {
            return -1f;
        }
    }

    private void toggleEffUnit() {
        sp.edit().putBoolean(ShortcutStore.KEY_EFF_WHKM, !effWhkm()).apply();
    }

    private void applyDockBackground(LinearLayout bar, boolean grid) {
        if (!grid) {
            barBg.setColor(0xDD111111);
            barBg.setStroke(d(1), 0x55FFFFFF);
            bar.setBackground(barBg);
            return;
        }

        String backgroundMode = sp.getString(ShortcutStore.KEY_GRID_BG_MODE, "default");
        if ("photo".equals(backgroundMode)) {
            java.io.File photo = new java.io.File(getFilesDir(),
                    GridBackgroundPickerActivity.PHOTO_FILE);
            long modified = photo.isFile() ? photo.lastModified() : 0L;
            String sizeMode = sp.getString(ShortcutStore.KEY_GRID_PHOTO_SIZE, "fit");
            String alignX = sp.getString(ShortcutStore.KEY_GRID_PHOTO_ALIGN_X, "center");
            String alignY = sp.getString(ShortcutStore.KEY_GRID_PHOTO_ALIGN_Y, "bottom");
            String backdrop = sp.getString(ShortcutStore.KEY_GRID_PHOTO_BACKDROP, "color");
            int color = sp.getInt(ShortcutStore.KEY_GRID_BG_COLOR,
                    ShortcutStore.DEFAULT_GRID_BG_COLOR) | 0xFF000000;
            String config = sizeMode + '|' + alignX + '|' + alignY + '|'
                    + backdrop + '|' + color;
            if (gridPhotoBackground == null || gridPhotoModified != modified
                    || !config.equals(gridPhotoConfig)) {
                gridPhotoBackground = GridBackgroundDrawable.load(photo, sizeMode,
                        alignX, alignY, backdrop, color);
                gridPhotoModified = modified;
                gridPhotoConfig = config;
            }
            if (gridPhotoBackground != null) {
                bar.setBackground(gridPhotoBackground);
                return;
            }
        }

        int color = "color".equals(backgroundMode)
                ? sp.getInt(ShortcutStore.KEY_GRID_BG_COLOR,
                        ShortcutStore.DEFAULT_GRID_BG_COLOR) | 0xFF000000
                : ShortcutStore.DEFAULT_GRID_BG_COLOR;
        barBg.setColor(color);
        barBg.setStroke(0, 0);
        barBg.setCornerRadius(0f);
        bar.setBackground(barBg);
    }

    /** 독 불투명도 0.2~1.0 (그리드는 항상 불투명 고정) */
    private float dockAlpha() {
        if (gridActive) return 1f;
        int a = sp.getInt(ShortcutStore.KEY_ALPHA, ShortcutStore.DEFAULT_ALPHA);
        return Math.max(20, Math.min(100, a)) / 100f;
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
        gridPage = page;   // 재구성 후 유지용
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
        ui.removeCallbacks(updateCheck);
        ui.removeCallbacks(clockTick);
        ui.removeCallbacks(envTick);
        ui.removeCallbacks(musicTick);
        ui.removeCallbacks(hazardTick);
        stopVehicle();
        stopMediaCallbacks();
        stopDisplayAreaTracking();
        stopShortcutAddedTracking();
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

    /** 중지된 독도 차량 런처에서 다시 누르면 확실히 되살린다. */
    static void start(Context ctx) {
        Intent intent = new Intent(ctx, OverlayService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }
        } catch (Throwable ignored) {
        }
    }
}
