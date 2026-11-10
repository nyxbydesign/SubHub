package com.subhub.app.appmode;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.popup.PopupStormSettings;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.*;

import org.junit.*;

import java.util.*;

/** Shared inclusion is a runtime contract, not a cosmetic picker consolidation. */
public class AppModeContractTest {
    private Context context;
    private SharedPreferences preferences;
    private Map<String, ?> before;

    @Before
    public void fixture() {
        assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences = new SettingsRepository(context)
                .preferences();
        before = preferences.getAll();
        ControllerPinManager.enterDomMode();
        new FeatureModuleManager(context).save(true, true, true, true);
        preferences
                .edit()
                .putString(
                        SettingsRepository.KEY_CAPTURE_METHOD,
                        CaptureMethod.APP_MODE.preferenceValue())
                .putBoolean(PopupStormSettings.K_ENABLED, true)
                .commit();
    }

    @After
    public void restore() {
        if (preferences == null) return;
        SharedPreferences.Editor e = preferences.edit().clear();
        for (Map.Entry<String, ?> item : before.entrySet()) {
            String k = item.getKey();
            Object v = item.getValue();
            if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
            else if (v instanceof Integer) e.putInt(k, (Integer) v);
            else if (v instanceof Long) e.putLong(k, (Long) v);
            else if (v instanceof Float) e.putFloat(k, (Float) v);
            else if (v instanceof String) e.putString(k, (String) v);
            else if (v instanceof Set) e.putStringSet(k, new LinkedHashSet<>((Set<String>) v));
        }
        assertTrue(e.commit());
        ControllerPinManager.enterSubMode();
    }

    @Test
    public void selectedLegacyUnionKeepsAllowancesAndRemovesRetiredKeys() {
        AppTimerManager timers = new AppTimerManager(context);
        timers.saveSettings(true, 30, true, 120);
        timers.saveAllowances(
                Set.of("first.app", "second.app"), Map.of("first.app", 7, "second.app", 17));
        preferences
                .edit()
                .remove(AppModeManager.KEY_INCLUDED_PACKAGES)
                .putBoolean(AppModeManager.KEY_ARMED, true)
                .putString("app_mode_kind", "selected")
                .putBoolean("app_mode_kind_explicit_v2", true)
                .putStringSet("app_mode_selected_packages", Set.of("first.app"))
                .putStringSet("app_timer_selected_packages", Set.of("second.app"))
                .putStringSet("subliminal_selected_packages", Set.of("third.app"))
                .commit();
        AppModeManager mode = new AppModeManager(context);
        assertEquals(Set.of("first.app", "second.app", "third.app"), mode.getIncludedPackages());
        assertTrue(mode.isArmed());
        assertEquals(7, timers.allowanceMinutes("first.app"));
        assertEquals(17, timers.allowanceMinutes("second.app"));
        for (String key :
                List.of(
                        "app_mode_kind",
                        "app_mode_kind_explicit_v2",
                        "app_mode_selected_packages",
                        "app_timer_selected_packages",
                        "subliminal_selected_packages")) assertFalse(preferences.contains(key));
        for (String app : mode.getIncludedPackages()) {
            assertTrue(mode.shouldRecognize(app));
            assertTrue(mode.shouldShowSubliminal(app));
            assertTrue(mode.shouldShowPopups(app));
            assertEquals(Set.of(app), mode.timerScopeForForeground(app));
        }
        mode.saveIncludedPackages(Set.of());
        assertTrue(mode.isArmed());
        assertTrue(new AppModeManager(context).getIncludedPackages().isEmpty());
        assertEquals(17, timers.allowanceMinutes("second.app"));
    }

    @Test
    public void allFeaturesExcludeTheSameAppAndSelectionNeverArmsService() {
        AppModeManager mode = new AppModeManager(context);
        mode.save(false, Set.of("chosen.app"));
        assertFalse(mode.isArmed());
        assertFalse(mode.shouldRecognize("chosen.app"));
        mode.setArmed(true);
        for (String excluded :
                List.of("excluded.app", context.getPackageName(), "com.android.systemui")) {
            assertFalse(mode.shouldRecognize(excluded));
            assertFalse(mode.shouldShowSubliminal(excluded));
            assertFalse(mode.shouldShowPopups(excluded));
            assertTrue(mode.timerScopeForForeground(excluded).isEmpty());
        }
        preferences
                .edit()
                .putString(
                        SettingsRepository.KEY_CAPTURE_METHOD,
                        CaptureMethod.SCREEN_RECORDING.preferenceValue())
                .commit();
        assertFalse(mode.shouldRecognize("chosen.app"));
        assertTrue(mode.shouldCensorForeground("chosen.app"));
        assertFalse(mode.shouldCensorForeground("excluded.app"));
        new FeatureModuleManager(context).save(false, true, true, false);
        assertFalse(mode.shouldCensorForeground("chosen.app"));
        assertFalse(mode.shouldShowSubliminal("chosen.app"));
        assertFalse(mode.timerScopeForForeground("chosen.app").isEmpty());
    }

    @Test
    public void formerAllScopePreservesSelectionBeneathTheRefreshedOverride() {
        for (boolean hasModernSnapshot : new boolean[]{false, true}) {
            String name = "all-migration-" + UUID.randomUUID();
            android.content.ContextWrapper isolated = new android.content.ContextWrapper(context) {
                @Override public Context getApplicationContext() { return this; }
                @Override public SharedPreferences getSharedPreferences(String requested, int flags) {
                    assertEquals(SettingsRepository.PREFERENCES_NAME, requested);
                    return context.getSharedPreferences(name, flags);
                }
            };
            SharedPreferences saved = isolated.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
            try {
                SharedPreferences.Editor legacy = saved.edit()
                        .putString("app_mode_kind", "always").putBoolean("app_mode_kind_explicit_v2", true)
                        .putStringSet("app_mode_selected_packages", Set.of("retained.app"))
                        .putBoolean(AppModeManager.KEY_ARMED, false);
                if (hasModernSnapshot) legacy.putBoolean(AppModeManager.KEY_ALL_APPS, false)
                        .putStringSet("app_include_all_packages_v1", Set.of("old.snapshot"));
                assertTrue(legacy.commit());
                AppModeManager mode = new AppModeManager(isolated);
                assertTrue(mode.isAllApps());
                assertEquals(Set.of("retained.app"), mode.getSelectedPackages());
                assertEquals(hasModernSnapshot ? Set.of("old.snapshot") : Set.of("retained.app"), mode.getIncludedPackages());
                Set<String> discovered = Set.of("installed.one", "installed.two");
                long revision = mode.scopeRevision();
                assertTrue(mode.refreshAllApps(revision, discovered));
                assertEquals(discovered, mode.getIncludedPackages());
                mode.saveIncludedPackages(Set.of("chosen.app"));
                assertTrue(mode.isAllApps());
                assertEquals(Set.of("chosen.app"), new AppModeManager(isolated).getSelectedPackages());
                assertEquals(discovered, new AppModeManager(isolated).getIncludedPackages());
                mode.setAllAppsEnabled(false);
                assertFalse(mode.refreshAllApps(revision, Set.of("stale.app")));
                assertEquals(Set.of("chosen.app"), mode.getIncludedPackages());
                assertFalse(mode.isArmed());
                assertFalse(saved.contains("app_mode_kind"));
                assertFalse(saved.contains("app_mode_selected_packages"));
            } finally { assertTrue(saved.edit().clear().commit()); }
        }
    }
}
