package com.subhub.app.stats;

import java.time.ZoneId;
import java.util.Collections;
import java.util.Map;

/** Logical session lifetime, not proof of capture, screen use, connectivity or process uptime. */
final class SessionDurationProjection {
    private static final long MAX_MILLIS = 366L * 24 * 60 * 60 * 1000;
    private SessionDurationProjection() { }

    static Map<String, Long> slices(long start, long seconds, long observedAt, String zone) {
        if (start <= 0 || start > observedAt || seconds < 0
                || seconds > MAX_MILLIS / 1000 || seconds > (observedAt - start) / 1000)
            return Collections.emptyMap();
        try { return DailyTimeSlices.split(start, seconds * 1000, ZoneId.of(zone)); }
        catch (RuntimeException invalid) { return Collections.emptyMap(); }
    }

    static long activeSeconds(long start, long observedAt) {
        return start > 0 && start <= observedAt && observedAt - start <= MAX_MILLIS
                ? (observedAt - start) / 1000 : 0;
    }

    /** Old screen checkpoints overlap identified sessions. Retain the unmatched lower bound only. */
    static long legacyBaseline(long recorded, long legacySessions, long identifiedSessions) {
        return Math.max(Math.max(0, legacySessions), Math.max(0, recorded - identifiedSessions));
    }
}
