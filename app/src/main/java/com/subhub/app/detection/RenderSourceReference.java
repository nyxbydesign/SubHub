package com.subhub.app.detection;

import java.util.Objects;

/** Immutable render provenance; never an input to tracking or document-motion authority. */
public final class RenderSourceReference {
    public static final RenderSourceReference UNKNOWN =
            new RenderSourceReference(null, -1L, 0.0, 0.0);
    private static final double BASIS_TOLERANCE = 1e-6;

    public static final class Origin {
        public final long captureEpoch, documentEpoch, anchorOrigin;
        public final int windowId, viewportWidth, viewportHeight;

        public Origin(long captureEpoch, long documentEpoch, int windowId,
                int viewportWidth, int viewportHeight, long anchorOrigin) {
            if (captureEpoch < 0 || documentEpoch < 0 || windowId < 0
                    || viewportWidth <= 0 || viewportHeight <= 0 || anchorOrigin <= 0) {
                throw new IllegalArgumentException("Invalid render origin");
            }
            this.captureEpoch = captureEpoch;
            this.documentEpoch = documentEpoch;
            this.windowId = windowId;
            this.viewportWidth = viewportWidth;
            this.viewportHeight = viewportHeight;
            this.anchorOrigin = anchorOrigin;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Origin)) return false;
            Origin value = (Origin) other;
            return captureEpoch == value.captureEpoch && documentEpoch == value.documentEpoch
                    && windowId == value.windowId && viewportWidth == value.viewportWidth
                    && viewportHeight == value.viewportHeight && anchorOrigin == value.anchorOrigin;
        }

        @Override public int hashCode() {
            return Objects.hash(captureEpoch, documentEpoch, windowId,
                    viewportWidth, viewportHeight, anchorOrigin);
        }
    }

    private final Origin origin;
    private final long sourceUptimeMillis;
    private final double biasX, biasY;

    private RenderSourceReference(Origin origin, long sourceUptimeMillis, double biasX, double biasY) {
        this.origin = origin;
        this.sourceUptimeMillis = sourceUptimeMillis;
        this.biasX = biasX;
        this.biasY = biasY;
    }

    public static RenderSourceReference known(
            Origin origin, long sourceUptimeMillis, double biasX, double biasY) {
        Objects.requireNonNull(origin, "origin");
        if (sourceUptimeMillis < 0 || !Double.isFinite(biasX) || !Double.isFinite(biasY)) {
            throw new IllegalArgumentException("Invalid render source measurement");
        }
        return new RenderSourceReference(origin, sourceUptimeMillis, biasX, biasY);
    }

    public boolean isKnown() { return origin != null; }
    public Origin origin() { return origin; }
    public long sourceUptimeMillis() { return sourceUptimeMillis; }
    public double biasX() { return biasX; }
    public double biasY() { return biasY; }

    public boolean sameOrigin(RenderSourceReference other) {
        return isKnown() && other != null && origin.equals(other.origin);
    }

    public boolean sameBasis(RenderSourceReference other) {
        if (other == null) return false;
        if (!isKnown() || !other.isKnown()) return !isKnown() && !other.isKnown();
        return sameOrigin(other) && Math.abs(biasX - other.biasX) <= BASIS_TOLERANCE
                && Math.abs(biasY - other.biasY) <= BASIS_TOLERANCE;
    }

    public double correctionX(RenderSourceReference current) {
        return sameOrigin(current) ? current.biasX - biasX : 0.0;
    }

    public double correctionY(RenderSourceReference current) {
        return sameOrigin(current) ? current.biasY - biasY : 0.0;
    }
}
