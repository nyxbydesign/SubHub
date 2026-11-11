package com.subhub.app.stats;

import java.time.Instant;
import java.time.ZoneId;

/** Immutable event clock/calendar captured before admission is queued, not resampled at commit. */
public final class OwnerObservation {
    public final long at;
    public final String zone;
    public OwnerObservation(long at, String zone) {
        if (at <= 0) throw new IllegalArgumentException("Invalid owner observation clock");
        this.zone = ZoneId.of(zone).getId(); this.at = at;
    }
    public static OwnerObservation capture(long at) { return new OwnerObservation(at, ZoneId.systemDefault().getId()); }
    public String day() { return Instant.ofEpochMilli(at).atZone(ZoneId.of(zone)).toLocalDate().toString(); }
}
