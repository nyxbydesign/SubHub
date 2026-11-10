package com.subhub.app.remote;

/** Scheduling only: no authority, delivery receipt or invitation lifetime is inferred from a delay. */
final class RemoteSyncCadence {
    static final long BACKGROUND_IDLE_MILLIS = 5 * 60_000L;
    static final long BACKGROUND_RETRY_MAX_MILLIS = 15 * 60_000L;
    private RemoteSyncCadence() { }

    static long delay(boolean backgroundConnection, boolean urgent, boolean success, int failures) {
        if (success) return urgent ? 2_000L : backgroundConnection ? BACKGROUND_IDLE_MILLIS : 30_000L;
        // A bounded job needs another opportunity within its listening window; pairing and visible
        // work and revocation delivery also retain quick retries. Only the persistent background owner backs off.
        long base = backgroundConnection && !urgent ? 30_000L : 2_000L;
        long cap = backgroundConnection && !urgent ? BACKGROUND_RETRY_MAX_MILLIS : 60_000L;
        return Math.min(cap, base << Math.max(0, Math.min(5, failures - 1)));
    }
}
