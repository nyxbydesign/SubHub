package com.subhub.app.tasks;

import java.time.ZoneId;

/** New schedules use fresh target metadata; existing task definitions retain their zone. */
public final class TaskTimezone {
    public static final long MAX_AGE_MILLIS = 30 * 60_000L;
    private TaskTimezone() { }

    public static ZoneId require(String zone, long receivedAt, long now) {
        if (receivedAt <= 0 || now < receivedAt || now - receivedAt > MAX_AGE_MILLIS)
            throw new IllegalStateException("Sub timezone unavailable or stale");
        if (zone == null || !ZoneId.getAvailableZoneIds().contains(zone))
            throw new IllegalStateException("Sub timezone unavailable or stale");
        return ZoneId.of(zone);
    }
}
