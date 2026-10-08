package com.subhub.app.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Baseline-relative, non-predictive screen offsets from a bounded set of screen anchors. */
public final class ViewportAnchorGeometry {
    public static final int MIN_ANCHORS = 3;
    public static final int MAX_ANCHORS = 5;

    private static final long SIZE_TOLERANCE_PX = 2L;
    private static final double EDGE_TOLERANCE_PX = 2.0;
    private static final double GROUP_TOLERANCE_PX = 4.0;

    private Baseline baseline;
    private long lastBaselineIdentity;

    /** Replaces the baseline and returns its new identity. Invalid input leaves the old baseline. */
    public synchronized long reset(
            List<Bounds> rectangles,
            double referenceScreenX,
            double referenceScreenY,
            long fenceToken,
            long referenceUptimeMs) {
        if (!Double.isFinite(referenceScreenX) || !Double.isFinite(referenceScreenY)) {
            throw new IllegalArgumentException("Screen reference must be finite");
        }
        if (referenceUptimeMs < 0L) {
            throw new IllegalArgumentException("Reference uptime must be non-negative");
        }
        List<Bounds> copied = copyDistinctRectangles(rectangles);
        if (lastBaselineIdentity == Long.MAX_VALUE) {
            throw new IllegalStateException("Baseline identity exhausted");
        }
        long identity = lastBaselineIdentity + 1L;
        baseline = new Baseline(copied, referenceScreenX, referenceScreenY,
                fenceToken, referenceUptimeMs, identity);
        lastBaselineIdentity = identity;
        return identity;
    }

    public synchronized void clear() {
        baseline = null;
    }

    /** Returns zero when cleared or before the first baseline. */
    public synchronized long baselineIdentity() {
        return baseline == null ? 0L : baseline.identity;
    }

    /**
     * Estimates an absolute screen offset from the fixed baseline, never from a prior estimate.
     * Translation is literal screen movement: current minus baseline, positive right/down.
     */
    public synchronized Result estimate(
            List<Bounds> currentRectangles,
            long fenceToken,
            long readStartUptimeMs,
            long readEndUptimeMs,
            long nowUptimeMs,
            long maxReadAgeMs) {
        if (maxReadAgeMs < 0L) {
            throw new IllegalArgumentException("Maximum read age must be non-negative");
        }
        Baseline value = baseline;
        if (value == null) return Result.rejected(Status.NO_BASELINE, 0L, 0, 0,
                readStartUptimeMs, readEndUptimeMs);
        if (fenceToken != value.fenceToken) {
            return Result.rejected(Status.FENCE_MISMATCH, value.identity, 0,
                    value.rectangles.size(), readStartUptimeMs, readEndUptimeMs);
        }
        if (readEndUptimeMs < readStartUptimeMs
                || readStartUptimeMs < value.referenceUptimeMs) {
            return Result.rejected(Status.NON_MONOTONIC_READ, value.identity, 0,
                    value.rectangles.size(), readStartUptimeMs, readEndUptimeMs);
        }
        if (nowUptimeMs < value.referenceUptimeMs
                || readStartUptimeMs > nowUptimeMs
                || readEndUptimeMs > nowUptimeMs) {
            return Result.rejected(Status.FUTURE_READ, value.identity, 0,
                    value.rectangles.size(), readStartUptimeMs, readEndUptimeMs);
        }
        if (nowUptimeMs - readEndUptimeMs > maxReadAgeMs) {
            return Result.rejected(Status.STALE_READ, value.identity, 0,
                    value.rectangles.size(), readStartUptimeMs, readEndUptimeMs);
        }
        Objects.requireNonNull(currentRectangles, "currentRectangles");
        if (currentRectangles.size() < MIN_ANCHORS) {
            return Result.rejected(Status.INSUFFICIENT_ANCHORS, value.identity, 0,
                    value.rectangles.size(), readStartUptimeMs, readEndUptimeMs);
        }
        if (currentRectangles.size() != value.rectangles.size()) {
            return Result.rejected(Status.ANCHOR_COUNT_MISMATCH, value.identity, 0,
                    value.rectangles.size(), readStartUptimeMs, readEndUptimeMs);
        }

        int count = value.rectangles.size();
        Translation[] rigid = new Translation[count];
        int rigidCount = 0;
        int resizedCount = 0;
        for (int index = 0; index < count; index++) {
            Bounds current = Objects.requireNonNull(
                    currentRectangles.get(index), "current rectangle");
            Translation candidate = rigidTranslation(value.rectangles.get(index), current);
            if (candidate == null) {
                resizedCount++;
            } else {
                rigid[rigidCount++] = candidate;
            }
        }
        if (rigidCount < MIN_ANCHORS) {
            Status status = resizedCount > 0
                    ? Status.RESIZED_OR_CLIPPED : Status.INSUFFICIENT_RIGID_INLIERS;
            return Result.rejected(status, value.identity, rigidCount,
                    count - rigidCount, readStartUptimeMs, readEndUptimeMs);
        }

        double medianX = medianAxis(rigid, rigidCount, true);
        double medianY = medianAxis(rigid, rigidCount, false);
        Translation[] inliers = new Translation[rigidCount];
        int inlierCount = 0;
        for (int index = 0; index < rigidCount; index++) {
            Translation candidate = rigid[index];
            if (Math.abs(candidate.x - medianX) <= GROUP_TOLERANCE_PX
                    && Math.abs(candidate.y - medianY) <= GROUP_TOLERANCE_PX) {
                inliers[inlierCount++] = candidate;
            }
        }
        if (inlierCount < MIN_ANCHORS) {
            return Result.rejected(Status.INCONSISTENT_TRANSLATION, value.identity,
                    inlierCount, count - inlierCount, readStartUptimeMs, readEndUptimeMs);
        }

        double translationX = medianAxis(inliers, inlierCount, true);
        double translationY = medianAxis(inliers, inlierCount, false);
        return Result.measured(value.identity,
                value.referenceScreenX + translationX,
                value.referenceScreenY + translationY,
                translationX, translationY, inlierCount, count - inlierCount,
                readStartUptimeMs, readEndUptimeMs);
    }

    private static List<Bounds> copyDistinctRectangles(List<Bounds> rectangles) {
        Objects.requireNonNull(rectangles, "rectangles");
        if (rectangles.size() < MIN_ANCHORS || rectangles.size() > MAX_ANCHORS) {
            throw new IllegalArgumentException("Baseline requires three to five anchors");
        }
        List<Bounds> copied = new ArrayList<>(rectangles.size());
        for (Bounds rectangle : rectangles) {
            Bounds source = Objects.requireNonNull(rectangle, "rectangle");
            Bounds copy = new Bounds(source.left, source.top, source.right, source.bottom);
            if (copied.contains(copy)) {
                throw new IllegalArgumentException("Baseline anchors must be distinct");
            }
            copied.add(copy);
        }
        return Collections.unmodifiableList(copied);
    }

    private static Translation rigidTranslation(Bounds baseline, Bounds current) {
        if (Math.abs(baseline.width() - current.width()) > SIZE_TOLERANCE_PX
                || Math.abs(baseline.height() - current.height()) > SIZE_TOLERANCE_PX) {
            return null;
        }
        double left = (long) current.left - baseline.left;
        double right = (long) current.right - baseline.right;
        double top = (long) current.top - baseline.top;
        double bottom = (long) current.bottom - baseline.bottom;
        double x = (left + right) * 0.5;
        double y = (top + bottom) * 0.5;
        if (Math.abs(left - x) > EDGE_TOLERANCE_PX
                || Math.abs(right - x) > EDGE_TOLERANCE_PX
                || Math.abs(top - y) > EDGE_TOLERANCE_PX
                || Math.abs(bottom - y) > EDGE_TOLERANCE_PX) {
            return null;
        }
        return new Translation(x, y);
    }

    private static double medianAxis(Translation[] values, int count, boolean xAxis) {
        double[] sorted = new double[count];
        for (int index = 0; index < count; index++) {
            sorted[index] = xAxis ? values[index].x : values[index].y;
        }
        Arrays.sort(sorted);
        int middle = count / 2;
        return (count & 1) == 1
                ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) * 0.5;
    }

    public enum Status {
        MEASURED,
        NO_BASELINE,
        FENCE_MISMATCH,
        NON_MONOTONIC_READ,
        FUTURE_READ,
        STALE_READ,
        INSUFFICIENT_ANCHORS,
        ANCHOR_COUNT_MISMATCH,
        INSUFFICIENT_RIGID_INLIERS,
        INCONSISTENT_TRANSLATION,
        RESIZED_OR_CLIPPED
    }

    public static final class Bounds {
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public Bounds(int left, int top, int right, int bottom) {
            if (right <= left || bottom <= top) {
                throw new IllegalArgumentException("Bounds must have positive area");
            }
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public int left() { return left; }
        public int top() { return top; }
        public int right() { return right; }
        public int bottom() { return bottom; }
        public long width() { return (long) right - left; }
        public long height() { return (long) bottom - top; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Bounds)) return false;
            Bounds bounds = (Bounds) other;
            return left == bounds.left && top == bounds.top
                    && right == bounds.right && bottom == bounds.bottom;
        }

        @Override public int hashCode() {
            return Objects.hash(left, top, right, bottom);
        }
    }

    public static final class Result {
        public final Status status;
        public final long baselineIdentity;
        public final double measuredX;
        public final double measuredY;
        public final long readStartUptimeMs;
        public final long readEndUptimeMs;

        private final double translationX;
        private final double translationY;
        private final int inlierCount;
        private final int excludedAnchorCount;

        private Result(
                Status status,
                long baselineIdentity,
                double screenOffsetX,
                double screenOffsetY,
                double translationX,
                double translationY,
                int inlierCount,
                int excludedAnchorCount,
                long readStartUptimeMs,
                long readEndUptimeMs) {
            this.status = status;
            this.baselineIdentity = baselineIdentity;
            this.measuredX = screenOffsetX;
            this.measuredY = screenOffsetY;
            this.translationX = translationX;
            this.translationY = translationY;
            this.inlierCount = inlierCount;
            this.excludedAnchorCount = excludedAnchorCount;
            this.readStartUptimeMs = readStartUptimeMs;
            this.readEndUptimeMs = readEndUptimeMs;
        }

        private static Result measured(
                long identity,
                double offsetX,
                double offsetY,
                double translationX,
                double translationY,
                int inlierCount,
                int excludedAnchorCount,
                long readStartUptimeMs,
                long readEndUptimeMs) {
            return new Result(Status.MEASURED, identity, offsetX, offsetY,
                    translationX, translationY, inlierCount, excludedAnchorCount,
                    readStartUptimeMs, readEndUptimeMs);
        }

        private static Result rejected(
                Status status,
                long identity,
                int inlierCount,
                int excludedAnchorCount,
                long readStartUptimeMs,
                long readEndUptimeMs) {
            return new Result(status, identity, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, inlierCount, excludedAnchorCount,
                    readStartUptimeMs, readEndUptimeMs);
        }

        public Status status() { return status; }
        public boolean measured() { return status == Status.MEASURED; }
        public boolean accepted() { return status == Status.MEASURED; }
        public long baselineIdentity() { return baselineIdentity; }
        public double screenOffsetX() { return measuredX; }
        public double screenOffsetY() { return measuredY; }
        public double translationX() { return translationX; }
        public double translationY() { return translationY; }
        public int inlierCount() { return inlierCount; }
        public int excludedAnchorCount() { return excludedAnchorCount; }
        public long readStartUptimeMs() { return readStartUptimeMs; }
        public long readEndUptimeMs() { return readEndUptimeMs; }
    }

    private static final class Baseline {
        final List<Bounds> rectangles;
        final double referenceScreenX;
        final double referenceScreenY;
        final long fenceToken;
        final long referenceUptimeMs;
        final long identity;

        Baseline(
                List<Bounds> rectangles,
                double referenceScreenX,
                double referenceScreenY,
                long fenceToken,
                long referenceUptimeMs,
                long identity) {
            this.rectangles = rectangles;
            this.referenceScreenX = referenceScreenX;
            this.referenceScreenY = referenceScreenY;
            this.fenceToken = fenceToken;
            this.referenceUptimeMs = referenceUptimeMs;
            this.identity = identity;
        }
    }

    private static final class Translation {
        final double x;
        final double y;

        Translation(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }
}
