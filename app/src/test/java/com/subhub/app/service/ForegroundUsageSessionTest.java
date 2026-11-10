package com.subhub.app.service;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ForegroundUsageSessionTest {
    private static final String APP = "com.example.selected";

    @Test public void threeSecondBurstsNeverSpendAllowanceOrCombineAcrossDepartures() {
        ForegroundUsageSession session = new ForegroundUsageSession();
        for (long start = 0; start < 100_000; start += 5_000) {
            for (long time = start; time <= start + 3_000; time += 1_000)
                assertEquals(0L, session.observe(APP, time));
            assertEquals(0L, session.observe("", start + 3_001));
            assertFalse(session.qualified(APP));
        }
    }

    @Test public void fiveConfirmedSecondsCountRetroactivelyOnceThenOnlyNewTime() {
        ForegroundUsageSession session = new ForegroundUsageSession();
        for (long time = 0; time < 5_000; time += 1_000)
            assertEquals(0L, session.observe(APP, time));
        assertEquals(5_000L, session.observe(APP, 5_000));
        assertTrue(session.qualified(APP));
        assertEquals(0L, session.observe(APP, 5_000));
        assertEquals(1_000L, session.observe(APP, 6_000));
    }

    @Test public void changingAppsDiscardsUnqualifiedTimeAndNeverChargesItToTheNewApp() {
        ForegroundUsageSession session = new ForegroundUsageSession();
        for (long time = 0; time <= 4_000; time += 1_000) session.observe(APP, time);
        assertEquals(0L, session.observe("com.example.other", 5_000));
        assertFalse(session.qualified(APP));
        for (long time = 6_000; time < 10_000; time += 1_000)
            assertEquals(0L, session.observe("com.example.other", time));
        assertEquals(5_000L, session.observe("com.example.other", 10_000));
    }

    @Test public void anUnobservedWorkdayCannotBeBackfilledEvenWhenThePackageIsUnchanged() {
        ForegroundUsageSession session = qualifiedSession();
        assertEquals(0L, session.observe(APP, 8 * 60 * 60 * 1_000L));
        assertFalse(session.qualified(APP));
        assertEquals(0L, session.observe(APP, 8 * 60 * 60 * 1_000L + 1_000));
    }

    @Test public void aDelayedTimerBreaksPendingQualificationRatherThanBridgingUnknownTime() {
        ForegroundUsageSession session = new ForegroundUsageSession();
        session.observe(APP, 0);
        session.observe(APP, 2_000);
        assertEquals(0L, session.observe(APP, 4_501));
        assertEquals(0L, session.observe(APP, 6_501));
        assertEquals(0L, session.observe(APP, 8_501));
        assertEquals(5_000L, session.observe(APP, 9_501));
    }

    @Test public void screenOffLockLabAndRuntimeBoundariesDiscardOnlyPendingSessionState() {
        ForegroundUsageSession session = qualifiedSession();
        session.reset();
        assertFalse(session.qualified(APP));
        assertEquals(0L, session.observe(APP, 6_000));
        assertEquals(0L, session.observe(null, 7_000));
        assertEquals(0L, session.observe(APP, 8_000));
        assertFalse(session.qualified(APP));
    }

    @Test public void anInvalidOrReversedMonotonicClockCannotAddOrRetainQualification() {
        ForegroundUsageSession session = qualifiedSession();
        assertEquals(0L, session.observe(APP, 4_999));
        assertFalse(session.qualified(APP));
        assertEquals(0L, session.observe(APP, -1));
        assertEquals(0L, session.observe(APP, 6_000));
    }

    @Test public void wallClockRollbackAndJumpDoNotChangeElapsedUsage() {
        // Wall time is used by the durable daily store, never as the session's duration clock.
        long[] wallClock = {100_000, 101_000, 1_000, 40_000_000, 40_001_000, 40_002_000};
        ForegroundUsageSession session = new ForegroundUsageSession();
        long charged = 0L;
        for (int i = 0; i < wallClock.length; i++) charged += session.observe(APP, i * 1_000L);
        assertEquals(5_000L, charged);
    }

    @Test public void pendingHomeSuppressesStaleAppUntilAnObservedExitOrBudgetRelease() {
        ForegroundUsageSession session = qualifiedSession();
        ForegroundLimitHandoff handoff = new ForegroundLimitHandoff();
        handoff.observed(APP);
        handoff.budgetReached(true);
        handoff.homeRequested();
        session.reset();
        handoff.observed(APP);
        assertFalse(handoff.accountingAllowed());
        assertFalse(session.qualified(APP));
        handoff.budgetReached(false);
        assertTrue(handoff.accountingAllowed());
        assertEquals(0L, session.observe(APP, 6_000));
        handoff.budgetReached(true);
        handoff.homeRequested();
        handoff.observed("com.example.launcher");
        assertTrue(handoff.accountingAllowed());
    }

    private static ForegroundUsageSession qualifiedSession() {
        ForegroundUsageSession session = new ForegroundUsageSession();
        for (long time = 0; time <= 5_000; time += 1_000) session.observe(APP, time);
        assertTrue(session.qualified(APP));
        return session;
    }
}
