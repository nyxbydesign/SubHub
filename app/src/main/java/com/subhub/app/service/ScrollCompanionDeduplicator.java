package com.subhub.app.service;

/** Coalesces a proven absolute/explicit companion pair, never same-producer motion. */
final class ScrollCompanionDeduplicator {
    private static final long MAX_PAIR_GAP_MS = 32L;
    private boolean pending;
    private long document, surface, producer, sourceTime, receivedTime;
    private int width, height, dx, dy;

    boolean observe(long document, long surface, long producer, int width, int height,
            boolean trustedAbsolute, boolean reusedCompanion,
            AccessibilityScrollMotionResolver.Motion.Evidence evidence,
            int dx, int dy, long sourceTime, long receivedTime) {
        boolean valid = surface != 0 && producer != 0 && width > 0 && height > 0
                && sourceTime > 0 && receivedTime >= sourceTime
                && receivedTime - sourceTime <= MAX_PAIR_GAP_MS;
        boolean duplicate = pending && valid && reusedCompanion
                && evidence == AccessibilityScrollMotionResolver.Motion.Evidence.EXPLICIT
                && this.document == document && this.surface == surface
                && this.producer != producer && this.width == width && this.height == height
                && this.dx == dx && this.dy == dy
                && sourceTime >= this.sourceTime
                && sourceTime - this.sourceTime <= MAX_PAIR_GAP_MS
                && receivedTime >= this.receivedTime
                && receivedTime - this.receivedTime <= MAX_PAIR_GAP_MS;
        // One immediately preceding trusted sample supplies one credit. Even an intervening
        // zero/unknown event breaks the pair; equal deltas from a producer remain additive.
        pending = valid && trustedAbsolute && !reusedCompanion && (dx != 0 || dy != 0)
                && evidence == AccessibilityScrollMotionResolver.Motion.Evidence.ABSOLUTE;
        if (pending) {
            this.document = document;
            this.surface = surface;
            this.producer = producer;
            this.width = width;
            this.height = height;
            this.dx = dx;
            this.dy = dy;
            this.sourceTime = sourceTime;
            this.receivedTime = receivedTime;
        }
        return duplicate;
    }

    void reset() { pending = false; }
}
