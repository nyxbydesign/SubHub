package com.subhub.app.stats;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.UiRefreshWitness;
import com.subhub.app.NativeUiActions;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.is;

@RunWith(AndroidJUnit4.class)
public final class StatsRefreshAndroidTest {
    @Rule public final com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final com.subhub.app.UiAutomationStateRule automation = new com.subhub.app.UiAutomationStateRule();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Map<String, Map<String, ?>> preferences = new LinkedHashMap<>();
    @Before public void prepare() {
        assertFalse(com.subhub.app.people.LocalSessionControls.isActive(context));
        for (String name : Arrays.asList(StatsRepository.PREFS_NAME, "betablocker_achievements")) {
            preferences.put(name, new LinkedHashMap<>(context.getSharedPreferences(name, 0).getAll()));
            assertTrue(context.getSharedPreferences(name, 0).edit().clear().commit());
        }
        assertTrue(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.BOTH));
        ControllerPinManager.enterSubMode();
    }
    @After public void restore() {
        for (Map.Entry<String, Map<String, ?>> item : preferences.entrySet())
            SharedPreferenceTestRestore.restore(context.getSharedPreferences(item.getKey(), 0), item.getValue());
    }

    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    public void calendarRefreshKeepsNativeControlsMetricMonthAndScrollForUnchangedData() throws Exception {
        LocalDate today = LocalDate.now();
        int selectedMonth = Math.max(1, today.getMonthValue() - 1);
        LocalDate selectedDay = today.withMonth(selectedMonth).withDayOfMonth(1);
        Map<String, Map<String, Long>> days = new TreeMap<>();
        days.put(selectedDay.toString(), new TreeMap<>(Collections.singletonMap("censors", 2L)));
        try (ActivityScenario<StatsActivity> page = ActivityScenario.launch(StatsActivity.class)) {
            AtomicReference<DailyStatsPanel> panel = new AtomicReference<>();
            AtomicReference<StatsActivity> current = new AtomicReference<>();
            page.onActivity(activity -> {
                current.set(activity);
                View original = activity.findViewById(R.id.daily_statistics_panel);
                ViewGroup parent = (ViewGroup) original.getParent(); int index = parent.indexOfChild(original);
                parent.removeView(original);
                DailyStatsPanel controlled = new DailyStatsPanel(activity, false, null,
                        (first, last) -> new DailyStatsPanel.DailyData(today.withDayOfYear(1).toString(), days));
                parent.addView(controlled, index, original.getLayoutParams()); panel.set(controlled);
            });
            await(() -> panel.get().findViewById(R.id.daily_metric_filter) != null);
            onView(withId(R.id.daily_range_year)).perform(NativeUiActions.revealAboveNavigation(), click());
            await(() -> panel.get().findViewById(R.id.daily_metric_filter) != null
                    && panel.get().findViewById(R.id.daily_range_year).isSelected());
            onView(withId(R.id.daily_metric_filter)).perform(NativeUiActions.revealAboveNavigation(), click());
            onData(is(context.getString(R.string.daily_censors))).perform(click());
            await(() -> ((Spinner) panel.get().findViewById(R.id.daily_metric_filter)).getSelectedItemPosition() == 1);
            if (today.getMonthValue() > 1)
                onView(withContentDescription(R.string.stats_previous_month)).perform(NativeUiActions.revealAboveNavigation(), click());
            await(() -> {
                TextView summary = panel.get().findViewById(R.id.daily_day_summary);
                return summary != null && summary.getText().toString().endsWith(" · 2");
            });
            AtomicReference<View> filter = new AtomicReference<>(), summary = new AtomicReference<>();
            java.util.concurrent.atomic.AtomicInteger position = new java.util.concurrent.atomic.AtomicInteger();
            page.onActivity(activity -> {
                filter.set(panel.get().findViewById(R.id.daily_metric_filter)); summary.set(panel.get().findViewById(R.id.daily_day_summary));
                UiRefreshWitness.scroll(panel.get()).scrollTo(0, 200);
            });
            UiRefreshWitness.frame(current.get());
            page.onActivity(activity -> position.set(UiRefreshWitness.scroll(panel.get()).getScrollY()));
            assertTrue("Fixture must be scrolled", position.get() > 0);
            refresh(page, panel.get());
            UiRefreshWitness.capture(current.get(), "calendar-unchanged-refresh");
            page.onActivity(activity -> {
                assertSame(filter.get(), panel.get().findViewById(R.id.daily_metric_filter));
                assertSame(summary.get(), panel.get().findViewById(R.id.daily_day_summary));
                assertEquals(position.get(), UiRefreshWitness.scroll(panel.get()).getScrollY());
                Bundle saved = new Bundle(); panel.get().save(saved);
                assertEquals(365, saved.getInt("daily_range")); assertEquals(1, saved.getInt("daily_metric"));
                assertEquals(selectedMonth, saved.getInt("daily_month"));
            });
            // Mutating the same source map must still invalidate the prior rendered value snapshot.
            days.get(selectedDay.toString()).put("censors", 3L);
            refresh(page, panel.get());
            UiRefreshWitness.capture(current.get(), "calendar-changed-value");
            page.onActivity(activity -> {
                assertTrue(((TextView) panel.get().findViewById(R.id.daily_day_summary)).getText().toString().endsWith(" · 3"));
                assertEquals(1, ((Spinner) panel.get().findViewById(R.id.daily_metric_filter)).getSelectedItemPosition());
                assertEquals(position.get(), UiRefreshWitness.scroll(panel.get()).getScrollY());
                Bundle saved = new Bundle(); panel.get().save(saved); assertEquals(selectedMonth, saved.getInt("daily_month"));
            });
        }
    }

    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    public void achievementRefreshKeepsUnchangedBadgeViewsAndRendersNewProgress() throws Exception {
        try (ActivityScenario<StatsActivity> page = ActivityScenario.launch(StatsActivity.class)) {
            AtomicReference<StatsActivity> current = new AtomicReference<>();
            AtomicReference<AchievementPreviewView> preview = new AtomicReference<>();
            page.onActivity(activity -> { current.set(activity); preview.set(activity.findViewById(R.id.stats_achievements)); });
            await(() -> preview.get().getChildCount() > 0);
            HomeAchievementPreview initial = HomeAchievementPreview.load(context, new StatsRepository(context).load());
            AtomicReference<View> title = new AtomicReference<>(), badges = new AtomicReference<>();
            page.onActivity(activity -> {
                preview.get().render(initial); title.set(preview.get().getChildAt(0)); badges.set(preview.get().getChildAt(2));
                UiRefreshWitness.scroll(preview.get()).scrollTo(0, 200); preview.get().render(initial);
            });
            UiRefreshWitness.capture(current.get(), "achievement-unchanged-refresh");
            page.onActivity(activity -> {
                assertSame(title.get(), preview.get().getChildAt(0)); assertSame(badges.get(), preview.get().getChildAt(2));
            });
            int retainedSessions = LocalRetainedStatistics.load(context).stats.getSessions();
            assertTrue(retainedSessions < Integer.MAX_VALUE);
            assertTrue(context.getSharedPreferences(StatsRepository.PREFS_NAME, 0).edit()
                    .putInt("sessions_count", retainedSessions + 1).commit());
            HomeAchievementPreview updated = HomeAchievementPreview.load(context, new StatsRepository(context).load());
            assertNotEquals(initial.fingerprint, updated.fingerprint);
            page.onActivity(activity -> preview.get().render(updated));
            UiRefreshWitness.capture(current.get(), "achievement-changed-progress");
            page.onActivity(activity -> assertNotSame(title.get(), preview.get().getChildAt(0)));
        }
    }

    @Test public void openCatalogRefreshesProgressAndDetailsWithoutNavigation() throws Exception {
        ControllerPinManager.enterDomMode();
        new StatsRepository(context, false).setCustomPhrasesCount(3);
        try (ActivityScenario<AchievementsActivity> page = ActivityScenario.launch(AchievementsActivity.class)) {
            AtomicReference<AchievementsActivity> current = new AtomicReference<>();
            page.onActivity(current::set);
            String rowTag = "achievement:phrase_library";
            await(() -> containsText(current.get().findViewById(android.R.id.content)
                    .findViewWithTag(rowTag), "3 / 10"));
            AtomicReference<View> unchanged = new AtomicReference<>();
            page.onActivity(activity -> {
                unchanged.set(activity.findViewById(android.R.id.content).findViewWithTag(rowTag));
                try {
                    java.lang.reflect.Field field = StatsActivity.class.getDeclaredField("catalog");
                    field.setAccessible(true);
                    AchievementsPane pane = (AchievementsPane) field.get(activity);
                    pane.pause(); pane.resume();
                    assertSame("Same-authority resume must not erase retained rows", unchanged.get(),
                            activity.findViewById(android.R.id.content).findViewWithTag(rowTag));
                    assertEquals(View.VISIBLE, pane.view().getVisibility());
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            // A complete polling interval must leave unchanged controls in place.
            SystemClock.sleep(5_500);
            page.onActivity(activity -> assertSame(unchanged.get(),
                    activity.findViewById(android.R.id.content).findViewWithTag(rowTag)));
            new StatsRepository(context, false).setCustomPhrasesCount(7);
            await(() -> containsText(current.get().findViewById(android.R.id.content)
                    .findViewWithTag(rowTag), "7 / 10"));
            onView(withTagValue(is(rowTag))).perform(NativeUiActions.revealAboveNavigation(), click());
            onView(withId(R.id.achievement_detail_progress)).check(androidx.test.espresso.assertion.ViewAssertions.matches(
                    withText(context.getString(R.string.achievement_progress_detail, "7 / 10"))));
            new StatsRepository(context, false).setCustomPhrasesCount(8);
            long end = SystemClock.uptimeMillis() + 8_000;
            boolean updated = false;
            do {
                try {
                    onView(withId(R.id.achievement_detail_progress)).check(androidx.test.espresso.assertion.ViewAssertions.matches(
                            withText(context.getString(R.string.achievement_progress_detail, "8 / 10"))));
                    updated = true;
                } catch (AssertionError notYet) { SystemClock.sleep(50); }
            } while (!updated && SystemClock.uptimeMillis() < end);
            assertTrue("The open detail dialog must receive current progress", updated);
            onView(withId(R.id.achievement_detail_close)).perform(click());
            await(() -> current.get().hasWindowFocus()
                    && current.get().getLifecycle().getCurrentState() == androidx.lifecycle.Lifecycle.State.RESUMED);
            page.recreate();
            page.onActivity(current::set);
            await(() -> containsText(current.get().findViewById(android.R.id.content)
                    .findViewWithTag(rowTag), "8 / 10"));
            ControllerPinManager.enterSubMode();
            await(() -> page.getState() == androidx.lifecycle.Lifecycle.State.DESTROYED);
        }
    }

    @Test public void pausedDomCatalogWithholdsRowsUntilSameProviderMembershipIsValidated() throws Exception {
        ControllerPinManager.enterDomMode();
        new StatsRepository(context, false).setCustomPhrasesCount(3);
        com.subhub.app.relationships.RelationshipUi.Provider previous = com.subhub.app.relationships.RelationshipUi.provider();
        MembershipProjection provider = new MembershipProjection();
        provider.snapshot = java.util.concurrent.CompletableFuture.completedFuture(provider.snapshot(true));
        com.subhub.app.relationships.RelationshipUi.install(provider);
        try (ActivityScenario<AchievementsActivity> page = ActivityScenario.launch(AchievementsActivity.class)) {
            AtomicReference<AchievementsActivity> current = new AtomicReference<>();
            AtomicReference<View> oldRow = new AtomicReference<>();
            AtomicReference<AchievementsPane> pane = new AtomicReference<>();
            page.onActivity(current::set);
            String rowTag = "achievement:phrase_library";
            await(() -> containsText(current.get().findViewById(android.R.id.content).findViewWithTag(rowTag), "3 / 10"));
            java.util.concurrent.CompletableFuture<com.subhub.app.relationships.RelationshipUi.Snapshot> blocked =
                    new java.util.concurrent.CompletableFuture<>();
            page.onActivity(activity -> {
                try {
                    java.lang.reflect.Field field = StatsActivity.class.getDeclaredField("catalog");
                    field.setAccessible(true); pane.set((AchievementsPane) field.get(activity));
                    oldRow.set(activity.findViewById(android.R.id.content).findViewWithTag(rowTag));
                    assertTrue(oldRow.get().isShown());
                    pane.get().pause();
                    // The provider instance is unchanged; its paused membership observation is blocked.
                    provider.snapshot = blocked;
                    pane.get().resume();
                    assertSame(oldRow.get(), activity.findViewById(android.R.id.content).findViewWithTag(rowTag));
                    assertFalse("Unvalidated retained aggregate must not be visible", oldRow.get().isShown());
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            // Revocation becomes observable through the same provider, never through a new identity.
            com.subhub.app.relationships.RelationshipUi.Snapshot revoked = provider.snapshot(false);
            blocked.complete(revoked);
            await(() -> {
                View row = current.get().findViewById(android.R.id.content).findViewWithTag(rowTag);
                return row != null && row.isShown() && !catalogHasLocalScope(pane.get());
            });
            java.util.concurrent.CompletableFuture<com.subhub.app.relationships.RelationshipUi.Snapshot> failed =
                    new java.util.concurrent.CompletableFuture<>();
            page.onActivity(activity -> {
                oldRow.set(activity.findViewById(android.R.id.content).findViewWithTag(rowTag));
                pane.get().pause(); provider.snapshot = failed; pane.get().resume();
                assertFalse(oldRow.get().isShown());
            });
            // Neither the new read nor fallback revalidation may expose rows on an unknown membership.
            failed.completeExceptionally(new IllegalStateException("Synthetic membership unavailable"));
            await(() -> current.get().findViewById(android.R.id.content).findViewWithTag(rowTag) == null);
            page.onActivity(activity -> assertFalse(oldRow.get().isShown()));
            // The same provider now reports the revoked membership. Fresh authorized content may return.
            provider.snapshot = java.util.concurrent.CompletableFuture.completedFuture(provider.snapshot(false));
            page.onActivity(activity -> { pane.get().pause(); pane.get().resume(); });
            await(() -> containsText(current.get().findViewById(android.R.id.content).findViewWithTag(rowTag), "3 / 10"));
            page.onActivity(activity -> {
                View fresh = activity.findViewById(android.R.id.content).findViewWithTag(rowTag);
                assertNotSame(oldRow.get(), fresh); assertTrue(fresh.isShown());
                assertSame(provider, com.subhub.app.relationships.RelationshipUi.provider());
            });
        } finally { com.subhub.app.relationships.RelationshipUi.install(previous); }
    }

    private boolean catalogHasLocalScope(AchievementsPane pane) {
        try {
            java.lang.reflect.Field retained = AchievementsPane.class.getDeclaredField("retained");
            retained.setAccessible(true); Object catalog = retained.get(pane);
            if (catalog == null) throw new AssertionError("Expected loaded catalog");
            java.lang.reflect.Field scope = catalog.getClass().getDeclaredField("scope"); scope.setAccessible(true);
            return ((com.subhub.app.relationships.DomSubjectScope) scope.get(catalog))
                    .includes(com.subhub.app.people.PersonId.LOCAL);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static final class MembershipProjection implements com.subhub.app.relationships.RelationshipUi.Provider {
        volatile java.util.concurrent.CompletableFuture<com.subhub.app.relationships.RelationshipUi.Snapshot> snapshot;
        com.subhub.app.relationships.RelationshipUi.Snapshot snapshot(boolean linked) {
            com.subhub.app.relationships.RelationshipUi.Edge edge = new com.subhub.app.relationships.RelationshipUi.Edge(
                    "catalog-local", "generation", "dom", "sub", com.subhub.app.relationships.RelationshipUi.Delivery.LOCAL,
                    "ACTIVE", 1, com.subhub.app.people.PersonId.LOCAL,
                    com.subhub.app.relationships.RelationshipUi.Availability.UNKNOWN,
                    com.subhub.app.people.PersonSnapshot.Freshness.UNKNOWN);
            return new com.subhub.app.relationships.RelationshipUi.Snapshot(true, linked ? "linked" : "revoked",
                    Collections.emptyList(), linked ? Collections.singletonList(edge) : Collections.emptyList(),
                    null, Collections.emptyList());
        }
        public java.util.concurrent.CompletableFuture<com.subhub.app.relationships.RelationshipUi.Snapshot> snapshot() { return snapshot; }
        public java.util.concurrent.CompletableFuture<com.subhub.app.relationships.RelationshipUi.LocalReview> prepareLocal(
                String k, String d, String s, String r) { throw new AssertionError("Read-only fixture"); }
        public java.util.concurrent.CompletableFuture<com.subhub.app.relationships.RelationshipUi.Operation> confirmLocal(
                String i, String d) { throw new AssertionError("Read-only fixture"); }
        public java.util.concurrent.CompletableFuture<com.subhub.app.relationships.RelationshipUi.Operation> resume(
                String i) { throw new AssertionError("Read-only fixture"); }
        public java.util.concurrent.CompletableFuture<com.subhub.app.relationships.RelationshipUi.Operation> cancel(
                String i) { throw new AssertionError("Read-only fixture"); }
    }

    private boolean containsText(View view, String text) {
        if (view == null) return false;
        if (view instanceof TextView && text.equals(((TextView) view).getText().toString())) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (containsText(((ViewGroup) view).getChildAt(i), text)) return true;
        return false;
    }

    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    public void incompleteSubHistoryHidesLargeUnknownTotalAndRetainsChart() throws Exception {
        assertPeriodTotals(false);
    }

    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    public void incompleteDomHistoryHidesLargeUnknownTotalAndRetainsChart() throws Exception {
        assertPeriodTotals(true);
    }

    private void assertPeriodTotals(boolean dom) throws Exception {
        if (dom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
        LocalDate today = LocalDate.now();
        Map<String, Map<String, Long>> days = new TreeMap<>();
        days.put(today.minusDays(1).toString(), new TreeMap<>(Collections.singletonMap("service_ms", 3600000L)));
        days.put(today.toString(), new TreeMap<>(Collections.singletonMap("service_ms", 0L)));
        try (ActivityScenario<StatsActivity> page = ActivityScenario.launch(StatsActivity.class)) {
            AtomicReference<DailyStatsPanel> panel = new AtomicReference<>();
            AtomicReference<StatsActivity> current = new AtomicReference<>();
            page.onActivity(activity -> {
                current.set(activity);
                View original = activity.findViewById(R.id.daily_statistics_panel);
                ViewGroup parent = (ViewGroup) original.getParent(); int index = parent.indexOfChild(original);
                parent.removeView(original);
                DailyStatsPanel controlled = new DailyStatsPanel(activity, false, null, new DailyStatsPanel.Source() {
                    @Override public DailyStatsPanel.DailyData load(String first, String last) {
                        return new DailyStatsPanel.DailyData(today.minusDays(1).toString(), days);
                    }
                    @Override public boolean combined() { return dom; }
                });
                parent.addView(controlled, index, original.getLayoutParams()); panel.set(controlled);
            });
            await(() -> panel.get().findViewById(R.id.daily_metric_filter) != null);
            onView(withId(R.id.daily_range_30)).perform(NativeUiActions.revealAboveNavigation(), click());
            await(() -> panel.get().findViewById(R.id.daily_range_30).isSelected());
            UiRefreshWitness.capture(current.get(), dom ? "dom-partial-history" : "sub-partial-history");
            page.onActivity(activity -> {
                assertNull(panel.get().findViewById(R.id.daily_period_total));
                assertNotNull(panel.get().findViewById(R.id.daily_day_summary));
                assertTrue(containsDescription(panel.get(), activity.getString(R.string.daily_trend)));
                assertTrue(containsDescription(panel.get(), today.toString() + ":"));
            });
            assertEquals(2, days.size());
            assertEquals(Long.valueOf(3600000L), days.get(today.minusDays(1).toString()).get("service_ms"));
            for (int i = 0; i < 30; i++) days.putIfAbsent(today.minusDays(i).toString(),
                    new TreeMap<>(Collections.singletonMap("service_ms", 0L)));
            refresh(page, panel.get());
            await(() -> panel.get().findViewById(R.id.daily_period_total) != null);
            page.onActivity(activity -> assertNotEquals(activity.getString(R.string.role_unknown_data),
                    ((TextView) panel.get().findViewById(R.id.daily_period_total)).getText().toString()));
            for (Map<String, Long> day : days.values()) day.put("service_ms", 0L);
            refresh(page, panel.get());
            UiRefreshWitness.capture(current.get(), dom ? "dom-known-zero-history" : "sub-known-zero-history");
            page.onActivity(activity -> {
                TextView amount = panel.get().findViewById(R.id.daily_period_total);
                assertNotNull(amount);
                assertEquals("0m", amount.getText().toString());
            });
        }
    }

    private boolean containsDescription(View view, String prefix) {
        if (view.getContentDescription() != null && view.getContentDescription().toString().startsWith(prefix)) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (containsDescription(((ViewGroup) view).getChildAt(i), prefix)) return true;
        return false;
    }

    private void refresh(ActivityScenario<StatsActivity> page, DailyStatsPanel panel) throws Exception {
        page.onActivity(activity -> panel.refresh());
        await(() -> {
            try { java.lang.reflect.Field data = DailyStatsPanel.class.getDeclaredField("uiData"); data.setAccessible(true);
                java.lang.reflect.Field key = DailyStatsPanel.class.getDeclaredField("taskKey"); key.setAccessible(true);
                return !((com.subhub.app.util.AsyncUiScope) data.get(panel)).isPending((String) key.get(panel));
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
    }
    private interface Check { boolean ready() throws Exception; }
    private void await(Check check) throws Exception {
        long end = SystemClock.uptimeMillis() + 8000;
        do {
            AtomicBoolean ready = new AtomicBoolean(); AtomicReference<Exception> error = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try { ready.set(check.ready()); } catch (Exception failure) { error.set(failure); }
            });
            if (error.get() != null) throw error.get();
            if (ready.get()) return;
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < end);
        throw new AssertionError("Statistics refresh did not reach the expected state");
    }
}
