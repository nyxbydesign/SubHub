package com.subhub.app.stats;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AchievementRequirementTest {
    @Test public void picturedContentRequirementsAreTenTimesHigher() {
        assertEquals(100L, AchievementManager.target("blocks_10"));
        assertEquals(1_000L, AchievementManager.target("blocks_100"));
        assertEquals(10_000L, AchievementManager.target("blocks_1000"));
        assertEquals(100_000L, AchievementManager.target("blocks_10000"));
        assertEquals(500L, AchievementManager.target("peak_50"));
        assertEquals(5_000L, AchievementManager.target("peak_500"));
    }
}
