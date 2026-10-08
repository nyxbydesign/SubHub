package com.subhub.app.service;
import org.junit.Test;
import static org.junit.Assert.*;
public final class ChromeGeometryProbeTest {
    @Test public void recordsOnlyNativeChromeResourceNames() {
        assertTrue(ChromeGeometryProbe.nativeId("com.android.chrome:id/url_bar"));
        assertFalse(ChromeGeometryProbe.nativeId("page:user-private-id"));
        assertFalse(ChromeGeometryProbe.nativeId("com.other.app:id/toolbar"));
        assertFalse(ChromeGeometryProbe.nativeId("com.android.chrome:id/url bar"));
        assertFalse(ChromeGeometryProbe.nativeId(null));
    }
    @Test public void watchListFocusesOnNativeControlsAndContainers() {
        assertTrue(ChromeGeometryProbe.relevantId("com.android.chrome:id/toolbar"));
        assertTrue(ChromeGeometryProbe.relevantId("com.android.chrome:id/compositor_view_holder"));
        assertFalse(ChromeGeometryProbe.relevantId("com.android.chrome:id/menu_button"));
    }
}
