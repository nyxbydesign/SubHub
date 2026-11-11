package com.subhub.app.stats;

import static org.junit.Assert.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public final class ActivityDateHistoryTest {
    private static long time(String value) { return Instant.parse(value).toEpochMilli(); }
    private static Set<String> dates(String first, int count) {
        Set<String> values = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) values.add(LocalDate.parse(first).plusDays(i).toString());
        return values;
    }

    @Test public void modernCensorDatesCannotCreateTheMissingTravelDayBeforeOrAfterUpgrade() {
        long start = time("2025-11-05T00:00:00Z"), end = time("2025-11-10T01:00:00Z");
        long restart = time("2025-11-11T16:00:00Z"), closed = restart + 60_000;
        Set<String> raw = dates("2025-11-05", 7), encoded = new LinkedHashSet<>();
        for (String date : dates("2025-11-05", 6)) encoded.add("Asia/Tokyo;" + date);
        encoded.add("America/Los_Angeles;2025-11-11");
        for (Set<String> clocks : java.util.Arrays.asList(Collections.<String>emptySet(), encoded)) {
            RetainedStatistics history = new RetainedStatistics(ZoneId.of("America/Los_Angeles"), closed, Collections.emptyMap());
            history.session(new StatsRepository.SessionEntry(start, (end - start) / 1000, 6)
                    .identified("12345678-1234-1234-1234-123456789abc", "Asia/Tokyo"));
            history.session(new StatsRepository.SessionEntry(restart, 60, 1)
                    .identified("22345678-1234-1234-1234-123456789abc", "America/Los_Angeles"));
            history.legacyServiceDates(Collections.emptySet()); history.activeDates(raw);
            Set<String> legacy = ActivityDateHistory.legacy(raw, clocks, start);
            assertTrue(legacy.isEmpty()); history.legacyActivityDates(legacy);
            for (Map.Entry<String, Set<String>> dates : ActivityDateHistory.calendars(clocks).entrySet())
                history.calendarDates(dates.getKey(), dates.getValue());
            assertEquals(raw, history.activityDates());
            assertFalse(history.awards(false).containsKey("streak_7"));
            assertEquals(1, history.currentStreak(LocalDate.parse("2025-11-11"), "America/Los_Angeles", 1, "2025-11-11"));
        }
    }

    @Test public void genuineLegacyRunRemainsEligibleAlongsideAmbiguousModernCopies() {
        Set<String> legacy = dates("2025-07-01", 30), raw = new LinkedHashSet<>(legacy);
        raw.addAll(dates("2025-11-05", 7));
        Set<String> proof = ActivityDateHistory.legacy(raw, Collections.emptySet(), time("2025-11-05T00:00:00Z"));
        assertEquals(legacy, proof);
        RetainedStatistics history = new RetainedStatistics(ZoneId.of("UTC"), time("2025-12-01T00:00:00Z"), Collections.emptyMap());
        history.activeDates(raw); history.legacyActivityDates(proof);
        assertTrue(history.awards(false).get("streak_30") > 0);
        assertEquals(raw, history.activityDates());
    }

    @Test public void installationsWithoutModernOriginsKeepLegacyButKnownCopiesNeverBecomeLegacy() {
        Set<String> old = dates("2025-07-01", 30);
        assertEquals(old, ActivityDateHistory.legacy(old, Collections.emptySet(), 0));
        Set<String> clock = Collections.singleton("UTC;2025-07-30");
        Set<String> expected = dates("2025-07-01", 29);
        assertEquals(expected, ActivityDateHistory.legacy(old, clock, 0));
        assertTrue(ActivityDateHistory.calendars(Set.of("invalid", "bad;2025-07-30", "UTC;bad")).isEmpty());
        assertEquals(time("2025-07-01T00:00:00Z"), ActivityDateHistory.earliest(time("2025-08-01T00:00:00Z"), time("2025-07-01T00:00:00Z")));
    }

    @Test public void calendarLabelledCensorsStillProveARealHistoricalRun() {
        RetainedStatistics history = new RetainedStatistics(ZoneId.of("America/Los_Angeles"), time("2025-12-01T00:00:00Z"), Collections.emptyMap());
        Set<String> encoded = new LinkedHashSet<>();
        for (String date : dates("2025-11-05", 7)) encoded.add("Asia/Tokyo;" + date);
        for (Map.Entry<String, Set<String>> dates : ActivityDateHistory.calendars(encoded).entrySet())
            history.calendarDates(dates.getKey(), dates.getValue());
        assertTrue(history.awards(false).get("streak_7") > 0);
    }
}
