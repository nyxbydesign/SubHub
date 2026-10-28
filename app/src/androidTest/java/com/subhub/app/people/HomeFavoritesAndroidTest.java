package com.subhub.app.people;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.subhub.app.MainActivity;
import com.subhub.app.NativeUiActions;
import com.subhub.app.R;
import com.subhub.app.activity.ActivityEntry;
import com.subhub.app.activity.ActivityEvent;
import com.subhub.app.activity.ActivityRepository;
import com.subhub.app.activity.ActivitySources;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class HomeFavoritesAndroidTest {
    @Rule public final com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final com.subhub.app.UiAutomationStateRule automation = new com.subhub.app.UiAutomationStateRule();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Map<String, Map<String, ?>> original = new LinkedHashMap<>();
    private ActivityRepository originalActivity;
    private final AtomicInteger peopleReads = new AtomicInteger(), localReads = new AtomicInteger();
    private final AtomicInteger activityReads = new AtomicInteger(), recentLimit = new AtomicInteger();
    private List<PersonSnapshot> roster;
    private FavoriteSubStore favorites;
    private volatile List<ActivityEntry> fixedEvents;

    @Before public void prepare() {
        assertFalse("Use an inactive, owned UI guest", LocalSessionControls.isActive(context));
        for (String name : java.util.Arrays.asList(FavoriteSubStore.PREFERENCES_NAME, "subhub_service_duration_ui",
                "betablocker_stats", "betablocker_achievements"))
            original.put(name, new LinkedHashMap<>(context.getSharedPreferences(name, 0).getAll()));
        assertTrue(context.getSharedPreferences(FavoriteSubStore.PREFERENCES_NAME, 0).edit().clear().commit());
        assertTrue(ControllerPinManager.setPin(context, "2468"));
        assertTrue(ControllerPinManager.verify(context, "2468"));
        ControllerPinManager.enterDomMode();
        assertTrue(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.BOTH));
        OnboardingState.complete(context);
        favorites = new FavoriteSubStore(context);
        roster = new ArrayList<>(java.util.Arrays.asList(person('a', "Alex", 1), person('b', "Sam", 2)));
        People.installRemoteRepository(new PersonRepository() {
            public List<PersonId> people() {
                List<PersonId> ids = new ArrayList<>();
                for (PersonSnapshot person : roster) ids.add(person.id);
                return ids;
            }
            public PersonSnapshot load(PersonId id) {
                peopleReads.incrementAndGet();
                for (PersonSnapshot person : roster) if (id.equals(person.id)) return person;
                throw new IllegalArgumentException("Missing synthetic person");
            }
        });
        People.installLocalProjection(local -> { localReads.incrementAndGet(); return local; });
        originalActivity = ActivitySources.repository();
        ActivitySources.install(new ActivityRepository() {
            public Page load(String before, int limit) { return new Page(events(limit), null); }
            public Page locate(String key, int limit) { return load(null, limit); }
            public int unreadCount() { activityReads.incrementAndGet(); return 0; }
            public void markRead(List<String> keys) { }
            public List<ActivityEntry> recent(PersonId person, int limit) {
                activityReads.incrementAndGet(); recentLimit.set(limit); return events(limit);
            }
        });
    }

    @After public void restore() {
        if (originalActivity != null) ActivitySources.install(originalActivity);
        for (Map.Entry<String, Map<String, ?>> saved : original.entrySet())
            SharedPreferenceTestRestore.restore(context.getSharedPreferences(saved.getKey(), 0), saved.getValue());
    }

    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    public void changedFavoriteAndActivityKeepOtherHomeSectionsAndScrolledPosition() throws Exception {
        assertTrue(favorites.toggle(roster.get(0)));
        fixedEvents = events(5);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(home())) {
            String favorite = "home_favorite:" + roster.get(0).id.value();
            String recent = "home_recent_person:" + roster.get(1).id.value();
            awaitTag(scenario, favorite); awaitTag(scenario, "activity_preview_entry:fact-0");
            MainActivity home = resumed(MainActivity.class);
            com.subhub.app.UiRefreshWitness.frame(home);
            AtomicReference<View> summary = new AtomicReference<>(), recentRow = new AtomicReference<>(), preview = new AtomicReference<>();
            AtomicReference<View> event = new AtomicReference<>(), favoriteView = new AtomicReference<>();
            java.util.concurrent.atomic.AtomicInteger position = new java.util.concurrent.atomic.AtomicInteger();
            scenario.onActivity(activity -> {
                View root = activity.findViewById(R.id.home_dom_overview);
                summary.set(root.findViewWithTag("home_devices")); recentRow.set(root.findViewWithTag(recent));
                preview.set(root.findViewWithTag("activity_preview:all")); event.set(root.findViewWithTag("activity_preview_entry:fact-0"));
                favoriteView.set(root.findViewWithTag(favorite));
                com.subhub.app.UiRefreshWitness.scroll(root).scrollTo(0, PersonUi.dp(activity, 220));
            });
            com.subhub.app.UiRefreshWitness.frame(home);
            scenario.onActivity(activity -> position.set(com.subhub.app.UiRefreshWitness.scroll(activity.findViewById(R.id.home_dom_overview)).getScrollY()));
            assertTrue("Fixture must be scrolled", position.get() > 0);
            PersonSnapshot first = roster.get(0);
            PersonSnapshot changed = new PersonSnapshot(first.id, first.name, first.device, first.contractName,
                    first.serviceLabel, first.connection, first.freshness, first.lastSyncedAt, first.capabilities,
                    first.sections, first.allowances, first.currency, first.protectedSeconds + 120,
                    first.activityEvents + 1, first.tributeCents, first.sessions, first.serviceActive,
                    first.sync, first.sessionIdentity, first.additionOrder);
            roster = new ArrayList<>(java.util.Arrays.asList(changed, roster.get(1)));
            refreshHome(scenario);
            com.subhub.app.UiRefreshWitness.capture(home, "home-dom-changed-statistics");
            scenario.onActivity(activity -> {
                View root = activity.findViewById(R.id.home_dom_overview);
                assertNotSame(favoriteView.get(), root.findViewWithTag(favorite));
                assertTrue(contains(root.findViewWithTag("home_favorite_stats:" + first.id.value()),
                        com.subhub.app.stats.StatsSnapshot.formatDuration(changed.protectedSeconds) + " · "
                                + changed.activityEvents + " " + activity.getString(R.string.role_stat_activity)));
                assertSame(summary.get(), root.findViewWithTag("home_devices"));
                assertSame(recentRow.get(), root.findViewWithTag(recent));
                assertSame(preview.get(), root.findViewWithTag("activity_preview:all"));
                assertSame(event.get(), root.findViewWithTag("activity_preview_entry:fact-0"));
                assertEquals(position.get(), com.subhub.app.UiRefreshWitness.scroll(root).getScrollY());
            });
            ActivityEntry previous = fixedEvents.get(0);
            ActivityEntry updated = new ActivityEntry(previous.key, previous.personId, previous.personName, previous.device,
                    previous.event, previous.receivedAt, true, "Settings");
            List<ActivityEntry> next = new ArrayList<>(fixedEvents); next.set(0, updated); fixedEvents = next;
            ActivitySources.changed();
            long deadline = SystemClock.uptimeMillis() + 5000;
            AtomicReference<Boolean> rendered = new AtomicReference<>(false);
            do {
                scenario.onActivity(activity -> rendered.set(contains(activity.findViewById(R.id.home_dom_overview)
                        .findViewWithTag("activity_preview_entry:fact-0"), com.subhub.app.activity.ActivityText.title(activity, updated))));
                if (!rendered.get()) SystemClock.sleep(20);
            } while (!rendered.get() && SystemClock.uptimeMillis() < deadline);
            com.subhub.app.UiRefreshWitness.capture(home, "home-dom-changed-activity");
            assertTrue("Changed Activity title did not arrive", rendered.get());
            scenario.onActivity(activity -> {
                View root = activity.findViewById(R.id.home_dom_overview);
                assertSame(event.get(), root.findViewWithTag("activity_preview_entry:fact-0"));
                assertSame(summary.get(), root.findViewWithTag("home_devices"));
                assertEquals(position.get(), com.subhub.app.UiRefreshWitness.scroll(root).getScrollY());
            });
        }
    }

    private void refreshHome(ActivityScenario<MainActivity> scenario) throws Exception {
        scenario.onActivity(activity -> {
            try { java.lang.reflect.Field field = MainActivity.class.getDeclaredField("roleHome"); field.setAccessible(true);
                ((RoleHomePresenter) field.get(activity)).refresh(true);
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
        long deadline = SystemClock.uptimeMillis() + 5000;
        AtomicReference<Boolean> pending = new AtomicReference<>(true);
        do {
            scenario.onActivity(activity -> {
                try { java.lang.reflect.Field field = MainActivity.class.getDeclaredField("roleHome"); field.setAccessible(true);
                    java.lang.reflect.Field data = RoleHomePresenter.class.getDeclaredField("data"); data.setAccessible(true);
                    pending.set(((com.subhub.app.util.AsyncUiScope) data.get(field.get(activity))).isPending("role-home"));
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            if (pending.get()) SystemClock.sleep(20);
        } while (pending.get() && SystemClock.uptimeMillis() < deadline);
        assertFalse("Home read did not finish", pending.get());
    }

    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    public void homeSummaryShowsOnlineAndOfflineDevices() throws Exception {
        roster = new ArrayList<>(java.util.Arrays.asList(
                statusPerson(person('a', "Alex", 1), PersonSnapshot.Connection.CONNECTED,
                        true, PersonSnapshot.Freshness.CURRENT, System.currentTimeMillis()),
                statusPerson(person('b', "Sam", 2), PersonSnapshot.Connection.OFFLINE,
                        true, PersonSnapshot.Freshness.STALE, System.currentTimeMillis() - 7_200_000L)));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(home())) {
            awaitTag(scenario, "home_devices");
            scenario.onActivity(activity -> {
                View root = activity.findViewById(R.id.home_dom_overview);
                View devices = root.findViewWithTag("home_devices");
                View sessions = root.findViewWithTag("home_in_session");
                assertNotNull(sessions);
                assertTrue(devices.isClickable());
                assertTrue(sessions.isClickable());
                assertTrue(contains(devices, "2 online · 1 offline"));
                assertTrue(contains(sessions, activity.getString(R.string.home_in_session)));
                assertTrue("Only current active sessions count", contains(sessions, "1 / 3 Subs"));
                assertTrue("Stale session is explicitly unknown", contains(sessions, "1 unknown"));
                TextView lastOnline = root.findViewWithTag("home_recent_connection:" + roster.get(1).id.value());
                assertNotNull(lastOnline);
                assertTrue(lastOnline.getText().toString().contains("Last online"));
                assertFalse(lastOnline.isSingleLine());
            });
            MainActivity home = resumed(MainActivity.class);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    home.findViewById(R.id.primary_header).setTag("home_header_capture"));
            ProfileStatsActivityAndroidTest.captureNativeProfile(home, "home-header", "home_header_capture");
            if (home.getResources().getConfiguration().fontScale < 1.3f) {
                ProfileStatsActivityAndroidTest.captureNativeProfile(home, "home-presence", null);
            } else {
                ProfileStatsActivityAndroidTest.captureNativeProfile(home, "home-session", "home_in_session");
                ProfileStatsActivityAndroidTest.captureNativeProfile(home, "home-devices", "home_devices");
                ProfileStatsActivityAndroidTest.captureNativeProfile(home, "home-last-online",
                        "home_recent_connection:" + roster.get(1).id.value());
            }
        }
    }

    @Test public void homeCountsLocalActiveSubAndDeduplicatesRemoteIdentity() {
        PersonSnapshot remote = statusPerson(person('a', "Alex", 1), PersonSnapshot.Connection.CONNECTED,
                true, PersonSnapshot.Freshness.CURRENT, System.currentTimeMillis());
        PersonId nextGeneration = new PersonId("remote_" + String.join("", Collections.nCopies(64, "a")) + "_new_generation");
        roster = new ArrayList<>(java.util.Arrays.asList(remote, copy(remote, nextGeneration, remote.connection)));
        com.subhub.app.appmode.AppModeManager appMode = new com.subhub.app.appmode.AppModeManager(context);
        appMode.setArmed(true);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(home())) {
            awaitTag(scenario, "home_devices");
            scenario.onActivity(activity -> {
                View root = activity.findViewById(R.id.home_dom_overview);
                assertTrue(contains(root.findViewWithTag("home_in_session"), "2 / 2 Subs"));
                assertTrue(contains(root.findViewWithTag("home_devices"), "2 online · 0 offline"));
                View tab = activity.findViewById(R.id.nav_people);
                assertEquals("Subs", tab.getContentDescription().toString());
                assertEquals("Subs", ((TextView) activity.findViewById(R.id.nav_people_label)).getText().toString());
            });
        } finally { appMode.setArmed(false); }
    }
    @Test public void homePreservesEmptyCountsWithoutLocalOrRemoteSubs() {
        assertTrue(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.DOM_ONLY));
        roster.clear();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(home())) {
            awaitTag(scenario, "home_devices");
            scenario.onActivity(activity -> {
                View root = activity.findViewById(R.id.home_dom_overview);
                assertTrue(contains(root.findViewWithTag("home_in_session"), "0 / 0 Subs"));
                assertTrue(contains(root.findViewWithTag("home_devices"), "0 online · 0 offline"));
            });
        }
    }
    @Test public void longPressFavoritePersistsAndHomeHasOnlyIdentityNavigation() {
        PersonSnapshot favorite = roster.get(1);
        try (ActivityScenario<PeopleActivity> scenario = ActivityScenario.launch(PeopleActivity.class)) {
            awaitTag(scenario, "person:" + favorite.id.value());
            onView(withTagValue(is("person:" + favorite.id.value())))
                    .perform(NativeUiActions.revealAboveNavigation(), longClick());
            awaitTag(scenario, "people_favorite:" + favorite.id.value());
            scenario.recreate();
            awaitTag(scenario, "people_favorite:" + favorite.id.value());
        }
        assertEquals(FavoriteSubStore.identity(favorite.id), favorites.identity());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(home())) {
            awaitTag(scenario, "home_favorite:" + favorite.id.value());
            scenario.onActivity(activity -> {
                View root = activity.findViewById(R.id.home_dom_overview);
                View header = root.findViewWithTag("home_favorite_identity:" + favorite.id.value());
                assertTrue(header.isClickable());
                for (String key : java.util.Arrays.asList("session", "stats")) {
                    View row = root.findViewWithTag("home_favorite_" + key + ":" + favorite.id.value());
                    assertNotNull(row);
                    assertFalse(row.isClickable());
                    assertFalse(contains(row, "›"));
                }
                assertFalse(contains(root, favorite.contractName));
                assertNoQuickControls(root);
            });
            onView(withTagValue(is("home_favorite_identity:" + favorite.id.value())))
                    .perform(NativeUiActions.revealAboveNavigation(), click());
            PersonActivity profile = resumed(PersonActivity.class);
            assertEquals(favorite.id, PersonRoutes.requireId(profile.getIntent()));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(profile::finish);
        }
    }

    @Test public void additionsUseTrueOrderAndRevokedFavoriteNeverFallsBack() {
        PersonSnapshot revoked = withConnection(person('f', "Former favorite", 99), PersonSnapshot.Connection.REVOKED);
        assertTrue(favorites.toggle(person('f', "Former favorite", 99)));
        PersonSnapshot newest = person('b', "Abel", 8), middle = person('c', "Cara", 6), oldest = person('d', "Zed", 3);
        roster = new ArrayList<>(java.util.Arrays.asList(oldest, person('e', "Unknown order", 0), revoked, middle, newest));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(home())) {
            awaitTag(scenario, "home_choose_favorite");
            awaitTag(scenario, "activity_preview_entry:fact-4");
            scenario.onActivity(activity -> {
                ViewGroup composition = activity.findViewById(R.id.home_dom_overview).findViewWithTag("home_people_composition");
                List<String> actual = tags(composition, "home_recent_person:");
                assertEquals(java.util.Arrays.asList("home_recent_person:" + newest.id.value(), "home_recent_person:" + middle.id.value(),
                        "home_recent_person:" + oldest.id.value()), actual);
                assertEquals(5, tags(activity.findViewById(R.id.home_dom_overview), "activity_preview_entry:").size());
                assertNull(composition.findViewWithTag("home_favorite:" + revoked.id.value()));
                assertNoQuickControls(composition);
            });
            assertEquals(5, recentLimit.get());
            onView(withTagValue(is("home_choose_favorite")))
                    .perform(NativeUiActions.revealAboveNavigation(), click());
            PeopleActivity people = resumed(PeopleActivity.class);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(people::finish);
        }
    }

    @Test public void favoriteTracksStablePeerAcrossGenerationAndRejectsAmbiguity() {
        PersonSnapshot first = person('a', "Alex", 1);
        assertTrue(favorites.toggle(first));
        PersonSnapshot next = copy(first, new PersonId("remote_" + String.join("", Collections.nCopies(64, "a")) + "_new-generation"),
                PersonSnapshot.Connection.OFFLINE);
        assertSame(next, favorites.resolve(java.util.Arrays.asList(next)));
        assertNull(favorites.resolve(java.util.Arrays.asList(person('b', "Alex", 9))));
        assertNull(favorites.resolve(java.util.Arrays.asList(withConnection(first, PersonSnapshot.Connection.REVOKED))));
        assertNull(favorites.resolve(java.util.Arrays.asList(first, next)));
        assertTrue(favorites.toggle(next));
        assertEquals("", favorites.identity());
    }

    @Test public void domOnlyLockKeepsItsWorkspaceAndStopsPeopleAndActivityReads() {
        assertTrue(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.DOM_ONLY));
        assertTrue(favorites.toggle(roster.get(0)));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(home())) {
            awaitTag(scenario, "home_favorite:" + roster.get(0).id.value());
            awaitTag(scenario, "activity_preview_entry:fact-4");
            assertEquals(0, localReads.get());
            onView(withId(R.id.button_edit_lock)).perform(click());
            awaitTag(scenario, "home_dom_unlock");
            int beforePeople = peopleReads.get(), beforeActivity = activityReads.get();
            SystemClock.sleep(1100);
            assertEquals(beforePeople, peopleReads.get());
            assertEquals(beforeActivity, activityReads.get());
            scenario.onActivity(activity -> {
                assertFalse(ControllerPinManager.isDomModeActive());
                for (int id : new int[] {R.id.nav_people, R.id.nav_activity, R.id.nav_stats}) assertTrue(activity.findViewById(id).isShown());
                for (int id : new int[] {R.id.nav_contract, R.id.home_service_widget, R.id.global_notice_host})
                    assertFalse(activity.findViewById(id).isShown());
                assertNull(activity.findViewById(R.id.home_dom_overview).findViewWithTag("activity_preview:all"));
                activity.findViewById(R.id.button_protection).performClick();
                assertFalse(new com.subhub.app.appmode.AppModeManager(context).isArmed());
            });
            scenario.recreate();
            awaitTag(scenario, "home_dom_unlock");
            assertEquals(beforePeople, peopleReads.get());
            assertEquals(0, localReads.get());
        }
    }

    @Test public void domOnlyRosterExcludesThisDeviceAndBothPreservesIt() throws Exception {
        assertTrue(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.DOM_ONLY));
        assertFalse(People.repository(context).people().contains(PersonId.LOCAL));
        assertTrue(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.BOTH));
        assertTrue(People.repository(context).people().contains(PersonId.LOCAL));
    }

    @Test public void subOnlyTemporaryLocalAuthenticationNeverBecomesRemoteManagement() {
        assertTrue(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.SUB_ONLY));
        assertTrue(ControllerPinManager.isDomModeActive());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(home())) {
            awaitTag(scenario, "achievement_preview");
            scenario.onActivity(activity -> {
                assertTrue("Device capabilities do not erase a local credential session", ControllerPinManager.isDomModeActive());
                assertFalse(activity.findViewById(R.id.home_dom_overview).isShown());
                assertFalse(activity.findViewById(R.id.nav_activity).isShown());
                assertFalse(activity.findViewById(R.id.nav_people).isShown());
                assertTrue(activity.findViewById(R.id.nav_contract).isShown());
                assertNull(activity.findViewById(android.R.id.content).findViewWithTag("home_choose_favorite"));
            });
            assertEquals(0, peopleReads.get());
            assertEquals(0, activityReads.get());
        }
    }

    @Test public void homeFitsActualRequestedFontScaleAndViewport() throws Exception {
        roster = new ArrayList<>(roster);
        roster.add(person('c', "Cara", 3));
        roster.add(person('d', "Dee", 4));
        assertTrue(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.DOM_ONLY));
        assertTrue(favorites.toggle(roster.get(0)));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(home())) {
            awaitTag(scenario, "home_favorite:" + roster.get(0).id.value());
            awaitTag(scenario, "activity_preview_entry:fact-4");
            scenario.onActivity(activity -> {
                android.os.Bundle args = InstrumentationRegistry.getArguments();
                String scale = args.getString("shell_expected_font_scale"), width = args.getString("shell_expected_width_dp");
                if (scale != null) assertEquals(Float.parseFloat(scale), activity.getResources().getConfiguration().fontScale, 0.01f);
                if (width != null) assertEquals(Integer.parseInt(width), activity.getResources().getConfiguration().screenWidthDp);
                ViewGroup composition = activity.findViewById(R.id.home_dom_overview).findViewWithTag("home_people_composition");
                assertEquals(1, tags(composition, "home_favorite:").size());
                assertEquals(java.util.Arrays.asList("home_recent_person:" + roster.get(3).id.value(),
                        "home_recent_person:" + roster.get(2).id.value(), "home_recent_person:" + roster.get(1).id.value()),
                        tags(composition, "home_recent_person:"));
                TextView name = composition.findViewWithTag("home_favorite_name:" + roster.get(0).id.value());
                assertEquals(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                        22, name.getResources().getDisplayMetrics()), name.getTextSize(), 0.01f);
                assertNoQuickControls(composition);
                assertTextFits(composition, false);
            });
            AtomicReference<MainActivity> page = new AtomicReference<>();
            scenario.onActivity(page::set);
            captureHome(page.get(), "top", null);
            captureHome(page.get(), "recent", "home_recent_person:" + roster.get(3).id.value());
            captureHome(page.get(), "activity", "activity_preview:all");
        }
    }

    private void captureHome(Activity activity, String section, String targetTag) throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 29) return;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View content = activity.findViewById(R.id.page_content);
            android.widget.ScrollView scroll = (android.widget.ScrollView) content.getParent();
            if (targetTag == null) scroll.scrollTo(0, 0);
            else {
                View target = content.findViewWithTag(targetTag);
                assertNotNull(target);
                android.graphics.Rect bounds = new android.graphics.Rect();
                target.getDrawingRect(bounds);
                scroll.offsetDescendantRectToMyCoords(target, bounds);
                scroll.scrollTo(0, Math.max(0, bounds.top - Math.round(12 * activity.getResources().getDisplayMetrics().density)));
            }
        });
        String run = InstrumentationRegistry.getArguments().getString("evidence_run", "candidate-1");
        if (!run.matches("[A-Za-z0-9_-]+")) throw new AssertionError("Invalid evidence namespace");
        java.io.File folder = new java.io.File(context.getFilesDir(), "ui-v3/" + run);
        assertTrue(folder.isDirectory() || folder.mkdirs());
        String prefix = "home-favorites-dom-only-" + section + "-"
                + activity.getResources().getConfiguration().fontScale + "-"
                + activity.getResources().getConfiguration().screenWidthDp;
        android.graphics.Bitmap previous = null;
        long deadline = SystemClock.uptimeMillis() + 7000, stableSince = 0;
        try {
            do {
                java.util.concurrent.CountDownLatch committed = new java.util.concurrent.CountDownLatch(1);
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    View root = activity.getWindow().getDecorView();
                    root.getViewTreeObserver().registerFrameCommitCallback(committed::countDown);
                    root.invalidate();
                });
                assertTrue("No hardware frame committed", committed.await(2, java.util.concurrent.TimeUnit.SECONDS));
                android.graphics.Bitmap current = InstrumentationRegistry.getInstrumentation()
                        .getUiAutomation(android.app.UiAutomation.FLAG_DONT_USE_ACCESSIBILITY).takeScreenshot();
                assertNotNull(current);
                long now = SystemClock.uptimeMillis();
                boolean same = previous != null && previous.sameAs(current);
                if (!same) stableSince = now;
                if (previous != null) previous.recycle();
                previous = current;
                AtomicReference<Boolean> ready = new AtomicReference<>(false);
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    View root = activity.getWindow().getDecorView();
                    View snackbar = root.findViewById(com.google.android.material.R.id.snackbar_text);
                    View navigation = root.findViewById(R.id.bottom_navigation);
                    ready.set(activity.hasWindowFocus() && root.isLaidOut() && !root.isLayoutRequested()
                            && !containsVisible(root, activity.getString(R.string.role_loading))
                            && (snackbar == null || !snackbar.isShown())
                            && navigation.getWidth() > 0 && navigation.getHeight() > 0);
                });
                if (!ready.get()) stableSince = now;
                if (same && ready.get() && now - stableSince >= 200) {
                    try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(folder, prefix + ".png"))) {
                        assertTrue(previous.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
                    }
                    return;
                }
            } while (SystemClock.uptimeMillis() < deadline);
            throw new AssertionError("Synthetic Home compositor did not settle");
        } catch (Exception | AssertionError failure) {
            try {
                if (previous == null) previous = InstrumentationRegistry.getInstrumentation()
                        .getUiAutomation(android.app.UiAutomation.FLAG_DONT_USE_ACCESSIBILITY).takeScreenshot();
                if (previous != null) try (java.io.FileOutputStream output = new java.io.FileOutputStream(
                        new java.io.File(folder, prefix + "-failure-" + SystemClock.uptimeMillis() + ".png"))) {
                    assertTrue(previous.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
                }
            } catch (Exception | AssertionError exportFailure) { failure.addSuppressed(exportFailure); }
            throw failure;
        } finally { if (previous != null) previous.recycle(); }
    }

    private static boolean containsVisible(View view, String text) {
        if (!view.isShown()) return false;
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (containsVisible(((ViewGroup) view).getChildAt(i), text)) return true;
        return false;
    }

    private List<ActivityEntry> events(int limit) {
        if (fixedEvents != null) return new ArrayList<>(fixedEvents.subList(0, Math.min(limit, fixedEvents.size())));
        List<ActivityEntry> events = new ArrayList<>();
        PersonSnapshot person = roster.get(0);
        for (int index = 0; index < Math.min(6, limit); index++) events.add(new ActivityEntry("fact-" + index,
                person.id, person.name, person.device, new ActivityEvent("event-" + index, ActivityEvent.Kind.APP_OPENED,
                System.currentTimeMillis() - index * 1000L, "com.android.chrome", 0, "", "", 0),
                System.currentTimeMillis(), false, "Chrome"));
        return events;
    }

    private Intent home() { return new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN)
            .putExtra(MainActivity.EXTRA_SUPPRESS_PERMISSION_READINESS, true); }

    private static PersonSnapshot person(char peer, String name, long order) {
        return new PersonSnapshot(new PersonId("remote_" + String.join("", Collections.nCopies(64, Character.toString(peer))) + "_generation"),
                name, "Synthetic phone", "Contract shortcut must not appear", "Service inactive", PersonSnapshot.Connection.CONNECTED,
                PersonSnapshot.Freshness.CURRENT, 1000 - order, EnumSet.of(PersonSnapshot.Capability.VIEW_CONTRACT,
                        PersonSnapshot.Capability.EDIT_CONTRACT, PersonSnapshot.Capability.VIEW_STATS),
                java.util.Arrays.asList(new PersonSnapshot.Section("censor", "Censor", "Enabled", "")), Collections.emptyList(),
                "EUR", 3600, 7, 0, 1, false, new PersonSnapshot.SyncState(PersonSnapshot.Freshness.CURRENT,
                        PersonSnapshot.Freshness.CURRENT, PersonSnapshot.Freshness.CURRENT, "", ""), "", order);
    }

    private static PersonSnapshot statusPerson(PersonSnapshot person, PersonSnapshot.Connection connection,
            boolean active, PersonSnapshot.Freshness runtime, long lastContact) {
        return new PersonSnapshot(person.id, person.name, person.device, person.contractName, person.serviceLabel,
                connection, person.freshness, lastContact, person.capabilities, person.sections, person.allowances,
                person.currency, person.protectedSeconds, person.activityEvents, person.tributeCents, person.sessions,
                active, new PersonSnapshot.SyncState(person.sync.contract, person.sync.stats, runtime,
                        person.sync.desiredRevision, person.sync.appliedRevision), person.sessionIdentity, person.additionOrder);
    }

    private static PersonSnapshot withConnection(PersonSnapshot person, PersonSnapshot.Connection connection) {
        return copy(person, person.id, connection);
    }
    private static PersonSnapshot copy(PersonSnapshot person, PersonId id, PersonSnapshot.Connection connection) {
        return new PersonSnapshot(id, person.name, person.device, person.contractName, person.serviceLabel, connection,
                person.freshness, person.lastSyncedAt, person.capabilities, person.sections, person.allowances, person.currency,
                person.protectedSeconds, person.activityEvents, person.tributeCents, person.sessions, person.serviceActive,
                person.sync, person.sessionIdentity, person.additionOrder);
    }

    private static <T extends Activity> void awaitTag(ActivityScenario<T> scenario, String tag) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        AtomicReference<Boolean> found = new AtomicReference<>(false);
        do {
            scenario.onActivity(activity -> found.set(activity.findViewById(android.R.id.content).findViewWithTag(tag) != null));
            if (found.get()) return;
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Missing native Home tag " + tag);
    }

    private static <T extends Activity> T resumed(Class<T> type) {
        AtomicReference<T> value = new AtomicReference<>();
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                    if (type.isInstance(activity)) value.set(type.cast(activity));
            });
            if (value.get() != null) return value.get();
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("No resumed " + type.getSimpleName());
    }

    private static List<String> tags(View root, String prefix) {
        List<String> result = new ArrayList<>();
        if (root.getTag() instanceof String && ((String) root.getTag()).startsWith(prefix)) result.add((String) root.getTag());
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++)
            result.addAll(tags(((ViewGroup) root).getChildAt(i), prefix));
        return result;
    }

    private static void assertNoQuickControls(View root) {
        assertTrue(tags(root, "home_person_controls:").isEmpty());
        assertTrue(tags(root, "home_person_edit:").isEmpty());
        assertTrue(tags(root, "home_person_choice:").isEmpty());
        assertNull(root.findViewWithTag("home_status_totals"));
    }

    private static boolean contains(View root, String value) {
        if (root instanceof TextView && value.contentEquals(((TextView) root).getText())) return true;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++)
            if (contains(((ViewGroup) root).getChildAt(i), value)) return true;
        return false;
    }

    private static void assertTextFits(View view, boolean compact) {
        if (view.getTag() instanceof String && ((String) view.getTag()).startsWith("home_recent_person:")) compact = true;
        if (view.getTag() instanceof String && ((String) view.getTag()).startsWith("home_recent_connection:")) compact = false;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            assertNotNull(text.getLayout());
            int available = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
            if (compact) assertEquals(1, text.getLineCount());
            for (int line = 0; line < text.getLineCount(); line++) {
                if (!compact) assertEquals(0, text.getLayout().getEllipsisCount(line));
                assertTrue(text.getLayout().getLineMax(line) <= available + 1);
            }
            assertTrue(text.getLayout().getHeight() <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            assertTextFits(((ViewGroup) view).getChildAt(i), compact);
    }
}
