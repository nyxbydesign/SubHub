package com.subhub.app.service;

/** Counts only continuously confirmed foreground sessions, using a monotonic clock. */
final class ForegroundUsageSession {
    static final long QUALIFY_MILLIS = 5_000L;
    static final long MAX_OBSERVATION_GAP_MILLIS = 2_500L;
    private String packageName = "";
    private long lastObserved = -1L;
    private long pendingMillis;
    private boolean qualified;

    long observe(String visiblePackage, long elapsedRealtimeMillis) {
        String next = visiblePackage == null ? "" : visiblePackage;
        long delta = lastObserved < 0L ? 0L : elapsedRealtimeMillis - lastObserved;
        if (next.isEmpty() || elapsedRealtimeMillis < 0L) {
            reset();
            return 0L;
        }
        if (!next.equals(packageName) || lastObserved < 0L || delta < 0L
                || delta > MAX_OBSERVATION_GAP_MILLIS) {
            reset();
            packageName = next;
            lastObserved = elapsedRealtimeMillis;
            return 0L;
        }
        lastObserved = elapsedRealtimeMillis;
        if (qualified) return delta;
        pendingMillis += delta;
        if (pendingMillis < QUALIFY_MILLIS) return 0L;
        qualified = true;
        long result = pendingMillis;
        pendingMillis = 0L;
        return result;
    }

    boolean qualified(String name) { return qualified && packageName.equals(name); }

    void reset() {
        packageName = "";
        lastObserved = -1L;
        pendingMillis = 0L;
        qualified = false;
    }
}
