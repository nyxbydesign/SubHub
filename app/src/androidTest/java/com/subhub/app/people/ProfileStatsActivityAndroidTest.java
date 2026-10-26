package com.subhub.app.people;

import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static org.hamcrest.Matchers.is;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.subhub.app.R;
import com.subhub.app.activity.ActivityEntry;
import com.subhub.app.activity.ActivityEvent;
import com.subhub.app.activity.ActivityRepository;
import com.subhub.app.activity.ActivitySources;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.commitment.CommitmentManager;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import com.subhub.app.stats.StatsActivity;
import com.subhub.app.stats.StatsRepository;
import com.subhub.app.util.AsyncUiScope;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ProfileStatsActivityAndroidTest {
    @Rule public final com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final com.subhub.app.UiAutomationStateRule automation = new com.subhub.app.UiAutomationStateRule();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Map<String, Map<String, ?>> original = new LinkedHashMap<>();
    private ActivityRepository prior;
    private final Source source = new Source();

    @Before public void prepare() throws Exception {
        for (String name : List.of(SettingsRepository.PREFERENCES_NAME, "subhub_service_duration_ui",
                "subhub_onboarding", "subhub_app_timer_usage", "subhub_penance", StatsRepository.PREFS_NAME)) {
            original.put(name, new LinkedHashMap<>(context.getSharedPreferences(name, 0).getAll()));
        }
        new SettingsRepository(context).preferences().edit()
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.BOTH.name())
                .putBoolean(AppModeManager.KEY_ARMED, false).commit();
        CommitmentManager.emergencyRelease(context);
        new StatsRepository(context).endSession();
        OnboardingState.complete(context);
        ControllerPinManager.enterDomMode();
        prior = ActivitySources.repository();
        ActivitySources.install(source);
        Set<String> apps = Set.of("com.android.chrome", "com.android.settings");
        for (String app : apps) context.getPackageManager().getApplicationInfo(app, 0);
        AppModeManager mode = new AppModeManager(context);
        mode.setAllAppsEnabled(false);
        mode.saveIncludedPackages(apps);
        AppTimerManager timers = new AppTimerManager(context);
        timers.saveSettings(true, 30, false, 120);
        timers.saveAllowances(apps, Map.of("com.android.chrome", 20, "com.android.settings", 45));
        assertEquals(20, timers.allowanceMinutes("com.android.chrome"));
        assertEquals(45, timers.allowanceMinutes("com.android.settings"));
    }

    @After public void restore() {
        source.release.countDown();
        CommitmentManager.emergencyRelease(context);
        if (prior != null) ActivitySources.install(prior);
        for (Map.Entry<String, Map<String, ?>> saved : original.entrySet()) {
            SharedPreferenceTestRestore.restore(context.getSharedPreferences(saved.getKey(), 0), saved.getValue());
        }
    }

    @Test public void inlineDurationChangesFutureSelectionWithoutChangingTheActiveSession() throws Exception {
        assertTrue(CommitmentManager.start(context, 3_600_000L));
        long started = CommitmentManager.startedAtMillis(context);
        try (ActivityScenario<PersonActivity> page = ActivityScenario.launch(profile(PersonId.LOCAL))) {
            PersonActivity activity = resumed(PersonActivity.class);
            await(() -> tag(activity, "person_duration:local") != null && tag(activity, "person_current_countdown") != null);
            assertNotNull(activity.findViewById(R.id.person_session_action));
            onView(withId(R.id.commitment_timer_24h)).perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), click());
            assertEquals(86_400_000L, context.getSharedPreferences("subhub_service_duration_ui", 0).getLong("duration", 0));
            assertEquals(3_600_000L, CommitmentManager.originalDurationMillis(context));
            assertEquals(started, CommitmentManager.startedAtMillis(context));
            assertTrue(CommitmentManager.isActive(context));
            AtomicReference<View> stale = new AtomicReference<>();
            page.onActivity(value -> stale.set(value.findViewById(R.id.commitment_timer_1h)));
            ControllerPinManager.enterSubMode();
            onMain(() -> stale.get().performClick());
            assertEquals(86_400_000L, context.getSharedPreferences("subhub_service_duration_ui", 0).getLong("duration", 0));
            assertEquals(started, CommitmentManager.startedAtMillis(context));
        }
    }

    @Test public void profileOpensLocalStatsDirectlyWithTotalsAllowancesAndHistoryOnOnePage() throws Exception {
        long start = System.currentTimeMillis() - 600000;
        assertTrue(context.getSharedPreferences(StatsRepository.PREFS_NAME, 0).edit()
                .putString("session_history", "2," + start + ",90,4,120000,1,2,123,4,5,0").commit());
        List<StatsRepository.SessionEntry> seededHistory = new StatsRepository(context).getSessionHistory();
        assertEquals(1, seededHistory.size());
        assertEquals(start, seededHistory.get(0).getStartMillis());
        assertEquals(90L, seededHistory.get(0).getDurationSeconds());
        assertEquals(123L, seededHistory.get(0).getTributeCents());
        try (ActivityScenario<PersonActivity> page = ActivityScenario.launch(profile(PersonId.LOCAL))) {
            PersonActivity profile = resumed(PersonActivity.class);
            await(() -> tag(profile, "person_tab:stats") != null);
            onView(withTagValue(is("person_tab:stats"))).perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), click());
            StatsActivity stats = resumed(StatsActivity.class);
            await(() -> stats.findViewById(R.id.profile_stats_allowances) != null
                    && text(stats.findViewById(R.id.profile_stats_allowances)).contains("Chrome"));
            onMain(() -> {
                assertEquals(PersonId.LOCAL, PersonRoutes.requireId(stats.getIntent()));
                assertNotNull(stats.findViewById(R.id.stats_lifetime_summary));
                assertNotNull(stats.findViewById(R.id.daily_statistics_panel));
                assertNotNull(tag(stats, "stats_session_history"));
                assertNull(stats.findViewById(R.id.stats_totals_toggle));
                assertEquals(View.VISIBLE, stats.findViewById(R.id.stats_totals_details).getVisibility());
                String allowances = text(stats.findViewById(R.id.profile_stats_allowances));
                assertTrue(allowances.contains(context.getString(R.string.role_allowance_usage, 0, 20)));
                assertTrue(allowances.contains(context.getString(R.string.role_allowance_usage, 0, 45)));
                String history = text(tag(stats, "stats_session_history"));
                assertTrue(history.contains(context.getString(R.string.stats_assessed)));
                assertTrue(history.contains(com.subhub.app.penance.WalletCurrency.format("EUR", 123)));
            });
            onMain(stats::finish);
        }
    }

    @Test public void aDomOnlyModeChangeRejectsTheAlreadyDeliveredDurationListener() throws Exception {
        assertTrue(context.getSharedPreferences("subhub_service_duration_ui", 0).edit().putLong("duration", 86_400_000L).commit());
        try (ActivityScenario<PersonActivity> page = ActivityScenario.launch(profile(PersonId.LOCAL))) {
            PersonActivity profile = resumed(PersonActivity.class);
            await(() -> tag(profile, "person_duration:local") != null);
            AtomicReference<View> stale = new AtomicReference<>();
            page.onActivity(activity -> stale.set(activity.findViewById(R.id.commitment_timer_1h)));
            assertTrue(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.DOM_ONLY));
            assertTrue(ControllerPinManager.isDomModeActive());
            onMain(() -> stale.get().performClick());
            assertEquals(86_400_000L, context.getSharedPreferences("subhub_service_duration_ui", 0).getLong("duration", 0));
        }
    }

    @Test public void oldStatsSectionRedirectsOnceAndRecreationReturnsToProfile() throws Exception {
        try (ActivityScenario<PersonActivity> page = ActivityScenario.launch(
                profile(PersonId.LOCAL).putExtra("person_section", "stats"))) {
            StatsActivity stats = resumed(StatsActivity.class);
            onMain(stats::finish);
            PersonActivity profile = resumed(PersonActivity.class);
            await(() -> tag(profile, "person_tab:stats") != null);
            page.recreate();
            PersonActivity recreated = resumed(PersonActivity.class);
            await(() -> tag(recreated, "person_duration:local") != null);
            assertNotNull(tag(recreated, "person_tab:stats"));
        }
    }

    @Test public void remoteDurationAndStatsStayBoundToTheSelectedPerson() throws Exception {
        PersonId peer = new PersonId("peer:profile-duration-fixture");
        PersonSnapshot snapshot = remote(peer);
        People.installRemoteRepository(new PersonRepository() {
            public List<PersonId> people() { return List.of(peer); }
            public PersonSnapshot load(PersonId id) { assertEquals(peer, id); return snapshot; }
        });
        AtomicReference<PersonId> duration = new AtomicReference<>(), stats = new AtomicReference<>();
        People.installConnectionUi(new ConnectionUi() {
            public void openPairing(Activity activity) { }
            public void openConnection(Activity activity, PersonId id) { }
            public void bindSessionDuration(Activity activity, LinearLayout parent, PersonSnapshot selected) {
                duration.set(selected.id);
                PersonUi.text(parent, "Selected remote duration", false).setTag("remote_duration_fixture");
            }
            public void openStats(Activity activity, PersonId id) { stats.set(id); }
        });
        Map<String, ?> before = new LinkedHashMap<>(context.getSharedPreferences("subhub_service_duration_ui", 0).getAll());
        try (ActivityScenario<PersonActivity> page = ActivityScenario.launch(profile(peer))) {
            PersonActivity profile = resumed(PersonActivity.class);
            await(() -> tag(profile, "remote_duration_fixture") != null);
            assertEquals(peer, duration.get());
            onMain(() -> assertNull(profile.findViewById(R.id.commitment_timer_1h)));
            onView(withTagValue(is("person_tab:stats"))).perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), click());
            assertEquals(peer, stats.get());
            assertNull(tag(profile, "person_full_stats"));
            assertEquals(before, context.getSharedPreferences("subhub_service_duration_ui", 0).getAll());
        }
    }

    @Test public void recentActivitySitsBelowDestinationsAndRefreshesOnlyWhileStarted() throws Exception {
        ActivityEntry first = event(PersonId.LOCAL, "first", 1);
        source.entries = List.of(first);
        try (ActivityScenario<PersonActivity> page = ActivityScenario.launch(profile(PersonId.LOCAL))) {
            PersonActivity profile = resumed(PersonActivity.class);
            await(() -> tag(profile, "activity_preview_entry:local:first") != null);
            onMain(() -> {
                LinearLayout body = profile.findViewById(R.id.role_page_body);
                assertTrue(body.indexOfChild(tag(profile, "person_destinations"))
                        < body.indexOfChild(tag(profile, "activity_preview:local")));
                assertNotNull(tag(profile, "activity_preview_view_all:local"));
            });
            ActivityEntry second = event(PersonId.LOCAL, "second", 2);
            source.entries = List.of(first, second);
            ActivitySources.changed();
            await(() -> tag(profile, "activity_preview_entry:local:second") != null);
            assertEquals(0, source.marked.get());
            await(() -> !onMainValue(() -> AsyncUiScope.forPage(profile).isPending("person-recent-activity")));
            page.moveToState(Lifecycle.State.CREATED);
            int reads = source.reads.get();
            source.entries = List.of(event(PersonId.LOCAL, "third", 3));
            ActivitySources.changed();
            SystemClock.sleep(100);
            assertEquals(reads, source.reads.get());
            page.moveToState(Lifecycle.State.RESUMED);
            PersonActivity resumed = resumed(PersonActivity.class);
            await(() -> tag(resumed, "activity_preview_entry:local:third") != null);
        }
    }

    @Test public void activityChangesCoalesceBehindOneInFlightRead() throws Exception {
        source.block = true;
        source.entries = List.of(event(PersonId.LOCAL, "first", 1));
        try (ActivityScenario<PersonActivity> page = ActivityScenario.launch(profile(PersonId.LOCAL))) {
            PersonActivity profile = resumed(PersonActivity.class);
            assertTrue(source.started.await(5, TimeUnit.SECONDS));
            source.entries = List.of(event(PersonId.LOCAL, "newest", 2));
            onMain(() -> { for (int i = 0; i < 100; i++) ActivitySources.changed(); });
            assertEquals(1, source.reads.get());
            source.release.countDown();
            await(() -> tag(profile, "activity_preview_entry:local:newest") != null && source.reads.get() >= 2);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(2, source.reads.get());
        }
    }

    @Test public void switchingToSubOnlyRejectsAnInFlightPreviewAndItsStaleViewAllAction() throws Exception {
        source.block = true;
        source.entries = List.of(event(PersonId.LOCAL, "pending", 1));
        try (ActivityScenario<PersonActivity> page = ActivityScenario.launch(profile(PersonId.LOCAL))) {
            PersonActivity profile = resumed(PersonActivity.class);
            assertTrue(source.started.await(5, TimeUnit.SECONDS));
            View preview = tag(profile, "activity_preview:local");
            View viewAll = tag(profile, "activity_preview_view_all:local");
            assertNotNull(preview);
            assertNotNull(viewAll);
            assertTrue(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.SUB_ONLY));
            assertTrue(ControllerPinManager.isDomModeActive());
            source.release.countDown();
            await(() -> onMainValue(() -> preview.getVisibility() == View.GONE));
            onMain(viewAll::performClick);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(Lifecycle.State.RESUMED, page.getState());
            assertNull(tag(profile, "activity_preview_entry:local:pending"));
            int reads = source.reads.get();
            ActivitySources.changed();
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(reads, source.reads.get());
            assertEquals(0, source.marked.get());
            assertTrue(ControllerPinManager.isDomModeActive());
        }
    }

    @Test public void selectedPreviewRejectsFactsFromAnotherPerson() throws Exception {
        source.ignorePerson = true;
        source.entries = List.of(event(new PersonId("peer:other-person"), "foreign", 1));
        try (ActivityScenario<PersonActivity> page = ActivityScenario.launch(profile(PersonId.LOCAL))) {
            PersonActivity profile = resumed(PersonActivity.class);
            await(() -> text(tag(profile, "activity_preview:local")).contains(context.getString(R.string.dom_activity_unavailable)));
            assertNull(tag(profile, "activity_preview_entry:foreign"));
        }
    }

    @Test public void emptyLocalPreviewOpensLocalHistoryWithoutRequiringAnEntryKey() throws Exception {
        try (ActivityScenario<PersonActivity> page = ActivityScenario.launch(profile(PersonId.LOCAL))) {
            PersonActivity profile = resumed(PersonActivity.class);
            await(() -> text(tag(profile, "activity_preview:local")).contains(context.getString(R.string.dom_activity_empty)));
            onView(withTagValue(is("activity_preview_view_all:local")))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), click());
            com.subhub.app.activity.DomActivityActivity feed = resumed(com.subhub.app.activity.DomActivityActivity.class);
            assertTrue(feed.getIntent().getBooleanExtra(com.subhub.app.activity.DomActivityActivity.EXTRA_LOCAL_HISTORY, false));
            onMain(feed::finish);
        }
    }

    @Test public void switchingToSubOnlyRejectsAnInFlightRemoteProfileWithoutClearingLocalAuthentication() throws Exception {
        PersonId peer = new PersonId("peer:pending-profile-fixture");
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        People.installRemoteRepository(new PersonRepository() {
            public List<PersonId> people() { return List.of(peer); }
            public PersonSnapshot load(PersonId id) {
                loads.incrementAndGet();
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Profile fixture timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return remote(id);
            }
        });
        try (ActivityScenario<PersonActivity> page = ActivityScenario.launch(profile(peer))) {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.SUB_ONLY));
            assertTrue(ControllerPinManager.isDomModeActive());
            release.countDown();
            await(() -> page.getState() == Lifecycle.State.DESTROYED);
            assertTrue(ControllerPinManager.isDomModeActive());
            assertEquals(1, loads.get());
            assertEquals(0, source.reads.get());
        } finally {
            release.countDown();
        }
    }

    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    public void localProfileInlineDurationAndFlatStatsFitActualRequestedFontAndViewport() throws Exception {
        long start = System.currentTimeMillis() - 600000;
        assertTrue(context.getSharedPreferences(StatsRepository.PREFS_NAME, 0).edit()
                .putString("session_history", "2," + start + ",90,4,120000,1,2,123,4,5,0").commit());
        source.entries = List.of(event(PersonId.LOCAL, "visual-local", 1));
        try (ActivityScenario<PersonActivity> page = ActivityScenario.launch(profile(PersonId.LOCAL))) {
            PersonActivity profile = resumed(PersonActivity.class);
            await(() -> tag(profile, "person_duration:local") != null
                    && tag(profile, "activity_preview_entry:local:visual-local") != null);
            captureLocal(profile, "profile-top", null, () -> {
                assertEquals(PersonId.LOCAL, PersonRoutes.requireId(profile.getIntent()));
                assertNotNull(tag(profile, "person_target:local"));
                assertLocalTextFits(profile.findViewById(R.id.role_page_body));
                assertLocalTextFits(profile.findViewById(R.id.primary_header_title));
            });
            View duration = tag(profile, "person_duration:local");
            captureLocal(profile, "profile-inline-duration", duration, () -> {
                assertTrue(duration instanceof com.subhub.app.commitment.ServiceDurationView);
                assertLocalTextFits(duration);
                int[] choices = {R.id.commitment_timer_1h, R.id.commitment_timer_24h,
                        R.id.commitment_timer_7d, R.id.commitment_timer_30d,
                        R.id.commitment_timer_permanent, R.id.service_duration_random};
                for (int index = 0; index < choices.length; index++) {
                    View choice = profile.findViewById(choices[index]);
                    assertTrue("Inline duration remains editable", choice.isEnabled());
                    assertTrue("Native touch height", choice.getHeight() >= PersonUi.dp(profile, 48));
                    assertFullyVisibleAboveNavigation(profile, choice);
                    if (index % 2 == 1) {
                        android.graphics.Rect first = screenBounds(profile.findViewById(choices[index - 1]));
                        android.graphics.Rect second = screenBounds(choice);
                        assertTrue("Duration choices do not overlap", first.right <= second.left);
                    }
                }
                assertFullyVisibleAboveNavigation(profile, profile.findViewById(R.id.service_duration_hide));
            });
            onView(withTagValue(is("person_tab:stats")))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), click());
            StatsActivity stats = resumed(StatsActivity.class);
            await(() -> text(stats.findViewById(R.id.profile_stats_allowances)).contains("Chrome"));
            try {
                captureLocal(stats, "stats-top", null, () -> {
                    assertEquals(PersonId.LOCAL, PersonRoutes.requireId(stats.getIntent()));
                    View roleControl = stats.findViewById(R.id.button_edit_lock);
                    TextView roleBadge = stats.findViewById(R.id.header_role_badge);
                    assertEquals("Exactly one Stats role indicator", 1,
                            (roleControl.isShown() ? 1 : 0) + (roleBadge.isShown() ? 1 : 0));
                    assertEquals(View.GONE, roleControl.getVisibility());
                    assertTrue(roleBadge.isShown());
                    assertEquals(context.getString(R.string.role_dom), roleBadge.getText().toString());
                    assertTrue(stats.findViewById(R.id.button_back).isShown());
                    TextView subtitle = stats.findViewById(R.id.primary_header_subtitle);
                    assertTrue(subtitle.isShown());
                    assertEquals(context.getString(R.string.role_this_device), subtitle.getText().toString());
                    TextView title = stats.findViewById(R.id.primary_header_title);
                    assertEquals(context.getString(R.string.role_stats), title.getText().toString());
                    assertEquals("Stats remains on one line", 1, title.getLineCount());
                    assertLocalTextFits(title);
                    assertLocalTextFits(subtitle);
                    assertNull(stats.findViewById(R.id.stats_totals_toggle));
                    assertEquals(View.VISIBLE, stats.findViewById(R.id.stats_totals_details).getVisibility());
                    assertLocalTextFits(stats.findViewById(R.id.stats_lifetime_summary));
                });
                captureLocal(stats, "stats-daily", stats.findViewById(R.id.daily_statistics_panel),
                        () -> assertLocalTextFits(stats.findViewById(R.id.daily_statistics_panel)));
                captureLocal(stats, "stats-allowances", stats.findViewById(R.id.profile_stats_allowances), () -> {
                    View allowances = stats.findViewById(R.id.profile_stats_allowances);
                    assertLocalTextFits(allowances);
                    assertTrue(text(allowances).contains(context.getString(R.string.role_allowance_usage, 0, 20)));
                    assertTrue(text(allowances).contains(context.getString(R.string.role_allowance_usage, 0, 45)));
                });
                captureLocal(stats, "stats-history", tag(stats, "stats_session_history"), () -> {
                    View history = tag(stats, "stats_session_history");
                    assertLocalTextFits(history);
                    assertTrue(text(history).contains(context.getString(R.string.stats_assessed)));
                    assertTrue(text(history).contains(com.subhub.app.penance.WalletCurrency.format("EUR", 123)));
                });
                captureLocal(stats, "stats-feature-totals", stats.findViewById(R.id.stats_totals_details),
                        () -> assertLocalTextFits(stats.findViewById(R.id.stats_totals_details)));
            } finally { onMain(stats::finish); }
        }
    }

    /** Capture an existing native profile from the instrumentation thread without installing fixtures. */
    public static void captureNativeProfile(android.app.Activity activity, String name, String targetTag) throws Exception {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            throw new IllegalStateException("Native capture must run on the instrumentation thread");
        if (name == null || !name.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid capture name");
        AtomicReference<View> target = new AtomicReference<>();
        if (targetTag != null) onMain(() -> {
            target.set(activity.getWindow().getDecorView().findViewWithTag(targetTag));
            assertNotNull("Native profile target: " + targetTag, target.get());
        });
        captureSettledProfile(activity, name, target.get(), () -> {
            View content = target.get();
            if (content == null) content = activity.findViewById(R.id.role_page_body);
            if (content == null) content = activity.findViewById(android.R.id.content);
            assertLocalTextFits(content);
            View title = activity.findViewById(R.id.primary_header_title);
            if (title != null) assertLocalTextFits(title);
        });
    }

    private static void captureLocal(Activity activity, String section, View target, Runnable verify) throws Exception {
        captureSettledProfile(activity, "local-" + section, target, verify);
    }

    private static void captureSettledProfile(Activity activity, String name, View target, Runnable verify) throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 29) throw new AssertionError("Hardware frame capture requires API 29");
        android.os.Bundle args = InstrumentationRegistry.getArguments();
        String run = args.getString("evidence_run", "candidate-1");
        if (!run.matches("[A-Za-z0-9_-]+")) throw new AssertionError("Invalid evidence namespace");
        java.io.File folder = new java.io.File(activity.getApplicationContext().getFilesDir(), "ui-v3/" + run);
        assertTrue(folder.isDirectory() || folder.mkdirs());
        String prefix = name + "-" + activity.getResources().getConfiguration().fontScale
                + "-" + activity.getResources().getConfiguration().screenWidthDp;
        android.graphics.Bitmap previous = null;
        long deadline = SystemClock.uptimeMillis() + 7000, stableSince = 0;
        try {
            onMain(() -> {
                String scale = args.getString("shell_expected_font_scale"), width = args.getString("shell_expected_width_dp");
                if (scale != null) assertEquals(Float.parseFloat(scale), activity.getResources().getConfiguration().fontScale, 0.01f);
                if (width != null) assertEquals(Integer.parseInt(width), activity.getResources().getConfiguration().screenWidthDp);
                android.widget.ScrollView scroll = profileScroll(activity, target);
                assertNotNull("Actual page scroll surface", scroll);
                if (target == null) scroll.scrollTo(0, 0);
                else {
                    assertTrue(target.isShown());
                    android.graphics.Rect bounds = new android.graphics.Rect();
                    target.getDrawingRect(bounds);
                    scroll.offsetDescendantRectToMyCoords(target, bounds);
                    scroll.scrollTo(0, Math.max(0, bounds.top - PersonUi.dp(activity, 12)));
                }
            });
            do {
                CountDownLatch committed = new CountDownLatch(1);
                onMain(() -> {
                    View root = activity.getWindow().getDecorView();
                    assertTrue("Native hardware rendering", root.isHardwareAccelerated());
                    root.getViewTreeObserver().registerFrameCommitCallback(committed::countDown);
                    root.invalidate();
                });
                assertTrue("No hardware frame committed", committed.await(2, TimeUnit.SECONDS));
                android.graphics.Bitmap current = InstrumentationRegistry.getInstrumentation()
                        .getUiAutomation(android.app.UiAutomation.FLAG_DONT_USE_ACCESSIBILITY).takeScreenshot();
                assertNotNull("Native compositor pixels", current);
                long now = SystemClock.uptimeMillis();
                boolean same = previous != null && previous.sameAs(current);
                if (!same) stableSince = now;
                if (previous != null) previous.recycle();
                previous = current;
                boolean ready = onMainValue(() -> {
                    View root = activity.getWindow().getDecorView();
                    View snackbar = root.findViewById(com.google.android.material.R.id.snackbar_text);
                    View navigation = activity.findViewById(R.id.bottom_navigation);
                    return activity.hasWindowFocus() && root.isLaidOut() && !root.isLayoutRequested()
                            && !containsLocalVisibleText(root, activity.getString(R.string.role_loading))
                            && (snackbar == null || !snackbar.isShown())
                            && navigation != null && navigation.getWidth() > 0 && navigation.getHeight() > 0;
                });
                if (!ready) stableSince = now;
                if (same && ready && now - stableSince >= 200) {
                    try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(folder, prefix + ".png"))) {
                        assertTrue(previous.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
                    }
                    onMain(verify);
                    return;
                }
            } while (SystemClock.uptimeMillis() < deadline);
            throw new AssertionError("Native profile compositor did not settle: " + name);
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

    private static android.widget.ScrollView profileScroll(Activity activity, View target) {
        if (target != null) {
            android.view.ViewParent ancestor = target.getParent();
            while (ancestor != null) {
                if (ancestor instanceof android.widget.ScrollView) return (android.widget.ScrollView) ancestor;
                ancestor = ancestor.getParent();
            }
            throw new AssertionError("Native target has no page scroll ancestor");
        }
        return localScroll(activity.findViewById(android.R.id.content));
    }

    private static android.widget.ScrollView localScroll(View view) {
        if (view instanceof android.widget.ScrollView) return (android.widget.ScrollView) view;
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) {
            android.widget.ScrollView found = localScroll(((ViewGroup) view).getChildAt(index));
            if (found != null) return found;
        }
        return null;
    }

    private static boolean containsLocalVisibleText(View view, String value) {
        if (!view.isShown()) return false;
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
            if (containsLocalVisibleText(((ViewGroup) view).getChildAt(index), value)) return true;
        return false;
    }

    private static void assertLocalTextFits(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView && ((TextView) view).getText().length() > 0) {
            TextView label = (TextView) view;
            android.text.Layout layout = label.getLayout();
            assertNotNull("Laid-out text: " + label.getText(), layout);
            int available = label.getWidth() - label.getCompoundPaddingLeft() - label.getCompoundPaddingRight();
            for (int line = 0; line < layout.getLineCount(); line++) {
                assertEquals("Unabridged text: " + label.getText(), 0, layout.getEllipsisCount(line));
                assertTrue("Text width: " + label.getText(), layout.getLineMax(line) <= available + 1);
            }
            assertTrue("Text height: " + label.getText(), layout.getHeight()
                    <= label.getHeight() - label.getCompoundPaddingTop() - label.getCompoundPaddingBottom());
        }
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
            assertLocalTextFits(((ViewGroup) view).getChildAt(index));
    }

    private static android.graphics.Rect screenBounds(View view) {
        int[] point = new int[2];
        view.getLocationOnScreen(point);
        return new android.graphics.Rect(point[0], point[1], point[0] + view.getWidth(), point[1] + view.getHeight());
    }

    private static void assertFullyVisibleAboveNavigation(Activity activity, View view) {
        android.graphics.Rect full = screenBounds(view), visible = new android.graphics.Rect();
        assertTrue("Native control is visible", view.getGlobalVisibleRect(visible));
        assertEquals("Native control is unclipped", full, visible);
        android.graphics.Rect navigation = screenBounds(activity.findViewById(R.id.bottom_navigation));
        assertTrue("Native control clears bottom navigation", full.bottom <= navigation.top);
    }

    private Intent profile(PersonId id) { return new Intent(context, PersonActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID, id.value()); }

    private PersonSnapshot remote(PersonId id) {
        return new PersonSnapshot(id, "Synthetic Alex", "Test device", "Current configuration", "Unknown",
                PersonSnapshot.Connection.PAIRED, PersonSnapshot.Freshness.CURRENT, 0,
                EnumSet.of(PersonSnapshot.Capability.VIEW_CONTRACT, PersonSnapshot.Capability.EDIT_CONTRACT, PersonSnapshot.Capability.VIEW_STATS),
                List.of(), List.of(), "EUR", 0, 0, 0, 0, false);
    }

    private ActivityEntry event(PersonId id, String key, int order) {
        ActivityEvent event = new ActivityEvent(key, ActivityEvent.Kind.APP_OPENED,
                System.currentTimeMillis() - 60000L + order, "com.android.chrome", 0, "", "", 0);
        return new ActivityEntry(id.isLocal() ? "local:" + key : key, id, "Synthetic person", "Test device",
                event, System.currentTimeMillis(), !id.isLocal(), "Chrome");
    }

    private static final class Source implements ActivityRepository {
        volatile List<ActivityEntry> entries = List.of();
        volatile boolean block, ignorePerson;
        final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger reads = new AtomicInteger(), marked = new AtomicInteger();
        public Page load(String before, int limit) { return new Page(entries, null); }
        public Page locate(String key, int limit) { return new Page(entries, null); }
        public List<ActivityEntry> recent(PersonId person, int limit) throws Exception {
            reads.incrementAndGet();
            started.countDown();
            if (block && !release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture read timed out");
            List<ActivityEntry> selected = new ArrayList<>();
            for (ActivityEntry entry : entries) {
                if ((person == null || ignorePerson || person.equals(entry.personId)) && selected.size() < limit) selected.add(entry);
            }
            return selected;
        }
        public int unreadCount() { return entries.size(); }
        public void markRead(List<String> keys) { marked.addAndGet(keys.size()); }
    }

    private static void onMain(Runnable action) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) action.run();
        else InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
    private static boolean onMainValue(BooleanSupplier condition) {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        onMain(() -> result.set(condition.getAsBoolean()));
        return result.get();
    }
    private static View tag(Activity activity, String tag) {
        AtomicReference<View> value = new AtomicReference<>();
        onMain(() -> value.set(activity.getWindow().getDecorView().findViewWithTag(tag)));
        return value.get();
    }
    private static String text(View view) {
        if (view == null) return "";
        StringBuilder result = new StringBuilder(view instanceof TextView ? ((TextView) view).getText() : "");
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            result.append('\n').append(text(((ViewGroup) view).getChildAt(i)));
        }
        return result.toString();
    }
    private static <T extends Activity> T resumed(Class<T> type) {
        AtomicReference<T> found = new AtomicReference<>();
        await(() -> {
            onMain(() -> {
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                    if (type.isInstance(activity)) found.set(type.cast(activity));
                }
            });
            return found.get() != null;
        });
        return found.get();
    }
    private static void await(BooleanSupplier ready) {
        long until = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < until) {
            if (ready.getAsBoolean()) return;
            SystemClock.sleep(20);
        }
        fail("Native profile/statistics contract did not settle");
    }
}
