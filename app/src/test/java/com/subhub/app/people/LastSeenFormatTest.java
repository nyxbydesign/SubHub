package com.subhub.app.people;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class LastSeenFormatTest {
    @Test public void currentYearOmitsYearAndSecondsWithoutTrailingDateDot() {
        assertEquals("03.11 11:07", format("2026-11-03T10:07:59Z", "2026-11-04T12:00:00Z", "Europe/Berlin"));
    }

    @Test public void previousCalendarYearKeepsYearEvenWhenLessThanTwelveMonthsOld() {
        assertEquals("31.12.2025 23:15", format("2025-12-31T23:15:59Z", "2026-01-02T12:00:00Z", "UTC"));
    }

    @Test public void olderYearsKeepYearWithoutSeconds() {
        assertEquals("07.06.2023 09:04", format("2023-06-07T09:04:32Z", "2026-11-04T12:00:00Z", "UTC"));
    }

    @Test public void localCurrentYearCanDifferFromUtcCurrentYear() {
        assertEquals("30.12 18:05", format("2025-12-30T23:05:59Z", "2026-01-01T00:30:00Z", "America/New_York"));
    }

    @Test public void localObservationYearCanDifferWithinTheSameUtcYear() {
        assertEquals("31.12.2025 23:59", format("2026-01-01T04:59:59Z", "2026-01-01T06:00:00Z", "America/New_York"));
    }

    @Test public void displayRetainsTheObservationTimeZonesDaylightSavingOffset() {
        assertEquals("01.07 14:34", format("2026-07-01T12:34:56Z", "2026-11-04T12:00:00Z", "Europe/Berlin"));
    }

    private static String format(String observed, String now, String zone) {
        return ProfileViews.formatLastSeen(Instant.parse(observed).toEpochMilli(),
                Instant.parse(now).toEpochMilli(), ZoneId.of(zone));
    }
}
