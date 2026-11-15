package com.subhub.app.appmode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.settings.SettingsRepository;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Set;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public final class LegacyAppSelectionAndroidTest {
    @Test
    public void explicitEmptySelectionSurvivesMigrationAndAllAppsOverride() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "legacy-app-selection-" + UUID.randomUUID();
        SharedPreferences saved = target.getSharedPreferences(name, Context.MODE_PRIVATE);
        Context isolated = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }

            @Override public SharedPreferences getSharedPreferences(String requested, int mode) {
                assertEquals(SettingsRepository.PREFERENCES_NAME, requested);
                return saved;
            }
        };

        try {
            assertTrue(saved.edit().putString("app_mode_kind", "selected")
                    .putBoolean("app_mode_kind_explicit_v2", true)
                    .putStringSet("app_mode_selected_packages", Set.of())
                    .putStringSet("app_timer_selected_packages", Set.of())
                    .putStringSet("subliminal_selected_packages", Set.of())
                    .putBoolean(AppModeManager.KEY_ARMED, false).commit());

            assertEquals(Set.of(), AppModeManager.observedIncludedPackages(isolated));
            assertFalse(saved.contains(AppModeManager.KEY_INCLUDED_PACKAGES));
            assertTrue(saved.contains("app_mode_selected_packages"));

            AppModeManager mode = new AppModeManager(isolated);
            assertTrue(saved.contains(AppModeManager.KEY_INCLUDED_PACKAGES));
            assertEquals(Set.of(), saved.getStringSet(AppModeManager.KEY_INCLUDED_PACKAGES, null));
            assertFalse(mode.isAllApps());
            for (String key : Set.of("app_mode_kind", "app_mode_kind_explicit_v2",
                    "app_mode_selected_packages", "app_timer_selected_packages",
                    "subliminal_selected_packages")) {
                assertFalse(saved.contains(key));
            }

            Set<String> override = Set.of("override.app");
            mode.saveAllApps(override);
            assertEquals(Set.of(), new AppModeManager(isolated).getSelectedPackages());
            assertEquals(override, AppModeManager.observedIncludedPackages(isolated));
            assertEquals(override, mode.getIncludedPackages());
            mode.setAllAppsEnabled(false);
            AppModeManager reloaded = new AppModeManager(isolated);
            assertEquals(Set.of(), reloaded.getIncludedPackages());
            assertEquals(Set.of(), AppModeManager.observedIncludedPackages(isolated));
            assertFalse(reloaded.isArmed());
        } finally {
            assertTrue(target.deleteSharedPreferences(name));
        }
    }
}
