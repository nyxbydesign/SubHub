package com.subhub.app.overlay;

import com.subhub.app.detection.RenderSourceReference;

/** Screen-pixel renderer transforms; never changes document-camera authority. */
final class RenderCoordinates {
    private RenderCoordinates() { }

    static float offsetX(float documentOffset, float eventOffset,
            RenderSourceReference region, RenderSourceReference current) {
        return current.isKnown() ? documentOffset + (float) region.correctionX(current) : eventOffset;
    }

    static float offsetY(float documentOffset, float eventOffset,
            RenderSourceReference region, RenderSourceReference current) {
        return current.isKnown() ? documentOffset + (float) region.correctionY(current) : eventOffset;
    }

    static boolean canSample(RenderSourceReference region, RenderSourceReference bitmap) {
        return !region.isKnown() && !bitmap.isKnown() || region.sameOrigin(bitmap);
    }

    static float source(float destination, float effectiveOffset, float sourceCamera,
            float predictionOffset, double bitmapMinusRegionBias) {
        return destination - effectiveOffset - sourceCamera - predictionOffset
                + (float) bitmapMinusRegionBias;
    }
}
