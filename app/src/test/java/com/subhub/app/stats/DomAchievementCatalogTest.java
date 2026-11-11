package com.subhub.app.stats;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public final class DomAchievementCatalogTest {
    @Test public void domCatalogHasDistinctPositiveMilestonesAndDedicatedArtwork() {
        Set<String> ids = new HashSet<>(); Set<Integer> art = new HashSet<>();
        assertEquals(25, DomAchievementCatalog.all().size());
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

    @Test public void combinedTimeAndPaidTributeGoalsIncreaseExactlyTenfold() {
        assertEquals(36_000, AchievementManager.target("dom_time_1hr"));
        assertEquals(864_000, AchievementManager.target("dom_time_24hr"));
        assertEquals(6_048_000, AchievementManager.target("dom_time_7days"));
        assertEquals(25_920_000, AchievementManager.target("dom_time_30days"));
        assertEquals(10_000, AchievementManager.target("dom_tribute_10"));
        assertEquals(250_000, AchievementManager.target("dom_tribute_250"));
        assertEquals(1_000_000, AchievementManager.target("dom_tribute_1000"));
        assertEquals(10_000_000, AchievementManager.target("dom_tribute_10000"));
        assertEquals(10, AchievementManager.target("dom_sessions_10"));
        assertEquals(100, AchievementManager.target("dom_sessions_100"));
        assertEquals(3_600, AchievementManager.target("time_1hr"));
        assertEquals(1_000, AchievementManager.target("wallet_paid_10"));
    }

    @Test public void movedConfigurationBadgesKeepTheirPersistedIdsAndBelongOnlyToDom() {
        for (String id : Arrays.asList("first_custom_phrase", "phrase_library", "paypal_vault",
                "text_filter_enabled", "text_filter_catalog", "color_picker", "border_artist", "style_explorer")) {
            assertTrue(AchievementRole.DOM.owns(id)); assertFalse(AchievementRole.SUB.owns(id));
            assertTrue(DomAchievementCatalog.all().stream().anyMatch(value -> id.equals(value.getId())));
        }
    }

    @Test public void movedBadgesKeepRequirementsAndSharedPresentationGroups() {
        assertEquals(1, AchievementManager.target("text_filter_enabled"));
        assertEquals(3, AchievementManager.target("text_filter_catalog"));
        assertEquals(1, AchievementManager.target("color_picker"));
        assertEquals(4, AchievementManager.target("border_artist"));
        assertEquals(8, AchievementManager.target("style_explorer"));
        var groups = AchievementsPane.orderedGroups(DomAchievementCatalog.all());
        assertEquals(Arrays.asList("dom_subs", "dom_sessions", "dom_time", "dom_phrases",
                "dom_censor", "dom_appearance", "dom_wallet", "dom_hardcore"),
                new java.util.ArrayList<>(groups.keySet()));
        assertEquals(Arrays.asList("color_picker", "border_artist", "style_explorer"),
                groups.get("dom_appearance").stream().map(AchievementManager.Achievement::getId)
                        .collect(java.util.stream.Collectors.toList()));
        assertEquals(Arrays.asList("paypal_vault", "dom_tribute_10", "dom_tribute_250",
                "dom_tribute_1000", "dom_tribute_10000"),
                groups.get("dom_wallet").stream().map(AchievementManager.Achievement::getId)
                        .collect(java.util.stream.Collectors.toList()));
        assertFalse(groups.containsKey("dom_tribute"));
    }

    @Test public void appearanceProgressRequiresEveryOriginalChoice() {
        Set<String> styles = new HashSet<>(Arrays.asList("box", "blur", "pixelate", "custom",
                "static", "glitch", "tape", "unknown"));
        assertEquals(7, DomAchievementCatalog.appearanceCurrent("style_explorer", styles));
        styles.add("error_popup");
        assertEquals(8, DomAchievementCatalog.appearanceCurrent("style_explorer", styles));
        Set<String> borders = new HashSet<>(Arrays.asList("classic", "gradient", "glow", "unknown"));
        assertEquals(3, DomAchievementCatalog.appearanceCurrent("border_artist", borders));
        borders.add("rainbow");
        assertEquals(4, DomAchievementCatalog.appearanceCurrent("border_artist", borders));
    }

    private static DomStatsSnapshot.Sub sub(String id, long eur, long usd) {
        return new DomStatsSnapshot.Sub(id, "generation", 1, 1_000, 1_000,
                new DomStatsSnapshot.Totals(3_600, 5, eur, usd, eur, usd),
                new DomStatsSnapshot.Observation(true, false, true));
    }
}
