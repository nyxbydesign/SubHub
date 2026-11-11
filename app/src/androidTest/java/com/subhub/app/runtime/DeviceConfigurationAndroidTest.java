package com.subhub.app.runtime;

import static org.junit.Assert.*;
import com.subhub.app.settings.CaptureMethod;
import org.junit.Test;

/** Data-only plan tests use the actual Android-facing gateway contract, not android.jar JVM stubs. */
public final class DeviceConfigurationAndroidTest {
    private static AppRuntimeGateway.DeviceStartSpec spec(CaptureMethod capture) {
        return new AppRuntimeGateway.DeviceStartSpec(capture, 0, 0, false);
    }
    @Test public void ordinaryProjectionDoesNotAcquireUnrequestedAccessibilityRequirement() {
        DeviceConfiguration c = new DeviceConfiguration(1, spec(CaptureMethod.SCREEN_RECORDING), 33,
                true, false, false, false, false);
        assertFalse(c.plan.requiresAccessibility); assertTrue(c.plan.requiresOverlay);
        assertTrue(c.plan.requestNotifications);
    }
    @Test public void enabledAccessibilityModulesRequireTheirActualService() {
        for (int module = 0; module < 3; module++) {
            DeviceConfiguration c = new DeviceConfiguration(1, spec(CaptureMethod.SCREEN_RECORDING), 32,
                    true, module == 0, module == 1, module == 2, false);
            assertTrue(c.plan.requiresAccessibility); assertTrue(c.plan.requiresOverlay);
            assertFalse(c.plan.requestNotifications);
        }
    }
    @Test public void appModeUsesAccessibilityWithoutProjectionOverlayOrNotificationPrompts() {
        DeviceConfiguration c = new DeviceConfiguration(2, spec(CaptureMethod.APP_MODE), 26,
                false, true, false, false, false);
        assertTrue(c.plan.requiresAccessibility); assertFalse(c.plan.requiresOverlay);
        assertFalse(c.plan.requestNotifications);
    }
    @Test public void sourceChangeCannotSilentlyReplaceRequestedCapture() {
        assertThrows(IllegalArgumentException.class, () -> new DeviceConfiguration(1,
                spec(CaptureMethod.SCREEN_RECORDING), 33, false, true, false, false, false));
        assertThrows(IllegalArgumentException.class, () -> new DeviceConfiguration(1,
                spec(CaptureMethod.APP_MODE), 33, false, false, false, false, false));
    }
    @Test public void observationEpochDoesNotPretendToChangeLiveSettings() {
        DeviceConfiguration a = new DeviceConfiguration(1, spec(CaptureMethod.APP_MODE), 33,
                true, false, false, false, false);
        DeviceConfiguration b = new DeviceConfiguration(2, spec(CaptureMethod.APP_MODE), 33,
                true, false, false, false, false);
        DeviceConfiguration changed = new DeviceConfiguration(3, spec(CaptureMethod.APP_MODE), 33,
                true, true, false, false, false);
        assertTrue(a.sameStartPlan(b)); assertFalse(a.sameStartPlan(changed));
        assertFalse(a.sameStartPlan(null));
    }
}
