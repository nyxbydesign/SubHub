package com.subhub.app.service;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ForegroundUsageWindowResolverTest {
    private static final String APP = "com.example.selected";
    private static final String OTHER = "com.example.other";
    private static final String KEYBOARD = "com.example.keyboard";

    @Test public void aRetainedUnfocusedAppCannotOutrankTheActuallyFocusedApp() {
        assertEquals(OTHER, select(app(APP, false, true, 90), app(OTHER, true, true, 1)));
    }

    @Test public void aNotificationPackageEventIsNotAnInputToUsageIdentity() {
        // Only live windows enter this resolver; a notification event's package is no evidence.
        assertEquals(OTHER, select(app(OTHER, true, true, 1), app(APP, false, true, 99)));
    }

    @Test public void aFocusedNotificationShadeSuspendsTheAppUnderneath() {
        assertEquals("", select(app(APP, true, true, 1),
                new ForegroundUsageWindowResolver.Window("com.android.systemui", false, false, true, true, 5)));
    }

    @Test public void aNonfocusedOverlayIsNeverMistakenForItsOwningApp() {
        assertEquals(OTHER, select(app(OTHER, true, true, 1),
                new ForegroundUsageWindowResolver.Window(APP, false, false, false, true, 9)));
    }

    @Test public void aFocusedNonApplicationOverlaySuspendsAccounting() {
        assertEquals("", select(app(OTHER, true, true, 1),
                new ForegroundUsageWindowResolver.Window(APP, false, false, true, true, 9)));
    }

    @Test public void aKeyboardPreservesTheFocusedOwningActivityButCannotBecomeUsage() {
        ForegroundUsageWindowResolver.Window keyboard = new ForegroundUsageWindowResolver.Window(
                KEYBOARD, false, true, true, true, 5);
        assertEquals(APP, select(app(APP, true, true, 1), keyboard));
        assertEquals("", select(keyboard));
        assertEquals("", select(app(KEYBOARD, true, true, 1)));
    }

    @Test public void anInvisibleRootOrUnknownFocusDoesNotFallBackToTheLastApp() {
        assertEquals("", select(app(APP, true, false, 1)));
        assertEquals("", select(app(APP, false, true, 1)));
        assertEquals("", ForegroundUsageWindowResolver.select(null, KEYBOARD, true, false));
        assertEquals("", ForegroundUsageWindowResolver.select(Collections.emptyList(), KEYBOARD, true, false));
        assertEquals("", select(app("", true, true, 1)));
    }

    @Test public void screenOffAndLockExcludeEvenAStillFocusedVisibleApplication() {
        assertEquals("", ForegroundUsageWindowResolver.select(
                Collections.singletonList(app(APP, true, true, 1)), KEYBOARD, false, false));
        assertEquals("", ForegroundUsageWindowResolver.select(
                Collections.singletonList(app(APP, true, true, 1)), KEYBOARD, true, true));
    }

    @Test public void systemPermissionSurfacesCannotAccrueUsageEvenWhenReportedAsApplications() {
        assertEquals("", select(app("com.google.android.permissioncontroller", true, true, 1)));
        assertEquals("", select(app("com.android.systemui", true, true, 1)));
    }

    @Test public void threeSecondFocusedApplicationOverlayDoesNotSpendItsAllowance() {
        ForegroundUsageSession session = new ForegroundUsageSession();
        for (long time = 0; time <= 3_000; time += 1_000) {
            String observed = select(app(APP, true, true, 5), app(OTHER, false, true, 1));
            assertEquals(0L, session.observe(observed, time));
        }
        assertEquals(0L, session.observe(select(app(OTHER, true, true, 1)), 3_001));
        assertFalse(session.qualified(APP));
    }

    private static String select(ForegroundUsageWindowResolver.Window... windows) {
        return ForegroundUsageWindowResolver.select(Arrays.asList(windows), KEYBOARD, true, false);
    }

    private static ForegroundUsageWindowResolver.Window app(String name, boolean focused,
            boolean visible, int layer) {
        return new ForegroundUsageWindowResolver.Window(name, true, false, focused, visible, layer);
    }
}
