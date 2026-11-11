package com.subhub.app.service;

import static org.junit.Assert.*;
import org.junit.Test;

/** Pure capture planning boundaries; Android routing contracts live in instrumentation. */
public final class RuntimeCaptureHostTest {
    @Test public void projectionRetainsEverySupportedLegacyApi() {
        for (int sdk = 26; sdk <= 35; sdk++)
            assertEquals(RuntimeCaptureHost.Kind.PROJECTION,
                    new RuntimeCaptureHost.CapturePlan(RuntimeCaptureHost.Mode.PROJECTION, sdk).kind());
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeCaptureHost.CapturePlan(RuntimeCaptureHost.Mode.PROJECTION, 25));
    }

    @Test public void displayAndModernWindowGuardsAreNotInterchangeable() {
        for (int sdk = 30; sdk <= 35; sdk++)
            assertEquals(RuntimeCaptureHost.Kind.ACCESSIBILITY,
                    new RuntimeCaptureHost.CapturePlan(RuntimeCaptureHost.Mode.ACCESSIBILITY_DISPLAY, sdk).kind());
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeCaptureHost.CapturePlan(RuntimeCaptureHost.Mode.ACCESSIBILITY_DISPLAY, 29));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeCaptureHost.CapturePlan(RuntimeCaptureHost.Mode.ACCESSIBILITY_WINDOW, 33));
        assertEquals(RuntimeCaptureHost.Kind.ACCESSIBILITY,
                new RuntimeCaptureHost.CapturePlan(RuntimeCaptureHost.Mode.ACCESSIBILITY_WINDOW, 34).kind());
    }

    @Test public void modulesOnlyHostDoesNotClaimScreenshotSupportOnAnyApi() {
        for (int sdk = 26; sdk <= 35; sdk++) {
            RuntimeCaptureHost.CapturePlan plan = new RuntimeCaptureHost.CapturePlan(
                    RuntimeCaptureHost.Mode.ACCESSIBILITY_MODULES, sdk);
            assertEquals(RuntimeCaptureHost.Kind.ACCESSIBILITY, plan.kind());
            assertFalse(plan.capturesPixels());
        }
        assertThrows(IllegalArgumentException.class, () -> new RuntimeCaptureHost.CapturePlan(
                RuntimeCaptureHost.Mode.ACCESSIBILITY_MODULES, 25));
        assertTrue(new RuntimeCaptureHost.CapturePlan(RuntimeCaptureHost.Mode.PROJECTION, 26).capturesPixels());
        assertTrue(new RuntimeCaptureHost.CapturePlan(RuntimeCaptureHost.Mode.ACCESSIBILITY_DISPLAY, 30).capturesPixels());
        assertTrue(new RuntimeCaptureHost.CapturePlan(RuntimeCaptureHost.Mode.ACCESSIBILITY_WINDOW, 34).capturesPixels());
    }
}
