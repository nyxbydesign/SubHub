package com.subhub.app.stats;

import static org.junit.Assert.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import org.junit.Test;

public final class RetainedStatisticsTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final long NOW = Instant.parse("2026-11-01T12:00:00Z").toEpochMilli();

    private RetainedStatistics empty() { return new RetainedStatistics(UTC, NOW, Collections.emptyMap()); }
    private long time(String value) { return Instant.parse(value).toEpochMilli(); }

    @Test public void overlappingDailyAndSessionCopiesAreNotAddedTogether() {
        RetainedStatistics history = new RetainedStatistics(UTC, NOW,
                Collections.singletonMap("2026-08-01", Map.of("service_ms", 3_600_000L, "sessions", 1L, "censors", 5L)));
        StatsRepository.SessionEntry session = new StatsRepository.SessionEntry(time("2026-08-01T10:00:00Z"), 3600, 5);
        history.session(session); history.session(session);
        assertEquals(3600, history.sessionSeconds()); assertEquals(1, history.total("sessions"));
        assertEquals(5, history.total("censors")); assertEquals(1, history.sessions().size());
    }

    @Test public void richerLegacyCopyKeepsItsCountersWithoutCreatingAnotherSession() {
        RetainedStatistics history = empty(); long start = time("2026-08-01T10:00:00Z");
        history.session(new StatsRepository.SessionEntry(start, 3600, 5));
        history.session(new StatsRepository.SessionEntry(start, 3600, 5, 1000, 2, 3, 400, 6, 7, 8));
        assertEquals(1, history.sessions().size()); assertEquals(1, history.total("sessions"));
        assertEquals(1000, history.total("limits_ms")); assertEquals(400, history.total("assessed_EUR"));
        assertEquals(6, history.total("whispers"));
    }

    @Test public void aClockReusedByDistinctOriginalTuplesDoesNotEraseASession() {
        RetainedStatistics history = empty(); long start = time("2026-08-01T10:00:00Z");
        history.session(new StatsRepository.SessionEntry(start, 3600, 5));
        history.session(new StatsRepository.SessionEntry(start, 1800, 2));
        assertEquals(2, history.sessions().size()); assertEquals(5400, history.sessionSeconds());
    }

    @Test public void realIntervalsSplitAtMidnightWithoutInventingCrossDayEventClocks() {
        RetainedStatistics history = empty();
        history.session(new StatsRepository.SessionEntry(time("2026-08-01T23:30:00Z"), 3600, 5));
        assertEquals(Long.valueOf(1_800_000), history.days().get("2026-08-01").get("service_ms"));
        assertEquals(Long.valueOf(1_800_000), history.days().get("2026-08-02").get("service_ms"));
        assertFalse(history.days().get("2026-08-01").containsKey("censors"));
        assertEquals(0L, (long) history.awards(false).get("first_block"));
    }

    @Test public void midnightEndIsExclusiveAndDstUsesElapsedTime() {
        RetainedStatistics midnight = empty();
        midnight.session(new StatsRepository.SessionEntry(time("2026-08-01T23:30:00Z"), 1800, 5));
        assertEquals(5, midnight.total("censors"));
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        RetainedStatistics dst = new RetainedStatistics(berlin, NOW, Collections.emptyMap());
        long start = LocalDate.of(2026, 3, 28).atTime(23, 30).atZone(berlin).toInstant().toEpochMilli();
        dst.session(new StatsRepository.SessionEntry(start, 10_800, 0));
        assertEquals(Long.valueOf(1_800_000), dst.days().get("2026-03-28").get("service_ms"));
        assertEquals(Long.valueOf(9_000_000), dst.days().get("2026-03-29").get("service_ms"));
    }

    @Test public void replayedEventsAndOverlappingRepresentationsStayALowerBound() {
        RetainedStatistics history = new RetainedStatistics(UTC, NOW,
                Collections.singletonMap("2026-08-01", Map.of("assessed_EUR", 100L, "photos", 1L)));
        history.event("assessed", "tribute_original", "2026-08-01", "assessed_EUR", 100);
        history.event("assessed", "tribute_original", "2026-08-01", "assessed_EUR", 100);
        history.event("assessed", "tribute_second", "2026-08-01", "assessed_EUR", 200);
        history.event("exports", "job:1", "2026-08-01", "photos", 1);
        history.event("exports", "job:2", "2026-08-01", "photos", 1);
        assertEquals(300, history.total("assessed_EUR")); assertEquals(2, history.total("photos"));
    }

    @Test public void onlyRealDatedPaymentRowsCanDatePaymentAchievementsAndCurrenciesNeverMix() {
        RetainedStatistics history = empty();
        history.event("assessed", "paid_debt_creation", "2026-08-01", "assessed_EUR", 50_000);
        assertEquals(0L, (long) history.awards(false).get("wallet_paid_10"));
        history.mergeRecorded(Map.of("2026-08-01", Map.of("paid_EUR", 500L, "paid_USD", 24_500L)));
        assertEquals(0L, (long) history.awards(false).get("wallet_paid_100"));
        assertTrue(history.awards(false).get("wallet_paid_10") > 0);
    }

    @Test public void historicStreakWeekendAndHolidayNeedRecordedDays() {
        RetainedStatistics history = empty(); java.util.Set<String> dates = new LinkedHashSet<>();
        for (int day = 0; day < 30; day++) dates.add(LocalDate.of(2026, 8, 1).plusDays(day).toString());
        history.activeDates(dates);
        history.mergeRecorded(Map.of("2025-12-25", Map.of("censors", 1L)));
        Map<String, Long> awards = history.awards(false);
        assertTrue(awards.get("streak_30") > 0); assertTrue(awards.get("weekend_warrior") > 0);
        assertTrue(awards.get("christmas_block") > 0);
        assertFalse(awards.containsKey("midnight_blocker")); assertFalse(awards.containsKey("early_bird"));
    }

    @Test public void absentAndInvalidDatesCannotTurnLifetimeValuesIntoACalendar() {
        RetainedStatistics history = empty();
        history.event("exports", "unknown", "", "photos", 1);
        history.event("exports", "future", "2027-01-01", "photos", 1);
        history.session(new StatsRepository.SessionEntry(NOW + 1, 1, 1));
        history.session(new StatsRepository.SessionEntry(1, Long.MAX_VALUE, 1));
        assertTrue(history.days().isEmpty()); assertEquals(0, history.total("photos"));
        for (long value : history.awards(true).values()) assertEquals(0, value);
    }

    @Test public void thresholdsUseChronologicalSourceDatesAndSaturate() {
        Map<String, Map<String, Long>> days = new LinkedHashMap<>();
        days.put("2026-08-03", Map.of("service_ms", 180_000_000L));
        days.put("2026-08-01", Map.of("service_ms", 180_000_000L));
        days.put("2026-08-02", Map.of("service_ms", 180_000_000L));
        RetainedStatistics history = new RetainedStatistics(UTC, NOW, days);
        assertEquals(time("2026-08-03T00:00:00Z") - 1, (long) history.awards(true).get("dom_time_1hr"));
        history.event("large", "a", "2026-08-01", "photos", Long.MAX_VALUE);
        history.event("large", "b", "2026-08-01", "photos", 1);
        assertEquals(Long.MAX_VALUE, history.total("photos"));
    }
}
