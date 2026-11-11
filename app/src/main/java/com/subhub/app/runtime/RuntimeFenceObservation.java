package com.subhub.app.runtime;

import java.time.ZoneId;
import java.util.Objects;

/** Immutable clock/calendar evidence at the first fence, never cleanup time or runtime authority. */
public final class RuntimeFenceObservation {
    public final long at;
    public final String zone;
    RuntimeFenceObservation(long at, String zone) {
        this.at = at; this.zone = ZoneId.of(Objects.requireNonNull(zone)).getId();
    }
    private RuntimeFenceObservation(long at, String normalizedZone, boolean alreadyValidated) {
        this.at = at; this.zone = normalizedZone;
    }
    /** Calendar initialization stays outside the registry's short permission/effect lock. */
    static String captureZoneOrUnavailable() {
        try { return ZoneId.systemDefault().getId(); }
        catch (RuntimeException | Error unavailable) { return null; }
    }
    /** Only primitives and immutable allocation, after the registry has already sealed effects. */
    static RuntimeFenceObservation observeNowOrUnavailable(String normalizedZone) {
        if (normalizedZone == null) return null;
        try { return new RuntimeFenceObservation(System.currentTimeMillis(), normalizedZone, true); }
        catch (RuntimeException | Error unavailable) { return null; }
    }
}
