package com.subhub.app.service;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * One deadline and one in-flight owner for Accessibility screenshots.
 *
 * <p>Completion, settled-content demand and quality release may wake the same deadline. They do
 * not create independent polling phases. The platform spacing belongs to actual API dispatches
 * and survives scene changes and stop/start. Stopping never pretends an outstanding callback
 * completed: its identity must release ownership before another request can start.</p>
 */
final class AccessibilityCaptureScheduler {
    interface Cancellation { void cancel(); }

    interface Queue {
        /** Enqueue, never invoke inline. The task may still arrive after cancellation. */
        Cancellation schedule(Runnable task, long delayMillis);
    }

    private final LongSupplier clock;
    private final Queue queue;
    private final Runnable admission;
    private final long minimumIntervalMillis;
    private boolean active;
    private long session;
    private long timerVersion;
    private long requestSequence;
    private long inFlight;
    private long requestSession;
    private long lastDispatchMillis = -1L;
    private long retryNotBeforeMillis;
    private long deadline = Long.MAX_VALUE;
    private Cancellation pending;

    AccessibilityCaptureScheduler(LongSupplier clock, Queue queue, Runnable admission,
            long minimumIntervalMillis) {
        if (minimumIntervalMillis <= 0L) throw new IllegalArgumentException("interval");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.minimumIntervalMillis = minimumIntervalMillis;
    }

    synchronized void start() {
        cancelTimer();
        session++;
        active = true;
        wakeAt(clock.getAsLong());
    }

    synchronized void stop() {
        active = false;
        session++;
        cancelTimer();
    }

    synchronized boolean inFlight() { return inFlight != 0L; }

    synchronized long lastDispatchMillis() { return lastDispatchMillis; }

    synchronized long retryNotBeforeMillis() { return retryNotBeforeMillis; }

    synchronized long nextEligibleMillis() {
        long platformDeadline = lastDispatchMillis < 0L ? 0L
                : addSaturated(lastDispatchMillis, minimumIntervalMillis);
        return Math.max(platformDeadline, retryNotBeforeMillis);
    }

    /** Coalesce requests, preserving an already earlier wake-up. Admission rechecks all gates. */
    synchronized void wakeAt(long requestedDeadline) {
        if (!active || inFlight != 0L) return;
        long now = Math.max(0L, clock.getAsLong());
        long target = Math.max(now, Math.max(requestedDeadline, nextEligibleMillis()));
        if (pending != null && deadline <= target) return;
        cancelTimer();
        final long expectedSession = session;
        final long expectedTimer = timerVersion;
        deadline = target;
        try {
            pending = queue.schedule(() -> fire(expectedSession, expectedTimer), target - now);
        } catch (RuntimeException failure) {
            deadline = Long.MAX_VALUE;
            pending = null;
            timerVersion++;
            throw failure;
        }
    }

    /** Returns zero when stopped, too early, or still awaiting the previous callback. */
    synchronized long acquire(long now) {
        if (!active || inFlight != 0L) return 0L;
        if (now < nextEligibleMillis()) {
            wakeAt(nextEligibleMillis());
            return 0L;
        }
        cancelTimer();
        inFlight = ++requestSequence;
        requestSession = session;
        return inFlight;
    }

    /** Call immediately before the platform API, not before foreground-window resolution. */
    synchronized boolean dispatched(long requestId, long now) {
        if (requestId == 0L || requestId != inFlight || !active
                || requestSession != session || now < nextEligibleMillis()) return false;
        lastDispatchMillis = now;
        return true;
    }

    /** The API may spend time in Binder. Its return is a conservative end of dispatch, not pixels. */
    synchronized void dispatchReturned(long requestId, long now) {
        if (requestId != 0L && requestId == inFlight && lastDispatchMillis >= 0L) {
            lastDispatchMillis = Math.max(lastDispatchMillis, now);
        }
    }

    /** An old/duplicate callback cannot release or reschedule a different request. */
    synchronized boolean finish(long requestId, long notBeforeMillis) {
        if (requestId == 0L || requestId != inFlight) return false;
        inFlight = 0L;
        // Unlike a quality reservation's soft timer, recovery backoff cannot be shortened by
        // scroll/quality wake-ups when a pre-dispatch failure produced no platform timestamp.
        retryNotBeforeMillis = Math.max(retryNotBeforeMillis, notBeforeMillis);
        if (active) wakeAt(notBeforeMillis);
        return true;
    }

    private void fire(long expectedSession, long expectedTimer) {
        synchronized (this) {
            if (!active || session != expectedSession || timerVersion != expectedTimer) return;
            pending = null;
            deadline = Long.MAX_VALUE;
            timerVersion++;
        }
        // The caller checks recognition/epoch again; never hold our lock across platform work.
        admission.run();
    }

    private void cancelTimer() {
        timerVersion++;
        Cancellation old = pending;
        pending = null;
        deadline = Long.MAX_VALUE;
        if (old != null) old.cancel();
    }

    private static long addSaturated(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }
}
