package com.subhub.app;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.subhub.app.appmode.AppModeActivity;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.atmosphere.AtmosphereActivity;
import com.subhub.app.diagnostics.DiagnosticsActivity;
import com.subhub.app.penance.PenanceActivity;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.settings.GlobalSettingsActivity;
import com.subhub.app.settings.SettingsActivity;
import com.subhub.app.stats.StatsRepository;
import com.subhub.app.util.PrimaryHeader;
import com.subhub.app.util.SubHubNavigation;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

/** Proves enabled product tabs plus the always-visible Settings tab navigate correctly. */
@RunWith(AndroidJUnit4.class)
public final class SubHubNavigationTest {
    @Test public void achievementNoticeDoesNotCoverTheFloatingNavigation() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    java.lang.reflect.Method notice = MainActivity.class.getDeclaredMethod("showAchievementUnlock",
                            com.subhub.app.stats.AchievementManager.Achievement.class, int.class);
                    notice.setAccessible(true);
                    notice.invoke(activity, new com.subhub.app.stats.AchievementManager(activity).all().get(0), 0);
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            onView(withId(com.google.android.material.R.id.snackbar_text)).check((view, error) -> {
                if (error != null) throw error;
                Rect notice = new Rect(), navigation = new Rect();
                assertTrue(((View) view.getParent().getParent()).getGlobalVisibleRect(notice));
                assertTrue(view.getRootView().findViewById(R.id.bottom_navigation).getGlobalVisibleRect(navigation));
                assertTrue("Achievement notices must not cover navigation", notice.bottom <= navigation.top);
            });
        }
    }

    @Before public void enterDomMode() {
        android.content.Context context = ApplicationProvider.getApplicationContext();
        if (!ControllerPinManager.isConfigured(context)) {
            ControllerPinManager.setPin(context, "2468");
        }
        ControllerPinManager.enterDomMode();
    }

    @Test public void disabledAreasRemainReachableWhileSettingsRemainsAvailable() {
        android.content.Context context = ApplicationProvider.getApplicationContext();
        FeatureModuleManager modules = new FeatureModuleManager(context);
        modules.save(false, true, false);
        try (ActivityScenario<GlobalSettingsActivity> scenario =
                     ActivityScenario.launch(GlobalSettingsActivity.class)) {
            scenario.onActivity(activity -> {
                assertEquals(View.VISIBLE, activity.findViewById(R.id.nav_censor).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.nav_home).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.nav_limits).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.nav_money).getVisibility());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.nav_atmosphere).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.nav_settings).getVisibility());
            });
        } finally {
            modules.save(true, true, true);
        }
    }

    @Test public void subSpaceKeepsHomeAndSettings() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                ControllerPinManager.enterSubMode();
                SubHubNavigation.bind(activity, activity.findViewById(android.R.id.content),
                        SubHubNavigation.Screen.HOME);
                assertFalse(ControllerPinManager.isDomModeActive());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.nav_home).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.nav_censor).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.nav_limits).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.nav_money).getVisibility());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.nav_atmosphere).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.nav_settings).getVisibility());
            });
        } finally {
            ControllerPinManager.enterDomMode();
        }
    }

    @Test public void subSettingsRemoveDomOnlySectionsInsteadOfDisablingThem() {
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<GlobalSettingsActivity> scenario =
                     ActivityScenario.launch(GlobalSettingsActivity.class)) {
            scenario.onActivity(activity -> {
                assertNull(activity.findViewById(R.id.feature_areas_card));
                assertEquals(View.GONE, activity.findViewById(R.id.apps_card).getVisibility());
                assertNull(activity.findViewById(R.id.paypal_card));
                assertNull(activity.findViewById(R.id.button_packs));
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.button_diagnostics).getVisibility());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.button_help).getVisibility());
            });
        } finally {
            ControllerPinManager.enterDomMode();
        }
    }

    @Test public void settingsRebindsNavigationWhenResumedAfterEnteringSubSpace() {
        FeatureModuleManager modules = new FeatureModuleManager(
                ApplicationProvider.getApplicationContext());
        modules.save(true, true, true);
        try (ActivityScenario<GlobalSettingsActivity> scenario =
                     ActivityScenario.launch(GlobalSettingsActivity.class)) {
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            ControllerPinManager.enterSubMode();
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
            scenario.onActivity(activity -> {
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.nav_home).getVisibility());
                assertEquals(View.GONE,
                        activity.findViewById(R.id.nav_censor).getVisibility());
                assertEquals(View.GONE,
                        activity.findViewById(R.id.nav_limits).getVisibility());
                assertEquals(View.GONE,
                        activity.findViewById(R.id.nav_money).getVisibility());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.nav_atmosphere).getVisibility());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.nav_settings).getVisibility());
                assertNotNull(activity.findViewById(R.id.nav_settings).getBackground());
            });
        } finally {
            ControllerPinManager.enterDomMode();
        }
    }

    @Test public void subSettingsDiagnosticsOpensReadOnlyInsteadOfReturningHome() {
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<GlobalSettingsActivity> ignored =
                     ActivityScenario.launch(GlobalSettingsActivity.class)) {
            onView(withId(R.id.button_diagnostics)).perform(scrollTo(), click());
            Activity destination = resumedActivity(DiagnosticsActivity.class);
            assertEquals(DiagnosticsActivity.class, destination.getClass());
            assertEquals(View.GONE,
                    destination.findViewById(R.id.button_edit_lock).getVisibility());
            assertFalse(destination.findViewById(R.id.switch_diagnostics_overlay).isEnabled());
            destination.finish();
        } finally {
            ControllerPinManager.enterDomMode();
        }
    }

    @Test public void primaryPillNavigatesAndUpdatesSelection() {
        android.content.Context context = ApplicationProvider.getApplicationContext();
        context
                .getSharedPreferences("betablocker_achievements", 0)
                .edit().clear().commit();
        context.getSharedPreferences(StatsRepository.PREFS_NAME, 0)
                .edit().clear().commit();
        FeatureModuleManager modules = new FeatureModuleManager(context);
        // Keep MainActivity's first-open permission coordinator idle while this test exercises
        // navigation. The feature tabs are restored immediately after that startup pass.
        modules.save(false, false, false, false);
        new AppModeManager(context).setArmed(false);
        context.getSharedPreferences(com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME, 0)
                .edit().putBoolean(HardcoreModeManager.KEY_REQUESTED, false).commit();
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        SystemClock.sleep(500L);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        modules.save(true, true, true);
        scenario.onActivity(activity -> SubHubNavigation.bind(activity,
                activity.findViewById(android.R.id.content), SubHubNavigation.Screen.HOME));
        assertDestination(MainActivity.class, R.id.nav_home, R.id.nav_censor);

        onView(withId(R.id.nav_censor)).perform(click());
        assertDestination(SettingsActivity.class, R.id.nav_censor, R.id.nav_home);

        onView(withId(R.id.nav_limits)).perform(click());
        assertDestination(AppModeActivity.class, R.id.nav_limits, R.id.nav_censor);

        onView(withId(R.id.nav_money)).perform(click());
        assertDestination(PenanceActivity.class, R.id.nav_money, R.id.nav_limits);

        onView(withId(R.id.nav_atmosphere)).perform(click());
        assertDestination(AtmosphereActivity.class, R.id.nav_atmosphere, R.id.nav_money);

        onView(withId(R.id.nav_settings)).perform(click());
        assertDestination(GlobalSettingsActivity.class, R.id.nav_settings, R.id.nav_atmosphere);

        onView(withId(R.id.nav_home)).perform(click());
        assertDestination(MainActivity.class, R.id.nav_home, R.id.nav_settings);
        scenario.onActivity(Activity::finishAndRemoveTask);
    }

    @Test public void pillFitsFontScalingAndKeepsTheLastActionAboveNavigation() {
        Context context = ApplicationProvider.getApplicationContext();
        new FeatureModuleManager(context).save(true, true, true);
        try (ActivityScenario<AtmosphereActivity> scenario =
                     ActivityScenario.launch(AtmosphereActivity.class)) {
            scenario.onActivity(activity -> {
                int[] widths = {320, 360, 411, 600, 840};
                float[] fontScales = {1f, 1.3f, 2f};
                for (int width : widths) {
                    for (float fontScale : fontScales) {
                        Configuration configuration = new Configuration(activity.getResources()
                                .getConfiguration());
                        configuration.screenWidthDp = width;
                        configuration.smallestScreenWidthDp = width;
                        configuration.fontScale = fontScale;
                        Context themed = new ContextThemeWrapper(activity.createConfigurationContext(
                                configuration), R.style.Theme_SubHub);
                        ViewGroup page = (ViewGroup) LayoutInflater.from(themed)
                                .inflate(R.layout.activity_atmosphere, null, false);
                        PrimaryHeader.bind(page, R.drawable.ic_atmosphere,
                                R.string.atmosphere_title, R.string.atmosphere_subtitle_dom);
                        int pageWidth = Math.round(width * themed.getResources()
                                .getDisplayMetrics().density);
                        int pageHeight = Math.round(720 * themed.getResources()
                                .getDisplayMetrics().density);
                        measurePage(page, pageWidth, pageHeight);
                        SubHubNavigation.bind(activity, page, SubHubNavigation.Screen.ATMOSPHERE);
                                // The layout listener applies the measured pill clearance on the
                                // next pass.
                                measurePage(page, pageWidth, pageHeight);
                        measurePage(page, pageWidth, pageHeight);
                        ViewGroup navigation = page.findViewById(R.id.bottom_navigation);
                        int target = themed.getResources()
                                .getDimensionPixelSize(R.dimen.control_min_height);
                        for (int index = 0; index < navigation.getChildCount(); index++) {
                            ViewGroup tab = (ViewGroup) navigation.getChildAt(index);
                            assertTrue("Navigation target too narrow", tab.getWidth() >= target);
                            assertTrue("Navigation target too short", tab.getHeight() >= target);
                            TextView label = (TextView) tab.getChildAt(1);
                            assertTrue(label.getLayout() != null);
                            assertEquals("Navigation words should remain whole", 1,
                                    label.getLineCount());
                            for (int line = 0; line < label.getLineCount(); line++) {
                                assertEquals(0, label.getLayout().getEllipsisCount(line));
                                assertTrue("Navigation label exceeds its width",
                                        label.getLayout().getLineWidth(line) <= label.getWidth() + 1);
                            }
                            assertTrue("Navigation label exceeds its target",
                                    label.getBottom() <= tab.getHeight() - tab.getPaddingBottom());
                        }
                        ScrollView scroll = (ScrollView) page.getChildAt(0);
                        scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
                        View lastAction = page.findViewById(R.id.button_popup_storm);
                        Rect bounds = new Rect(0, 0, lastAction.getWidth(), lastAction.getHeight());
                        page.offsetDescendantRectToMyCoords(lastAction, bounds);
                        assertTrue("Last action must scroll above navigation",
                                bounds.bottom <= navigation.getTop());
                    }
                }
            });
        }
    }

    private static void measurePage(View page, int width, int height) {
        page.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        page.layout(0, 0, width, height);
    }

    private static void assertDestination(Class<? extends Activity> expected, int selectedId,
            int unselectedId) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Activity activity = resumedActivity(expected);
        assertEquals(expected, activity.getClass());
        View selected = activity.findViewById(selectedId);
        View unselected = activity.findViewById(unselectedId);
        assertNotNull(selected.getBackground());
        assertTrue(selected.isSelected());
        assertFalse(unselected.isSelected());
        AccessibilityNodeInfo selectedInfo = selected.createAccessibilityNodeInfo();
        assertTrue(selectedInfo.isSelected());
        assertEquals(Button.class.getName(), selectedInfo.getClassName());
        selectedInfo.recycle();
        int[] tabs = {R.id.nav_home, R.id.nav_censor, R.id.nav_limits,
                R.id.nav_money, R.id.nav_atmosphere, R.id.nav_settings};
        int[] icons = {R.id.nav_home_icon, R.id.nav_censor_icon, R.id.nav_limits_icon,
                R.id.nav_money_icon, R.id.nav_atmosphere_icon, R.id.nav_settings_icon};
        int[] labels = {R.id.nav_home_label, R.id.nav_censor_label, R.id.nav_limits_label,
                R.id.nav_money_label, R.id.nav_atmosphere_label, R.id.nav_settings_label};
        for (int index = 0; index < tabs.length; index++) {
            LinearLayout tab = activity.findViewById(tabs[index]);
            ImageView icon = activity.findViewById(icons[index]);
            TextView label = activity.findViewById(labels[index]);
            assertEquals(View.VISIBLE, tab.getVisibility());
            assertTrue(tab.getAlpha() >= 0.8f);
            assertEquals(Gravity.CENTER, tab.getGravity() & Gravity.CENTER);
            assertEquals(LinearLayout.VERTICAL, tab.getOrientation());
            assertTrue(Color.alpha(label.getCurrentTextColor()) > 0);
            assertTrue(!label.getIncludeFontPadding());
            assertTrue(icon.getDrawable() != null);
            assertTrue(icon.getBottom() <= label.getTop());
        }
    }

    private static Activity resumedActivity(Class<? extends Activity> expected) {
        AtomicReference<Activity> result = new AtomicReference<>();
        long deadline = SystemClock.uptimeMillis() + 3_000L;
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                Collection<Activity> resumed = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED);
                result.set(resumed.isEmpty() ? null : resumed.iterator().next());
            });
            if (result.get() != null) return result.get();
            SystemClock.sleep(50L);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("No activity reached RESUMED while waiting for "
                + expected.getSimpleName());
    }
}
