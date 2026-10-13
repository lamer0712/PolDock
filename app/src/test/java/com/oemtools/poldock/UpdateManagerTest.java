package com.oemtools.poldock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdateManagerTest {

    @Test
    public void comparesReleaseTagsAndVersionNames() {
        assertTrue(UpdateManager.compareVersions("v1.2.0", "1.1.9") > 0);
        assertTrue(UpdateManager.compareVersions("release-2.0", "1.99") > 0);
        assertEquals(0, UpdateManager.compareVersions("v1.2", "1.2.0"));
        assertTrue(UpdateManager.compareVersions("1.2.3", "1.2.10") < 0);
        assertTrue(UpdateManager.compareVersions("v1.3.1", "1.3") > 0);
        assertTrue(UpdateManager.compareVersions("v1.3.2", "1.3.1") > 0);
        assertTrue(UpdateManager.compareVersions("v1.3.3", "1.3.2") > 0);
        assertTrue(UpdateManager.compareVersions("v1.3.4", "1.3.3") > 0);
    }
}
