package com.oemtools.poldock;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ParkingPhotoManagerTest {
    @Test
    public void capturesOnlyWhenDriveOrReverseChangesToPark() {
        assertTrue(ParkingPhotoManager.isParkingTransition("D", "P"));
        assertTrue(ParkingPhotoManager.isParkingTransition("R", "P"));

        assertFalse(ParkingPhotoManager.isParkingTransition(null, "P"));
        assertFalse(ParkingPhotoManager.isParkingTransition("P", "P"));
        assertFalse(ParkingPhotoManager.isParkingTransition("N", "P"));
        assertFalse(ParkingPhotoManager.isParkingTransition("D", "N"));
        assertFalse(ParkingPhotoManager.isParkingTransition("R", "D"));
    }
}
