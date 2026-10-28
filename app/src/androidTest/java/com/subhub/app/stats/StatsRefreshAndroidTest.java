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
