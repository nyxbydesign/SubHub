package com.subhub.app.service;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CaptureTimeReferenceTest {
    @Test public void accessibilityEndpointsAreNeverExactPixelTime() {
        for (boolean window : new boolean[]{true, false}) {
            CaptureTimeReference time = CaptureTimeReference.accessibility(window, 100, 150, 160);
            assertTrue(time.valid);
            assertFalse(time.pixelTimeKnown());
            assertEquals(100, time.requestUptimeMillis);
            assertEquals(150, time.reportedUptimeMillis);
        }
    }

    @Test public void exactTimeRequiresExplicitEvidenceAndOrderedClocks() {
        assertTrue(CaptureTimeReference.verifiedPixelTime(100, 120, 150).pixelTimeKnown());
        assertFalse(CaptureTimeReference.verifiedPixelTime(100, 90, 150).pixelTimeKnown());
        assertFalse(CaptureTimeReference.verifiedPixelTime(100, 120, 110).pixelTimeKnown());
        assertFalse(CaptureTimeReference.verifiedPixelTime(-1, 120, 150).pixelTimeKnown());
    }
}
