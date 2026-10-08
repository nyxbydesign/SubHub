package com.subhub.app.service;

import com.subhub.app.detection.RenderSourceReference;
import com.subhub.app.detection.RenderSourceReference.Origin;

import java.util.ArrayDeque;
import java.util.Objects;

/** Resolves measured anchor camera state at a hardware screenshot timestamp. */
public final class RenderSourceTimeline {
    static final int DEFAULT_MAX_SAMPLES = 128;
    static final long DEFAULT_MAX_READ_SPAN_MS = 16L;
    static final long DEFAULT_MAX_BRACKET_SPAN_MS = 48L;

    private final int maxSamples;
    private final long maxReadSpanMs;
    private final long maxBracketSpanMs;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    public RenderSourceTimeline() {
        this(DEFAULT_MAX_SAMPLES, DEFAULT_MAX_READ_SPAN_MS,
                DEFAULT_MAX_BRACKET_SPAN_MS);
    }

    RenderSourceTimeline(int maxSamples, long maxReadSpanMs, long maxBracketSpanMs) {
        if (maxSamples <= 0 || maxReadSpanMs < 0L || maxBracketSpanMs < 0L) {
            throw new IllegalArgumentException("Invalid timeline bounds");
        }
        this.maxSamples = maxSamples;
        this.maxReadSpanMs = maxReadSpanMs;
        this.maxBracketSpanMs = maxBracketSpanMs;
    }

    /** Records an actual absolute anchor-camera measurement; forecast values do not belong here. */
    public synchronized void record(
            Origin origin,
            long readStartUptimeMs,
            long readEndUptimeMs,
            double measuredScreenCameraX,
            double measuredScreenCameraY) {
        Objects.requireNonNull(origin, "origin");
        boolean valid = readStartUptimeMs >= 0L
                && readEndUptimeMs >= readStartUptimeMs
                && readEndUptimeMs - readStartUptimeMs <= maxReadSpanMs
                && Double.isFinite(measuredScreenCameraX)
                && Double.isFinite(measuredScreenCameraY);
        samples.addLast(new Sample(origin, readStartUptimeMs, readEndUptimeMs,
                measuredScreenCameraX, measuredScreenCameraY, valid));
        while (samples.size() > maxSamples) samples.removeFirst();
    }

    /**
     * Resolves B(capture) = A(capture) + D(capture) from exact or bracketed actual reads.
     * No sample is extrapolated beyond the retained measurement envelope.
     */
    public synchronized RenderSourceReference resolve(
            Origin expectedOrigin,
            long screenshotUptimeMs,
            double documentCameraX,
            double documentCameraY) {
        if (expectedOrigin == null || screenshotUptimeMs < 0L
                || !Double.isFinite(documentCameraX)
                || !Double.isFinite(documentCameraY)) {
            return RenderSourceReference.UNKNOWN;
        }

        Sample lower = null;
        Sample upper = null;
        Sample previous = null;
        for (Sample sample : samples) {
            if (!expectedOrigin.equals(sample.origin)) continue;
            if (!sample.valid
                    || (previous != null
                    && (sample.readStartUptimeMs < previous.readStartUptimeMs
                    || sample.readEndUptimeMs <= previous.readEndUptimeMs))) {
                return RenderSourceReference.UNKNOWN;
            }
            previous = sample;
            if (sample.readEndUptimeMs <= screenshotUptimeMs) lower = sample;
            if (sample.readEndUptimeMs >= screenshotUptimeMs && upper == null) upper = sample;
        }

        if (lower == null || upper == null) return RenderSourceReference.UNKNOWN;
        double anchorCameraX;
        double anchorCameraY;
        if (lower.readEndUptimeMs == screenshotUptimeMs) {
            anchorCameraX = lower.measuredScreenCameraX;
            anchorCameraY = lower.measuredScreenCameraY;
        } else {
            long bracketSpanMs = upper.readEndUptimeMs - lower.readEndUptimeMs;
            if (bracketSpanMs <= 0L || bracketSpanMs > maxBracketSpanMs) {
                return RenderSourceReference.UNKNOWN;
            }
            double fraction = (double) (screenshotUptimeMs - lower.readEndUptimeMs)
                    / bracketSpanMs;
            anchorCameraX = lower.measuredScreenCameraX
                    + (upper.measuredScreenCameraX - lower.measuredScreenCameraX) * fraction;
            anchorCameraY = lower.measuredScreenCameraY
                    + (upper.measuredScreenCameraY - lower.measuredScreenCameraY) * fraction;
        }

        double biasX = anchorCameraX + documentCameraX;
        double biasY = anchorCameraY + documentCameraY;
        if (!Double.isFinite(biasX) || !Double.isFinite(biasY)) {
            return RenderSourceReference.UNKNOWN;
        }
        return RenderSourceReference.known(
                expectedOrigin, screenshotUptimeMs, biasX, biasY);
    }

    public synchronized void clear() {
        samples.clear();
    }

    public synchronized int size() {
        return samples.size();
    }

    private static final class Sample {
        final Origin origin;
        final long readStartUptimeMs;
        final long readEndUptimeMs;
        final double measuredScreenCameraX;
        final double measuredScreenCameraY;
        final boolean valid;

        Sample(
                Origin origin,
                long readStartUptimeMs,
                long readEndUptimeMs,
                double measuredScreenCameraX,
                double measuredScreenCameraY,
                boolean valid) {
            this.origin = origin;
            this.readStartUptimeMs = readStartUptimeMs;
            this.readEndUptimeMs = readEndUptimeMs;
            this.measuredScreenCameraX = measuredScreenCameraX;
            this.measuredScreenCameraY = measuredScreenCameraY;
            this.valid = valid;
        }
    }
}
