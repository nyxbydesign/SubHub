package com.subhub.app.stats;

import java.time.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** Split an elapsed interval at real local midnights, including DST-short/long days. */
public final class DailyTimeSlices {
    private DailyTimeSlices() { }
    public static Map<String, Long> split(long wallStart, long elapsed, ZoneId zone) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (elapsed <= 0) return result;
        long cursor = wallStart;
        while (elapsed > 0) {
            LocalDate day = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate();
            long midnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            long part = Math.min(elapsed, midnight - cursor);
            if (part <= 0) throw new IllegalArgumentException("Invalid clock interval");
            result.merge(day.toString(), part, Long::sum);
            elapsed -= part; cursor += part;
        }
        return result;
    }
}
