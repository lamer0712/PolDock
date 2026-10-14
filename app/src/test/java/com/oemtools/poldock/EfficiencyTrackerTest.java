package com.oemtools.poldock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EfficiencyTrackerTest {
    private static final double EPSILON = 0.000001;

    @Test
    public void wheelDistanceUsesConfiguredScaleInBothDirections() {
        int[] config = {15, 20_000, 20_000, 20_000, 20_000};
        long[] start = {3, 100, 100, 100, 100};

        assertEquals(2.0, EfficiencyTracker.wheelDeltaMeters(
                new long[]{3, 200, 200, 200, 200}, start, config), EPSILON);
        assertEquals(2.0, EfficiencyTracker.wheelDeltaMeters(
                new long[]{3, 0, 0, 0, 0}, start, config), EPSILON);
    }

    @Test
    public void wheelDistanceRejectsSamplesAcrossReset() {
        assertEquals(0.0, EfficiencyTracker.wheelDeltaMeters(
                new long[]{4, 200, 200, 200, 200},
                new long[]{3, 100, 100, 100, 100}, null), EPSILON);
    }

    @Test
    public void powerIntegrationUsesTrapezoid() {
        assertEquals(-0.015, EfficiencyTracker.integratedKwh(
                -10_000_000.0, -20_000_000.0, 3.6), EPSILON);
    }

    @Test
    public void repeatedVehicleTimestampIsNotIntegratedTwice() {
        assertEquals(0.0, EfficiencyTracker.powerIntervalSeconds(
                1000L, 5_000_000_000L, 5_000_000_000L), EPSILON);
        assertEquals(1.25, EfficiencyTracker.powerIntervalSeconds(
                1000L, 5_000_000_000L, 6_250_000_000L), EPSILON);
    }

    @Test
    public void calibrationWaitsForEnoughAccumulatedEnergy() {
        assertEquals(1.0, EfficiencyTracker.calibrationFromTotals(3.9, 3.0, 1.0), EPSILON);
        assertEquals(1.2, EfficiencyTracker.calibrationFromTotals(6.0, 5.0, 1.0), EPSILON);
        assertEquals(1.3, EfficiencyTracker.calibrationFromTotals(9.0, 5.0, 1.0), EPSILON);
    }

    @Test
    public void efficiencyStateDoesNotTriggerDockRebuild() {
        assertTrue(EfficiencyTracker.isInternalPreference("efficiency_state_active"));
        assertTrue(EfficiencyTracker.isInternalPreference("efficiency_calibration_factor"));
        assertFalse(EfficiencyTracker.isInternalPreference("dock_orientation"));
    }
}
