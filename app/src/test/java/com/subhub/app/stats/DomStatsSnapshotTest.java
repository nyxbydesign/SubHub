package com.subhub.app.stats;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class DomStatsSnapshotTest {
    private static final long NOW = 2_000_000;

    private static DomStatsSnapshot.Sub sub(String id, String generation, long order, long confirmed,
            long seconds, long sessions, long eur, long usd, Boolean hardcore, Boolean wallet) {
        return new DomStatsSnapshot.Sub(id, generation, order, confirmed, confirmed,
                new DomStatsSnapshot.Totals(seconds, sessions, eur, usd, eur * 2, usd * 2),
                new DomStatsSnapshot.Observation(true, hardcore, wallet));
    }

    @Test public void combinesDistinctSubsAndKeepsCurrenciesSeparate() {
        DomStatsSnapshot result = DomStatsSnapshot.aggregate(Arrays.asList(
                sub("a", "first", 1, NOW, 3_600, 2, 400, 500, true, true),
                sub("b", "second", 2, NOW, 7_200, 3, 600, 200, false, true)), NOW);
        assertEquals(2, result.subs); assertEquals(10_800, result.sessionSeconds);
        assertEquals(5, result.sessions); assertEquals(2, result.activeSubs);
        assertEquals(1, result.hardcoreSubs); assertEquals(2, result.hardcoreKnown);
        assertEquals(Long.valueOf(1_000), result.paidCents.get("EUR"));
        assertEquals(Long.valueOf(700), result.paidCents.get("USD"));
        assertEquals(1_000, result.leadingPaidCents()); assertEquals("EUR", result.leadingPaidCurrency());
        assertEquals(Long.valueOf(2_000), result.assessedCents.get("EUR"));
    }

    @Test public void repeatedReceiptsDoNotIncreaseCountersOrSubCount() {
        DomStatsSnapshot.Sub value = sub("a", "generation", 1, NOW, 3_600, 2, 1_000, 0, true, true);
        List<DomStatsSnapshot.Sub> repeats = new ArrayList<>();
        for (int i = 0; i < 100; i++) repeats.add(value);
        DomStatsSnapshot result = DomStatsSnapshot.aggregate(repeats, NOW);
        assertEquals(1, result.subs); assertEquals(3_600, result.sessionSeconds);
        assertEquals(2, result.sessions); assertEquals(1_000, result.leadingPaidCents());
        assertEquals(1, result.hardcoreSubs);
    }

    @Test public void rePairUsesOneCurrentGenerationInsteadOfAddingLifetimes() {
        DomStatsSnapshot.Sub old = sub("a", "old", 1, NOW, 100, 2, 100, 0, true, true);
        DomStatsSnapshot.Sub current = sub("a", "new", 2, NOW, 150, 3, 200, 0, false, true);
        for (List<DomStatsSnapshot.Sub> inputs : Arrays.asList(Arrays.asList(old, current), Arrays.asList(current, old))) {
            DomStatsSnapshot result = DomStatsSnapshot.aggregate(inputs, NOW);
            assertEquals(1, result.subs); assertEquals(150, result.sessionSeconds);
            assertEquals(3, result.sessions); assertEquals(200, result.leadingPaidCents());
            assertEquals(0, result.hardcoreSubs);
        }
    }

    @Test public void removalAndReentryRecomputeWithoutAnAccumulatingLedger() {
        DomStatsSnapshot.Sub value = sub("a", "generation", 1, NOW, 10, 2, 3, 4, true, true);
        assertEquals(10, DomStatsSnapshot.aggregate(Collections.singletonList(value), NOW).sessionSeconds);
        assertEquals(0, DomStatsSnapshot.empty().sessionSeconds);
        assertEquals(10, DomStatsSnapshot.aggregate(Collections.singletonList(value), NOW).sessionSeconds);
    }

    @Test public void olderTotalsRemainExplicitlyStaleButCurrentStatusExpires() {
        DomStatsSnapshot result = DomStatsSnapshot.aggregate(Collections.singletonList(
                sub("a", "generation", 1, NOW - 1_900_000, 100, 2, 0, 0, true, true)), NOW);
        assertEquals(100, result.sessionSeconds); assertEquals(1, result.totalsKnown);
        assertEquals(0, result.totalsFresh); assertEquals(0, result.activeKnown);
        assertEquals(0, result.hardcoreKnown); assertEquals(0, result.hardcoreSubs);
    }

    @Test public void futureOrMissingReceiptTimesCannotConfirmLiveHardcore() {
        for (long timestamp : new long[]{0, NOW + 1}) {
            DomStatsSnapshot result = DomStatsSnapshot.aggregate(Collections.singletonList(
                    sub("a", "generation", 1, timestamp, 100, 1, 0, 0, true, true)), NOW);
            assertEquals(0, result.hardcoreKnown); assertEquals(0, result.activeSubs);
        }
    }

    @Test public void legacyPeerWithoutHardcoreFieldStaysUnknown() {
        DomStatsSnapshot result = DomStatsSnapshot.aggregate(Collections.singletonList(
                sub("a", "generation", 1, NOW, 100, 1, 0, 0, null, true)), NOW);
        assertEquals(1, result.activeKnown); assertEquals(0, result.hardcoreKnown);
        assertFalse(result.hasHardcoreStatus());
    }

    @Test public void walletOptOutAndUnknownConsentExcludeFinancialTotals() {
        for (Boolean allowed : Arrays.asList(false, null)) {
            DomStatsSnapshot result = DomStatsSnapshot.aggregate(Collections.singletonList(
                    sub("a", "generation", 1, NOW, 100, 1, 10_000, 20_000, true, allowed)), NOW);
            assertEquals(100, result.sessionSeconds); assertEquals(0, result.walletKnown);
            assertEquals(0, result.leadingPaidCents()); assertFalse(result.hasWalletTotals());
        }
    }

    @Test public void emptyRosterIsKnownZeroAndMissingSnapshotsAreUnknown() {
        assertTrue(DomStatsSnapshot.empty().hasTotals());
        assertTrue(DomStatsSnapshot.empty().hasHardcoreStatus());
        DomStatsSnapshot result = DomStatsSnapshot.aggregate(Collections.singletonList(
                new DomStatsSnapshot.Sub("a", "generation", 1, 0, 0, null, null)), NOW);
        assertEquals(1, result.subs); assertFalse(result.hasTotals());
        assertFalse(result.hasWalletTotals()); assertFalse(result.hasHardcoreStatus());
    }

    @Test public void countsDoNotOverflowWhenSummingValidLongCounters() {
        DomStatsSnapshot.Totals large = new DomStatsSnapshot.Totals(Long.MAX_VALUE, Long.MAX_VALUE,
                Long.MAX_VALUE, 0, Long.MAX_VALUE, 0);
        DomStatsSnapshot.Observation live = new DomStatsSnapshot.Observation(false, false, true);
        DomStatsSnapshot result = DomStatsSnapshot.aggregate(Arrays.asList(
                new DomStatsSnapshot.Sub("a", "one", 1, NOW, NOW, large, live),
                new DomStatsSnapshot.Sub("b", "two", 2, NOW, NOW, large, live)), NOW);
        assertEquals(Long.MAX_VALUE, result.sessionSeconds); assertEquals(Long.MAX_VALUE, result.sessions);
        assertEquals(Long.MAX_VALUE, result.leadingPaidCents());
    }

    @Test public void negativeInputsAreRejectedInsteadOfCancellingAnotherSubsTotals() {
        assertThrows(IllegalArgumentException.class,
                () -> new DomStatsSnapshot.Totals(-1, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new DomStatsSnapshot.Totals(0, 0, -1, 0, 0, 0));
    }
}
