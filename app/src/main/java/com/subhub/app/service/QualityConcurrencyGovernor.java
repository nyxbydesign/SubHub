package com.subhub.app.service;

import java.util.Arrays;

/**
 * Tiny runtime guard for CPU-fast + NNAPI-quality overlap.
 *
 * <p>Fast remains authoritative. The governor learns a conservative idle fast-runtime baseline
 * and pauses new concurrent quality work only after two quality-overlap tail regressions land
 * inside one bounded observation window. They need not be adjacent: video-heavy feeds can hide a
 * damaging tail behind ordinary frames. A pause is temporary so different
 * thermal states and foreground workloads can recover without restarting the service.</p>
 */
final class QualityConcurrencyGovernor {
    static final int REQUIRED_SLOW_SAMPLES = 2;
    static final long MIN_SLOW_RUNTIME_MS = 80L;
    static final float MAX_RELATIVE_RUNTIME = 1.60f;
    static final long SLOW_SAMPLE_WINDOW_MS = 15_000L;
    static final long DEFAULT_PAUSE_MS = 10_000L;
    private static final int RUNTIME_WINDOW = 12;

    private final long pauseMillis;
    private final long[] idleRuntimes = new long[RUNTIME_WINDOW];
    private final long[] overlapRuntimes = new long[RUNTIME_WINDOW];
    private int idleSamples;
    private int overlapSamples;
    private int idleWriteIndex;
    private int overlapWriteIndex;
    private int slowSamplesInWindow;
    private long slowWindowStartedUptimeMillis;
    private long pausedUntilUptimeMillis;

    QualityConcurrencyGovernor() {
        this(DEFAULT_PAUSE_MS);
    }

    QualityConcurrencyGovernor(long pauseMillis) {
        if (pauseMillis < 0L) throw new IllegalArgumentException("pauseMillis must be non-negative");
        this.pauseMillis = pauseMillis;
    }

    synchronized boolean allows(long nowUptimeMillis) {
        return Math.max(0L, nowUptimeMillis) >= pausedUntilUptimeMillis;
    }

    synchronized Decision recordFast(
            long runtimeMillis,
            boolean qualityOverlapped,
            long nowUptimeMillis) {
        long runtime = Math.max(0L, runtimeMillis);
        long now = Math.max(0L, nowUptimeMillis);
        if (!qualityOverlapped) {
            recordIdleRuntime(runtime);
            expireSlowWindow(now);
            return Decision.NONE;
        }
        if (idleSamples < 3 || runtime <= 0L) return Decision.NONE;
        recordOverlapRuntime(runtime);
        long relativeLimit = Math.round(idleRuntimeReferenceMs() * MAX_RELATIVE_RUNTIME);
        boolean slow = runtime >= Math.max(MIN_SLOW_RUNTIME_MS, relativeLimit);
        if (!slow) {
            expireSlowWindow(now);
            return Decision.NONE;
        }
        if (slowSamplesInWindow == 0 || now < slowWindowStartedUptimeMillis
                || now - slowWindowStartedUptimeMillis > SLOW_SAMPLE_WINDOW_MS) {
            slowWindowStartedUptimeMillis = now;
            slowSamplesInWindow = 1;
            return Decision.NONE;
        }
        if (++slowSamplesInWindow < REQUIRED_SLOW_SAMPLES) return Decision.NONE;
        slowSamplesInWindow = 0;
        slowWindowStartedUptimeMillis = 0L;
        pausedUntilUptimeMillis = safeAdd(now, pauseMillis);
        return Decision.PAUSE_CONCURRENT_QUALITY;
    }

    synchronized float idleRuntimeEmaMs() { return idleRuntimeReferenceMs(); }
    synchronized long pausedUntilUptimeMillis() { return pausedUntilUptimeMillis; }

    synchronized void reset() {
        Arrays.fill(idleRuntimes, 0L);
        Arrays.fill(overlapRuntimes, 0L);
        idleSamples = 0;
        overlapSamples = 0;
        idleWriteIndex = 0;
        overlapWriteIndex = 0;
        slowSamplesInWindow = 0;
        slowWindowStartedUptimeMillis = 0L;
        pausedUntilUptimeMillis = 0L;
    }

    private void recordIdleRuntime(long runtimeMillis) {
        if (runtimeMillis <= 0L) return;
        idleRuntimes[idleWriteIndex] = runtimeMillis;
        idleWriteIndex = (idleWriteIndex + 1) % RUNTIME_WINDOW;
        if (idleSamples < RUNTIME_WINDOW) idleSamples++;
    }

    private void recordOverlapRuntime(long runtimeMillis) {
        overlapRuntimes[overlapWriteIndex] = runtimeMillis;
        overlapWriteIndex = (overlapWriteIndex + 1) % RUNTIME_WINDOW;
        if (overlapSamples < RUNTIME_WINDOW) overlapSamples++;
    }

    private float idleRuntimeReferenceMs() {
        if (idleSamples == 0) return 0f;
        long[] sorted = Arrays.copyOf(idleRuntimes, idleSamples);
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        if ((sorted.length & 1) == 1) return sorted[middle];
        return (sorted[middle - 1] + sorted[middle]) * .5f;
    }

    private void expireSlowWindow(long nowUptimeMillis) {
        if (slowSamplesInWindow > 0 && (nowUptimeMillis < slowWindowStartedUptimeMillis
                || nowUptimeMillis - slowWindowStartedUptimeMillis
                > SLOW_SAMPLE_WINDOW_MS)) {
            slowSamplesInWindow = 0;
            slowWindowStartedUptimeMillis = 0L;
        }
    }

    private static long safeAdd(long first, long second) {
        return second > 0L && first > Long.MAX_VALUE - second
                ? Long.MAX_VALUE : first + second;
    }

    enum Decision { NONE, PAUSE_CONCURRENT_QUALITY }
}
