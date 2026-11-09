package com.subhub.app.stats;

import java.time.Instant;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionDurationProjectionTest {
    private long time(String value) { return Instant.parse(value).toEpochMilli(); }

    @Test public void unchangedSessionCountsOvernightWithoutScreenOrSyncInputs() {
        long start = time("2026-11-08T22:00:00Z"), now = start + 36 * 3600_000L;
        long seconds = SessionDurationProjection.activeSeconds(start, now);
        assertEquals(36 * 3600L, seconds);
        assertEquals(Map.of("2026-11-08", 2 * 3600_000L, "2026-11-09", 24 * 3600_000L,
                "2026-11-10", 10 * 3600_000L), SessionDurationProjection.slices(start, seconds, now, "UTC"));
    }

    @Test public void dstAndMidnightAreSplitAtActualCivilBoundaries() {
        long start = time("2026-10-24T22:00:00Z"), now = time("2026-10-25T23:00:00Z");
        assertEquals(Map.of("2026-10-25", 25 * 3600_000L),
                SessionDurationProjection.slices(start, 25 * 3600, now, "Europe/Berlin"));
    }

    @Test public void invalidFutureAndUnboundedClocksDoNotInventTime() {
        long now = time("2026-11-09T12:00:00Z");
        assertEquals(0, SessionDurationProjection.activeSeconds(now + 1, now));
        assertEquals(0, SessionDurationProjection.activeSeconds(0, now));
        assertEquals(0, SessionDurationProjection.activeSeconds(now - 367L * 86400_000, now));
        assertTrue(SessionDurationProjection.slices(now - 1000, 2, now, "UTC").isEmpty());
        assertTrue(SessionDurationProjection.slices(1, Long.MAX_VALUE, now, "UTC").isEmpty());
        assertTrue(SessionDurationProjection.slices(now - 1000, 1, now, "invalid").isEmpty());
    }

    @Test public void migrationReplacesOverlappingScreenEvidenceButRetainsUnmatchedHistory() {
        assertEquals(3000, SessionDurationProjection.legacyBaseline(5000, 3000, 4000));
        assertEquals(1000, SessionDurationProjection.legacyBaseline(5000, 0, 4000));
        assertEquals(0, SessionDurationProjection.legacyBaseline(5000, 0, 10000));
        assertEquals(5000, SessionDurationProjection.legacyBaseline(5000, 0, 0));
    }
}
