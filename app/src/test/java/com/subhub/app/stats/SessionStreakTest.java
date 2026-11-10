package com.subhub.app.stats;

import static org.junit.Assert.*;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.Test;

public final class SessionStreakTest {
    private static long at(String value) { return Instant.parse(value).toEpochMilli(); }

    @Test public void ongoingSessionAdvancesAcrossDaysWithoutStartingAgain() {
        long start = at("2026-10-01T12:00:00Z");
        SessionStreak six = SessionStreak.through(1, "2026-10-01", start, at("2026-10-06T12:00:00Z"), "UTC");
        SessionStreak seven = SessionStreak.through(1, "2026-10-01", start, at("2026-10-07T00:00:00Z"), "UTC");
        assertEquals(6, six.days); assertEquals(7, seven.days);
        assertEquals(7, seven.current(LocalDate.parse("2026-10-07")));
        assertEquals(7, seven.current(LocalDate.parse("2026-10-08")));
        assertEquals(0, seven.current(LocalDate.parse("2026-10-09")));
    }

    @Test public void continuedAndRepeatedSessionsCountEachCivilDayOnce() {
        SessionStreak first = SessionStreak.through(5, "2026-10-05", at("2026-10-06T12:00:00Z"),
                at("2026-10-08T12:00:00Z"), "UTC");
        assertEquals(8, first.days);
        SessionStreak repeated = SessionStreak.through(first.days, first.lastDay,
                at("2026-10-08T18:00:00Z"), at("2026-10-08T19:00:00Z"), "UTC");
        assertEquals(8, repeated.days);
        assertEquals(1, SessionStreak.through(8, "2026-10-08", at("2026-10-10T12:00:00Z"),
                at("2026-10-10T13:00:00Z"), "UTC").days);
    }

    @Test public void dstUsesCivilDatesNotTwentyFourHourPeriods() {
        SessionStreak value = SessionStreak.through(1, "2026-10-24", at("2026-10-24T22:30:00Z"),
                at("2026-10-25T23:30:00Z"), "Europe/Berlin");
        assertEquals(3, value.days); assertEquals("2026-10-26", value.lastDay);
    }

    @Test public void westwardTravelKeepsRecencyInTheSessionsRecordedZone() {
        long observedAt = at("2026-11-10T01:00:00Z");
        SessionStreak value = SessionStreak.through(1, "2026-11-03",
                at("2026-11-03T00:00:00Z"), observedAt, "Asia/Tokyo");
        assertEquals("2026-11-10", value.lastDay);
        assertEquals(8, value.current(observedAt, "Asia/Tokyo"));
        SessionStreak stopped = SessionStreak.through(value.days, value.lastDay, 0, observedAt, "Asia/Tokyo");
        assertEquals(8, stopped.current(observedAt, "Asia/Tokyo"));
        assertEquals(8, stopped.current(at("2026-11-11T01:00:00Z"), "Asia/Tokyo"));
        assertEquals(0, stopped.current(at("2026-11-12T01:00:00Z"), "Asia/Tokyo"));
        assertEquals(LocalDate.parse("2026-11-09"), Instant.ofEpochMilli(observedAt)
                .atZone(java.time.ZoneId.of("America/Los_Angeles")).toLocalDate());
        assertEquals(0, value.current(observedAt, "invalid"));
    }

    @Test public void changedZoneRestartCannotReinterpretAnExpiredCivilDate() {
        long firstStart = at("2026-11-03T00:00:00Z"), firstEnd = at("2026-11-10T01:00:00Z");
        long restart = at("2026-11-11T16:00:00Z");
        SessionStreak first = SessionStreak.through(1, "2026-11-03", "Asia/Tokyo", firstStart, firstEnd, "Asia/Tokyo");
        assertEquals(8, first.days); assertEquals(0, first.current(restart, "Asia/Tokyo"));
        SessionStreak next = SessionStreak.through(first.days, first.lastDay, "Asia/Tokyo",
                restart, restart, "America/Los_Angeles");
        assertEquals(1, next.days); assertEquals("2026-11-11", next.lastDay);
        SessionStreak ongoing = SessionStreak.through(next.days, next.lastDay, "America/Los_Angeles",
                restart, at("2026-11-12T16:00:00Z"), "America/Los_Angeles");
        assertEquals(2, ongoing.days);
    }

    @Test public void sameCalendarAliasesAndLegacyDatesKeepContinuityButInvalidZonesDoNot() {
        long now = at("2026-11-11T16:00:00Z");
        assertEquals(9, SessionStreak.through(8, "2026-11-10", "UTC", now, now, "Etc/UTC").days);
        assertEquals(9, SessionStreak.through(8, "2026-11-10", null, now, now, "UTC").days);
        assertEquals(1, SessionStreak.through(8, "2026-11-10", "invalid", now, now, "UTC").days);
        assertEquals(1, SessionStreak.through(8, "2026-11-10", "Pacific/Auckland", now, now, "Asia/Tokyo").days);
    }

    @Test public void idleInvalidAndFutureFactsCannotInventAStreak() {
        assertEquals(0, SessionStreak.through(9, "2026-10-01", 0, at("2026-10-10T00:00:00Z"), "UTC")
                .current(LocalDate.parse("2026-10-10")));
        assertEquals(0, SessionStreak.through(9, "invalid", 0, 1, "UTC").current(LocalDate.now()));
        assertEquals(0, SessionStreak.through(9, "2026-10-11", 0, 1, "UTC")
                .current(LocalDate.parse("2026-10-10")));
        assertEquals(Integer.MAX_VALUE, SessionStreak.through(Integer.MAX_VALUE, "2026-10-01",
                at("2026-10-02T00:00:00Z"), at("2026-10-03T00:00:00Z"), "UTC").days);
    }
}
