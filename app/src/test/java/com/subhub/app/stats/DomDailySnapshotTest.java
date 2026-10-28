package com.subhub.app.stats;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public final class DomDailySnapshotTest {
    private static final String DAY = "2026-10-28";

    private static DomDailySnapshot.History history(String peer, String generation, long order,
            boolean wallet, long milliseconds, long eur, long usd) {
        Map<String, Long> values = new LinkedHashMap<>();
        values.put("service_ms", milliseconds); values.put("sessions", 1L);
        values.put("paid_EUR", eur); values.put("paid_USD", usd);
        values.put("assessed_EUR", eur * 2); values.put("assessed_USD", usd * 2);
        return new DomDailySnapshot.History(new DomStatsSnapshot.Sub(peer, generation, order,
                0, 0, null, null), wallet, Collections.singletonMap(DAY, values));
    }

    @Test public void combinesDatedCountersAndKeepsPaidAssessedAndCurrenciesSeparate() {
        Map<String, Long> values = DomDailySnapshot.aggregate(Arrays.asList(
                history("a", "one", 1, true, 60_000, 100, 200),
                history("b", "one", 2, true, 120_000, 300, 400)), DAY, DAY).days.get(DAY);
        assertEquals(Long.valueOf(180_000), values.get("service_ms"));
        assertEquals(Long.valueOf(2), values.get("sessions"));
        assertEquals(Long.valueOf(400), values.get("paid_EUR"));
        assertEquals(Long.valueOf(600), values.get("paid_USD"));
        assertEquals(Long.valueOf(800), values.get("assessed_EUR"));
    }

    @Test public void duplicateInputsAndReplacedGenerationsNeverAccumulate() {
        DomDailySnapshot.History old = history("a", "old", 1, true, 30_000, 1, 2);
        DomDailySnapshot.History current = history("a", "new", 2, true, 60_000, 3, 4);
        for (java.util.List<DomDailySnapshot.History> inputs : Arrays.asList(
                Arrays.asList(old, current, current), Arrays.asList(current, old))) {
            Map<String, Long> values = DomDailySnapshot.aggregate(inputs, DAY, DAY).days.get(DAY);
            assertEquals(Long.valueOf(60_000), values.get("service_ms"));
            assertEquals(Long.valueOf(3), values.get("paid_EUR"));
            assertEquals(Long.valueOf(1), values.get("sessions"));
        }
    }

    @Test public void absentDatesStayUnknownInsteadOfCopyingLifetimeCounters() {
        DomDailySnapshot snapshot = DomDailySnapshot.aggregate(Collections.singletonList(
                history("a", "one", 1, true, 60_000, 1, 2)), "2026-10-27", DAY);
        assertEquals(DAY, snapshot.since);
        assertEquals(Long.valueOf(-1), snapshot.days.get("2026-10-27").get("service_ms"));
        assertEquals(Long.valueOf(60_000), snapshot.days.get(DAY).get("service_ms"));
    }

    @Test public void oneMissingPeerDateMakesTheCombinedDayUnknown() {
        DomDailySnapshot.History missing = new DomDailySnapshot.History(
                new DomStatsSnapshot.Sub("b", "one", 1, 0, 0, null, null), true, Collections.emptyMap());
        Map<String, Long> values = DomDailySnapshot.aggregate(Arrays.asList(
                history("a", "one", 1, true, 60_000, 1, 2), missing), DAY, DAY).days.get(DAY);
        assertEquals(Long.valueOf(-1), values.get("service_ms"));
        assertEquals(Long.valueOf(-1), values.get("paid_EUR"));
    }

    @Test public void walletOptOutExcludesAmountsWithoutDiscardingSessionTime() {
        Map<String, Long> values = DomDailySnapshot.aggregate(Arrays.asList(
                history("a", "one", 1, true, 60_000, 100, 200),
                history("b", "one", 2, false, 120_000, 999, 999)), DAY, DAY).days.get(DAY);
        assertEquals(Long.valueOf(180_000), values.get("service_ms"));
        assertEquals(Long.valueOf(100), values.get("paid_EUR"));
        assertEquals(Long.valueOf(200), values.get("paid_USD"));
        Map<String, Long> excluded = DomDailySnapshot.aggregate(Collections.singletonList(
                history("b", "one", 2, false, 120_000, 999, 999)), DAY, DAY).days.get(DAY);
        assertEquals(Long.valueOf(-1), excluded.get("paid_EUR"));
    }

    @Test public void aSharedSparseRecordIsKnownZeroWhileAnEmptyRosterIsAlsoKnownZero() {
        DomDailySnapshot.History sparse = new DomDailySnapshot.History(
                new DomStatsSnapshot.Sub("a", "one", 1, 0, 0, null, null), true,
                Collections.singletonMap(DAY, Collections.singletonMap("service_ms", 1L)));
        assertEquals(Long.valueOf(0), DomDailySnapshot.aggregate(Collections.singletonList(sparse), DAY, DAY)
                .days.get(DAY).get("sessions"));
        assertEquals(Long.valueOf(0), DomDailySnapshot.aggregate(Collections.emptyList(), DAY, DAY)
                .days.get(DAY).get("paid_EUR"));
    }

    @Test public void validLargeValuesSaturateWithoutWrapping() {
        DomDailySnapshot.History a = history("a", "one", 1, true, Long.MAX_VALUE, 0, 0);
        DomDailySnapshot.History b = history("b", "one", 1, true, 1, 0, 0);
        assertEquals(Long.valueOf(Long.MAX_VALUE), DomDailySnapshot.aggregate(Arrays.asList(a, b), DAY, DAY)
                .days.get(DAY).get("service_ms"));
    }

    @Test public void invalidRangesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> DomDailySnapshot.aggregate(Collections.emptyList(), DAY, "2026-10-27"));
        assertThrows(IllegalArgumentException.class,
                () -> DomDailySnapshot.aggregate(Collections.emptyList(), "2024-01-01", DAY));
    }
}
