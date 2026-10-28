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
import android.util.TypedValue;
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
import androidx.core.view.ViewCompat;
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
    @org.junit.Rule public final UiAutomationStateRule automationState = new UiAutomationStateRule();
    @Before public void prepareRole() {
        ControllerPinManager.enterDomMode();
        com.subhub.app.people.People.installRemoteAuthority(() -> false);
        com.subhub.app.onboarding.OnboardingState.complete(ApplicationProvider.getApplicationContext());
    }

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

    @Test public void disabledAreasRemainReachableThroughPeopleWhileSettingsRemainsAvailable() {
        try (ActivityScenario<GlobalSettingsActivity> scenario = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            scenario.onActivity(activity -> {
                assertEquals(View.VISIBLE, activity.findViewById(R.id.nav_people).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.nav_activity).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.nav_home).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.nav_settings).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.nav_censor).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.nav_limits).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.nav_money).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.nav_atmosphere).getVisibility());
                assertVisibleTabOrder(activity.findViewById(android.R.id.content),
                        R.id.nav_home, R.id.nav_people, R.id.nav_tasks, R.id.nav_activity, R.id.nav_settings);
            });
        }
    }

    @Test public void subSpaceKeepsOverviewContractStatsAndSettings() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                ControllerPinManager.enterSubMode();
                SubHubNavigation.bind(activity, activity.findViewById(android.R.id.content),
                        SubHubNavigation.Screen.HOME);
                assertFalse(ControllerPinManager.isDomModeActive());
                assertEquals(View.GONE, activity.findViewById(R.id.nav_activity).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.nav_home).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.nav_censor).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.nav_limits).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.nav_money).getVisibility());
                assertEquals(View.GONE,
                        activity.findViewById(R.id.nav_atmosphere).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.nav_contract).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.nav_stats).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.nav_settings).getVisibility());
                assertVisibleTabOrder(activity.findViewById(android.R.id.content),
                        R.id.nav_home, R.id.nav_tasks, R.id.nav_contract, R.id.nav_stats, R.id.nav_settings);
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
                assertNull(activity.findViewById(R.id.apps_card));
                assertNull(activity.findViewById(R.id.app_list_card));
                assertNull(activity.findViewById(android.R.id.content).findViewWithTag("settings:apps"));
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
                assertEquals(View.GONE,
                        activity.findViewById(R.id.nav_atmosphere).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.nav_contract).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.nav_stats).getVisibility());
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
            onView(androidx.test.espresso.matcher.ViewMatchers.withTagValue(org.hamcrest.Matchers.is("settings:help")))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), click());
            onView(withId(R.id.button_diagnostics))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), click());
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
        new AppModeManager(context).setArmed(false);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new android.content.Intent(context, MainActivity.class)
                .setAction(android.content.Intent.ACTION_MAIN)
                .putExtra(MainActivity.EXTRA_SUPPRESS_PERMISSION_READINESS, true))) {
            assertDestination(MainActivity.class, R.id.nav_home, R.id.nav_people);
            onView(withId(R.id.nav_people)).perform(click());
            assertDestination(com.subhub.app.people.PeopleActivity.class, R.id.nav_people, R.id.nav_home);
            onView(withId(R.id.nav_tasks)).perform(click());
            assertDestination(com.subhub.app.tasks.TasksOverviewActivity.class, R.id.nav_tasks, R.id.nav_people);
            onView(withId(R.id.nav_activity)).perform(click());
            assertDestination(com.subhub.app.activity.DomActivityActivity.class, R.id.nav_activity, R.id.nav_people);
            onView(withId(R.id.nav_settings)).perform(click());
            assertDestination(GlobalSettingsActivity.class, R.id.nav_settings, R.id.nav_activity);
            onView(withId(R.id.nav_home)).perform(click());
            assertDestination(MainActivity.class, R.id.nav_home, R.id.nav_settings);
        }
    }

    @Test public void activityTabRejectsItsStaleDomClickAfterEnteringSubSpace() {
        try (ActivityScenario<GlobalSettingsActivity> scenario = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            scenario.onActivity(activity -> {
                View stale = activity.findViewById(R.id.nav_activity);
                assertEquals(View.VISIBLE, stale.getVisibility());
                ControllerPinManager.enterSubMode();
                SubHubNavigation.bind(activity, activity.findViewById(android.R.id.content),
                        SubHubNavigation.Screen.SETTINGS);
                assertEquals(View.GONE, stale.getVisibility());
                stale.performClick();
                assertVisibleTabOrder(activity.findViewById(android.R.id.content),
                        R.id.nav_home, R.id.nav_tasks, R.id.nav_contract, R.id.nav_stats, R.id.nav_settings);
            });
            assertEquals(GlobalSettingsActivity.class, resumedActivity(GlobalSettingsActivity.class).getClass());
        } finally { ControllerPinManager.enterDomMode(); }
    }

    @Test public void actualActivityDestinationFitsRequestedFontScaleAndViewport() {
        try (ActivityScenario<com.subhub.app.activity.DomActivityActivity> scenario =
                     ActivityScenario.launch(com.subhub.app.activity.DomActivityActivity.class)) {
            assertDestination(com.subhub.app.activity.DomActivityActivity.class, R.id.nav_activity, R.id.nav_people);
            scenario.onActivity(activity -> {
                android.os.Bundle arguments = InstrumentationRegistry.getArguments();
                String expectedFont = arguments.getString("shell_expected_font_scale");
                String expectedWidth = arguments.getString("shell_expected_width_dp");
                if (expectedFont != null) assertEquals(Float.parseFloat(expectedFont),
                        activity.getResources().getConfiguration().fontScale, 0.01f);
                if (expectedWidth != null) assertEquals(Integer.parseInt(expectedWidth),
                        activity.getResources().getConfiguration().screenWidthDp);
                View root = activity.findViewById(android.R.id.content);
                assertRailFits(root, 5);
                assertVisibleTabOrder(root, R.id.nav_home, R.id.nav_people, R.id.nav_tasks, R.id.nav_activity, R.id.nav_settings);
                TextView title = activity.findViewById(R.id.primary_header_title);
                assertEquals(activity.getString(com.subhub.app.activity.ActivitySources.repository().localOnly()
                        ? R.string.dom_activity_local_title : R.string.dom_activity_title), title.getText().toString());
                assertCompleteText(title);
                assertTrue(ViewCompat.isAccessibilityHeading(title));
                View navigation = activity.findViewById(R.id.bottom_navigation);
                android.widget.ListView list = activity.findViewById(R.id.dom_activity_list);
                assertTrue("Actual Activity list clears the adaptive rail", list.getPaddingBottom()
                        >= navigation.getHeight() + activity.getResources().getDimensionPixelSize(R.dimen.page_content_tail_gap));
            });
        }
    }

    @Test public void activityUnreadBadgeUsesBackgroundEventsAndStopsWithItsPage() {
        com.subhub.app.activity.ActivityRepository original = com.subhub.app.activity.ActivitySources.repository();
        java.util.concurrent.atomic.AtomicInteger unread = new java.util.concurrent.atomic.AtomicInteger(3);
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean queriedOnMain = new java.util.concurrent.atomic.AtomicBoolean();
        com.subhub.app.activity.ActivitySources.install(new com.subhub.app.activity.ActivityRepository() {
            public Page load(String before, int limit) { return new Page(java.util.Collections.emptyList(), null); }
            public Page locate(String entryKey, int limit) { return load(null, limit); }
            public java.util.List<com.subhub.app.activity.ActivityEntry> recent(com.subhub.app.people.PersonId person, int limit) {
                return java.util.Collections.emptyList();
            }
            public int unreadCount() {
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) queriedOnMain.set(true);
                reads.incrementAndGet();
                return unread.get();
            }
            public void markRead(java.util.List<String> keys) { }
        });
        try (ActivityScenario<GlobalSettingsActivity> scenario = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            awaitUnreadBadge(scenario, 3);
            int beforeRebind = reads.get();
            scenario.onActivity(activity -> {
                for (int tick = 0; tick < 20; tick++) SubHubNavigation.bind(activity,
                        activity.findViewById(android.R.id.content), SubHubNavigation.Screen.SETTINGS);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals("Render ticks reuse the observed unread count", beforeRebind, reads.get());
            unread.set(0);
            com.subhub.app.activity.ActivitySources.changed();
            awaitUnreadBadge(scenario, 0);
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            int stoppedReads = reads.get();
            unread.set(5);
            com.subhub.app.activity.ActivitySources.changed();
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals("Stopped pages release the unread observer", stoppedReads, reads.get());
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
            awaitUnreadBadge(scenario, 5);
            scenario.onActivity(activity -> {
                ControllerPinManager.enterSubMode();
                SubHubNavigation.bind(activity, activity.findViewById(android.R.id.content),
                        SubHubNavigation.Screen.SETTINGS);
                assertEquals(View.GONE, activity.findViewById(R.id.nav_activity_unread).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.nav_activity).getVisibility());
            });
            int subReads = reads.get();
            unread.set(7);
            com.subhub.app.activity.ActivitySources.changed();
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals("Sub space does not query Dom unread counts", subReads, reads.get());
            assertFalse("Unread repository must never run on main", queriedOnMain.get());
        } finally {
            ControllerPinManager.enterDomMode();
            com.subhub.app.activity.ActivitySources.install(original);
        }
    }

    private static void awaitUnreadBadge(ActivityScenario<GlobalSettingsActivity> scenario, int unread) {
        long deadline = SystemClock.uptimeMillis() + 3_000;
        java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
        do {
            scenario.onActivity(activity -> {
                View indicator = activity.findViewById(R.id.nav_activity_unread);
                View tab = activity.findViewById(R.id.nav_activity);
                String label = activity.getString(R.string.dom_activity_title);
                String expected = unread > 0 ? label + ", "
                        + activity.getString(R.string.dom_activity_unread_count, unread) : label;
                ready.set(indicator.getVisibility() == (unread > 0 ? View.VISIBLE : View.GONE)
                        && expected.contentEquals(tab.getContentDescription()));
            });
            if (ready.get()) return;
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Unread indicator did not reach " + unread);
    }

    @Test public void railFitsFontScalingAndKeepsTheLastActionAboveNavigation() {
        Context context = ApplicationProvider.getApplicationContext();
        new FeatureModuleManager(context).save(true, true, true);
        try (ActivityScenario<AtmosphereActivity> scenario =
                     ActivityScenario.launch(AtmosphereActivity.class)) {
            scenario.onActivity(activity -> {
                int[] widths = {244, 320, 360, 411, 600, 840};
                float[] fontScales = {1f, 1.3f, 2f};
                for (int width : widths) {
                    for (float fontScale : fontScales) {
                        for (boolean dom : new boolean[] {true, false}) {
                            if (dom) ControllerPinManager.enterDomMode();
                            else ControllerPinManager.enterSubMode();
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
                            SubHubNavigation.bind(activity, page, dom ? SubHubNavigation.Screen.ACTIVITY
                                    : SubHubNavigation.Screen.ATMOSPHERE);
                            // Apply measured navigation clearance before checking the final action.
                            measurePage(page, pageWidth, pageHeight);
                            measurePage(page, pageWidth, pageHeight);
                            ViewGroup navigation = page.findViewById(R.id.bottom_navigation);
                            assertEquals(fontScale, themed.getResources().getConfiguration().fontScale, 0.001f);
                            assertRailFits(page, 5);
                            assertVisibleTabOrder(page, dom
                                    ? new int[] {R.id.nav_home, R.id.nav_people, R.id.nav_tasks, R.id.nav_activity, R.id.nav_settings}
                                    : new int[] {R.id.nav_home, R.id.nav_tasks, R.id.nav_contract, R.id.nav_stats, R.id.nav_settings});
                            if (dom) assertTrue(page.findViewById(R.id.nav_activity).isSelected());
                            ScrollView scroll = (ScrollView) page.getChildAt(0);
                            scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
                            View lastAction = page.findViewById(R.id.button_popup_storm);
                            Rect bounds = new Rect(0, 0, lastAction.getWidth(), lastAction.getHeight());
                            page.offsetDescendantRectToMyCoords(lastAction, bounds);
                            assertTrue("Last action must scroll above navigation",
                                    bounds.bottom <= navigation.getTop());
                        }
                    }
                }
            });
        } finally { ControllerPinManager.enterDomMode(); }
    }

    @Test public void actualDeviceShellFitsAtRequestedFontScaleAndViewport() {
        android.os.Bundle arguments = InstrumentationRegistry.getArguments();
        String expectedFont = arguments.getString("shell_expected_font_scale");
        String expectedWidth = arguments.getString("shell_expected_width_dp");
        try (ActivityScenario<GlobalSettingsActivity> scenario = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            scenario.onActivity(activity -> {
                if (expectedFont != null) assertEquals("Actual device font scale", Float.parseFloat(expectedFont),
                        activity.getResources().getConfiguration().fontScale, 0.01f);
                if (expectedWidth != null) assertEquals("Actual device viewport", Integer.parseInt(expectedWidth),
                        activity.getResources().getConfiguration().screenWidthDp);
                ViewGroup root = activity.findViewById(android.R.id.content);
                assertRailFits(root, 5);
                TextView title = activity.findViewById(R.id.primary_header_title);
                assertCompleteText(title);
                assertEquals(Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20,
                        title.getResources().getDisplayMetrics())), title.getTextSize(), 0.01f);
                assertTrue(ViewCompat.isAccessibilityHeading(title));
                TextView role = PrimaryHeader.editLockButton(root);
                assertCompleteText(role);
                int target = activity.getResources().getDimensionPixelSize(R.dimen.control_min_height);
                assertTrue(role.getWidth() >= target && role.getHeight() >= target);
                Rect titleBounds = new Rect(), roleBounds = new Rect();
                assertTrue(title.getGlobalVisibleRect(titleBounds));
                assertTrue(role.getGlobalVisibleRect(roleBounds));
                assertEquals(title.getWidth(), titleBounds.width());
                assertEquals(title.getHeight(), titleBounds.height());
                assertEquals(role.getWidth(), roleBounds.width());
                assertEquals(role.getHeight(), roleBounds.height());
                assertFalse("Header title and role action must not overlap", Rect.intersects(titleBounds, roleBounds));
            });
        }
    }

    @Test public void compactActivityNavigationReservesItsMeasuredListClearance() {
        try (ActivityScenario<GlobalSettingsActivity> scenario = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            scenario.onActivity(activity -> {
                for (int width : new int[] {244, 320}) {
                    Configuration configuration = new Configuration(activity.getResources().getConfiguration());
                    configuration.screenWidthDp = width;
                    configuration.smallestScreenWidthDp = width;
                    configuration.fontScale = 2f;
                    Context themed = new ContextThemeWrapper(activity.createConfigurationContext(configuration), R.style.Theme_SubHub);
                    ViewGroup page = (ViewGroup) LayoutInflater.from(themed).inflate(R.layout.activity_dom_activity, null, false);
                    PrimaryHeader.bind(page, 0, R.string.dom_activity_title, 0);
                    int densityWidth = Math.round(width * themed.getResources().getDisplayMetrics().density);
                    int densityHeight = Math.round(720 * themed.getResources().getDisplayMetrics().density);
                    measurePage(page, densityWidth, densityHeight);
                    SubHubNavigation.bind(activity, page, SubHubNavigation.Screen.ACTIVITY);
                    measurePage(page, densityWidth, densityHeight);
                    measurePage(page, densityWidth, densityHeight);
                    assertRailFits(page, 5);
                    View navigation = page.findViewById(R.id.bottom_navigation);
                    android.widget.ListView list = page.findViewById(R.id.dom_activity_list);
                    assertTrue("Activity content must clear every measured navigation row", list.getPaddingBottom()
                            >= navigation.getHeight() + themed.getResources().getDimensionPixelSize(R.dimen.page_content_tail_gap));
                    assertTrue(page.findViewById(R.id.nav_activity).isSelected());
                }
            });
        }
    }

    private static void assertRailFits(View root, int expectedTabs) {
        ViewGroup navigation = root.findViewById(R.id.bottom_navigation);
        int target = navigation.getResources().getDimensionPixelSize(R.dimen.control_min_height);
        int visible = 0, occupiedBottom = 0, singleRowWidth = 0;
        java.util.Set<Integer> rows = new java.util.HashSet<>();
        java.util.List<Rect> targets = new java.util.ArrayList<>();
        View parent = (View) navigation.getParent();
        int margin = navigation.getResources().getDimensionPixelSize(R.dimen.bottom_nav_margin);
        assertEquals("Floating navigation has its intended left margin", margin, navigation.getLeft());
        assertEquals("Floating navigation has its intended right margin", margin, parent.getWidth() - navigation.getRight());
        assertEquals("Floating navigation width stays within its margins", parent.getWidth() - 2 * margin, navigation.getWidth());
        for (int index = 0; index < navigation.getChildCount(); index++) {
            ViewGroup tab = (ViewGroup) navigation.getChildAt(index);
            if (tab.getVisibility() != View.VISIBLE) continue;
            visible++;
            rows.add(tab.getTop());
            occupiedBottom = Math.max(occupiedBottom, tab.getBottom());
            assertTrue("Navigation target too narrow", tab.getWidth() >= target);
            assertTrue("Navigation target too short", tab.getHeight() >= target);
            assertTrue("Navigation target exceeds viewport", tab.getRight() <= navigation.getWidth() - navigation.getPaddingRight());
            assertTrue(tab.getLeft() >= navigation.getPaddingLeft());
            Rect targetBounds = new Rect(tab.getLeft(), tab.getTop(), tab.getRight(), tab.getBottom());
            for (Rect other : targets) assertFalse("Navigation targets must not overlap", Rect.intersects(targetBounds, other));
            targets.add(targetBounds);
            TextView label = (TextView) tab.getChildAt(1);
            assertEquals(View.VISIBLE, label.getVisibility());
            assertCompleteText(label);
            assertEquals("A complete short navigation label must fit without breaking its word", 1, label.getLineCount());
            singleRowWidth += Math.max(target, (int) Math.ceil(android.text.Layout.getDesiredWidth(
                    label.getText(), label.getPaint())) + label.getCompoundPaddingLeft()
                    + label.getCompoundPaddingRight() + tab.getPaddingLeft() + tab.getPaddingRight());
            Rect labelBounds = new Rect(0, 0, label.getWidth(), label.getHeight());
            tab.offsetDescendantRectToMyCoords(label, labelBounds);
            assertTrue("Label must remain inside its target", labelBounds.left >= tab.getPaddingLeft()
                    && labelBounds.right <= tab.getWidth() - tab.getPaddingRight());
            assertEquals(Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11,
                    label.getResources().getDisplayMetrics())), label.getTextSize(), 0.01f);
            if (tab.getId() == R.id.nav_activity
                    && tab.findViewById(R.id.nav_activity_unread).getVisibility() == View.VISIBLE) {
                assertTrue(tab.getContentDescription().toString().contains(label.getText()));
                assertFalse("Unread description must include more than the label",
                        label.getText().toString().contentEquals(tab.getContentDescription()));
            } else assertEquals(label.getText().toString(), tab.getContentDescription().toString());
            assertTrue("Navigation label exceeds its target", label.getBottom() <= tab.getHeight() - tab.getPaddingBottom());
            AccessibilityNodeInfo info = tab.createAccessibilityNodeInfo();
            assertEquals(Button.class.getName(), info.getClassName());
            assertEquals(tab.isSelected(), info.isSelected());
            info.recycle();
        }
        assertEquals(expectedTabs, visible);
        int availableWidth = navigation.getWidth() - navigation.getPaddingLeft() - navigation.getPaddingRight();
        if (singleRowWidth <= availableWidth) assertEquals("Fitting labels retain a single rail", 1, rows.size());
        else {
            assertTrue("Scaled labels need multiple balanced rows", rows.size() > 1);
            int columns = ((android.widget.GridLayout) navigation).getColumnCount();
            assertEquals("Only occupied rows are retained", (expectedTabs + columns - 1) / columns, rows.size());
        }
        assertTrue("Navigation must not retain empty row space", navigation.getHeight()
                <= Math.max(navigation.getMinimumHeight(), occupiedBottom + navigation.getPaddingBottom()) + 1);
    }

    private static void assertVisibleTabOrder(View root, int... expected) {
        ViewGroup navigation = root.findViewById(R.id.bottom_navigation);
        int visible = 0;
        for (int index = 0; index < navigation.getChildCount(); index++) {
            View tab = navigation.getChildAt(index);
            if (tab.getVisibility() != View.VISIBLE) continue;
            assertTrue("Unexpected extra primary destination", visible < expected.length);
            assertEquals("Primary destination order", expected[visible++], tab.getId());
        }
        assertEquals("Complete primary destination inventory", expected.length, visible);
    }

    private static void assertCompleteText(TextView text) {
        assertNotNull(text.getLayout());
        int available = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
        for (int line = 0; line < text.getLineCount(); line++) {
            assertEquals("Text must not be ellipsized", 0, text.getLayout().getEllipsisCount(line));
            assertTrue("Text exceeds its width: " + text.getText(), text.getLayout().getLineMax(line) <= available + 1);
        }
        assertEquals("The complete label must remain laid out", text.length(),
                text.getLayout().getLineEnd(text.getLineCount() - 1));
        assertTrue("Text exceeds its height", text.getLayout().getHeight()
                <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
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
        int[] tabs = {R.id.nav_home, R.id.nav_people, R.id.nav_tasks, R.id.nav_activity, R.id.nav_contract, R.id.nav_stats, R.id.nav_settings};
        int[] icons = {R.id.nav_home_icon, R.id.nav_people_icon, R.id.nav_tasks_icon, R.id.nav_activity_icon, R.id.nav_contract_icon, R.id.nav_stats_icon, R.id.nav_settings_icon};
        int[] labels = {R.id.nav_home_label, R.id.nav_people_label, R.id.nav_tasks_label, R.id.nav_activity_label, R.id.nav_contract_label, R.id.nav_stats_label, R.id.nav_settings_label};
        for (int index = 0; index < tabs.length; index++) {
            LinearLayout tab = activity.findViewById(tabs[index]);
            if (tab.getVisibility() != View.VISIBLE) continue;
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
