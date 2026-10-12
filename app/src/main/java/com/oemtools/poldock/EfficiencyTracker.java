package com.oemtools.poldock;

import android.content.SharedPreferences;

/** P.Log 방식의 주행 거리/배터리 전력 적분 전비 계산기. */
final class EfficiencyTracker {
    private static final String KEY_CALIBRATION = "efficiency_calibration_factor";
    private static final double DEFAULT_WHEEL_UM_PER_TICK = 24000.0;

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

    EfficiencyTracker(SharedPreferences preferences) {
        this.preferences = preferences;
        calibrationFactor = clampCalibration(preferences.getFloat(KEY_CALIBRATION, 1.2f));
    }

    void sample(long nowMs, String gear, Float speedMps, Float powerMilliwatts,
                Float batteryWh, long[] wheelTicks, int[] wheelUm) {
        boolean driveSelected = "D".equals(gear) || "R".equals(gear);
        float speed = valid(speedMps) ? Math.abs(speedMps) : 0f;

        if (!active && (driveSelected || (gear == null && speed >= 0.55f))) {
            start(nowMs, speed, batteryWh, wheelTicks);
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
    }

    Float efficiencyKmPerKwh() {
        if (!active) return lastEfficiency;
        double distanceKm = preferredDistanceM() / 1000.0;
        double energyKwh = currentEnergyKwh();
        if (distanceKm < 0.3 || energyKwh <= 0.1) return null;
        double value = distanceKm / energyKwh;
        return Double.isFinite(value) && value > 0.0 ? (float) value : null;
    }

    /** 거리/배터리 잔량 기반 예상 전비에도 P.Log과 같은 보정 방향을 적용한다. */
    Float calibratedEfficiency(Float rawEfficiency) {
        if (!valid(rawEfficiency) || rawEfficiency <= 0f) return null;
        double value = rawEfficiency / calibrationFactor;
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
