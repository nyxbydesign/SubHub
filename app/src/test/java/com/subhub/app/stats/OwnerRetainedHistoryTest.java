package com.subhub.app.stats;

import static org.junit.Assert.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.Test;

/** Pure owner projection contracts; legacy recovery continues to use the existing constructor. */
public final class OwnerRetainedHistoryTest {
    private static final String ID = "00000000-0000-0000-0000-000000000001";
    @Test public void exactDailyFactsDoNotGainAnInferredSessionCalendarCopy() {
        long at = Instant.parse("2024-04-09T12:00:00Z").toEpochMilli();
        RetainedStatistics exact = new RetainedStatistics(ZoneId.of("UTC"), at + 86_400_000,
                Map.of("2024-04-10", Map.of("limits_ms", 125L, "assessed_EUR", 100L, "assessed_USD", 150L)), false);
        exact.session(new StatsRepository.SessionEntry(at, 60, 0, 125, 0, 2, 100, 0, 0, 0).identified(ID, "UTC"));
        assertEquals(125, exact.total("limits_ms")); assertEquals(100, exact.total("assessed_EUR"));
        assertEquals(150, exact.total("assessed_USD")); assertEquals(60, exact.sessionSeconds());
    }
    @Test public void legacyConstructorStillRecoversUndatedSessionEventFacts() {
        long at = Instant.parse("2024-04-09T12:00:00Z").toEpochMilli();
        RetainedStatistics legacy = new RetainedStatistics(ZoneId.of("UTC"), at + 86_400_000, Map.of());
        legacy.session(new StatsRepository.SessionEntry(at, 60, 5, 125, 0, 2, 100, 0, 0, 0).identified(ID, "UTC"));
        assertEquals(125, legacy.total("limits_ms")); assertEquals(5, legacy.total("censors"));
        assertEquals(100, legacy.total("assessed_EUR"));
    }
    @Test public void acknowledgedOwnerDurationSurvivesBackwardWallObservationAndOlderActiveCopy() {
        long at = Instant.parse("2024-04-09T12:00:00Z").toEpochMilli();
        StatsRepository.SessionEntry known = new StatsRepository.SessionEntry(at, 120, 0).identified(ID, "UTC");
        RetainedStatistics exact = OwnerRetainedHistory.project(Map.of("session:" + ID, OwnerRetainedHistory.encode(known)), at + 60_000);
        exact.session(new StatsRepository.SessionEntry(at, 60, 0).identified(ID, "UTC"));
        assertEquals(120, exact.sessionSeconds()); assertEquals(120_000, exact.total("service_ms"));
    }
    @Test public void capturedClockRetainsCivilDateIndependentOfCurrentDefaultCalendar() {
        long at = Instant.parse("2024-04-09T12:00:00Z").toEpochMilli();
        assertEquals("2024-04-09", new OwnerObservation(at, "UTC").day());
        assertEquals("2024-04-10", new OwnerObservation(at, "Pacific/Kiritimati").day());
        assertThrows(IllegalArgumentException.class, () -> new OwnerObservation(0, "UTC"));
    }
    @Test public void corruptOwnedHistoryCannotSilentlyBecomeEmptyLegacyEvidence() {
        assertThrows(IllegalStateException.class, () -> OwnerRetainedHistory.project(Map.of("session:" + ID, "corrupt"), 1000));
    }
}
