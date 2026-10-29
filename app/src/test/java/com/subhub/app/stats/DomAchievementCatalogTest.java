package com.subhub.app.stats;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public final class DomAchievementCatalogTest {
    @Test public void earnedDomProgressUsesObservedTotalsAndKeepsUnavailableTotalsUnknown() {
        AchievementManager.Progress unavailable = new AchievementManager.Progress(
                0, 360_000, true, false, false);
        assertTrue(unavailable.isUnlocked());
        assertFalse(unavailable.isKnown());
        assertEquals(0, unavailable.getCurrent());
        AchievementManager.Progress observed = new AchievementManager.Progress(
                268_920, 360_000, true, true, false);
        assertTrue(observed.isKnown());
        assertEquals(268_920, observed.getCurrent());
        assertEquals(75, observed.percent());
        assertEquals(360_000, new AchievementManager.Progress(0, 360_000, true, false).getCurrent());
    }

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
                sub("a", 5_000, 0), sub("b", 6_000, 0)), 1_000);
        assertEquals(11_000, DomAchievementCatalog.current("dom_tribute_10", snapshot));
        assertEquals(10_000, AchievementManager.target("dom_tribute_10"));
    }

    @Test public void sessionTimeAndCountUseDifferentExistingFacts() {
        DomStatsSnapshot snapshot = DomStatsSnapshot.aggregate(Arrays.asList(
                sub("a", 0, 0), sub("b", 0, 0)), 1_000);
        assertEquals(7_200, DomAchievementCatalog.current("dom_time_1hr", snapshot));
        assertEquals(10, DomAchievementCatalog.current("dom_sessions_10", snapshot));
        assertEquals(2, DomAchievementCatalog.current("dom_subs_3", snapshot));
    }

    @Test public void allNumericFamiliesRiseAndKeepTheirStableIds() {
        String[][] families = {{"dom_subs_1","dom_subs_3","dom_subs_5","dom_subs_10"},
                {"dom_time_1hr","dom_time_24hr","dom_time_7days","dom_time_30days"},
                {"dom_sessions_10","dom_sessions_100"},
                {"dom_tribute_10","dom_tribute_250","dom_tribute_1000","dom_tribute_10000"},
                {"dom_hardcore_1","dom_hardcore_3","dom_hardcore_5"}};
        for(String[] family:families) {
            long previous=0;
            for(String id:family) {
                long goal=DomAchievementCatalog.target(id);
                assertTrue(id,goal>previous); assertTrue(id,goal>DomAchievementCatalog.earlierTarget(id));
                previous=goal;
            }
        }
        assertEquals(360_000,DomAchievementCatalog.target("dom_time_1hr"));
        assertEquals(250,DomAchievementCatalog.target("dom_sessions_10"));
        assertEquals(25,DomAchievementCatalog.target("dom_subs_10"));
        assertEquals(10,DomAchievementCatalog.target("dom_hardcore_5"));
    }

    @Test public void aSingleSubsReportedTotalsDoNotClearAggregateGoals() {
        DomStatsSnapshot snapshot=DomStatsSnapshot.aggregate(Arrays.asList(new DomStatsSnapshot.Sub("local","on-device",1,1000,1000,
                new DomStatsSnapshot.Totals(268_920,100,9_999,0,9_999,0),
                new DomStatsSnapshot.Observation(true,true,true))),1000);
        for(AchievementManager.Achievement value:DomAchievementCatalog.all())
            assertTrue(value.getId(),DomAchievementCatalog.current(value.getId(),snapshot)<DomAchievementCatalog.target(value.getId()));
    }

    @Test public void earnedDomProgressDoesNotFabricateCurrentFacts() {
        AchievementManager.Progress progress=new AchievementManager.Progress(268_920,360_000,true,true,false);
        assertTrue(progress.isUnlocked()); assertEquals(268_920,progress.getCurrent());
        assertTrue(progress.percent()<100); assertEquals(360_000,progress.getTarget());
    }

    private static DomStatsSnapshot.Sub sub(String id, long eur, long usd) {
        return new DomStatsSnapshot.Sub(id, "generation", 1, 1_000, 1_000,
                new DomStatsSnapshot.Totals(3_600, 5, eur, usd, eur, usd),
                new DomStatsSnapshot.Observation(true, false, true));
    }
}
