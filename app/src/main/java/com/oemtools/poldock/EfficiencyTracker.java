package com.oemtools.poldock;

import android.content.SharedPreferences;

/** P.Log 방식의 주행 거리/배터리 전력 적분 전비 계산기. */
final class EfficiencyTracker {
    private static final String KEY_CALIBRATION = "efficiency_calibration_factor";
    private static final String KEY_ACTIVE = "efficiency_state_active";
    private static final String KEY_SPEED_DISTANCE = "efficiency_state_speed_distance";
    private static final String KEY_WHEEL_DISTANCE = "efficiency_state_wheel_distance";
    private static final String KEY_NET_INTEGRAL = "efficiency_state_net_integral";
    private static final String KEY_START_BATTERY = "efficiency_state_start_battery";
    private static final String KEY_LATEST_BATTERY = "efficiency_state_latest_battery";
    private static final String KEY_LAST_EFFICIENCY = "efficiency_state_last_efficiency";
    private static final double DEFAULT_WHEEL_UM_PER_TICK = 24000.0;
    private static final double RANGE_CALIBRATION = 1.3;
    private static final long PERSIST_INTERVAL_MS = 10_000L;

    private final SharedPreferences preferences;
    private boolean active;
    private long lastSampleMs;
    private long stoppedSinceMs;
    private long[] lastWheelTicks;
    private double speedDistanceM;
    private double wheelDistanceM;
    private double netIntegralKwh;
    private Float startBatteryWh;
    private Float latestBatteryWh;
    private Float lastEfficiency;
    private double calibrationFactor;
    private long lastPersistMs;

    EfficiencyTracker(SharedPreferences preferences) {
        this.preferences = preferences;
        calibrationFactor = clampCalibration(preferences.getFloat(KEY_CALIBRATION, 1.3f));
        active = preferences.getBoolean(KEY_ACTIVE, false);
        speedDistanceM = readDouble(KEY_SPEED_DISTANCE);
        wheelDistanceM = readDouble(KEY_WHEEL_DISTANCE);
        netIntegralKwh = readDouble(KEY_NET_INTEGRAL);
        startBatteryWh = readFloat(KEY_START_BATTERY);
        latestBatteryWh = readFloat(KEY_LATEST_BATTERY);
        lastEfficiency = readFloat(KEY_LAST_EFFICIENCY);
    }

    synchronized void sample(long nowMs, String gear, Float speedMps, Float powerMilliwatts,
                Float batteryWh, long[] wheelTicks, int[] wheelUm) {
        boolean driveSelected = "D".equals(gear) || "R".equals(gear);
        float speed = valid(speedMps) ? Math.abs(speedMps) : 0f;

        if (!active && (driveSelected || (gear == null && speed >= 0.55f))) {
            start(nowMs, speed, batteryWh, wheelTicks);
            persist(false);
            return;
        }

        if (!active) {
            lastWheelTicks = cloneTicks(wheelTicks);
            return;
        }

        long elapsedMs = lastSampleMs > 0 ? nowMs - lastSampleMs : 0L;
        if (elapsedMs >= 200L && elapsedMs <= 10000L) {
            double dtSeconds = elapsedMs / 1000.0;
            speedDistanceM += speed * dtSeconds;
            wheelDistanceM += wheelDeltaMeters(wheelTicks, wheelUm);
            if (valid(powerMilliwatts)) {
                double rateKw = powerMilliwatts / 1_000_000.0;
                netIntegralKwh += rateKw * dtSeconds / 3600.0;
            }
        }

        lastSampleMs = nowMs;
        lastWheelTicks = cloneTicks(wheelTicks);
        if (valid(batteryWh)) latestBatteryWh = batteryWh;

        if (gear == null && speed < 0.28f) {
            if (stoppedSinceMs == 0L) stoppedSinceMs = nowMs;
        } else {
            stoppedSinceMs = 0L;
        }
        if ("P".equals(gear) || (gear == null && stoppedSinceMs > 0L
                && nowMs - stoppedSinceMs >= 180_000L)) finish();
        else if (nowMs - lastPersistMs >= PERSIST_INTERVAL_MS) persist(false);
    }

    synchronized Float efficiencyKmPerKwh() {
        if (!active) return lastEfficiency;
        double distanceKm = preferredDistanceM() / 1000.0;
        double energyKwh = currentEnergyKwh();
        if (distanceKm < 0.3 || energyKwh <= 0.1) return lastEfficiency;
        double value = distanceKm / energyKwh;
        if (!Double.isFinite(value) || value <= 0.0) return lastEfficiency;
        lastEfficiency = (float) value;
        return lastEfficiency;
    }

    /** 주행가능거리/배터리 잔량 기반 예상 전비에는 고정 보정계수 1.3을 곱한다. */
    synchronized Float calibratedEfficiency(Float rawEfficiency) {
        if (!valid(rawEfficiency) || rawEfficiency <= 0f) return null;
        double value = rawEfficiency * RANGE_CALIBRATION;
        return Double.isFinite(value) && value > 0.0 ? (float) value : null;
    }

    private void start(long nowMs, float speed, Float batteryWh, long[] wheelTicks) {
        active = true;
        lastSampleMs = nowMs;
        stoppedSinceMs = 0L;
        lastWheelTicks = cloneTicks(wheelTicks);
        speedDistanceM = 0.0;
        wheelDistanceM = 0.0;
        netIntegralKwh = 0.0;
        lastEfficiency = null;
        startBatteryWh = valid(batteryWh) ? batteryWh : null;
        latestBatteryWh = startBatteryWh;
    }

    private void finish() {
        double distanceKm = preferredDistanceM() / 1000.0;
        double energyKwh = currentEnergyKwh();
        if (distanceKm >= 0.3 && energyKwh > 0.1) {
            lastEfficiency = (float) (distanceKm / energyKwh);
        }

        double integralKwh = -netIntegralKwh;
        Double deltaKwh = batteryDeltaKwh();
        if (deltaKwh != null && deltaKwh > 0.0 && integralKwh > 0.3) {
            double target = clampCalibration(deltaKwh / integralKwh);
            calibrationFactor = (calibrationFactor * 0.8) + (target * 0.2);
            preferences.edit().putFloat(KEY_CALIBRATION, (float) calibrationFactor).apply();
        }
        active = false;
        lastSampleMs = 0L;
        persist(false);
    }

    /** 독 재구성이나 서비스 종료 전에 현재 누적 상태를 디스크에 확정한다. */
    synchronized void persist() {
        persist(true);
    }

    private void persist(boolean synchronous) {
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(KEY_ACTIVE, active)
                .putLong(KEY_SPEED_DISTANCE, Double.doubleToRawLongBits(speedDistanceM))
                .putLong(KEY_WHEEL_DISTANCE, Double.doubleToRawLongBits(wheelDistanceM))
                .putLong(KEY_NET_INTEGRAL, Double.doubleToRawLongBits(netIntegralKwh));
        putFloat(editor, KEY_START_BATTERY, startBatteryWh);
        putFloat(editor, KEY_LATEST_BATTERY, latestBatteryWh);
        putFloat(editor, KEY_LAST_EFFICIENCY, lastEfficiency);
        if (synchronous) editor.commit();
        else editor.apply();
        lastPersistMs = android.os.SystemClock.elapsedRealtime();
    }

    private double readDouble(String key) {
        return Double.longBitsToDouble(preferences.getLong(
                key, Double.doubleToRawLongBits(0.0)));
    }

    private Float readFloat(String key) {
        float value = preferences.getFloat(key, Float.NaN);
        return Float.isFinite(value) ? value : null;
    }

    private static void putFloat(SharedPreferences.Editor editor, String key, Float value) {
        if (valid(value)) editor.putFloat(key, value);
        else editor.remove(key);
    }

    private double currentEnergyKwh() {
        double integralKwh = -netIntegralKwh;
        if (integralKwh > 0.1) return integralKwh * calibrationFactor;
        Double delta = batteryDeltaKwh();
        return delta == null ? 0.0 : delta;
    }

    private Double batteryDeltaKwh() {
        if (startBatteryWh == null || latestBatteryWh == null
                || startBatteryWh <= latestBatteryWh) return null;
        return (startBatteryWh - latestBatteryWh) / 1000.0;
    }

    private double preferredDistanceM() {
        return wheelDistanceM >= 50.0 ? wheelDistanceM : speedDistanceM;
    }

    private double wheelDeltaMeters(long[] now, int[] wheelUm) {
        if (now == null || lastWheelTicks == null || now.length < 5
                || lastWheelTicks.length < 5) return 0.0;
        double sumM = 0.0;
        int count = 0;
        for (int i = 1; i <= 4; i++) {
            long delta = now[i] - lastWheelTicks[i];
            if (delta <= 0L || delta > 2_000_000L) continue;
            double umPerTick = wheelUm != null && wheelUm.length > i && wheelUm[i] > 0
                    ? wheelUm[i] : DEFAULT_WHEEL_UM_PER_TICK;
            sumM += delta * umPerTick / 1_000_000.0;
            count++;
        }
        return count == 0 ? 0.0 : sumM / count;
    }

    private static double clampCalibration(double value) {
        if (!Double.isFinite(value)) return 1.0;
        return Math.max(0.7, Math.min(1.3, value));
    }

    private static boolean valid(Float value) {
        return value != null && Float.isFinite(value);
    }

    private static long[] cloneTicks(long[] ticks) {
        return ticks == null ? null : ticks.clone();
    }
}
