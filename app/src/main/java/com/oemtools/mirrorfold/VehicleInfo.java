package com.oemtools.mirrorfold;

import android.content.Context;

import java.lang.reflect.Method;

/**
 * Android Car API(CarPropertyManager)로 VHAL 속성을 읽는다. 전부 리플렉션이라
 * android.car 스텁 없이 빌드된다 (android.car 는 기기 부트클래스패스에 있음).
 *
 * 이 ROM은 읽기 권한을 normal/dangerous 로 열어둬서 사이드로드 앱도 값을 받는다.
 *   GEAR_SELECTION        289408000 (0x11400400)  CAR_POWERTRAIN(normal)
 *   PERF_VEHICLE_SPEED    291504647 (0x11600207)  CAR_SPEED(dangerous)
 *   ENV_OUTSIDE_TEMP      291505923 (0x11600703)  CAR_EXTERIOR_ENVIRONMENT(normal)
 *   EV_BATTERY_LEVEL      291504905 (0x11600309)  CAR_ENERGY(dangerous)  [Wh]
 *   INFO_EV_BATTERY_CAPACITY 291504390 (0x11600306) CAR_ENERGY           [Wh]
 *   RANGE_REMAINING       291504904 (0x11600308)  CAR_ENERGY            [m]
 */
public class VehicleInfo {

    public static final int GEAR_SELECTION = 289408000;
    public static final int PERF_VEHICLE_SPEED = 291504647;
    public static final int ENV_OUTSIDE_TEMPERATURE = 291505923;
    public static final int EV_BATTERY_LEVEL = 291504905;
    public static final int INFO_EV_BATTERY_CAPACITY = 291504390;
    public static final int RANGE_REMAINING = 291504904;

    private Object car;
    private Object cpm;
    private Method getInt;
    private Method getFloat;
    private boolean connected;

    boolean connect(Context ctx) {
        try {
            Class<?> carCls = Class.forName("android.car.Car");
            Method createCar = carCls.getMethod("createCar", Context.class);
            car = createCar.invoke(null, ctx);
            if (car == null) return false;
            Method getMgr = carCls.getMethod("getCarManager", String.class);
            cpm = getMgr.invoke(car, "property");   // Car.PROPERTY_SERVICE
            if (cpm == null) return false;
            getInt = cpm.getClass().getMethod("getIntProperty", int.class, int.class);
            getFloat = cpm.getClass().getMethod("getFloatProperty", int.class, int.class);
            connected = true;
        } catch (Throwable t) {
            connected = false;
        }
        return connected;
    }

    boolean isConnected() {
        return connected;
    }

    private Integer readInt(int prop) {
        try {
            return (Integer) getInt.invoke(cpm, prop, 0);
        } catch (Throwable t) {
            return null;
        }
    }

    private Float readFloat(int prop) {
        try {
            return (Float) getFloat.invoke(cpm, prop, 0);
        } catch (Throwable t) {
            return null;
        }
    }

    /** P/R/N/D 문자열. 못 읽으면 null. */
    String gear() {
        Integer g = readInt(GEAR_SELECTION);
        if (g == null) return null;
        switch (g) {
            case 4: return "P";
            case 2: return "R";
            case 1: return "N";
            case 8: return "D";
            default: return "G" + g;
        }
    }

    /** km/h, 못 읽으면 null */
    Integer speedKmh() {
        Float mps = readFloat(PERF_VEHICLE_SPEED);
        if (mps == null) return null;
        return Math.round(Math.abs(mps) * 3.6f);
    }

    /** 외기온 °C, 못 읽으면 null */
    Integer outsideTemp() {
        Float c = readFloat(ENV_OUTSIDE_TEMPERATURE);
        if (c == null) return null;
        return Math.round(c);
    }

    /** 속도 m/s (전비 적분용), 못 읽으면 null */
    Float speedMps() {
        return readFloat(PERF_VEHICLE_SPEED);
    }

    /** 배터리 잔량 에너지 Wh (전비 계산용), 못 읽으면 null */
    Float batteryWh() {
        return readFloat(EV_BATTERY_LEVEL);
    }

    /** 주행가능거리 km, 못 읽으면 null */
    Integer rangeKm() {
        Float m = readFloat(RANGE_REMAINING);
        if (m == null) return null;
        return Math.round(m / 1000f);
    }

    /** 배터리 %, 못 읽으면 null */
    Integer batteryPercent() {
        Float lvl = readFloat(EV_BATTERY_LEVEL);
        Float cap = readFloat(INFO_EV_BATTERY_CAPACITY);
        if (lvl == null || cap == null || cap <= 0) return null;
        int p = Math.round(lvl / cap * 100f);
        return Math.max(0, Math.min(100, p));
    }

    void close() {
        try {
            if (car != null) car.getClass().getMethod("disconnect").invoke(car);
        } catch (Throwable ignored) {
        }
        connected = false;
    }
}
