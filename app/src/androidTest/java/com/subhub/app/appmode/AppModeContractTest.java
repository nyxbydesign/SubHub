package com.subhub.app.appmode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.R;
import com.subhub.app.commitment.CommitmentManager;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.GlobalSettingsActivity;
import com.subhub.app.settings.AppAssignmentRow;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public final class AppModeContractTest {
    @Test public void allAppsCoversEachEnabledFeatureAndSelectedModeRetainsIndependentChoices() {
        com.subhub.app.settings.FeatureModuleManager modules = new com.subhub.app.settings.FeatureModuleManager(context);
        modules.save(true, true, true, true);
        new SettingsRepository(context).saveCaptureMethod(com.subhub.app.settings.CaptureMethod.APP_MODE);
        AppModeManager manager = new AppModeManager(context);
        manager.saveAppSelections(Set.of("com.example.censor"), Set.of("com.example.timer"), Set.of("com.example.messages"));
        manager.save(true, AppModePolicy.Mode.ALWAYS, Set.of("com.example.censor"));
        assertTrue(manager.shouldRecognize("com.example.unassigned"));
        assertTrue(manager.shouldShowSubliminal("com.example.unassigned"));
        assertEquals(Set.of("com.example.unassigned"), manager.timerScopeForForeground("com.example.unassigned"));
        assertTrue(manager.timerScopeForForeground(context.getPackageName()).isEmpty());
        assertTrue(manager.timerScopeForForeground("com.android.settings").isEmpty());
        assertFalse(manager.shouldShowSubliminal(context.getPackageName()));
        String keyboard = manager.inputMethodPackage();
        if (!keyboard.isEmpty()) {
            assertTrue(manager.timerScopeForForeground(keyboard).isEmpty());
            assertFalse(manager.shouldShowSubliminal(keyboard));
        }
        manager.save(true, AppModePolicy.Mode.SELECTED_APPS, manager.getSelectedPackages());
        assertEquals(Set.of("com.example.timer"), manager.getTimerPackages());
        assertEquals(Set.of("com.example.messages"), manager.getSubliminalPackages());
        assertTrue(manager.timerScopeForForeground("com.example.unassigned").isEmpty());
        assertFalse(manager.shouldShowSubliminal("com.example.unassigned"));
        assertTrue(manager.shouldShowSubliminal("com.example.messages"));
        assertFalse(manager.shouldShowSubliminal("com.example.timer"));
        assertEquals(Set.of("com.example.timer"), manager.timerScopeForForeground("com.example.timer"));
        modules.save(true, false, true, false);
        assertTrue(manager.timerScopeForForeground("com.example.timer").isEmpty());
        assertFalse(manager.shouldShowSubliminal("com.example.messages"));
        modules.save(true, true, true, true);
        manager.setArmed(false);
        assertTrue(manager.timerScopeForForeground("com.example.timer").isEmpty());
        assertFalse(manager.shouldShowSubliminal("com.example.messages"));
    }

    @Test public void allAppsDefaultAllowanceIsEditableWithoutAnyAppAssignments() {
        ControllerPinManager.enterDomMode();
        new com.subhub.app.settings.FeatureModuleManager(context).save(true, true, true, false);
        AppModeManager manager = new AppModeManager(context);
        manager.saveAppSelections(Set.of(), Set.of(), Set.of());
        manager.save(false, AppModePolicy.Mode.ALWAYS, Set.of());
        AppTimerManager timer = new AppTimerManager(context);
        timer.saveSettings(true, 30, false, 120);
        try (ActivityScenario<AppModeActivity> scenario = ActivityScenario.launch(AppModeActivity.class)) {
            scenario.onActivity(activity -> {
                assertEquals(View.VISIBLE, activity.findViewById(R.id.default_allowance_group).getVisibility());
                assertTrue(activity.findViewById(R.id.default_limit_minutes).isEnabled());
            });
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.default_limit_minutes))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(),
                            androidx.test.espresso.action.ViewActions.replaceText("45"),
                            androidx.test.espresso.action.ViewActions.closeSoftKeyboard());
        }
        assertEquals(45, timer.loadSettings().perAppMinutes);
        assertTrue(timer.loadSettings().perAppEnabled);
        assertTrue(manager.getTimerPackages().isEmpty());
        assertFalse(manager.isArmed());
    }

    @Test public void allAppsCountsAndEnforcesAnUnassignedAppsExistingDefaultAllowance() {
        new com.subhub.app.settings.FeatureModuleManager(context).save(true, true, true, true);
        AppModeManager manager = new AppModeManager(context);
        manager.saveAppSelections(Set.of(), Set.of(), Set.of());
        manager.save(true, AppModePolicy.Mode.ALWAYS, Set.of());
        AppTimerManager timer = new AppTimerManager(context);
        timer.clearUsageForTesting();
        timer.saveSettings(true, 1, false, 120);
        long now = System.currentTimeMillis();
        Set<String> scope = manager.timerScopeForForeground("com.example.unassigned");
        timer.recordUsage("com.example.unassigned", 60_000L, scope, now);
        assertEquals(60_000L, timer.snapshot("com.example.unassigned", now).appUsedMillis);
        assertEquals(AppTimerManager.LimitStatus.PER_APP, timer.limitStatus("com.example.unassigned", scope, now));
        assertEquals(AppTimerManager.LimitStatus.NONE,
                timer.limitStatus(context.getPackageName(), manager.timerScopeForForeground(context.getPackageName()), now));
        timer.clearUsageForTesting();
    }
    private Context context;
    private SharedPreferences preferences;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        preferences = context.getSharedPreferences(
                SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
        preferences.edit().remove(AppModeManager.KEY_ARMED)
                .remove(AppModeManager.KEY_MODE)
                .remove(AppModeManager.KEY_MODE_EXPLICIT)
                .remove(AppModeManager.KEY_SELECTED_PACKAGES)
                .remove(AppModeManager.KEY_TIMER_PACKAGES)
                .remove(HardcoreModeManager.KEY_REQUESTED).commit();
        ProtectionSessionManager.markMediaProjectionExplicitlyStopped(context);
        CommitmentManager.emergencyRelease(context);
        ControllerPinManager.enterSubMode();
    }

    @After public void tearDown() {
        preferences.edit().remove(AppModeManager.KEY_ARMED)
                .remove(AppModeManager.KEY_MODE)
                .remove(AppModeManager.KEY_SELECTED_PACKAGES)
                .remove(AppModeManager.KEY_TIMER_PACKAGES)
                .remove(HardcoreModeManager.KEY_REQUESTED).commit();
        ProtectionSessionManager.markMediaProjectionExplicitlyStopped(context);
        CommitmentManager.emergencyRelease(context);
        ControllerPinManager.enterSubMode();
        ResumeNotificationManager.cancel(context);
    }

    @Test public void selectedAppsAndBootIntentArePersisted() {
        AppModeManager manager = new AppModeManager(context);
        manager.save(true, AppModePolicy.Mode.SELECTED_APPS, Set.of("com.example.watched"));
        AppModeManager restored = new AppModeManager(context);
        assertTrue(restored.isArmed());
        assertEquals(AppModePolicy.Mode.SELECTED_APPS, restored.getMode());
        assertTrue(restored.getSelectedPackages().contains("com.example.watched"));
        restored.applyBootPolicy();
        assertTrue(restored.isArmed());
    }

    @Test public void censorAndTimerAssignmentsAreIndependent() {
        AppModeManager manager = new AppModeManager(context);
        manager.save(false, AppModePolicy.Mode.ALWAYS, Set.of());
        manager.saveAppSelections(Set.of("com.example.censor"), Set.of("com.example.timer"));
        assertEquals(Set.of("com.example.censor"), manager.getSelectedPackages());
        assertEquals(Set.of("com.example.timer"), manager.getTimerPackages());
        assertEquals(AppModePolicy.Mode.SELECTED_APPS, manager.getMode());
    }

    @Test public void existingExplicitAssignmentsOverrideAStaleAlwaysMode() {
        preferences.edit()
                .putString(AppModeManager.KEY_MODE, "always")
                .putStringSet(AppModeManager.KEY_SELECTED_PACKAGES,
                        Set.of("com.twitter.android"))
                .commit();

        AppModeManager manager = new AppModeManager(context);
        assertEquals(AppModePolicy.Mode.SELECTED_APPS, manager.getMode());
        assertTrue(manager.getSelectedPackages().contains("com.twitter.android"));
    }

    @Test public void normalBootPreservesTheLastArmedState() {
        AppModeManager manager = new AppModeManager(context);
        manager.save(true, AppModePolicy.Mode.ALWAYS, Set.of());
        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertTrue(new AppModeManager(context).isArmed());

        manager.setArmed(false);
        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertFalse(new AppModeManager(context).isArmed());
    }

    @Test public void sealedPactRearmsAtBootAndRejectsSubModeDisarm() {
        AppModeManager manager = new AppModeManager(context);
        manager.setArmed(false);
        assertTrue(CommitmentManager.start(
                context, CommitmentManager.MIN_DURATION_MS, "keeper-code"));
        assertTrue(manager.isArmed());

        manager.setArmed(false);
        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertTrue(manager.isArmed());

        new BootReceiver().onReceive(context, new Intent(BootReceiver.ACTION_DISARM));
        assertTrue(manager.isArmed());
    }

    @Test public void disarmNotificationActionAlsoClearsProjectionIntent() {
        AppModeManager manager = new AppModeManager(context);
        manager.setArmed(true);
        ProtectionSessionManager.markMediaProjectionStarted(context);
        new BootReceiver().onReceive(context, new Intent(BootReceiver.ACTION_DISARM));
        assertFalse(manager.isArmed());
        assertFalse(ProtectionSessionManager.needsMediaProjectionResume(context));
    }

    @Test public void limitsScreenOnlyContainsAllowanceControls() {
        new AppModeManager(context).save(true, AppModePolicy.Mode.SELECTED_APPS,
                Set.of("com.android.chrome"));
        try (ActivityScenario<AppModeActivity> scenario = ActivityScenario.launch(
                AppModeActivity.class)) {
            scenario.onActivity(activity -> {
                assertEquals(View.VISIBLE, activity.findViewById(R.id.timer_card).getVisibility());
                assertNull(activity.findViewById(R.id.button_accessibility_settings));
                assertNull(activity.findViewById(R.id.app_list_card));
            });
        }
    }

    @Test public void globalSettingsOwnsRecognitionAndAppAssignments() {
        ControllerPinManager.enterDomMode();
        new AppModeManager(context).save(false, AppModePolicy.Mode.ALWAYS, Set.of());
        try (ActivityScenario<GlobalSettingsActivity> scenario = ActivityScenario.launch(
                GlobalSettingsActivity.class)) {
            scenario.onActivity(activity -> {
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.button_accessibility_settings).getVisibility());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.app_list_card).getVisibility());
                ViewGroup sections = activity.findViewById(R.id.settings_sections);
                View hardcore = activity.findViewById(R.id.hardcore_card);
                assertEquals(sections, ((View) hardcore.getParent()).getParent());
                assertFalse(hardcore.isShown());
                View apps = activity.findViewById(R.id.apps_card);
                assertEquals(sections, ((View) apps.getParent()).getParent());
                assertFalse(apps.isShown());
                for (int id : new int[] {R.id.recognition_card, R.id.app_list_card,
                        R.id.android_access_card}) {
                    assertEquals(apps, activity.findViewById(id).getParent());
                }
                assertEquals(View.GONE, activity.findViewById(R.id.armed).getVisibility());
            });
        }
    }

    @Test public void censorScopeChangesPreserveServiceAndIndependentAssignments() {
        ControllerPinManager.enterDomMode();
        for (boolean armed : new boolean[] {false, true}) {
            AppModeManager manager = new AppModeManager(context);
            manager.saveAppSelections(Set.of("com.example.censor"),
                    Set.of("com.example.limited"), Set.of("com.example.messages"));
            manager.save(armed, AppModePolicy.Mode.SELECTED_APPS,
                    Set.of("com.example.censor"));
            try (ActivityScenario<GlobalSettingsActivity> scenario = ActivityScenario.launch(
                    GlobalSettingsActivity.class)) {
                scenario.onActivity(activity -> {
                    // Use the live scope listener before the asynchronous launcher refresh.
                    activity.findViewById(R.id.mode_always).performClick();
                    assertEquals(armed, manager.isArmed());
                    assertEquals(AppModePolicy.Mode.ALWAYS, manager.getMode());
                    assertEquals(Set.of("com.example.limited"), manager.getTimerPackages());
                    assertEquals(Set.of("com.example.messages"), manager.getSubliminalPackages());
                    activity.findViewById(R.id.mode_selected).performClick();
                    assertEquals(armed, manager.isArmed());
                    assertEquals(AppModePolicy.Mode.SELECTED_APPS, manager.getMode());
                });
            }
        }
    }

    @Test public void launcherQueryIsDeclaredWithoutQueryAllPackages() {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        @SuppressWarnings("deprecation")
        List<ResolveInfo> apps = context.getPackageManager().queryIntentActivities(
                launcher, PackageManager.MATCH_ALL);
        assertFalse(apps.isEmpty());
        try {
            @SuppressWarnings("deprecation")
            ActivityInfo receiver = context.getPackageManager().getReceiverInfo(
                    new android.content.ComponentName(context, BootReceiver.class), 0);
            assertFalse(receiver.exported);
        } catch (PackageManager.NameNotFoundException error) {
            throw new AssertionError(error);
        }
    }

    @Test public void launcherPickerUsesCompactRowsWithIndependentModuleTargets() throws Exception {
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<GlobalSettingsActivity> scenario =
                     ActivityScenario.launch(new Intent(context, GlobalSettingsActivity.class).putExtra("settings_group", "apps"))) {
            scenario.onActivity(activity ->
                    activity.findViewById(R.id.button_toggle_apps).performClick());
            Thread.sleep(750L);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                LinearLayout list = activity.findViewById(R.id.app_list);
                assertEquals(LinearLayout.VERTICAL, list.getOrientation());
                assertTrue(list.getChildCount() > 2);
                AppAssignmentRow first = (AppAssignmentRow) list.getChildAt(1);
                AppAssignmentRow second = (AppAssignmentRow) list.getChildAt(2);
                assertTrue(second.getTop() > first.getTop());
                assertEquals(list.getWidth(), first.getWidth());
                int minimum = Math.round(48 * activity.getResources().getDisplayMetrics().density);
                for (int index = 0; index < 3; index++) {
                    assertTrue(first.choice(index).getWidth() >= minimum);
                    assertTrue(first.choice(index).getHeight() >= minimum);
                    assertTrue(first.choice(index).getContentDescription().toString().contains(", "));
                }
            });
        }
    }
}
