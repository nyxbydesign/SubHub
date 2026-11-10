package com.subhub.app.detection;

/** Monotonic foreground inactivity, independent of capture or inference progress. */
public final class PipelineIdlePolicy {
    public static final long HIGH_GRACE_MILLIS = 3 * 60 * 1000L;
    private long inactiveSince = -1;

    public long delayMillis(boolean active, boolean high, boolean powerSave, long now) {
        if (active) { inactiveSince = -1; return -1; }
        if (inactiveSince < 0) inactiveSince = now;
        if (!high || powerSave) return 0;
        return Math.max(0, HIGH_GRACE_MILLIS - Math.max(0, now - inactiveSince));
    }

    public static boolean allowSpinning(boolean latencyPriority, int threads, boolean powerSave) {
        return latencyPriority && threads >= 4 && !powerSave;
    }
}
