package com.subhub.app.runtime;

import static org.junit.Assert.*;
import com.subhub.app.stats.OwnerObservation;
import java.time.Instant;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class WhisperObservationTest {
    @Test public void delayedWriterRetainsActualImpressionDayAndCalendar() {
        TimeZone before = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            long at = Instant.parse("2026-11-11T07:59:59Z").toEpochMilli();
            AtomicInteger reads = new AtomicInteger(); AtomicReference<OwnerObservation> recorded = new AtomicReference<>();
            Runnable queued = WhisperObservation.capture(() -> { reads.incrementAndGet(); return at; }, recorded::set);
            assertNull(recorded.get()); assertEquals(1, reads.get());
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            queued.run();
            assertEquals(at, recorded.get().at); assertEquals("America/Los_Angeles", recorded.get().zone);
            assertEquals("2026-11-10", recorded.get().day()); assertEquals(1, reads.get());
        } finally { TimeZone.setDefault(before); }
    }

    @Test public void separateImpressionsRetainTheirSeparateObservation() {
        TimeZone before = TimeZone.getDefault();
        try {
            AtomicReference<OwnerObservation> one = new AtomicReference<>(), two = new AtomicReference<>();
            long at = Instant.parse("2026-11-11T07:59:59Z").toEpochMilli();
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            Runnable first = WhisperObservation.capture(() -> at, one::set);
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            Runnable second = WhisperObservation.capture(() -> at + 2_000, two::set);
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            second.run(); first.run();
            assertEquals("2026-11-10", one.get().day()); assertEquals("2026-11-11", two.get().day());
            assertEquals(at + 2_000, two.get().at); assertEquals("Asia/Tokyo", two.get().zone);
        } finally { TimeZone.setDefault(before); }
    }

    @Test public void invalidObservationCannotReachCounter() {
        assertThrows(IllegalArgumentException.class, () -> WhisperObservation.capture(() -> 0, ignored -> fail()));
    }
}
