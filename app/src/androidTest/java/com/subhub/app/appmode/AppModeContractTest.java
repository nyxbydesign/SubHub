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
    public void formerAllScopeBecomesCurrentLauncherPackagesAndThenExplicitExclusionsPersist() {
        preferences
                .edit()
                .remove(AppModeManager.KEY_INCLUDED_PACKAGES)
                .putString("app_mode_kind", "always")
                .putBoolean("app_mode_kind_explicit_v2", true)
                .putBoolean(AppModeManager.KEY_ARMED, false)
                .commit();
        AppModeManager mode = new AppModeManager(context);
        android.content.Intent launcher = new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER);
        for (android.content.pm.ResolveInfo info :
                context.getPackageManager()
                        .queryIntentActivities(launcher, android.content.pm.PackageManager.MATCH_ALL))
            if (info.activityInfo != null
                    && !context.getPackageName().equals(info.activityInfo.packageName))
                assertTrue(mode.getIncludedPackages().contains(info.activityInfo.packageName));
        mode.saveIncludedPackages(Set.of("chosen.app"));
                        assertEquals(Set.of("chosen.app"), new AppModeManager(context).getIncludedPackages());
        assertFalse(mode.isArmed());
                        }
                    }
