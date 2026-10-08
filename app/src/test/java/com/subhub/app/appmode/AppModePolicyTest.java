package com.subhub.app.appmode;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Set;

public final class AppModePolicyTest {
    @Test public void sharedAllAppsLimitScopeIncludesUnassignedAppsButNeverEscapeSurfaces() {
        assertTrue(AppModePolicy.shouldLimit(true, AppModePolicy.Mode.ALWAYS, Set.of(),
                "com.example.browser", "com.subhub.app", "com.example.ime", "com.example.launcher"));
        for (String excluded : new String[]{"com.subhub.app", "com.example.ime", "com.example.launcher",
                "com.android.settings", "com.android.permissioncontroller", "com.android.systemui", "android", ""}) {
            assertFalse(excluded, AppModePolicy.shouldLimit(true, AppModePolicy.Mode.ALWAYS, Set.of(),
                    excluded, "com.subhub.app", "com.example.ime", "com.example.launcher"));
        }
        assertFalse(AppModePolicy.shouldLimit(false, AppModePolicy.Mode.ALWAYS, Set.of(),
                "com.example.browser", "com.subhub.app", "com.example.ime", "com.example.launcher"));
        assertFalse(AppModePolicy.shouldLimit(true, AppModePolicy.Mode.SELECTED_APPS, Set.of("com.example.other"),
                "com.example.browser", "com.subhub.app", "com.example.ime", "com.example.launcher"));
    }
    @Test public void selectedModeWakesOnlyForAnExactSelectedPackage() {
        Set<String> selected = Set.of("com.example.watched");
        assertTrue(AppModePolicy.shouldRecognize(true, AppModePolicy.Mode.SELECTED_APPS,
                selected, "com.example.watched", "com.subhub.app", "com.example.ime"));
        assertFalse(AppModePolicy.shouldRecognize(true, AppModePolicy.Mode.SELECTED_APPS,
                selected, "com.example.other", "com.subhub.app", "com.example.ime"));
        assertFalse(AppModePolicy.shouldRecognize(false, AppModePolicy.Mode.SELECTED_APPS,
                selected, "com.example.watched", "com.subhub.app", "com.example.ime"));
    }

    @Test public void alwaysModeSleepsOnSubHubAndSystemSurfaces() {
        assertTrue(AppModePolicy.shouldRecognize(true, AppModePolicy.Mode.ALWAYS, Set.of(),
                "com.example.browser", "com.subhub.app", "com.example.ime"));
        assertFalse(AppModePolicy.shouldRecognize(true, AppModePolicy.Mode.ALWAYS, Set.of(),
                "com.subhub.app", "com.subhub.app", "com.example.ime"));
        assertFalse(AppModePolicy.shouldRecognize(true, AppModePolicy.Mode.ALWAYS, Set.of(),
                "com.android.systemui", "com.subhub.app", "com.example.ime"));
        assertFalse(AppModePolicy.shouldRecognize(true, AppModePolicy.Mode.ALWAYS, Set.of(),
                "", "com.subhub.app", "com.example.ime"));
    }

    @Test public void keyboardWindowEventsDoNotReplaceTheForegroundApp() {
        assertFalse(AppModePolicy.shouldAcceptForegroundEvent(
                "com.example.ime", "com.example.ime.Keyboard", "com.subhub.app",
                "com.example.ime"));
        assertTrue(AppModePolicy.shouldAcceptForegroundEvent(
                "com.example.watched", "com.example.watched.MainActivity", "com.subhub.app",
                "com.example.ime"));
    }

    @Test public void notificationsAndOtherSystemChromeDoNotReplaceTheForegroundApp() {
        assertFalse(AppModePolicy.shouldAcceptForegroundEvent(
                "com.android.systemui", "android.widget.FrameLayout", "com.subhub.app",
                "com.example.ime"));
        assertFalse(AppModePolicy.shouldAcceptForegroundEvent(
                "android", "android.app.Dialog", "com.subhub.app", "com.example.ime"));
        assertFalse(AppModePolicy.shouldAcceptForegroundEvent(
                "com.android.permissioncontroller", "com.android.permissioncontroller.PermissionActivity",
                "com.subhub.app", "com.example.ime"));
        assertFalse(AppModePolicy.shouldAcceptForegroundEvent(
                "com.google.android.permissioncontroller", "android.app.Activity",
                "com.subhub.app", "com.example.ime"));
    }

    @Test public void ownOverlayEventsDoNotMasqueradeAsAppSwitches() {
        assertFalse(AppModePolicy.shouldAcceptForegroundEvent(
                "com.subhub.app", "android.widget.FrameLayout", "com.subhub.app",
                "com.example.ime"));
        assertTrue(AppModePolicy.shouldAcceptForegroundEvent(
                "com.subhub.app", "com.subhub.app.MainActivity", "com.subhub.app",
                "com.example.ime"));
        assertFalse(AppModePolicy.shouldAcceptForegroundEvent(
                "com.subhub.app", "com.subhub.app.popup.PopupOverlayView",
                "com.subhub.app", "com.example.ime"));
    }

    @Test public void liveAccessibilityRootConfirmsRealAppSwitchesButNotSystemChrome() {
        assertTrue(AppModePolicy.shouldAcceptLiveForegroundPackage(
                "com.openai.chatgpt", "com.example.ime"));
        assertTrue(AppModePolicy.shouldAcceptLiveForegroundPackage(
                "com.subhub.app", "com.example.ime"));
        assertFalse(AppModePolicy.shouldAcceptLiveForegroundPackage(
                "com.android.systemui", "com.example.ime"));
        assertFalse(AppModePolicy.shouldAcceptLiveForegroundPackage(
                "com.example.ime", "com.example.ime"));
    }

    @Test public void malformedPackageNamesAreNotPersisted() {
        Set<String> clean = AppModePolicy.sanitizePackages(Set.of(
                "com.example.ok", "not a package", "", "../escape"));
        assertTrue(clean.contains("com.example.ok"));
        assertFalse(clean.contains("not a package"));
        assertFalse(clean.contains("../escape"));
    }
}
