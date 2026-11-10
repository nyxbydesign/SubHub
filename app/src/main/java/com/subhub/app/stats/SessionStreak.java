package com.subhub.app.stats;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** Civil-day session continuity, including days inside one ongoing session. No persistence effects. */
final class SessionStreak {
    final int days;
    final String lastDay;

    private SessionStreak(long days, String lastDay) {
        this.days = (int) Math.min(Integer.MAX_VALUE, Math.max(0, days));
        this.lastDay = lastDay;
    }

    static SessionStreak through(int savedDays, String savedDay, long startedAt, long through, String zone) {
        LocalDate previous = parse(savedDay);
        long count = Math.max(0, savedDays);
        if (startedAt <= 0 || through < startedAt) return new SessionStreak(count, savedDay);
        try {
            ZoneId clock = ZoneId.of(zone);
            LocalDate first = Instant.ofEpochMilli(startedAt).atZone(clock).toLocalDate();
            LocalDate last = Instant.ofEpochMilli(through).atZone(clock).toLocalDate();
            if (previous != null && !previous.isBefore(first.minusDays(1)) && !previous.isAfter(last))
                count += ChronoUnit.DAYS.between(previous, last);
            else count = ChronoUnit.DAYS.between(first, last) + 1;
            return new SessionStreak(Math.max(count, ChronoUnit.DAYS.between(first, last) + 1), last.toString());
        } catch (RuntimeException invalid) { return new SessionStreak(count, savedDay); }
    }

    int current(long observedAt, String zone) {
        try { return current(Instant.ofEpochMilli(observedAt).atZone(ZoneId.of(zone)).toLocalDate()); }
        catch (RuntimeException invalid) { return 0; }
    }

    int current(LocalDate today) {
        LocalDate last = parse(lastDay);
        return last != null && !last.isAfter(today) && !last.isBefore(today.minusDays(1)) ? days : 0;
    }

    private static LocalDate parse(String value) {
        try { return LocalDate.parse(value); }
        catch (RuntimeException invalid) { return null; }
    }
}
