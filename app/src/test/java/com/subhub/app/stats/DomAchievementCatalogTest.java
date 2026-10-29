package com.subhub.app.stats;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public final class DomAchievementCatalogTest {
    @Test public void domCatalogHasDistinctPositiveMilestonesAndDedicatedArtwork() {
        Set<String> ids = new HashSet<>(); Set<Integer> art = new HashSet<>();
        assertEquals(17, DomAchievementCatalog.all().size());
        for (AchievementManager.Achievement value : DomAchievementCatalog.all()) {
            assertTrue(ids.add(value.getId())); assertTrue(art.add(value.getBadgeArtRes()));
            assertEquals(AchievementRole.DOM, value.getRole());
            assertTrue(AchievementRole.DOM.owns(value.getId()));
            assertFalse(AchievementRole.SUB.owns(value.getId()));
            assertTrue(DomAchievementCatalog.target(value.getId()) > 0);
            assertNotEquals(0, value.getBadgeArtRes());
        }
        assertFalse(AchievementRole.DOM.owns("first_session"));
        assertTrue(AchievementRole.SUB.owns("first_session"));
    }

    @Test public void currenciesCannotBeAddedTogetherToReachATributeMilestone() {
        DomStatsSnapshot snapshot = DomStatsSnapshot.aggregate(Arrays.asList(
                sub("a", 500, 0), sub("b", 0, 600)), 1_000);
        assertEquals(600, DomAchievementCatalog.current("dom_tribute_10", snapshot));
        assertTrue(DomAchievementCatalog.current("dom_tribute_10", snapshot)
                < DomAchievementCatalog.target("dom_tribute_10"));
    }

    @Test public void sameCurrencyPaymentsAcrossDistinctSubsCanReachTheMilestone() {
        DomStatsSnapshot snapshot = DomStatsSnapshot.aggregate(Arrays.asList(
                sub("a", 500, 0), sub("b", 600, 0)), 1_000);
        assertEquals(1_100, DomAchievementCatalog.current("dom_tribute_10", snapshot));
        assertEquals(1_000, AchievementManager.target("dom_tribute_10"));
    }

    @Test public void sessionTimeAndCountUseDifferentExistingFacts() {
        DomStatsSnapshot snapshot = DomStatsSnapshot.aggregate(Arrays.asList(
                sub("a", 0, 0), sub("b", 0, 0)), 1_000);
        assertEquals(7_200, DomAchievementCatalog.current("dom_time_1hr", snapshot));
        assertEquals(10, DomAchievementCatalog.current("dom_sessions_10", snapshot));
        assertEquals(2, DomAchievementCatalog.current("dom_subs_3", snapshot));
    }

    private static DomStatsSnapshot.Sub sub(String id, long eur, long usd) {
        return new DomStatsSnapshot.Sub(id, "generation", 1, 1_000, 1_000,
                new DomStatsSnapshot.Totals(3_600, 5, eur, usd, eur, usd),
                new DomStatsSnapshot.Observation(true, false, true));
    }
}
