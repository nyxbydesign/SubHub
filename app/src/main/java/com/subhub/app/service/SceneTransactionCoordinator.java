package com.subhub.app.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Coordinates the private fast and quality observations for one captured scene.
 *
 * <p>The coordinator deliberately does not execute inference, mutate the tracker, or publish an
 * overlay. Callers feed lane results into it and may act only on the single {@link Commit} returned
 * for a scene. All methods are synchronized so a deadline and an inference completion have one
 * deterministic linearization point.</p>
 */
final class SceneTransactionCoordinator<T> {
    enum Mode {
        /** Motion owns presentation; publish the fast observation without waiting for quality. */
        ACTIVE_FAST,
        /** A settled capture may atomically join fast and quality before its deadline. */
        SETTLED_ATOMIC,
        /** A settled capture for which no quality engine is available. */
        SETTLED_FAST_ONLY
    }

    enum CommitKind {
        ACTIVE_FAST,
        SETTLED_FUSED,
        SETTLED_FAST_ONLY,
        DEADLINE_FAST
    }

    enum Status {
        WAITING,
        WAITING_FOR_FAST,
        COMMITTED,
        DROPPED_STALE,
        DROPPED_CLOSED,
        DROPPED_DUPLICATE,
        DROPPED_POLICY
    }

    static final class SceneKey {
        private final long captureEpoch;
        private final long fastSequence;
        private final long motionGeneration;
        private final long screenshotUptimeMillis;

        SceneKey(
                long captureEpoch,
                long fastSequence,
                long motionGeneration,
                long screenshotUptimeMillis) {
            this.captureEpoch = captureEpoch;
            this.fastSequence = fastSequence;
            this.motionGeneration = motionGeneration;
            this.screenshotUptimeMillis = Math.max(0L, screenshotUptimeMillis);
        }

        long captureEpoch() { return captureEpoch; }
        long fastSequence() { return fastSequence; }
        long motionGeneration() { return motionGeneration; }
        long screenshotUptimeMillis() { return screenshotUptimeMillis; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof SceneKey)) return false;
            SceneKey key = (SceneKey) other;
            return captureEpoch == key.captureEpoch
                    && fastSequence == key.fastSequence
                    && motionGeneration == key.motionGeneration
                    && screenshotUptimeMillis == key.screenshotUptimeMillis;
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    captureEpoch, fastSequence, motionGeneration, screenshotUptimeMillis);
        }

        @Override
        public String toString() {
            return captureEpoch + ":" + fastSequence + ":" + motionGeneration
                    + ":" + screenshotUptimeMillis;
        }
    }

    static final class BeginResult {
        private final SceneKey started;
        private final SceneKey superseded;

        private BeginResult(SceneKey started, SceneKey superseded) {
            this.started = started;
            this.superseded = superseded;
        }

        SceneKey started() { return started; }
        SceneKey superseded() { return superseded; }
    }

    static final class Commit<T> {
        private final long transactionToken;
        private final SceneKey key;
        private final CommitKind kind;
        private final List<T> fastObservations;
        private final List<T> qualityObservations;
        private final long committedAtUptimeMillis;
        private final long deadlineUptimeMillis;

        private Commit(
                long transactionToken,
                SceneKey key,
                CommitKind kind,
                List<T> fastObservations,
                List<T> qualityObservations,
                long committedAtUptimeMillis,
                long deadlineUptimeMillis) {
            this.transactionToken = transactionToken;
            this.key = key;
            this.kind = kind;
            this.fastObservations = immutableCopy(fastObservations);
            this.qualityObservations = immutableCopy(qualityObservations);
            this.committedAtUptimeMillis = Math.max(0L, committedAtUptimeMillis);
            this.deadlineUptimeMillis = deadlineUptimeMillis;
        }

        SceneKey key() { return key; }
        CommitKind kind() { return kind; }
        List<T> fastObservations() { return fastObservations; }
        List<T> qualityObservations() { return qualityObservations; }
        long committedAtUptimeMillis() { return committedAtUptimeMillis; }
        long deadlineUptimeMillis() { return deadlineUptimeMillis; }
        boolean includesQuality() { return kind == CommitKind.SETTLED_FUSED; }
    }

    static final class Transition<T> {
        private final Status status;
        private final Commit<T> commit;

        private Transition(Status status, Commit<T> commit) {
            this.status = status;
            this.commit = commit;
        }

        Status status() { return status; }
        Commit<T> commit() { return commit; }
        Optional<Commit<T>> optionalCommit() { return Optional.ofNullable(commit); }
        boolean committed() { return commit != null; }
    }

    private enum Lifecycle { OPEN, COMMITTED, INVALIDATED }

    private long nextTransactionToken;
    private Transaction<T> current;
    private final LongSupplier uptimeMillis;

    SceneTransactionCoordinator() {
        this(() -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    SceneTransactionCoordinator(LongSupplier uptimeMillis) {
        this.uptimeMillis = Objects.requireNonNull(uptimeMillis, "uptimeMillis");
    }

    synchronized BeginResult begin(
            SceneKey key,
            Mode mode,
            long deadlineUptimeMillis) {
        return begin(key, mode, mode == Mode.SETTLED_ATOMIC, deadlineUptimeMillis);
    }

    /** Starts a new scene and atomically supersedes any older open or committed scene. */
    synchronized BeginResult begin(
            SceneKey key,
            Mode mode,
            boolean qualityExpected,
            long deadlineUptimeMillis) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(mode, "mode");
        if (deadlineUptimeMillis < key.screenshotUptimeMillis()) {
            throw new IllegalArgumentException("deadline precedes the screenshot timestamp");
        }
        if (current != null && current.key.equals(key)) {
            throw new IllegalStateException("scene key is already current: " + key);
        }
        SceneKey superseded = current == null ? null : current.key;
        current = new Transaction<>(++nextTransactionToken, key, mode,
                mode == Mode.SETTLED_ATOMIC && qualityExpected, deadlineUptimeMillis);
        return new BeginResult(key, superseded);
    }

    synchronized Transition<T> submitFast(SceneKey key, List<? extends T> observations) {
        return fastReady(key, observations, uptimeMillis.getAsLong());
    }

    synchronized Transition<T> submitQuality(SceneKey key, List<? extends T> observations) {
        return qualityReady(key, observations, uptimeMillis.getAsLong());
    }

    synchronized Transition<T> deadline(SceneKey key) {
        return deadline(key, uptimeMillis.getAsLong());
    }

    synchronized Transition<T> fastReady(
            SceneKey key, List<? extends T> observations, long nowUptimeMillis) {
        Transaction<T> transaction = currentFor(key);
        if (transaction == null) return transition(Status.DROPPED_STALE);
        if (transaction.lifecycle != Lifecycle.OPEN) return transition(Status.DROPPED_CLOSED);
        if (transaction.fastReady) return transition(Status.DROPPED_DUPLICATE);

        transaction.fastReady = true;
        transaction.fastObservations = immutableCopy(observations);
        if (transaction.mode == Mode.ACTIVE_FAST) {
            return commit(transaction, CommitKind.ACTIVE_FAST, nowUptimeMillis, false);
        }
        if (!transaction.qualityExpected) {
            return commit(transaction, CommitKind.SETTLED_FAST_ONLY, nowUptimeMillis, false);
        }
        if (deadlineReached(transaction, nowUptimeMillis)) {
            transaction.closeQuality();
            return commit(transaction, CommitKind.DEADLINE_FAST, nowUptimeMillis, false);
        }
        if (transaction.qualityReady && !transaction.qualityClosed) {
            return commit(transaction, CommitKind.SETTLED_FUSED, nowUptimeMillis, true);
        }
        return transition(Status.WAITING);
    }

    synchronized Transition<T> qualityReady(
            SceneKey key, List<? extends T> observations, long nowUptimeMillis) {
        Transaction<T> transaction = currentFor(key);
        if (transaction == null) return transition(Status.DROPPED_STALE);
        if (transaction.lifecycle != Lifecycle.OPEN) return transition(Status.DROPPED_CLOSED);
        if (!transaction.qualityExpected || transaction.mode != Mode.SETTLED_ATOMIC) {
            return transition(Status.DROPPED_POLICY);
        }
        if (transaction.qualityClosed || deadlineReached(transaction, nowUptimeMillis)) {
            transaction.closeQuality();
            if (transaction.fastReady) {
                return commit(transaction, CommitKind.DEADLINE_FAST, nowUptimeMillis, false);
            }
            return transition(Status.WAITING_FOR_FAST);
        }
        if (transaction.qualityReady) return transition(Status.DROPPED_DUPLICATE);

        transaction.qualityReady = true;
        transaction.qualityObservations = immutableCopy(observations);
        if (transaction.fastReady) {
            return commit(transaction, CommitKind.SETTLED_FUSED, nowUptimeMillis, true);
        }
        return transition(Status.WAITING);
    }

    /** Closes quality at the absolute deadline; safety fast coverage may still arrive afterward. */
    synchronized Transition<T> deadline(SceneKey key, long nowUptimeMillis) {
        Transaction<T> transaction = currentFor(key);
        if (transaction == null) return transition(Status.DROPPED_STALE);
        if (transaction.lifecycle != Lifecycle.OPEN) return transition(Status.DROPPED_CLOSED);
        if (!deadlineReached(transaction, nowUptimeMillis)) return transition(Status.WAITING);

        transaction.closeQuality();
        if (!transaction.fastReady) return transition(Status.WAITING_FOR_FAST);
        CommitKind kind = transaction.mode == Mode.ACTIVE_FAST
                ? CommitKind.ACTIVE_FAST : CommitKind.DEADLINE_FAST;
        return commit(transaction, kind, nowUptimeMillis, false);
    }

    /** Invalidates only the exact current scene. */
    synchronized boolean invalidate(SceneKey key) {
        Transaction<T> transaction = currentFor(key);
        if (transaction == null || transaction.lifecycle == Lifecycle.INVALIDATED) return false;
        transaction.lifecycle = Lifecycle.INVALIDATED;
        transaction.closeQuality();
        transaction.clearObservations();
        return true;
    }

    /** Preserves reprojectable fast-only scenes; motion still invalidates every other mode. */
    synchronized SceneKey invalidateForMotion(boolean reprojectableFast) {
        // Only fast-only observations can survive ordinary viewport translation. Structural
        // invalidation and supersession still close these transactions unconditionally.
        if (reprojectableFast && current != null
                && current.mode != Mode.SETTLED_ATOMIC) return null;
        return invalidateCurrent();
    }

    /** Invalidates whichever scene is current, if any. */
    synchronized SceneKey invalidateCurrent() {
        if (current == null || current.lifecycle == Lifecycle.INVALIDATED) return null;
        SceneKey invalidated = current.key;
        current.lifecycle = Lifecycle.INVALIDATED;
        current.closeQuality();
        current.clearObservations();
        return invalidated;
    }

    /** The reason remains with the caller's trace; it does not affect state-machine behavior. */
    synchronized SceneKey invalidate(String reason) {
        Objects.requireNonNull(reason, "reason");
        return invalidateCurrent();
    }

    /** UI/vsync code uses this immediately before presenting a previously returned commit. */
    synchronized boolean isPresentationCurrent(Commit<T> commit) {
        return commit != null
                && current != null
                && current.lifecycle == Lifecycle.COMMITTED
                && current.transactionToken == commit.transactionToken
                && current.key.equals(commit.key);
    }

    synchronized SceneKey currentKey() {
        return current == null ? null : current.key;
    }

    private Transaction<T> currentFor(SceneKey key) {
        return key != null && current != null && current.key.equals(key) ? current : null;
    }

    private Transition<T> commit(
            Transaction<T> transaction,
            CommitKind kind,
            long nowUptimeMillis,
            boolean includeQuality) {
        if (transaction.lifecycle != Lifecycle.OPEN) return transition(Status.DROPPED_CLOSED);
        transaction.lifecycle = Lifecycle.COMMITTED;
        transaction.qualityClosed = true;
        Commit<T> commit = new Commit<>(
                transaction.transactionToken,
                transaction.key,
                kind,
                transaction.fastObservations,
                includeQuality ? transaction.qualityObservations : Collections.emptyList(),
                nowUptimeMillis,
                transaction.deadlineUptimeMillis);
        transaction.clearObservations();
        return new Transition<>(Status.COMMITTED, commit);
    }

    private static boolean deadlineReached(Transaction<?> transaction, long nowUptimeMillis) {
        return Math.max(0L, nowUptimeMillis) >= transaction.deadlineUptimeMillis;
    }

    private static <T> Transition<T> transition(Status status) {
        return new Transition<>(status, null);
    }

    private static <T> List<T> immutableCopy(List<? extends T> observations) {
        if (observations == null || observations.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(observations));
    }

    private static final class Transaction<T> {
        private final long transactionToken;
        private final SceneKey key;
        private final Mode mode;
        private final boolean qualityExpected;
        private final long deadlineUptimeMillis;
        private Lifecycle lifecycle = Lifecycle.OPEN;
        private boolean fastReady;
        private boolean qualityReady;
        private boolean qualityClosed;
        private List<T> fastObservations = Collections.emptyList();
        private List<T> qualityObservations = Collections.emptyList();

        private Transaction(
                long transactionToken,
                SceneKey key,
                Mode mode,
                boolean qualityExpected,
                long deadlineUptimeMillis) {
            this.transactionToken = transactionToken;
            this.key = key;
            this.mode = mode;
            this.qualityExpected = qualityExpected;
            this.deadlineUptimeMillis = deadlineUptimeMillis;
        }

        private void closeQuality() {
            qualityClosed = true;
            qualityReady = false;
            qualityObservations = Collections.emptyList();
        }

        private void clearObservations() {
            fastObservations = Collections.emptyList();
            qualityObservations = Collections.emptyList();
        }
    }
}
