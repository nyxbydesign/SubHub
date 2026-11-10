package com.subhub.app.service;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Small execution seam around {@link QualityBackfillCoordinator}'s one-slot source mailbox.
 *
 * <p>The runner deliberately does not know about a detector, tracker, overlay, statistics, or
 * policy. A service supplies an immediate quality permit and a cache-only callback. The permit is
 * acquired before polling, so a fast lane can make quality yield without ever waiting for it. A
 * denied permit leaves the source in the mailbox for a later capture; once a source is claimed,
 * this class owns it until the callback returns and closes it exactly once.</p>
 *
 * <p>The callback receives the source's immutable stamp, rather than the current camera. Callers
 * must use camera/geometry carried by their source object when converting results. The context
 * passed to {@link #runOne(QualityBackfillCoordinator.BackfillContext, long, PermitSupplier,
 * Work)} supplies only non-motion fences, so ordinary scroll motion does not invalidate a source.
 * </p>
 */
final class QualityBackfillRunner<T> implements AutoCloseable {
    static final long DEFAULT_MIN_INTERVAL_MS = 0L;
    static final long DEFAULT_DUTY_WINDOW_MS = 5_000L;
    static final long DEFAULT_DUTY_BUDGET_MS = 3_000L;
    static final long DEFAULT_ESTIMATED_RUN_MS = 200L;
    static final long DEFAULT_CIRCUIT_BREAKER_MS = 30_000L;

    private final QualityBackfillCoordinator<T> mailbox;
    private final Policy policy;
    private final LongSupplier clock;
    private boolean closed;
    private boolean runReserved;
    private long lastRunFinishedUptimeMillis = Long.MIN_VALUE;
    private long dutyWindowStartedUptimeMillis = Long.MIN_VALUE;
    private long dutyUsedMillis;
    private long circuitOpenUntilUptimeMillis;

    QualityBackfillRunner() {
        this(new QualityBackfillCoordinator<>(), Policy.defaults());
    }

    QualityBackfillRunner(
            QualityBackfillCoordinator<T> mailbox,
            Policy policy) {
        this(mailbox, policy,
                () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    QualityBackfillRunner(
            QualityBackfillCoordinator<T> mailbox,
            Policy policy,
            LongSupplier clock) {
        this.mailbox = Objects.requireNonNull(mailbox, "mailbox");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    QualityBackfillCoordinator.OfferResult offer(
            QualityBackfillCoordinator.BackfillFrame<T> frame,
            long nowUptimeMillis) {
        return mailbox.offer(frame, safeNow(nowUptimeMillis));
    }

    synchronized int pendingCount() {
        return mailbox.pendingCount();
    }

    synchronized boolean isRunning() {
        return runReserved;
    }

    synchronized boolean isClosed() {
        return closed || mailbox.isClosed();
    }

    synchronized boolean circuitAllows(long nowUptimeMillis) {
        return safeNow(nowUptimeMillis) >= circuitOpenUntilUptimeMillis;
    }

    synchronized long circuitRetryAfterMillis(long nowUptimeMillis) {
        long now = safeNow(nowUptimeMillis);
        return now >= circuitOpenUntilUptimeMillis
                ? 0L : Math.max(1L, circuitOpenUntilUptimeMillis - now);
    }

    /** Clears only the pending source and confirmation table; an active callback owns its source. */
    synchronized void clear() {
        if (!closed) mailbox.clear();
    }

    /** Resets adaptive admission state at a recognition/provider/document boundary. */
    synchronized void resetPolicyState() {
        lastRunFinishedUptimeMillis = Long.MIN_VALUE;
        dutyWindowStartedUptimeMillis = Long.MIN_VALUE;
        dutyUsedMillis = 0L;
        circuitOpenUntilUptimeMillis = 0L;
    }

    /**
     * Opens the bounded quality circuit without discarding the pending source. This is useful when
     * the fast lane observes a contention timeout; the source then expires naturally in the
     * coordinator's 2--3 second age window instead of being run during a known bad interval.
     */
    synchronized void openCircuit(long nowUptimeMillis) {
        if (policy.circuitBreakerMillis <= 0L) return;
        long until = safeAdd(safeNow(nowUptimeMillis), policy.circuitBreakerMillis);
        circuitOpenUntilUptimeMillis = Math.max(circuitOpenUntilUptimeMillis, until);
    }

    /**
     * Tries one cache-only source. No method in this class waits for a permit or for another run.
     * The caller should invoke it from its existing quality worker after a fast drain, and can
     * schedule another attempt when the result is deferred or the mailbox remains populated.
     */
    <P extends AutoCloseable> RunResult runOne(
            QualityBackfillCoordinator.BackfillContext current,
            long nowUptimeMillis,
            PermitSupplier<P> permitSupplier,
            Work<T> work) {
        Objects.requireNonNull(permitSupplier, "permitSupplier");
        Objects.requireNonNull(work, "work");
        long now = safeNow(nowUptimeMillis);
        synchronized (this) {
            if (closed || mailbox.isClosed()) return RunResult.of(RunStatus.CLOSED);
            if (runReserved) return RunResult.of(RunStatus.BUSY);
            if (mailbox.pendingCount() == 0) return RunResult.of(RunStatus.EMPTY);
            RunResult policyDeferral = policyDeferral(now);
            if (policyDeferral != null) return policyDeferral;
            // Reserve before calling out so concurrent drain triggers cannot claim two permits.
            runReserved = true;
        }

        P permit = null;
        QualityBackfillCoordinator.PollResult<T> polled = null;
        QualityBackfillCoordinator.BackfillFrame<T> frame = null;
        long measurementStarted = 0L;
        try {
            try {
                // Permit suppliers are required to be immediate/non-blocking. In particular, a
                // FastPriorityInferenceGate supplier should use tryAcquireQuality(), never lock().
                permit = permitSupplier.tryAcquire();
            } catch (RuntimeException failure) {
                openCircuit(now);
                return finishFailure(now, 0L, failure);
            }
            if (permit == null) return finishDeferred(RunStatus.DEFERRED_ADMISSION);

            synchronized (this) {
                if (closed || mailbox.isClosed()) return finishDeferred(RunStatus.CLOSED);
            }
            polled = mailbox.poll(current, now);
            if (!polled.ready()) return finishPollResult(polled.status());
            frame = polled.frame();
            T resource = frame.resource();
            if (resource == null) return finishDeferred(RunStatus.CLOSED);
            measurementStarted = safeNow(clock.getAsLong());
            try {
                work.run(resource, frame.stamp());
                long measurementFinished = safeNow(clock.getAsLong());
                long duration = elapsedMillis(measurementStarted, measurementFinished);
                long policyFinished = safeAdd(now, duration);
                return finishSuccess(policyFinished, duration);
            } catch (Exception failure) {
                long measurementFinished = safeNow(clock.getAsLong());
                long duration = elapsedMillis(measurementStarted, measurementFinished);
                long policyFinished = safeAdd(now, duration);
                openCircuit(policyFinished);
                return finishFailure(policyFinished, duration, failure);
            }
        } finally {
            // The source is always closed even if a callback or permit close fails. Close is
            // idempotent at the coordinator frame boundary, which protects all replacement,
            // fence, expiry, and shutdown paths from double release.
            try {
                if (frame != null) frame.close();
            } catch (RuntimeException ignored) {
                // A hostile/custom source releaser cannot strand the inference permit below.
            } finally {
                if (permit != null) {
                    try {
                    permit.close();
                    } catch (Exception ignored) {
                        // A quality lease's close is not expected to fail; source ownership above
                        // is still complete if a custom test/provider lease does fail.
                    }
                }
                synchronized (this) {
                    runReserved = false;
                }
            }
        }
    }

    private RunResult finishPollResult(QualityBackfillCoordinator.PollStatus status) {
        switch (status) {
            case EMPTY:
                return finishDeferred(RunStatus.EMPTY);
            case EXPIRED:
                return finishDeferred(RunStatus.EXPIRED);
            case FENCE_MISMATCH:
                return finishDeferred(RunStatus.FENCE_MISMATCH);
            case CLOSED:
            default:
                return finishDeferred(RunStatus.CLOSED);
        }
    }

    private RunResult finishDeferred(RunStatus status) {
        return new RunResult(status, 0L, 0L, null);
    }

    private RunResult finishSuccess(long finished, long duration) {
        synchronized (this) {
            recordRun(finished, duration);
        }
        return new RunResult(RunStatus.RAN, duration, 0L, null);
    }

    private RunResult finishFailure(long finished, long duration, Exception failure) {
        synchronized (this) {
            recordRun(finished, duration);
        }
        return new RunResult(RunStatus.FAILED, duration, 0L, failure);
    }

    private synchronized RunResult policyDeferral(long now) {
        if (now < circuitOpenUntilUptimeMillis) {
            return RunResult.deferred(
                    RunStatus.DEFERRED_CIRCUIT,
                    Math.max(1L, circuitOpenUntilUptimeMillis - now));
        }
        resetDutyWindowIfNeeded(now);
        if (lastRunFinishedUptimeMillis != Long.MIN_VALUE
                && elapsedMillis(lastRunFinishedUptimeMillis, now)
                        < policy.minimumIntervalMillis) {
            long elapsed = elapsedMillis(lastRunFinishedUptimeMillis, now);
            return RunResult.deferred(
                    RunStatus.DEFERRED_CADENCE,
                    Math.max(1L, policy.minimumIntervalMillis - elapsed));
        }
        if (policy.dutyBudgetMillis > 0L
                && (dutyUsedMillis > policy.dutyBudgetMillis
                || policy.estimatedRunMillis > policy.dutyBudgetMillis - dutyUsedMillis)) {
            long elapsed = elapsedMillis(dutyWindowStartedUptimeMillis, now);
            return RunResult.deferred(
                    RunStatus.DEFERRED_DUTY,
                    Math.max(1L, policy.dutyWindowMillis - elapsed));
        }
        return null;
    }

    private synchronized void recordRun(long finished, long duration) {
        long now = safeNow(finished);
        resetDutyWindowIfNeeded(now);
        if (dutyWindowStartedUptimeMillis == Long.MIN_VALUE) {
            dutyWindowStartedUptimeMillis = now;
        }
        dutyUsedMillis = safeAdd(dutyUsedMillis, duration);
        lastRunFinishedUptimeMillis = now;
    }

    private synchronized void resetDutyWindowIfNeeded(long now) {
        if (dutyWindowStartedUptimeMillis == Long.MIN_VALUE
                || elapsedMillis(dutyWindowStartedUptimeMillis, now)
                        >= policy.dutyWindowMillis) {
            dutyWindowStartedUptimeMillis = now;
            dutyUsedMillis = 0L;
        }
    }

    private static long safeNow(long value) {
        return Math.max(0L, value);
    }

    private static long elapsedMillis(long start, long end) {
        if (start <= 0L || end <= start) return 0L;
        return end - start;
    }

    private static long safeAdd(long first, long second) {
        if (second > 0L && first > Long.MAX_VALUE - second) return Long.MAX_VALUE;
        if (second < 0L && first < Long.MIN_VALUE - second) return Long.MIN_VALUE;
        return first + second;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        mailbox.close();
    }

    interface PermitSupplier<P extends AutoCloseable> {
        P tryAcquire();
    }

    interface Work<T> {
        void run(T resource, QualityBackfillCoordinator.BackfillStamp sourceStamp)
                throws Exception;
    }

    enum RunStatus {
        RAN,
        EMPTY,
        BUSY,
        DEFERRED_ADMISSION,
        DEFERRED_CADENCE,
        DEFERRED_DUTY,
        DEFERRED_CIRCUIT,
        EXPIRED,
        FENCE_MISMATCH,
        CLOSED,
        FAILED
    }

    static final class RunResult {
        private final RunStatus status;
        private final long durationMillis;
        private final long retryAfterMillis;
        private final Exception failure;

        private RunResult(
                RunStatus status,
                long durationMillis,
                long retryAfterMillis,
                Exception failure) {
            this.status = status;
            this.durationMillis = Math.max(0L, durationMillis);
            this.retryAfterMillis = Math.max(0L, retryAfterMillis);
            this.failure = failure;
        }

        private static RunResult of(RunStatus status) {
            return new RunResult(status, 0L, 0L, null);
        }

        private static RunResult deferred(RunStatus status, long retryAfterMillis) {
            return new RunResult(status, 0L, retryAfterMillis, null);
        }

        RunStatus status() { return status; }
        long durationMillis() { return durationMillis; }
        long retryAfterMillis() { return retryAfterMillis; }
        Exception failure() { return failure; }
        boolean ran() { return status == RunStatus.RAN; }
        boolean deferred() {
            return status == RunStatus.DEFERRED_ADMISSION
                    || status == RunStatus.DEFERRED_CADENCE
                    || status == RunStatus.DEFERRED_DUTY
                    || status == RunStatus.DEFERRED_CIRCUIT;
        }
    }

    static final class Policy {
        private final long minimumIntervalMillis;
        private final long dutyWindowMillis;
        private final long dutyBudgetMillis;
        private final long estimatedRunMillis;
        private final long circuitBreakerMillis;

        Policy(
                long minimumIntervalMillis,
                long dutyWindowMillis,
                long dutyBudgetMillis,
                long estimatedRunMillis,
                long circuitBreakerMillis) {
            if (minimumIntervalMillis < 0L) {
                throw new IllegalArgumentException("minimumIntervalMillis must be non-negative");
            }
            if (dutyWindowMillis <= 0L) {
                throw new IllegalArgumentException("dutyWindowMillis must be positive");
            }
            if (dutyBudgetMillis < 0L) {
                throw new IllegalArgumentException("dutyBudgetMillis must be non-negative");
            }
            if (estimatedRunMillis < 0L) {
                throw new IllegalArgumentException("estimatedRunMillis must be non-negative");
            }
            if (circuitBreakerMillis < 0L) {
                throw new IllegalArgumentException("circuitBreakerMillis must be non-negative");
            }
            this.minimumIntervalMillis = minimumIntervalMillis;
            this.dutyWindowMillis = dutyWindowMillis;
            this.dutyBudgetMillis = dutyBudgetMillis;
            this.estimatedRunMillis = estimatedRunMillis;
            this.circuitBreakerMillis = circuitBreakerMillis;
        }

        static Policy defaults() {
            return new Policy(
                    DEFAULT_MIN_INTERVAL_MS,
                    DEFAULT_DUTY_WINDOW_MS,
                    DEFAULT_DUTY_BUDGET_MS,
                    DEFAULT_ESTIMATED_RUN_MS,
                    DEFAULT_CIRCUIT_BREAKER_MS);
        }

        static Policy unbounded() {
            return new Policy(0L, Long.MAX_VALUE, 0L, 0L, 0L);
        }
    }
}
