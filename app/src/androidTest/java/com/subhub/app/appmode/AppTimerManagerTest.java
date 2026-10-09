package com.subhub.app.appmode;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public final class AppTimerManagerTest {
    private static final String APP_ONE = "com.example.one";
    private static final String APP_TWO = "com.example.two";
    private static final long DAY_ONE = 1_787_482_800_000L;
    private static final long DAY_THREE = DAY_ONE + 2L * 86_400_000L;
    private static final long DAY_FIVE = DAY_ONE + 4L * 86_400_000L;
    private static final long DAY_SIX = DAY_ONE + 5L * 86_400_000L;
    private static final long DAY_EIGHT = DAY_ONE + 7L * 86_400_000L;

    private AppTimerManager timers;
    private Set<String> selected;

    @Before public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        timers = new AppTimerManager(context);
        selected = new LinkedHashSet<>();
        selected.add(APP_ONE);
        selected.add(APP_TWO);
    }

    @After public void tearDown() {
        timers.saveSettings(false, 30, false, 120);
        timers.saveAllowances(Set.of(), Map.of());
    }

    @Test public void eachLimitedAppCanHaveItsOwnAllowance() {
        timers.saveSettings(true, 30, false, 120);
        timers.saveAllowances(selected, Map.of(APP_ONE, 1, APP_TWO, 3));
        timers.recordUsage(APP_ONE, 60_000L, selected, DAY_ONE);
        timers.recordUsage(APP_TWO, 60_000L, selected, DAY_ONE);

        assertEquals(AppTimerManager.LimitStatus.PER_APP,
                timers.limitStatus(APP_ONE, selected, DAY_ONE));
        assertEquals(AppTimerManager.LimitStatus.NONE,
                timers.limitStatus(APP_TWO, selected, DAY_ONE));

        AppTimerManager.AllowanceSummary summary = timers.summarizeAllowances(selected);
        assertEquals(2, summary.appCount);
        assertEquals(1, summary.minimumMinutes);
        assertEquals(3, summary.maximumMinutes);
        assertEquals(false, summary.isUniform());
    }

    @Test public void allowanceSummaryOnlyCallsValuesUniformWhenTheyMatch() {
        timers.saveSettings(true, 30, true, 120);
        timers.saveAllowances(selected, Map.of(APP_ONE, 45, APP_TWO, 45));

        AppTimerManager.AllowanceSummary summary = timers.summarizeAllowances(selected);
        assertEquals(2, summary.appCount);
        assertEquals(45, summary.minimumMinutes);
        assertEquals(45, summary.maximumMinutes);
        assertEquals(true, summary.isUniform());
    }

    @Test public void perAppLimitBlocksOnlySpentApp() {
        timers.saveSettings(true, 1, false, 120);
        timers.recordUsage(APP_ONE, 60_000L, selected, DAY_ONE);

        assertEquals(AppTimerManager.LimitStatus.PER_APP,
                timers.limitStatus(APP_ONE, selected, DAY_ONE));
        assertEquals(AppTimerManager.LimitStatus.NONE,
                timers.limitStatus(APP_TWO, selected, DAY_ONE));
    }

    @Test public void combinedLimitBlocksEverySelectedApp() {
        timers.saveSettings(false, 30, true, 2);
        timers.recordUsage(APP_ONE, 70_000L, selected, DAY_THREE);
        timers.recordUsage(APP_TWO, 50_000L, selected, DAY_THREE);

        assertEquals(AppTimerManager.LimitStatus.COMBINED,
                timers.limitStatus(APP_ONE, selected, DAY_THREE));
        assertEquals(AppTimerManager.LimitStatus.COMBINED,
                timers.limitStatus(APP_TWO, selected, DAY_THREE));
    }

    @Test public void usageResetsOnNextLocalDay() {
        timers.saveSettings(true, 1, false, 120);
        timers.recordUsage(APP_ONE, 60_000L, selected, DAY_FIVE);
        assertEquals(AppTimerManager.LimitStatus.PER_APP,
                timers.limitStatus(APP_ONE, selected, DAY_FIVE));

        assertEquals(AppTimerManager.LimitStatus.NONE,
                timers.limitStatus(APP_ONE, selected, DAY_SIX));
    }

    @Test public void visibleScopeKeepsEarlierUsageInCombinedTotalAndResetsNextDay() {
        timers.clearUsageForTesting();
        timers.saveSettings(true, 30, true, 120);
        timers.recordUsage(APP_ONE, 70_000L, selected, DAY_FIVE);
        timers.recordUsage(APP_TWO, 50_000L, selected, DAY_FIVE);
        Map<String, AppTimerManager.UsageSnapshot> subset =
                timers.snapshots(Set.of(APP_TWO), DAY_FIVE);
        assertEquals(120_000L, subset.get("").totalUsedMillis);
        assertEquals(50_000L, subset.get(APP_TWO).appUsedMillis);
        assertEquals(120_000L, subset.get(APP_TWO).totalUsedMillis);
        assertEquals(120_000L, timers.snapshots(Set.of(), DAY_FIVE).get("").totalUsedMillis);
        Map<String, AppTimerManager.UsageSnapshot> nextDay = timers.snapshots(selected, DAY_SIX);
        assertEquals(0L, nextDay.get("").totalUsedMillis);
        assertEquals(0L, nextDay.get(APP_ONE).appUsedMillis);
    }

    @Test
    public void unselectedAppsNeverAccrueOrBlock() {
        timers.saveSettings(true, 1, true, 1);
        String unselected = "com.example.unselected";
        timers.recordUsage(unselected, 120_000L, selected, DAY_EIGHT);

        assertEquals(0L, timers.snapshot(unselected, DAY_EIGHT).appUsedMillis);
        assertEquals(0L, timers.snapshot(unselected, DAY_EIGHT).totalUsedMillis);
        assertEquals(AppTimerManager.LimitStatus.NONE,
                timers.limitStatus(unselected, selected, DAY_EIGHT));
    }
}
