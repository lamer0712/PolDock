package com.oemtools.mirrorfold;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 바로가기 목록을 SharedPreferences(JSON)로 저장/로드. */
public class ShortcutStore {
    static final String PREF = "mirrorfold";
    static final String KEY = "shortcuts";
    private static final String SEEDED = "seeded_v1";

    // 위젯 설정
    static final String KEY_DATE_ON = "date_on";
    static final String KEY_DATE_FMT = "date_fmt";
    static final String KEY_TIME_ON = "time_on";
    static final String KEY_TIME_FMT = "time_fmt";
    static final String KEY_WEATHER_ON = "weather_on";
    static final String KEY_DUST_ON = "dust_on";
    // 차량 데이터 개별
    static final String KEY_V_GEAR = "v_gear";
    static final String KEY_V_SPEED = "v_speed";
    static final String KEY_V_BATTERY = "v_battery";
    static final String KEY_V_RANGE = "v_range";
    static final String KEY_V_TEMP = "v_temp";
    static final String KEY_V_EFF = "v_eff";
    static final String KEY_MUSIC_ON = "music_on";

    static final String KEY_SCALE = "dock_scale"; // 퍼센트 50~200
    static final int DEFAULT_SCALE = 150;
    static final String KEY_WIDGET_ORDER = "widget_order";
    static final String KEY_HOME_PROFILE = "home_profile"; // 홈(런처)에서 별도 배치 사용
    static final String KEY_EDIT_MODE = "edit_mode";       // 설정 편집 대상: auto/default/home
    static final String KEY_GRID_C = "grid_cols";          // 그리드 열
    static final String KEY_GRID_R = "grid_rows";          // 그리드 행
    static final int DEFAULT_GRID = 3;

    static final String DEFAULT_DATE_FMT = "M월 d일 (E)";
    static final String DEFAULT_TIME_FMT = "a h:mm";

    // 위젯 순서/식별자
    static final String[] WIDGET_IDS = {"date", "time", "weather", "dust", "music",
            "v_gear", "v_battery", "v_range", "v_speed", "v_eff", "v_temp"};

    static String widgetLabel(String id) {
        switch (id) {
            case "date": return "날짜";
            case "time": return "시간";
            case "weather": return "날씨";
            case "dust": return "미세먼지";
            case "music": return "음악";
            case "v_gear": return "기어";
            case "v_battery": return "배터리";
            case "v_range": return "주행거리";
            case "v_speed": return "속도";
            case "v_eff": return "전비";
            case "v_temp": return "외기온도";
        }
        return id;
    }

    static String widgetOnKey(String id) {
        switch (id) {
            case "date": return KEY_DATE_ON;
            case "time": return KEY_TIME_ON;
            case "weather": return KEY_WEATHER_ON;
            case "dust": return KEY_DUST_ON;
            case "music": return KEY_MUSIC_ON;
            case "v_gear": return KEY_V_GEAR;
            case "v_battery": return KEY_V_BATTERY;
            case "v_range": return KEY_V_RANGE;
            case "v_speed": return KEY_V_SPEED;
            case "v_eff": return KEY_V_EFF;
            case "v_temp": return KEY_V_TEMP;
        }
        return id + "_on";
    }

    static boolean widgetDefaultOn(String id) {
        switch (id) {
            case "date":
            case "time":
            case "weather":
            case "music":
            case "v_gear":
                return true;
        }
        return false;
    }

    /** 저장된 순서(없으면 기본). 누락 id는 뒤에 보충. */
    static List<String> widgetOrder(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String s = sp.getString(KEY_WIDGET_ORDER, "");
        List<String> order = new ArrayList<>();
        for (String id : s.split(",")) {
            id = id.trim();
            if (!id.isEmpty() && widgetValid(id) && !order.contains(id)) order.add(id);
        }
        for (String id : WIDGET_IDS) if (!order.contains(id)) order.add(id);
        return order;
    }

    static void saveWidgetOrder(Context ctx, List<String> order) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < order.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(order.get(i));
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY_WIDGET_ORDER, sb.toString()).apply();
    }

    private static boolean widgetValid(String id) {
        for (String w : WIDGET_IDS) if (w.equals(id)) return true;
        return false;
    }

    static boolean anyVehicle(android.content.Context c) {
        android.content.SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_V_GEAR, false) || sp.getBoolean(KEY_V_SPEED, false)
                || sp.getBoolean(KEY_V_BATTERY, false) || sp.getBoolean(KEY_V_RANGE, false)
                || sp.getBoolean(KEY_V_TEMP, false);
    }

    static final String SETTINGS_PKG = "com.android.car.settings";

    static List<Shortcut> load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (!sp.getBoolean(SEEDED, false)) {
            List<Shortcut> seed = defaults();
            save(ctx, seed);
            sp.edit().putBoolean(SEEDED, true).apply();
            return seed;
        }
        List<Shortcut> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) list.add(Shortcut.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        return list;
    }

    static void save(Context ctx, List<Shortcut> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Shortcut s : list) arr.put(s.toJson());
        } catch (Exception ignored) {
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply();
    }

    /** 처음 실행 시 기본 바로가기 */
    static List<Shortcut> defaults() {
        // 기본 바로가기 없음 (설치 직후 비어 있음)
        return new ArrayList<>();
    }

    /** 앱에서 "프리셋 추가"로 고를 수 있는, 뚫린 통로 모음 */
    static List<Shortcut> presets() {
        List<Shortcut> l = new ArrayList<>();
        String S = SETTINGS_PKG;
        l.add(new Shortcut("홈", "android.intent.action.MAIN",
                "android.intent.category.HOME", "", "", "s", "").icon(S, "ic_car"));
        l.add(new Shortcut("미러 접기", "ecarx.intent.action.SUB_SETTINGS", "",
                "sub_fragment_id", "0x7f0b03b6", "i", "").icon(S, "ic_fav_fold_mirror_48"));
        l.add(new Shortcut("성에제거 미러", "ecarx.intent.action.SUB_SETTINGS", "",
                "sub_fragment_id", "0x7f0b03b1", "i", "").icon(S, "ic_fav_fold_mirror_48"));
        l.add(new Shortcut("차량 상태", "ecarx.intent.action.SECONDARY_CAR_STATUS", "",
                "", "", "s", "").icon(S, "ic_app_car_status"));
        l.add(new Shortcut("앰비언스", "android.settings.AMBIENCE", "",
                "", "", "s", "").icon(S, "ic_app_ambience"));
        return l;
    }
}
