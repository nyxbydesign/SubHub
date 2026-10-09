package com.subhub.app.settings;

import static org.junit.Assert.*;
import android.content.*;
import android.os.SystemClock;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.security.ControllerPinManager;
import java.util.*;
import org.junit.*;

public final class AllAppsAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private SharedPreferences preferences;
    private Map<String, ?> original;
    @Before public void fixture() {
        preferences = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
        original = preferences.getAll();
        ControllerPinManager.setPin(context, "2468");
        ControllerPinManager.enterDomMode();
        AppModeManager scope = new AppModeManager(context);
        scope.setAllAppsEnabled(false);
        scope.saveIncludedPackages(Set.of("com.android.settings"));
    }
    @After public void restore() {
        SharedPreferenceTestRestore.restore(preferences, original);
        ControllerPinManager.enterSubMode();
    }
    @Test public void allAppsDiscoveryIsDynamicAndOlderRefreshCannotReplaceManualSelection() {
        AppModeManager scope = new AppModeManager(context);
        Set<String> installed = InstalledAppCatalog.packageNames(context);
        assertFalse(installed.isEmpty());
        scope.saveAllApps(Set.of("removed.synthetic"));
        long end = SystemClock.uptimeMillis() + 8000;
        while (!scope.getIncludedPackages().equals(installed) && SystemClock.uptimeMillis() < end)
            SystemClock.sleep(50);
        assertEquals(installed, scope.getIncludedPackages());
        long old = scope.scopeRevision();
        scope.saveIncludedPackages(Set.of("com.android.settings"));
        assertTrue(scope.isAllApps());
        assertTrue(scope.refreshAllApps(old, installed));
        assertEquals(Set.of("com.android.settings"), scope.getSelectedPackages());
        assertEquals(installed, scope.getIncludedPackages());
        scope.setAllAppsEnabled(false);
        assertFalse(scope.refreshAllApps(old, installed));
        assertEquals(Set.of("com.android.settings"), scope.getIncludedPackages());
        scope.saveAllApps(installed);
        assertFalse(scope.refreshAllApps(old, Set.of("stale.synthetic")));
        assertEquals(installed, scope.getIncludedPackages());
    }
    @Test public void sharedSelectorPersistsAllAndManualSelectionAcrossRecreation() {
        try (ActivityScenario<SetupAppsActivity> page = ActivityScenario.launch(SetupAppsActivity.class)) {
            awaitReady(page);
            page.onActivity(a -> ((CompoundButton) a.findViewById(R.id.all_apps)).setChecked(true));
            assertTrue(new AppModeManager(context).isAllApps());
            page.recreate();
            awaitReady(page);
            page.onActivity(a -> {
                assertTrue(((CompoundButton) a.findViewById(R.id.all_apps)).isChecked());
                ListView list = a.findViewById(R.id.app_list);
                IncludedAppsAdapter adapter = (IncludedAppsAdapter) list.getAdapter();
                IncludedAppRow row = (IncludedAppRow) adapter.getView(0, null, list);
                String excluded = adapter.getItem(0).packageName;
                AppModeManager scope = new AppModeManager(context);
                assertEquals(Set.of("com.android.settings"), scope.getSelectedPackages());
                boolean selected = scope.getSelectedPackages().contains(excluded);
                row.choice().setChecked(!selected);
                assertTrue(scope.isAllApps());
                assertEquals(!selected, scope.getSelectedPackages().contains(excluded));
                ((CompoundButton) a.findViewById(R.id.all_apps)).setChecked(false);
                assertEquals(scope.getSelectedPackages(), scope.getIncludedPackages());
                ControllerPinManager.enterSubMode();
                row.choice().setChecked(selected);
                assertEquals(!selected, scope.getSelectedPackages().contains(excluded));
            });
        }
    }
    private static void awaitReady(ActivityScenario<SetupAppsActivity> page) {
        long end = SystemClock.uptimeMillis() + 8000;
        java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
        do {
            page.onActivity(a -> ready.set(a.findViewById(R.id.all_apps).isEnabled()));
            if (ready.get()) return;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < end);
        fail("App discovery did not finish");
    }
}
