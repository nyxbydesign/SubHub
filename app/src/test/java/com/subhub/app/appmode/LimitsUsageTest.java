package com.subhub.app.appmode;

import static org.junit.Assert.*;

import org.junit.Test;

public final class LimitsUsageTest {
    @Test
    public void tighterCombinedBudgetControlsRemainingAndProgress() {
        LimitsUsage state = LimitsUsage.forApp(10, 100, 90, 100, true, true);
        assertTrue(state.limited);
        assertEquals(10, state.remainingMillis);
        assertEquals(90, state.progressPercent);
    }

    @Test
    public void tighterAppBudgetControlsRemainingAndProgress() {
        LimitsUsage state = LimitsUsage.forApp(75, 100, 80, 200, true, true);
        assertEquals(25, state.remainingMillis);
        assertEquals(75, state.progressPercent);
    }

    @Test
    public void exhaustedCombinedBudgetBlocksEvenUnusedApp() {
        LimitsUsage state = LimitsUsage.forApp(0, 100, 200, 200, true, true);
        assertEquals(0, state.remainingMillis);
        assertEquals(100, state.progressPercent);
    }

    @Test
    public void disabledIndividualBudgetDoesNotLimitCombinedAllowance() {
        LimitsUsage state = LimitsUsage.forApp(1000, 100, 50, 200, false, true);
        assertEquals(150, state.remainingMillis);
        assertEquals(25, state.progressPercent);
        assertFalse(LimitsUsage.forApp(1000, 100, 1000, 200, false, false).limited);
    }

    @Test
    public void saturatedUsageDoesNotOverflowProgress() {
        assertEquals(100, LimitsUsage.percent(Long.MAX_VALUE, 86_400_000));
        assertEquals(0, LimitsUsage.percent(-1, 60_000));
    }
}
