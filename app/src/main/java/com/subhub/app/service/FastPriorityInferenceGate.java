package com.subhub.app.service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes detector execution while allowing fast work to announce demand before it is queued.
 * Quality never waits for the resource and therefore cannot get ahead of an already-arrived frame.
 */
final class FastPriorityInferenceGate {
    enum Lane { IDLE, FAST, QUALITY }
    enum QualityRejection { NONE, INSUFFICIENT_SLACK, FAST_DEMAND, BUSY }

    private final ReentrantLock execution = new ReentrantLock(true);
    private final AtomicInteger fastDemandCount = new AtomicInteger();
    private volatile Lane activeLane = Lane.IDLE;
    private volatile long activeSinceNanos;

    FastDemand registerFastDemand() {
        fastDemandCount.incrementAndGet();
        return new FastDemand(this);
    }

    QualityAdmission tryAcquireQuality(long availableSlackMs, long requiredBudgetMs) {
        if (Math.max(0L, availableSlackMs) < Math.max(0L, requiredBudgetMs)) {
            return QualityAdmission.rejected(QualityRejection.INSUFFICIENT_SLACK);
        }
        if (hasFastDemand()) {
            return QualityAdmission.rejected(QualityRejection.FAST_DEMAND);
        }
        if (!execution.tryLock()) {
            return QualityAdmission.rejected(QualityRejection.BUSY);
        }
        if (hasFastDemand()) {
            execution.unlock();
            return QualityAdmission.rejected(QualityRejection.FAST_DEMAND);
        }
        activate(Lane.QUALITY);
        return QualityAdmission.admitted(new Lease(this, Lane.QUALITY, 0L));
    }

    boolean hasFastDemand() { return fastDemandCount.get() > 0; }
    boolean isQualityActive() { return activeLane == Lane.QUALITY; }
    Lane activeLane() { return activeLane; }

    long qualityActiveMs(long nowNanos) {
        long started = activeSinceNanos;
        if (activeLane != Lane.QUALITY || started <= 0L || nowNanos <= started) return 0L;
        return TimeUnit.NANOSECONDS.toMillis(nowNanos - started);
    }

    private Lease acquireFast(FastDemand demand) throws InterruptedException {
        long requestedAt = System.nanoTime();
        try {
            execution.lockInterruptibly();
        } catch (InterruptedException error) {
            demand.finishWithoutLease();
            throw error;
        }
        demand.finishWithLease();
        activate(Lane.FAST);
        return new Lease(this, Lane.FAST,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestedAt));
    }

    private Lease tryAcquireFast(FastDemand demand, long maximumWaitMs)
            throws InterruptedException {
        long requestedAt = System.nanoTime();
        boolean acquired;
        try {
            acquired = execution.tryLock(
                    Math.max(0L, maximumWaitMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            demand.finishWithoutLease();
            throw error;
        }
        if (!acquired) {
            demand.finishWithoutLease();
            return null;
        }
        demand.finishWithLease();
        activate(Lane.FAST);
        return new Lease(this, Lane.FAST,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestedAt));
    }

    private void activate(Lane lane) {
        activeSinceNanos = System.nanoTime();
        activeLane = lane;
    }

    private void release(Lane lane) {
        if (activeLane != lane) {
            execution.unlock();
            throw new IllegalStateException("Inference lane released out of order");
        }
        activeLane = Lane.IDLE;
        activeSinceNanos = 0L;
        execution.unlock();
    }

    static final class FastDemand implements AutoCloseable {
        private static final int PENDING = 0;
        private static final int ACQUIRING = 1;
        private static final int LEASED = 2;
        private static final int CLOSED = 3;

        private final FastPriorityInferenceGate owner;
        private final AtomicInteger state = new AtomicInteger(PENDING);

        private FastDemand(FastPriorityInferenceGate owner) { this.owner = owner; }

        Lease acquire() throws InterruptedException {
            if (!state.compareAndSet(PENDING, ACQUIRING)) {
                throw new IllegalStateException("Fast demand is no longer pending");
            }
            return owner.acquireFast(this);
        }

        Lease tryAcquire(long maximumWaitMs) throws InterruptedException {
            if (!state.compareAndSet(PENDING, ACQUIRING)) {
                throw new IllegalStateException("Fast demand is no longer pending");
            }
            return owner.tryAcquireFast(this, maximumWaitMs);
        }

        private void finishWithLease() {
            if (!state.compareAndSet(ACQUIRING, LEASED)) {
                owner.execution.unlock();
                throw new IllegalStateException("Fast demand changed while acquiring");
            }
            owner.fastDemandCount.decrementAndGet();
        }

        private void finishWithoutLease() {
            if (state.compareAndSet(ACQUIRING, CLOSED)) {
                owner.fastDemandCount.decrementAndGet();
            }
        }

        @Override
        public void close() {
            if (state.compareAndSet(PENDING, CLOSED)) {
                owner.fastDemandCount.decrementAndGet();
            }
        }
    }

    static final class Lease implements AutoCloseable {
        private final FastPriorityInferenceGate owner;
        private final Lane lane;
        private final long waitMs;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(FastPriorityInferenceGate owner, Lane lane, long waitMs) {
            this.owner = owner;
            this.lane = lane;
            this.waitMs = waitMs;
        }

        long waitMs() { return waitMs; }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) owner.release(lane);
        }
    }

    static final class QualityAdmission {
        private final Lease lease;
        private final QualityRejection rejection;

        private QualityAdmission(Lease lease, QualityRejection rejection) {
            this.lease = lease;
            this.rejection = rejection;
        }

        static QualityAdmission admitted(Lease lease) {
            return new QualityAdmission(lease, QualityRejection.NONE);
        }

        static QualityAdmission rejected(QualityRejection rejection) {
            return new QualityAdmission(null, rejection);
        }

        boolean admitted() { return lease != null; }
        Lease lease() { return lease; }
        QualityRejection rejection() { return rejection; }
    }
}
