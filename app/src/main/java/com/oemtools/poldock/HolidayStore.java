package com.oemtools.poldock;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 공공데이터포털 특일 정보(getRestDeInfo)에서 공휴일(isHoliday=Y)의 날짜와 이름을 받아
 * yyyymmdd→이름 맵으로 캐시한다. 하루 1회만 갱신하고, 실패하면 이전 캐시를 유지한다.
 */
final class HolidayStore {

    // 서비스 키는 저장소에 올리지 않도록 local.properties(poldock.holidayApiKey)에서
    // 빌드 시 BuildConfig 로 주입한다. 비어 있으면 갱신을 건너뛴다.
    private static final String SERVICE_KEY = BuildConfig.HOLIDAY_API_KEY;
    private static final String BASE_URL =
            "https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService/getRestDeInfo";

    static final String KEY_SET = "holiday_set";            // "20260101=1월1일\n..."
    static final String KEY_FETCH_DAY = "holiday_fetch_day"; // epoch-day(once/day 스로틀)

    private static final AtomicBoolean FETCHING = new AtomicBoolean(false);
    private static volatile Map<Integer, String> cache;      // yyyymmdd → 이름

    private HolidayStore() {
    }

    static boolean isInternalPreference(String key) {
        return KEY_SET.equals(key) || KEY_FETCH_DAY.equals(key);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(ShortcutStore.PREF, Context.MODE_PRIVATE);
    }

    private static Map<Integer, String> map(Context c) {
        Map<Integer, String> local = cache;
        if (local != null) return local;
        Map<Integer, String> loaded = new LinkedHashMap<>();
        String stored = prefs(c).getString(KEY_SET, "");
        if (!stored.isEmpty()) {
            for (String line : stored.split("\n")) {
                int eq = line.indexOf('=');
                String datePart = eq >= 0 ? line.substring(0, eq) : line;
                String name = eq >= 0 ? line.substring(eq + 1) : "";
                try {
                    loaded.put(Integer.parseInt(datePart.trim()), name);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        cache = loaded;
        return loaded;
    }

    /** 달력 뷰용: 공휴일 날짜 집합(yyyymmdd). */
    static Set<Integer> get(Context c) {
        return map(c).keySet();
    }

    static boolean isHoliday(Context c, int year, int month1to12, int day) {
        return map(c).containsKey(year * 10000 + month1to12 * 100 + day);
    }

    static boolean isHoliday(Context c, Calendar cal) {
        return isHoliday(c, cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    /** 공휴일 이름(없으면 null). */
    static String nameFor(Context c, Calendar cal) {
        int key = cal.get(Calendar.YEAR) * 10000
                + (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(Calendar.DAY_OF_MONTH);
        String name = map(c).get(key);
        return (name == null || name.isEmpty()) ? null : name;
    }

    /**
     * 하루 1회 공휴일 갱신. 네트워크는 별도 스레드. 성공 시 onUpdated 를 그 백그라운드
     * 스레드에서 실행하므로, UI 반영은 콜백 안에서 post 하라.
     */
    static void refreshIfDue(Context context, Runnable onUpdated) {
        if (SERVICE_KEY == null || SERVICE_KEY.isEmpty()) return;   // 키 미설정 시 비활성
        long today = System.currentTimeMillis() / 86_400_000L;
        long last = prefs(context).getLong(KEY_FETCH_DAY, 0L);
        if (last == today) return;                 // 오늘 이미 받음
        if (!FETCHING.compareAndSet(false, true)) return;

        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                Calendar now = Calendar.getInstance();
                int year = now.get(Calendar.YEAR);
                Map<Integer, String> fetched = new LinkedHashMap<>();
                boolean ok = fetchYear(app, year, fetched);
                // 연말에는 다음 해도 받아 1월 초가 바로 보이게 한다.
                if (now.get(Calendar.MONTH) >= Calendar.NOVEMBER) {
                    ok &= fetchYear(app, year + 1, fetched);
                }
                if (ok && !fetched.isEmpty()) {
                    save(app, fetched);
                    prefs(app).edit().putLong(KEY_FETCH_DAY, today).apply();
                    if (onUpdated != null) onUpdated.run();
                }
            } catch (Exception ignored) {
                // 실패 시 이전 캐시 유지, 스로틀도 갱신 안 함 → 다음 기회에 재시도
            } finally {
                FETCHING.set(false);
            }
        }, "poldock-holiday").start();
    }

    private static void save(Context c, Map<Integer, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> e : m.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.getKey()).append('=').append(e.getValue().replace("\n", " "));
        }
        prefs(c).edit().putString(KEY_SET, sb.toString()).apply();
        cache = Collections.unmodifiableMap(new LinkedHashMap<>(m));
    }

    /** 한 해의 공휴일을 out 에 추가. 성공 시 true. */
    private static boolean fetchYear(Context c, int year, Map<Integer, String> out) {
        HttpURLConnection conn = null;
        try {
            String url = BASE_URL + "?serviceKey=" + SERVICE_KEY
                    + "&solYear=" + year + "&numOfRows=100";
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(8_000);
            conn.setRequestProperty("Accept", "application/xml");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return false;
            try (InputStream in = conn.getInputStream()) {
                return parse(in, out);
            }
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** <item> 안의 isHoliday=Y 인 locdate(yyyymmdd)→dateName 만 수집. */
    private static boolean parse(InputStream in, Map<Integer, String> out) {
        try {
            XmlPullParser xml = Xml.newPullParser();
            xml.setInput(in, "UTF-8");
            boolean inItem = false;
            boolean holiday = false;
            Integer locdate = null;
            String name = "";
            String tag = null;
            int ev = xml.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    tag = xml.getName();
                    if ("item".equals(tag)) {
                        inItem = true;
                        holiday = false;
                        locdate = null;
                        name = "";
                    }
                } else if (ev == XmlPullParser.TEXT && inItem && tag != null) {
                    String text = xml.getText().trim();
                    if ("isHoliday".equals(tag)) {
                        holiday = "Y".equalsIgnoreCase(text);
                    } else if ("dateName".equals(tag)) {
                        name = text;
                    } else if ("locdate".equals(tag) && text.length() == 8) {
                        try {
                            locdate = Integer.parseInt(text);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    if ("item".equals(xml.getName())) {
                        if (inItem && holiday && locdate != null) out.put(locdate, name);
                        inItem = false;
                    }
                    tag = null;
                }
                ev = xml.next();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 색상 규칙: 공휴일/일요일=빨강, 토요일=파랑, 평일=기본색(def). */
    static int DAY_RED = 0xFFFF3B30;
    static int DAY_BLUE = 0xFF0A84FF;

    static int colorFor(int dayOfWeek, boolean holiday, int def) {
        if (holiday || dayOfWeek == Calendar.SUNDAY) return DAY_RED;
        if (dayOfWeek == Calendar.SATURDAY) return DAY_BLUE;
        return def;
    }

    static Date todayDate() {
        return new Date();
    }
}
