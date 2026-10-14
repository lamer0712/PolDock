package com.oemtools.poldock;

import android.content.SharedPreferences;

/** P.Log 방식의 주행 거리/배터리 전력 적분 전비 계산기. */
final class EfficiencyTracker {
    private static final String KEY_PREFIX = "efficiency_";
    private static final int CALIBRATION_VERSION = 2;
    private static final String KEY_CALIBRATION_VERSION = "efficiency_calibration_version";
    private static final String KEY_CALIBRATION = "efficiency_calibration_factor";
    private static final String KEY_CALIBRATION_DELTA = "efficiency_calibration_delta_kwh";
    private static final String KEY_CALIBRATION_INTEGRAL = "efficiency_calibration_integral_kwh";
    private static final String KEY_ACTIVE = "efficiency_state_active";
    private static final String KEY_SPEED_DISTANCE = "efficiency_state_speed_distance";
    private static final String KEY_WHEEL_DISTANCE = "efficiency_state_wheel_distance";
    private static final String KEY_NET_INTEGRAL = "efficiency_state_net_integral";
    private static final String KEY_START_BATTERY = "efficiency_state_start_battery";
    private static final String KEY_LATEST_BATTERY = "efficiency_state_latest_battery";
    private static final String KEY_LAST_EFFICIENCY = "efficiency_state_last_efficiency";
    private static final String KEY_LAST_ELAPSED = "efficiency_state_last_elapsed";
    private static final double DEFAULT_WHEEL_UM_PER_TICK = 24000.0;
    private static final double MIN_CALIBRATION_ENERGY_KWH = 5.0;
    private static final double MAX_CALIBRATION_HISTORY_KWH = 30.0;
    private static final long PERSIST_INTERVAL_MS = 10_000L;

    private final SharedPreferences preferences;
    private boolean active;
    private long lastSampleMs;
    private long stoppedSinceMs;
    private long[] lastWheelTicks;
    private Float lastSpeedMps;
    private Float lastPowerMilliwatts;
    private long lastPowerTimestampNanos;
    private double speedDistanceM;
    private double wheelDistanceM;
    private double netIntegralKwh;
    private Float startBatteryWh;
    private Float latestBatteryWh;
    private Float lastEfficiency;
    private double calibrationFactor;
    private double calibrationDeltaKwh;
    private double calibrationIntegralKwh;
    private long lastPersistMs;

    EfficiencyTracker(SharedPreferences preferences) {
        this.preferences = preferences;
        boolean currentCalibration = preferences.getInt(KEY_CALIBRATION_VERSION, 0)
                == CALIBRATION_VERSION;
        calibrationFactor = currentCalibration
                ? clampCalibration(preferences.getFloat(KEY_CALIBRATION, 1.0f)) : 1.0;
        calibrationDeltaKwh = currentCalibration ? readDouble(KEY_CALIBRATION_DELTA) : 0.0;
        calibrationIntegralKwh = currentCalibration ? readDouble(KEY_CALIBRATION_INTEGRAL) : 0.0;
        active = preferences.getBoolean(KEY_ACTIVE, false);
        speedDistanceM = readDouble(KEY_SPEED_DISTANCE);
        wheelDistanceM = readDouble(KEY_WHEEL_DISTANCE);
        netIntegralKwh = readDouble(KEY_NET_INTEGRAL);
        startBatteryWh = readFloat(KEY_START_BATTERY);
        latestBatteryWh = readFloat(KEY_LATEST_BATTERY);
        lastEfficiency = readFloat(KEY_LAST_EFFICIENCY);
        long savedElapsed = preferences.getLong(KEY_LAST_ELAPSED, 0L);
        if (active && savedElapsed > android.os.SystemClock.elapsedRealtime() + 60_000L) {
            resetTripState();
        }
    }

    static boolean isInternalPreference(String key) {
        return key != null && key.startsWith(KEY_PREFIX);
    }

    synchronized void sample(long nowMs, String gear, Float speedMps, Float powerMilliwatts,
                long powerTimestampNanos, Float batteryWh, long[] wheelTicks, int[] wheelUm) {
        boolean driveSelected = "D".equals(gear) || "R".equals(gear);
        float speed = valid(speedMps) ? Math.abs(speedMps) : 0f;

        if (!active && (driveSelected || (gear == null && speed >= 0.55f))) {
            start(nowMs, speed, powerMilliwatts, powerTimestampNanos, batteryWh, wheelTicks);
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
            double averageSpeed = valid(lastSpeedMps)
                    ? (Math.abs(lastSpeedMps) + speed) / 2.0 : speed;
            speedDistanceM += averageSpeed * dtSeconds;
            wheelDistanceM += wheelDeltaMeters(wheelTicks, wheelUm);
            if (valid(powerMilliwatts) && valid(lastPowerMilliwatts)) {
                double powerDt = powerIntervalSeconds(
                        elapsedMs, lastPowerTimestampNanos, powerTimestampNanos);
                if (powerDt > 0.0) {
                    netIntegralKwh += integratedKwh(
                            lastPowerMilliwatts, powerMilliwatts, powerDt);
                }
            }
        }

        lastSampleMs = nowMs;
        lastWheelTicks = cloneTicks(wheelTicks);
        lastSpeedMps = valid(speedMps) ? speedMps : null;
        lastPowerMilliwatts = valid(powerMilliwatts) ? powerMilliwatts : null;
        lastPowerTimestampNanos = powerTimestampNanos;
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

    private void start(long nowMs, float speed, Float powerMilliwatts,
                       long powerTimestampNanos, Float batteryWh, long[] wheelTicks) {
        active = true;
        lastSampleMs = nowMs;
        stoppedSinceMs = 0L;
        lastWheelTicks = cloneTicks(wheelTicks);
        lastSpeedMps = speed;
        lastPowerMilliwatts = valid(powerMilliwatts) ? powerMilliwatts : null;
        lastPowerTimestampNanos = powerTimestampNanos;
        speedDistanceM = 0.0;
        wheelDistanceM = 0.0;
        netIntegralKwh = 0.0;
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
            calibrationDeltaKwh += deltaKwh;
            calibrationIntegralKwh += integralKwh;
            if (calibrationIntegralKwh > MAX_CALIBRATION_HISTORY_KWH) {
                double scale = MAX_CALIBRATION_HISTORY_KWH / calibrationIntegralKwh;
                calibrationDeltaKwh *= scale;
                calibrationIntegralKwh *= scale;
            }
            calibrationFactor = calibrationFromTotals(
                    calibrationDeltaKwh, calibrationIntegralKwh, calibrationFactor);
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
                .putInt(KEY_CALIBRATION_VERSION, CALIBRATION_VERSION)
                .putFloat(KEY_CALIBRATION, (float) calibrationFactor)
                .putLong(KEY_CALIBRATION_DELTA,
                        Double.doubleToRawLongBits(calibrationDeltaKwh))
                .putLong(KEY_CALIBRATION_INTEGRAL,
                        Double.doubleToRawLongBits(calibrationIntegralKwh))
                .putLong(KEY_SPEED_DISTANCE, Double.doubleToRawLongBits(speedDistanceM))
                .putLong(KEY_WHEEL_DISTANCE, Double.doubleToRawLongBits(wheelDistanceM))
                .putLong(KEY_NET_INTEGRAL, Double.doubleToRawLongBits(netIntegralKwh))
                .putLong(KEY_LAST_ELAPSED, android.os.SystemClock.elapsedRealtime());
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
        return integralKwh > 0.0 ? integralKwh * calibrationFactor : 0.0;
    }

    private Double batteryDeltaKwh() {
        if (startBatteryWh == null || latestBatteryWh == null
                || startBatteryWh <= latestBatteryWh) return null;
        return (startBatteryWh - latestBatteryWh) / 1000.0;
    }

    private double preferredDistanceM() {
        return wheelDistanceM >= 50.0 ? wheelDistanceM : speedDistanceM;
    }

    static double wheelDeltaMeters(long[] now, long[] previous, int[] wheelUm) {
        if (now == null || previous == null || now.length < 5 || previous.length < 5
                || now[0] != previous[0]) return 0.0;
        double sumM = 0.0;
        int count = 0;
        for (int i = 1; i <= 4; i++) {
            long signedDelta = now[i] - previous[i];
            if (signedDelta == 0L || signedDelta == Long.MIN_VALUE) continue;
            long delta = Math.abs(signedDelta);
            if (delta > 2_000_000L) continue;
            double umPerTick = wheelUm != null && wheelUm.length > i && wheelUm[i] > 0
                    ? wheelUm[i] : DEFAULT_WHEEL_UM_PER_TICK;
            sumM += delta * umPerTick / 1_000_000.0;
            count++;
        }
        return count == 0 ? 0.0 : sumM / count;
    }

    private double wheelDeltaMeters(long[] now, int[] wheelUm) {
        return wheelDeltaMeters(now, lastWheelTicks, wheelUm);
    }

    static double integratedKwh(double previousMilliwatts, double currentMilliwatts,
                                double seconds) {
        return ((previousMilliwatts + currentMilliwatts) / 2.0)
                / 1_000_000.0 * seconds / 3600.0;
    }

    static double powerIntervalSeconds(long elapsedMs, long previousTimestampNanos,
                                       long currentTimestampNanos) {
        double seconds;
        if (previousTimestampNanos > 0L && currentTimestampNanos > 0L) {
            if (currentTimestampNanos <= previousTimestampNanos) return 0.0;
            seconds = (currentTimestampNanos - previousTimestampNanos) / 1_000_000_000.0;
        } else {
            seconds = elapsedMs / 1000.0;
        }
        return seconds >= 0.05 && seconds <= 10.0 ? seconds : 0.0;
    }

    static double calibrationFromTotals(double deltaKwh, double integralKwh,
                                        double current) {
        if (!Double.isFinite(deltaKwh) || !Double.isFinite(integralKwh)
                || integralKwh < MIN_CALIBRATION_ENERGY_KWH || deltaKwh <= 0.0) {
            return clampCalibration(current);
        }
        return clampCalibration(deltaKwh / integralKwh);
    }

    private void resetTripState() {
        active = false;
        speedDistanceM = 0.0;
        wheelDistanceM = 0.0;
        netIntegralKwh = 0.0;
        startBatteryWh = null;
        latestBatteryWh = null;
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
