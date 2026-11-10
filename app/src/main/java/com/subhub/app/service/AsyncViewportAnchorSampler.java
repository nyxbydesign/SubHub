package com.subhub.app.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single-worker, bounded position sampler. No Android, renderer, tracker, or cache authority. */
final class AsyncViewportAnchorSampler implements AutoCloseable {
    interface Clock { long now(); }
    /**
     * Serialize on ONE owned worker, never the drawing thread. The owner must not externally
     * shut it down: cleanup is queued before this sampler calls shutdown itself.
     */
    interface Worker {
        void execute(Runnable task);
        void schedule(Runnable task, long delayMs);
        void shutdown();
    }
    interface Anchor extends AutoCloseable {
        ViewportAnchorGeometry.Bounds read();
        @Override void close();
    }
    enum ReadFailure { CLOSED, REFRESH_FAILED, INVISIBLE, SCOPE_CHANGED, EMPTY_BOUNDS }
    static final class AnchorReadException extends IllegalStateException {
        final ReadFailure reason;
        AnchorReadException(ReadFailure reason) { super(reason.name()); this.reason = reason; }
    }
    interface Source {
        State state();
        /** Worker-only discovery; returned anchors transfer ownership to this sampler. */
        List<Anchor> acquire(State expected);
    }
    interface Sink {
        /** Worker callback; the presentation adapter must use a latest-only mailbox. */
        void accept(ViewportAnchorGeometry.Result result, State reference);
    }

    static final class State {
        final boolean enabled;
        final long epoch, cameraX, cameraY, lastMotionMs;
        final int windowId, width, height, frameIntervalMs;
        State(boolean enabled, long epoch, int windowId, int width, int height,
                long cameraX, long cameraY, long lastMotionMs, int frameIntervalMs) {
            this.enabled = enabled;
            this.epoch = epoch;
            this.windowId = windowId;
            this.width = width;
            this.height = height;
            this.cameraX = cameraX;
            this.cameraY = cameraY;
            this.lastMotionMs = lastMotionMs;
            this.frameIntervalMs = Math.max(8, Math.min(33, frameIntervalMs));
        }
        boolean sameStructure(State other) {
            return other != null && enabled && other.enabled && epoch == other.epoch
                    && windowId == other.windowId && width == other.width && height == other.height;
        }
        boolean sameCamera(State other) {
            return sameStructure(other) && cameraX == other.cameraX && cameraY == other.cameraY;
        }
    }

    static final class Stats {
        final long reads, accepted, slowDrops, invalidDrops, resets, maxReadMs;
        final String rejectionCounts;
        final long singleNodeBudgetDrops, aggregateBudgetDrops, discoveryBudgetDrops, maxNodeMs;
        Stats(long reads, long accepted, long slowDrops, long invalidDrops, long resets, long maxReadMs,
                String rejectionCounts, long singleNodeBudgetDrops, long aggregateBudgetDrops,
                long discoveryBudgetDrops, long maxNodeMs) {
            this.reads = reads; this.accepted = accepted; this.slowDrops = slowDrops;
            this.invalidDrops = invalidDrops; this.resets = resets; this.maxReadMs = maxReadMs;
            this.rejectionCounts = rejectionCounts;
            this.singleNodeBudgetDrops = singleNodeBudgetDrops;
            this.aggregateBudgetDrops = aggregateBudgetDrops;
            this.discoveryBudgetDrops = discoveryBudgetDrops;
            this.maxNodeMs = maxNodeMs;
        }
    }

    private static final long SETTLED_MS = 200, IDLE_MS = 64, RETRY_MS = 250;
    private static final long MAX_READ_MS = 16, MAX_AGE_MS = 32, BURST_MS = 500;
    private final Clock clock;
    private final Worker worker;
    private final Source source;
    private final Sink sink;
    private final ViewportAnchorGeometry geometry = new ViewportAnchorGeometry();
    private final AtomicBoolean started = new AtomicBoolean(), closed = new AtomicBoolean();
    private final AtomicBoolean shutdown = new AtomicBoolean();
    private final AtomicBoolean cleanupEnqueued = new AtomicBoolean();
    // Everything below except the immutable stats publication belongs to the worker.
    private List<Anchor> anchors = Collections.emptyList();
    private State reference;
    private boolean confirmed;
    private int consecutiveDrops;
    private long burstUntil, reads, accepted, slowDrops, invalidDrops, resets, maxReadMs;
    private double lastX, lastY;
    private final long[] geometryDrops = new long[ViewportAnchorGeometry.Status.values().length];
    private final long[] nodeDrops = new long[ReadFailure.values().length];
    private long scopeDrops, exceptionDrops;
    private long singleNodeBudgetDrops, aggregateBudgetDrops, discoveryBudgetDrops, maxNodeMs;
    private long batchMaxNodeMs;
    private volatile Stats stats = new Stats(0, 0, 0, 0, 0, 0, "NONE", 0, 0, 0, 0);

    AsyncViewportAnchorSampler(Clock clock, Worker worker, Source source, Sink sink) {
        this.clock = clock; this.worker = worker; this.source = source; this.sink = sink;
    }
    void start() {
        if (!closed.get() && started.compareAndSet(false, true)) worker.execute(this::tick);
    }
    Stats stats() { return stats; }

    private void tick() {
        if (closed.get()) { finishCloseOnWorker(); return; }
        long delay = IDLE_MS;
        try {
            State current = source.state();
            if (current == null || !current.enabled || current.windowId < 0
                    || current.width <= 0 || current.height <= 0) {
                clearBaseline();
                delay = RETRY_MS;
            } else if (reference == null) {
                if (clock.now() - current.lastMotionMs >= SETTLED_MS) {
                    discover(current);
                }
            } else if (!reference.sameStructure(current)) {
                clearBaseline();
            } else {
                long readStart = clock.now();
                List<ViewportAnchorGeometry.Bounds> bounds = readBounds(readStart + MAX_READ_MS);
                long readEnd = clock.now();
                reads++;
                long cost = Math.max(0, readEnd - readStart);
                maxReadMs = Math.max(maxReadMs, cost);
                if (closed.get()) return;
                State after = source.state();
                if (closed.get()) return;
                if (!reference.sameStructure(after)) {
                    invalidDrops++;
                    scopeDrops++;
                    clearBaseline();
                } else if (cost > MAX_READ_MS) {
                    slowDrops++;
                    if (batchMaxNodeMs > MAX_READ_MS) singleNodeBudgetDrops++;
                    else aggregateBudgetDrops++;
                    // A missed deadline says nothing about baseline validity. Keep the same
                    // absolute reference so a fresh read can recover even while scrolling;
                    // rediscovery requires idle and would lose the remainder of the gesture.
                    consecutiveDrops = Math.min(8, consecutiveDrops + 1);
                    delay = Math.min(RETRY_MS, Math.max(MAX_READ_MS, after.frameIntervalMs)
                            * (1L << (consecutiveDrops - 1)));
                } else {
                    ViewportAnchorGeometry.Result result = geometry.estimate(bounds,
                            after.epoch, readStart, readEnd, clock.now(), MAX_AGE_MS);
                    if (!result.accepted()) {
                        invalidDrops++;
                        geometryDrops[result.status.ordinal()]++;
                        clearBaseline();
                        delay = RETRY_MS;
                    } else if (!confirmed) {
                        consecutiveDrops = 0;
                        // Establish the baseline only through a second unchanged idle read.
                        // A scroll during discovery must never become an arbitrary camera origin.
                        if (!reference.sameCamera(after)
                                || clock.now() - after.lastMotionMs < SETTLED_MS
                                || Math.abs(result.measuredX - reference.cameraX) > 1
                                || Math.abs(result.measuredY - reference.cameraY) > 1) {
                            clearBaseline();
                        } else {
                            confirmed = true;
                            lastX = result.measuredX;
                            lastY = result.measuredY;
                        }
                    } else {
                        consecutiveDrops = 0;
                        if (Math.abs(result.measuredX - lastX) > 1
                                || Math.abs(result.measuredY - lastY) > 1
                                || Math.abs(result.measuredX - after.cameraX) > 1
                                || Math.abs(result.measuredY - after.cameraY) > 1) {
                            burstUntil = clock.now() + BURST_MS;
                        }
                        lastX = result.measuredX; lastY = result.measuredY;
                        // Absolute positions recover displacement lost during a dropped read.
                        // They never add another copy of an Accessibility event's delta.
                        sink.accept(result, reference);
                        accepted++;
                        if (clock.now() <= burstUntil
                                || clock.now() - after.lastMotionMs <= BURST_MS) {
                            delay = after.frameIntervalMs;
                        }
                    }
                }
            }
        } catch (RuntimeException failure) {
            invalidDrops++;
            if (failure instanceof AnchorReadException) {
                nodeDrops[((AnchorReadException) failure).reason.ordinal()]++;
            } else {
                exceptionDrops++;
            }
            clearBaseline();
            delay = RETRY_MS;
        } finally {
            stats = new Stats(reads, accepted, slowDrops, invalidDrops, resets, maxReadMs,
                    rejectionCounts(), singleNodeBudgetDrops, aggregateBudgetDrops,
                    discoveryBudgetDrops, maxNodeMs);
            if (closed.get()) finishCloseOnWorker();
            else worker.schedule(this::tick, delay);
        }
    }

    /** Fixed enum/count vocabulary only; never provider text, node IDs or exception messages. */
    private String rejectionCounts() {
        StringBuilder value = new StringBuilder();
        for (ViewportAnchorGeometry.Status status : ViewportAnchorGeometry.Status.values()) {
            appendCount(value, status.name(), geometryDrops[status.ordinal()]);
        }
        for (ReadFailure reason : ReadFailure.values()) {
            appendCount(value, reason.name(), nodeDrops[reason.ordinal()]);
        }
        appendCount(value, "STRUCTURE_CHANGED", scopeDrops);
        appendCount(value, "UNCLASSIFIED_EXCEPTION", exceptionDrops);
        return value.length() == 0 ? "NONE" : value.toString();
    }

    private static void appendCount(StringBuilder output, String key, long count) {
        if (count == 0) return;
        if (output.length() > 0) output.append(',');
        output.append(key).append(':').append(count);
    }

    private void discover(State before) {
        List<Anchor> acquired = source.acquire(before);
        anchors = acquired == null ? Collections.emptyList() : new ArrayList<>(acquired);
        if (closed.get() || anchors.size() < 3 || anchors.size() > 5) {
            clearBaseline(); return;
        }
        long readStart = clock.now();
        List<ViewportAnchorGeometry.Bounds> baseline = readBounds(readStart + MAX_READ_MS);
        if (closed.get()) return;
        if (clock.now() - readStart > MAX_READ_MS || baseline.size() != anchors.size()) {
            discoveryBudgetDrops++;
            clearBaseline();
            return;
        }
        State after = source.state();
        if (!before.sameCamera(after) || clock.now() - after.lastMotionMs < SETTLED_MS
                || !spatiallySpread(baseline, after)) {
            clearBaseline(); return;
        }
        geometry.reset(baseline, after.cameraX, after.cameraY, after.epoch, clock.now());
        reference = after;
        confirmed = false;
    }

    private static boolean spatiallySpread(List<ViewportAnchorGeometry.Bounds> values, State state) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (ViewportAnchorGeometry.Bounds bounds : values) {
            if (bounds == null) return false;
            double x = ((double) bounds.left + bounds.right) * .5;
            double y = ((double) bounds.top + bounds.bottom) * .5;
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }
        // Several tiny leaves inside one moving widget are not an independent camera ensemble.
        return maxX - minX >= state.width * .25 || maxY - minY >= state.height * .15;
    }

    private List<ViewportAnchorGeometry.Bounds> readBounds(long deadline) {
        List<ViewportAnchorGeometry.Bounds> bounds = new ArrayList<>(anchors.size());
        batchMaxNodeMs = 0;
        for (Anchor anchor : anchors) {
            if (closed.get() || clock.now() > deadline) break;
            long start = clock.now();
            try {
                bounds.add(anchor.read());
            } finally {
                long cost = Math.max(0, clock.now() - start);
                batchMaxNodeMs = Math.max(batchMaxNodeMs, cost);
                maxNodeMs = Math.max(maxNodeMs, cost);
            }
        }
        return bounds;
    }

    private void clearBaseline() {
        List<Anchor> old = anchors;
        anchors = Collections.emptyList();
        reference = null; confirmed = false; consecutiveDrops = 0; burstUntil = 0;
        geometry.clear();
        if (!old.isEmpty()) resets++;
        for (Anchor anchor : old) {
            try { if (anchor != null) anchor.close(); } catch (RuntimeException ignored) { }
        }
    }

    @Override public void close() {
        closed.set(true);
        if (shutdown.get() || !cleanupEnqueued.compareAndSet(false, true)) return;
        // No join or source reads on the caller. The worker owns nodes until its read returns.
        try {
            worker.execute(this::finishCloseOnWorker);
        } catch (RuntimeException rejected) {
            cleanupEnqueued.set(false); // Surface failure and allow an explicit cleanup retry.
            throw rejected;
        }
    }

    private void finishCloseOnWorker() {
        clearBaseline();
        if (shutdown.compareAndSet(false, true)) worker.shutdown();
    }
}
