package com.subhub.app.service;

/**
 * Worker-owned reference-image graph for a scoped content crop. Image poses are not current
 * display poses. Receipts are used only for ordering/reference expiry, never as exact pixel time.
 * A new map generation is a new coordinate space; callers must not reuse old-map cache entries.
 */
final class SpatialFrameMap {
    // Retaining an image for re-identification is not retaining a current-display prediction.
    // Unknown intervening frames remain unknown; only a new spatial match can recover this map.
    private static final long MAX_REFERENCE_AGE_MS = 3000L;
    enum Status { BASELINE, REGISTERED, UNMATCHED, NO_MOTION_HINT, INVALID, STALE }

    private RowMotionObserver.Scope scope;
    private int width, height, top, bottom;
    private int[] referencePixels;
    private double[][] referenceRows;
    private long mapGeneration;
    private long referenceId, referenceReceipt;
    private long lastId = -1, lastReceipt = -1;
    private double referenceY;

    Result observe(int[] pixels, int width, int height, int top, int bottom,
            RowMotionObserver.Scope candidateScope, long frameId, long receivedAt, boolean recentMotionHint) {
        if (pixels == null || width < 32 || width > 512 || height < RowMotionObserver.ROWS || height > 512
                || (long) width * height != pixels.length || top < 0 || bottom > height
                || bottom - top < RowMotionObserver.ROWS || candidateScope == null || !candidateScope.valid()
                || frameId < 0 || receivedAt < 0) return unknown(Status.INVALID, frameId);
        if (scope != null && (candidateScope.captureEpoch < scope.captureEpoch
                || candidateScope.captureEpoch == scope.captureEpoch && candidateScope.document < scope.document)) {
            return unknown(Status.STALE, frameId);
        }
        boolean sameDocument = scope != null && candidateScope.captureEpoch == scope.captureEpoch
                && candidateScope.document == scope.document;
        if (sameDocument && candidateScope.window != scope.window) {
            // The owning service must advance its document scope on a window change.
            return unknown(Status.INVALID, frameId);
        }
        if (sameDocument && (frameId <= lastId || receivedAt < lastReceipt)) return unknown(Status.STALE, frameId);
        boolean reset = scope == null || !scope.matches(candidateScope) || referencePixels == null
                || this.width != width || this.height != height || this.top != top || this.bottom != bottom;
        lastId = frameId;
        lastReceipt = receivedAt;
        double[][] rows = RowMotionObserver.describe(pixels, width, top, bottom);
        if (reset) {
            scope = candidateScope;
            this.width = width; this.height = height; this.top = top; this.bottom = bottom;
            return baseline(pixels, rows, frameId, receivedAt);
        }
        RowMotionEstimator.Result coarse = RowMotionEstimator.estimate(referenceRows, rows);
        if (!coarse.accepted) return unmatched(Status.UNMATCHED, pixels, rows, frameId, receivedAt);
        double coarseDy = coarse.dy * (bottom - top) / RowMotionObserver.ROWS;
        if (!recentMotionHint && Math.abs(coarseDy) > .01) {
            return unmatched(Status.NO_MOTION_HINT, pixels, rows, frameId, receivedAt);
        }
        SpatialFrameRegistration.Result registration = SpatialFrameRegistration.refine(
                referencePixels, pixels, width, height, top, bottom, coarseDy);
        if (!registration.accepted) return unmatched(Status.UNMATCHED, pixels, rows, frameId, receivedAt);
        double sourceDy = registration.dy * scope.sourceHeight / height;
        double nextY = referenceY - sourceDy;
        if (!Double.isFinite(nextY)) return unknown(Status.INVALID, frameId);
        long matchedReferenceId = referenceId;
        referenceY = nextY;
        keepReference(pixels, rows, frameId, receivedAt);
        return new Result(Status.REGISTERED, new Pose(mapGeneration, frameId, nextY),
                matchedReferenceId, sourceDy, registration.inliers);
    }

    private Result unmatched(Status status, int[] pixels, double[][] rows, long id, long receipt) {
        // Try spatial re-identification before expiring a retained image. An old receipt is not
        // evidence of image motion, nor does a successful image match establish display time.
        return receipt - referenceReceipt > MAX_REFERENCE_AGE_MS
                ? baseline(pixels, rows, id, receipt) : unknown(status, id);
    }

    private Result baseline(int[] pixels, double[][] rows, long id, long receipt) {
        mapGeneration++;
        referenceY = 0;
        keepReference(pixels, rows, id, receipt);
        return new Result(Status.BASELINE, new Pose(mapGeneration, id, 0), id, 0, 0);
    }

    void clear() {
        scope = null;
        referencePixels = null;
        referenceRows = null;
        lastId = lastReceipt = -1;
        mapGeneration++;
    }

    private void keepReference(int[] pixels, double[][] rows, long id, long receipt) {
        // Ownership must not depend on whether a caller recycles/reuses its prepared array.
        referencePixels = pixels.clone();
        referenceRows = rows;
        referenceId = id;
        referenceReceipt = receipt;
    }

    private Result unknown(Status status, long frameId) {
        return new Result(status, null, referencePixels == null ? -1 : referenceId, 0, 0);
    }

    static final class Pose {
        final long mapGeneration, frameId;
        final double sourceY;
        Pose(long mapGeneration, long frameId, double sourceY) {
            this.mapGeneration = mapGeneration; this.frameId = frameId; this.sourceY = sourceY;
        }
    }

    static final class Result {
        final Status status;
        final Pose pose; // Null means UNKNOWN, never a valid zero coordinate.
        final long referenceId;
        final double sourceDy;
        final int inliers;
        Result(Status status, Pose pose, long referenceId, double sourceDy, int inliers) {
            this.status = status; this.pose = pose; this.referenceId = referenceId;
            this.sourceDy = sourceDy; this.inliers = inliers;
        }
    }
}
