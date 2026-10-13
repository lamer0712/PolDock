package com.oemtools.poldock;

import android.content.Context;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.List;

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
 *   EV_BATTERY_INSTANTANEOUS_CHARGE_RATE 291504908 (0x1160030C) CAR_ENERGY [mW]
 *   WHEEL_TICK             290521862 (0x11510306)  CAR_SPEED             [ticks]
 */
public class VehicleInfo {

    public static final int GEAR_SELECTION = 289408000;
    public static final int PERF_VEHICLE_SPEED = 291504647;
    public static final int ENV_OUTSIDE_TEMPERATURE = 291505923;
    public static final int EV_BATTERY_LEVEL = 291504905;
    public static final int INFO_EV_BATTERY_CAPACITY = 291504390;
    public static final int RANGE_REMAINING = 291504904;
    public static final int EV_BATTERY_INSTANTANEOUS_CHARGE_RATE = 291504908;
    public static final int WHEEL_TICK = 290521862;

    private Object car;
    private Object cpm;
    private Method getInt;
    private Method getFloat;
    private Method getTypedProperty;
    private Method getPropertyConfig;
    private int[] wheelMicrometresPerTick;
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
            try {
                getTypedProperty = cpm.getClass().getMethod(
                        "getProperty", Class.class, int.class, int.class);
                getPropertyConfig = cpm.getClass().getMethod("getCarPropertyConfig", int.class);
                wheelMicrometresPerTick = readWheelConfig();
            } catch (Throwable ignored) {
                getTypedProperty = null;
                getPropertyConfig = null;
            }
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

    /** 속도 m/s, 못 읽으면 null. */
    Float speedMps() {
        return readFloat(PERF_VEHICLE_SPEED);
    }

    /** 순간 충전(+)/방전(-) 전력 mW, 못 읽으면 null. */
    Float chargeRateMilliwatts() {
        return readFloat(EV_BATTERY_INSTANTANEOUS_CHARGE_RATE);
    }

    /** 현재 남은 배터리 에너지 Wh, 못 읽으면 null. */
    Float batteryWh() {
        return readFloat(EV_BATTERY_LEVEL);
    }

    /** WHEEL_TICK의 [timestamp, FL, FR, RL, RR], 못 읽으면 null. */
    long[] wheelTicks() {
        if (getTypedProperty == null) return null;
        try {
            Class<?> boxedLongArray = Array.newInstance(Long.class, 0).getClass();
            Object property = getTypedProperty.invoke(cpm, boxedLongArray, WHEEL_TICK, 0);
            if (property == null) return null;
            Object raw = property.getClass().getMethod("getValue").invoke(property);
            if (!(raw instanceof Object[])) return null;
            Object[] values = (Object[]) raw;
            long[] result = new long[values.length];
            for (int i = 0; i < values.length; i++) {
                if (!(values[i] instanceof Number)) return null;
                result[i] = ((Number) values[i]).longValue();
            }
            return result;
        } catch (Throwable t) {
            return null;
        }
    }

    int[] wheelMicrometresPerTick() {
        return wheelMicrometresPerTick == null ? null : wheelMicrometresPerTick.clone();
    }

    private int[] readWheelConfig() {
        if (getPropertyConfig == null) return null;
        try {
            Object config = getPropertyConfig.invoke(cpm, WHEEL_TICK);
            if (config == null) return null;
            Object raw = config.getClass().getMethod("getConfigArray").invoke(config);
            if (!(raw instanceof List)) return null;
            List<?> values = (List<?>) raw;
            if (values.size() < 5) return null;
            int[] result = new int[values.size()];
            for (int i = 0; i < values.size(); i++) {
                if (!(values.get(i) instanceof Number)) return null;
                result[i] = ((Number) values.get(i)).intValue();
            }
            return result;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 주행가능거리 km, 못 읽으면 null */
    Integer rangeKm() {
        Float m = readFloat(RANGE_REMAINING);
        if (m == null) return null;
        return Math.round(m / 1000f);
    }

    /** 주행가능거리와 남은 배터리 에너지로 계산한 예상 전비 km/kWh. */
    Float predictedEfficiencyKmPerKwh() {
        Float rangeM = readFloat(RANGE_REMAINING);
        Float remainingWh = readFloat(EV_BATTERY_LEVEL);
        if (rangeM == null || remainingWh == null || rangeM <= 0f || remainingWh <= 0f) {
            return null;
        }
        float value = rangeM / remainingWh;
        return Float.isFinite(value) && value > 0f ? value : null;
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
