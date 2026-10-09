package com.subhub.app.appmode;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Set;

public class AppModePolicyTest {
    @Test public void includedAppsRunOnlyWhileArmed() {
        Set<String> apps = Set.of("chosen.app");
        assertTrue(AppModePolicy.shouldRecognize(true, apps, "chosen.app", "own.app", "keyboard.app"));
        assertFalse(
                AppModePolicy.shouldRecognize(
                        false, apps, "chosen.app", "own.app", "keyboard.app"));
        assertFalse(AppModePolicy.shouldRecognize(true, apps, "excluded.app", "own.app", "keyboard.app"));
        assertFalse(AppModePolicy.shouldRecognize(true, Set.of(), "chosen.app", "own.app", "keyboard.app"));
    }

    @Test public void ownKeyboardAndTransientSurfacesNeverParticipateEvenWhenIncluded() {
        for (String app :
                Set.of(
                        "own.app",
                        "keyboard.app",
                        "android",
                        "com.android.systemui",
                        "com.android.permissioncontroller",
                        "com.google.android.permissioncontroller"))
            assertFalse(
                    app,
                    AppModePolicy.shouldRecognize(
                            true, Set.of(app), app, "own.app", "keyboard.app"));
    }

    @Test public void limitsKeepHomeAndSettingsAsEscapeRoutes() {
        for (String app :
                Set.of(
                        "home.app",
                        "com.android.settings",
                        "com.google.android.settings",
                        "com.android.systemui",
                        "keyboard.app",
                        "own.app"))
            assertFalse(
                    app,
                    AppModePolicy.shouldLimit(
                            true, Set.of(app), app, "own.app", "keyboard.app", "home.app"));
        assertTrue(
                AppModePolicy.shouldLimit(
                        true,
                        Set.of("chosen.app"),
                        "chosen.app",
                        "own.app",
                        "keyboard.app",
                        "home.app"));
        assertFalse(AppModePolicy.shouldLimit(
                        false,
                        Set.of("chosen.app"),
                        "chosen.app",
                        "own.app",
                        "keyboard.app",
                        "home.app"));
    }

    @Test public void sanitizeRejectsMalformedNamesAndReturnsImmutableSet() {
        Set<String> clean = AppModePolicy.sanitizePackages(Set.of(" chosen.app ", "nope", "a/b", "", "other.app"));
        assertEquals(Set.of("chosen.app", "other.app"), clean);
        assertThrows(UnsupportedOperationException.class, () -> clean.add("new.app"));
        assertTrue(AppModePolicy.sanitizePackages(null).isEmpty());
    }

    @Test
    public void transientEventsDoNotReplaceAppEvidence() {
        assertFalse(
                AppModePolicy.shouldAcceptForegroundEvent(
                        "com.android.systemui", "", "own.app", "keyboard.app"));
        assertFalse(
                AppModePolicy.shouldAcceptForegroundEvent(
                        "keyboard.app", "", "own.app", "keyboard.app"));
        assertTrue(
                AppModePolicy.shouldAcceptForegroundEvent(
                        "chosen.app", "", "own.app", "keyboard.app"));
        assertFalse(
                AppModePolicy.shouldAcceptForegroundEvent(
                        "own.app", "own.app.OverlayView", "own.app", "keyboard.app"));
        assertTrue(
                AppModePolicy.shouldAcceptForegroundEvent(
                        "own.app", "own.app.MainActivity", "own.app", "keyboard.app"));
    }

    @Test
    public void liveOwnAppRootEndsTheUnderlyingAppScope() {
        assertTrue(AppModePolicy.shouldAcceptLiveForegroundPackage("own.app", "keyboard.app"));
        assertFalse(
                AppModePolicy.shouldAcceptLiveForegroundPackage(
                        "com.android.systemui", "keyboard.app"));
        assertFalse(
                AppModePolicy.shouldAcceptLiveForegroundPackage("keyboard.app", "keyboard.app"));
    }
}
