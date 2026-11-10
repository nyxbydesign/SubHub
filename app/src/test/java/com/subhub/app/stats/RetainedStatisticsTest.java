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

    @Test public void identifiedSessionsWithTheSameLegacyTupleRemainDistinct() {
        RetainedStatistics history = empty(); long start = time("2026-08-01T10:00:00Z");
        history.session(new StatsRepository.SessionEntry(start, 60, 0)
                .identified("12345678-1234-1234-1234-123456789abc", "UTC"));
        history.session(new StatsRepository.SessionEntry(start, 60, 0)
                .identified("22345678-1234-1234-1234-123456789abc", "UTC"));
        assertEquals(2, history.sessions().size()); assertEquals(120, history.sessionSeconds());
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
        history.activeDates(dates); history.legacyActivityDates(dates);
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
        days.put("2026-08-03", Map.of("service_ms", 36_000_000L));
        days.put("2026-08-01", Map.of("service_ms", 18_000_000L));
        days.put("2026-08-02", Map.of("service_ms", 18_000_000L));
        RetainedStatistics history = new RetainedStatistics(UTC, NOW, days);
        assertEquals(time("2026-08-03T00:00:00Z") - 1, (long) history.awards(true).get("dom_time_1hr"));
        history.event("large", "a", "2026-08-01", "photos", Long.MAX_VALUE);
        history.event("large", "b", "2026-08-01", "photos", 1);
        assertEquals(Long.MAX_VALUE, history.total("photos"));
    }
    @Test public void composedStreakUsesItsCapturedCalendarAcrossTheExpiryBoundary() {
        long start = time("2026-11-03T00:00:00Z"), end = time("2026-11-10T01:00:00Z");
        ZoneId tokyo = ZoneId.of("Asia/Tokyo"), losAngeles = ZoneId.of("America/Los_Angeles");
        for (String instant : new String[]{"2026-11-10T01:00:00Z", "2026-11-11T14:59:59Z",
                "2026-11-11T15:00:00Z", "2026-11-12T01:00:00Z"}) {
            long now = time(instant);
            LocalDate today = Instant.ofEpochMilli(now).atZone(tokyo).toLocalDate();
            RetainedStatistics history = new RetainedStatistics(losAngeles, now, Collections.emptyMap());
            history.session(new StatsRepository.SessionEntry(start, (end - start) / 1000, 0)
                    .identified("12345678-1234-1234-1234-123456789abc", tokyo.getId()));
            int expected = today.isBefore(LocalDate.parse("2026-11-12")) ? 8 : 0;
            StatsSnapshot original = streakSnapshot(expected, today);
            StatsSnapshot composed = original.withRetained(history);
            assertEquals(instant, expected, composed.getCurrentStreak());
            assertEquals(instant, expected, composed.withRetained(history).getCurrentStreak());
            assertFalse("Travel cannot create an extra session day", history.activityDates().contains("2026-11-02"));
            assertEquals(Long.valueOf(1), history.days().get("2026-11-03").get("sessions"));
            if (instant.equals("2026-11-12T01:00:00Z")) {
                // Reproject timestamped sessions instead of reinterpreting their civil-date labels.
                assertEquals(0, history.currentStreak(Instant.ofEpochMilli(now).atZone(losAngeles).toLocalDate(), losAngeles.getId(), 0, ""));
                assertEquals(0, composed.getCurrentStreak());
            }
        }
    }

    @Test public void retainedStreakRecoveryPreservesLiveProgressAndLegacyFallback() {
        RetainedStatistics history = new RetainedStatistics(ZoneId.of("America/Los_Angeles"),
                time("2026-11-12T01:00:00Z"), Collections.emptyMap());
        history.session(new StatsRepository.SessionEntry(time("2026-11-03T00:00:00Z"),
                (time("2026-11-10T01:00:00Z") - time("2026-11-03T00:00:00Z")) / 1000, 0)
                .identified("12345678-1234-1234-1234-123456789abc", "Asia/Tokyo"));
        assertEquals(9, streakSnapshot(9, LocalDate.parse("2026-11-11")).withRetained(history).getCurrentStreak());
        assertEquals(8, streakSnapshot(0, LocalDate.parse("2026-11-11")).withRetained(history).getCurrentStreak());
        assertEquals(0, streakSnapshot(0, null).withRetained(history).getCurrentStreak());
    }

    @Test public void eastwardCensorAndUnzonedDailyDatesCannotReviveAnExpiredStreak() {
        long start = time("2026-11-03T00:00:00Z"), end = time("2026-11-10T13:00:00Z");
        ZoneId tokyo = ZoneId.of("Asia/Tokyo"), auckland = ZoneId.of("Pacific/Auckland");
        String deviceActivityDay = Instant.ofEpochMilli(end - 1000).atZone(auckland).toLocalDate().toString();
        assertEquals("2026-11-11", deviceActivityDay);
        for (String observed : new String[]{"2026-11-10T13:00:00Z", "2026-11-11T14:59:59Z",
                "2026-11-11T15:00:00Z", "2026-11-12T01:00:00Z"}) {
            long now = time(observed);
            LocalDate today = Instant.ofEpochMilli(now).atZone(tokyo).toLocalDate();
            RetainedStatistics history = new RetainedStatistics(auckland, now,
                    Collections.singletonMap(deviceActivityDay, Map.of("service_ms", 1000L)));
            history.session(new StatsRepository.SessionEntry(start, (end - start) / 1000, 1)
                    .identified("12345678-1234-1234-1234-123456789abc", tokyo.getId()));
            history.activeDates(Collections.singleton(deviceActivityDay));
            int expected = today.isBefore(LocalDate.parse("2026-11-12")) ? 8 : 0;
            StatsSnapshot composed = streakSnapshot(expected, today).withRetained(history);
            assertEquals(observed, expected, composed.getCurrentStreak());
            assertEquals(observed, expected, composed.withRetained(history).getCurrentStreak());
            assertTrue("Keep historical activity evidence", composed.getActiveDates().contains(deviceActivityDay));
            assertTrue("Historical streak awards remain eligible", history.awards(false).get("streak_7") > 0);
        }
    }

    @Test public void dateOnlyEvidenceRetainsHistoricalAwardsWithoutInventingCurrentContinuity() {
        Map<String, Map<String, Long>> days = new LinkedHashMap<>();
        java.util.Set<String> activity = new LinkedHashSet<>();
        for (int i = 0; i < 30; i++) {
            String date = LocalDate.parse("2026-10-13").plusDays(i).toString();
            days.put(date, Map.of("service_ms", 1000L)); activity.add(date);
        }
        RetainedStatistics history = new RetainedStatistics(ZoneId.of("Pacific/Auckland"),
                time("2026-11-11T15:00:00Z"), days);
        history.activeDates(activity);
        assertEquals(0, streakSnapshot(0, LocalDate.parse("2026-11-12")).withRetained(history).getCurrentStreak());
        assertTrue(history.awards(false).get("streak_30") > 0);
        assertEquals(activity, history.activityDates());
    }

    @Test public void timestampedHistoryJoinsAnOngoingKnownStreakWithoutDateOnlyEvidence() {
        RetainedStatistics history = new RetainedStatistics(ZoneId.of("Pacific/Auckland"),
                time("2026-11-11T01:00:00Z"), Collections.emptyMap());
        history.session(new StatsRepository.SessionEntry(time("2026-11-03T00:00:00Z"),
                (time("2026-11-10T13:00:00Z") - time("2026-11-03T00:00:00Z")) / 1000, 0)
                .identified("12345678-1234-1234-1234-123456789abc", "Asia/Tokyo"));
        assertEquals(9, history.currentStreak(LocalDate.parse("2026-11-11"), "Asia/Tokyo", 1, "2026-11-11"));
        assertEquals(8, history.currentStreak(LocalDate.parse("2026-11-11"), "Asia/Tokyo", 0, ""));
        assertEquals(Integer.MAX_VALUE, history.currentStreak(LocalDate.parse("2026-11-11"),
                "Asia/Tokyo", Integer.MAX_VALUE, "2026-11-11"));
    }

    @Test public void recoveredProgressNeverBecomesNewEvidenceOnRepeatedComposition() {
        long start = time("2026-11-03T00:00:00Z"), end = time("2026-11-11T01:00:00Z");
        RetainedStatistics history = new RetainedStatistics(ZoneId.of("Pacific/Auckland"), end, Collections.emptyMap());
        history.session(new StatsRepository.SessionEntry(start, (end - start) / 1000, 0)
                .identified("12345678-1234-1234-1234-123456789abc", "Asia/Tokyo"));
        StatsSnapshot recovered = streakSnapshot(1, LocalDate.parse("2026-11-11"));
        for (int i = 0; i < 5; i++) {
            recovered = recovered.withRetained(history);
            assertEquals(9, recovered.getCurrentStreak());
        }
    }

    @Test public void changedZoneRestartCompositionCannotBridgeTheActualSessionGap() {
        long start = time("2026-11-03T00:00:00Z"), end = time("2026-11-10T01:00:00Z");
        long restart = time("2026-11-11T16:00:00Z");
        SessionStreak first = SessionStreak.through(1, "2026-11-03", "Asia/Tokyo", start, end, "Asia/Tokyo");
        SessionStreak next = SessionStreak.through(first.days, first.lastDay, "Asia/Tokyo",
                restart, restart, "America/Los_Angeles");
        RetainedStatistics history = new RetainedStatistics(ZoneId.of("America/Los_Angeles"),
                restart, Collections.emptyMap());
        history.session(new StatsRepository.SessionEntry(start, (end - start) / 1000, 0)
                .identified("12345678-1234-1234-1234-123456789abc", "Asia/Tokyo"));
        StatsSnapshot current = streakSnapshot(next.days, LocalDate.parse("2026-11-11"),
                next.lastDay, "America/Los_Angeles");
        for (int i = 0; i < 5; i++) {
            current = current.withRetained(history);
            assertEquals(1, current.getCurrentStreak());
        }
        assertTrue("Old streak remains historically eligible", history.awards(false).get("streak_7") > 0);
    }

    @Test public void mixedSessionCalendarsCannotManufactureAHistoricalSevenDayAward() {
        long start = time("2026-11-05T00:00:00Z"), end = time("2026-11-10T01:00:00Z");
        long restart = time("2026-11-11T16:00:00Z"), closed = restart + 60_000;
        for (String displayZone : new String[]{"Asia/Tokyo", "America/Los_Angeles", "Pacific/Auckland"}) {
            RetainedStatistics history = new RetainedStatistics(ZoneId.of(displayZone), closed, Collections.emptyMap());
            history.session(new StatsRepository.SessionEntry(start, (end - start) / 1000, 0)
                    .identified("12345678-1234-1234-1234-123456789abc", "Asia/Tokyo"));
            history.session(new StatsRepository.SessionEntry(restart, 60, 0)
                    .identified("22345678-1234-1234-1234-123456789abc", "America/Los_Angeles"));
            assertEquals(7, history.activityDates().size()); // Display labels are not streak proof.
            for (int i = 0; i < 3; i++) {
                assertFalse(displayZone, history.awards(false).containsKey("streak_7"));
                assertFalse(history.awards(false).containsKey("streak_30"));
            }
            // Persisting the daily presentation must not turn it into independent legacy evidence.
            Map<String, Map<String, Long>> copied = history.days();
            history.mergeRecorded(copied);
            history.legacyServiceDates(Collections.emptySet());
            assertFalse(history.awards(false).containsKey("streak_7"));
        }
    }

    @Test public void timestampedHistoryEarnsAStreakOnlyWithinOneCoherentCalendar() {
        long start = time("2026-11-05T00:00:00Z"), end = time("2026-11-10T01:00:00Z");
        long next = time("2026-11-11T01:00:00Z"); // Nov 11 Tokyo, Nov 10 LA: no missed day.
        RetainedStatistics history = new RetainedStatistics(ZoneId.of("Pacific/Auckland"), next, Collections.emptyMap());
        history.session(new StatsRepository.SessionEntry(start, (end - start) / 1000, 0)
                .identified("12345678-1234-1234-1234-123456789abc", "Asia/Tokyo"));
        history.session(new StatsRepository.SessionEntry(next, 0, 0)
                .identified("22345678-1234-1234-1234-123456789abc", "America/Los_Angeles"));
        assertTrue(history.awards(false).get("streak_7") > 0);
        assertFalse(history.awards(false).containsKey("streak_30"));
    }

    @Test public void legacyHistoryStandsOnItsOwnWithoutBorrowingTimestampedDays() {
        Map<String, Map<String, Long>> days = new LinkedHashMap<>();
        for (int i = 0; i < 6; i++) days.put(LocalDate.parse("2026-08-01").plusDays(i).toString(), Map.of("service_ms", 1000L));
        RetainedStatistics history = new RetainedStatistics(UTC, NOW, days);
        history.session(new StatsRepository.SessionEntry(time("2026-08-07T12:00:00Z"), 60, 0));
        assertFalse("Unknown calendars cannot supply a missing day", history.awards(false).containsKey("streak_7"));
        for (int i = 6; i < 30; i++) days.put(LocalDate.parse("2026-08-01").plusDays(i).toString(), Map.of("service_ms", 1000L));
        history.mergeRecorded(days);
        assertTrue("A genuine date-only legacy run is still eligible", history.awards(false).get("streak_30") > 0);
    }

    @Test public void prunedSessionLedgerPreservesCoherentHistoryWithoutCombiningTravelLabels() {
        RetainedStatistics history = new RetainedStatistics(ZoneId.of("America/Los_Angeles"),
                time("2026-11-12T01:00:00Z"), Collections.emptyMap());
        java.util.Set<String> tokyo = new LinkedHashSet<>();
        for (int i = 0; i < 6; i++) tokyo.add(LocalDate.parse("2026-11-05").plusDays(i).toString());
        history.calendarDates("Asia/Tokyo", tokyo);
        history.calendarDates("America/Los_Angeles", Collections.singleton("2026-11-11"));
        assertFalse(history.awards(false).containsKey("streak_7"));
        history.calendarDates("invalid", Collections.singleton("2026-11-11"));
        history.calendarDates("Asia/Tokyo", Collections.singleton("bad-date"));
        assertFalse(history.awards(false).containsKey("streak_7"));
        history.calendarDates("Asia/Tokyo", Collections.singleton("2026-11-11"));
        assertTrue("A valid stored calendar remains eligible after session-history pruning", history.awards(false).get("streak_7") > 0);
    }

    private StatsSnapshot streakSnapshot(int currentStreak, LocalDate today) {
        return streakSnapshot(currentStreak, today, "2026-11-10", "Asia/Tokyo");
    }

    private StatsSnapshot streakSnapshot(int currentStreak, LocalDate today, String lastDay, String zone) {
        return new StatsSnapshot(0, 0, 0, currentStreak, lastDay, 0, 0, 0,
                0, 0, 0, 0, 0, 0, false, Collections.emptySet(),
                Collections.emptySet(), Collections.emptySet(), 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, today, zone, currentStreak);
    }

}
