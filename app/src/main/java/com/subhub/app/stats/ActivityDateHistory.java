package com.subhub.app.stats;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Separates old date-only evidence from calendar-labelled censor copies. */
final class ActivityDateHistory {
    private ActivityDateHistory() { }

    static Map<String, Set<String>> calendars(Set<String> encoded) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String value : encoded) try {
            int split = value.indexOf(';');
            String zone = ZoneId.of(value.substring(0, split)).getId();
            String date = LocalDate.parse(value.substring(split + 1)).toString();
            result.computeIfAbsent(zone, ignored -> new LinkedHashSet<>()).add(date);
        } catch (RuntimeException invalid) { /* Invalid evidence cannot acquire a calendar. */ }
        return result;
    }

    static Set<String> legacy(Set<String> rawDates, Set<String> encoded, long identifiedSince) {
        Set<String> result = new LinkedHashSet<>();
        // During the identified-session era raw labels can be modern device-day copies. Their
        // original zone is unknowable on upgrade. Retain earlier legacy runs, but do not promote
        // ambiguous copies to legacy authority. The earliest legal offset is conservative even
        // when the device travelled before its first persisted censor event.
        LocalDate firstModern = identifiedSince > 0
                ? Instant.ofEpochMilli(identifiedSince).atZone(ZoneOffset.MIN).toLocalDate() : null;
        for (String value : rawDates) try {
            LocalDate date = LocalDate.parse(value);
            if (firstModern == null || date.isBefore(firstModern)) result.add(value);
        } catch (RuntimeException invalid) { /* Keep raw storage, not invalid eligibility. */ }
        for (Set<String> dates : calendars(encoded).values()) result.removeAll(dates);
        return result;
    }

    static long earliest(long previous, long next) {
        return next <= 0 ? previous : previous <= 0 ? next : Math.min(previous, next);
    }
}
