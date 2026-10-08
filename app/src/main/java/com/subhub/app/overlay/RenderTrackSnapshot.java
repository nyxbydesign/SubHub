package com.subhub.app.overlay;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.TrackedObject;
import com.subhub.app.detection.RenderSourceReference;

/** Immutable renderer input. Detection workers can keep mutating tracks without tearing a frame. */
final class RenderTrackSnapshot {
    private final int id;
    private final String category;
    private final BBox box;
    private final float velocityXPerMs;
    private final float velocityYPerMs;
    private final boolean cached;
    private final RenderSourceReference reference;
    private final boolean consolidated;
    private final BBox associationBox;
    private final boolean paddingApplied;
    private final int sourceId;

    static RenderTrackSnapshot from(TrackedObject track) {
        return new RenderTrackSnapshot(
                track.getId(),
                track.getCategory(),
                track.getBox(),
                track.getVelocityX(),
                track.getVelocityY(), false, track.getRenderSourceReference());
    }

    static RenderTrackSnapshot fromWorld(
            TrackedObject track,
            long cameraX,
            long cameraY,
            int sourceWidth,
            int sourceHeight,
            int viewportWidth,
            int viewportHeight) {
        return new RenderTrackSnapshot(
                track.getId(),
                track.getCategory(),
                ContentSpaceCoordinates.toWorld(track.getRenderSourceReference().isKnown()
                                ? track.getRawBox() : track.getBox(), cameraX, cameraY,
                        sourceWidth, sourceHeight, viewportWidth, viewportHeight),
                track.getVelocityX(),
                track.getVelocityY(), false, track.getRenderSourceReference());
    }

    static RenderTrackSnapshot fromTextDetection(Detection detection) {
        BBox box = detection.getBox();
        return new RenderTrackSnapshot(
                stableTextId(detection, box), detection.getCategory(), box, 0f, 0f, false,
                detection.getRenderSourceReference());
    }

    static RenderTrackSnapshot fromWorldTextDetection(
            Detection detection,
            long cameraX,
            long cameraY,
            int sourceWidth,
            int sourceHeight,
            int viewportWidth,
            int viewportHeight) {
        BBox world = ContentSpaceCoordinates.toWorld(
                detection.getBox(), cameraX, cameraY,
                sourceWidth, sourceHeight, viewportWidth, viewportHeight);
        return new RenderTrackSnapshot(
                stableTextId(detection, world),
                detection.getCategory(),
                world,
                0f, 0f, false, detection.getRenderSourceReference());
    }

    static RenderTrackSnapshot fromWorldCacheDetection(
            Detection detection,
            long cameraX,
            long cameraY,
            int sourceWidth,
            int sourceHeight,
            int viewportWidth,
            int viewportHeight) {
        BBox world = ContentSpaceCoordinates.toWorld(
                detection.getBox(), cameraX, cameraY,
                sourceWidth, sourceHeight, viewportWidth, viewportHeight);
        return new RenderTrackSnapshot(
                stableCacheId(detection, world), detection.getCategory(), world,
                0f, 0f, true, detection.getRenderSourceReference());
    }

    /** Keep render-memory identities disjoint from positive tracker and text identities. */
    static int stableCacheId(Detection detection, BBox identityBox) {
        int positive = stableTextId(detection, identityBox);
        return positive == Integer.MIN_VALUE ? Integer.MIN_VALUE + 1 : -positive;
    }

    private static int stableTextId(Detection detection, BBox identityBox) {
        String anchor = detection.getAnchorKey();
        String identity = anchor == null || anchor.isEmpty()
                ? detection.getClassName() + '|' + identityBox.getCenterX() / 32 + '|'
                        + identityBox.getCenterY() / 24 + '|' + identityBox.getWidth() / 32
                : anchor;
        return -1 - (identity.hashCode() & 0x3fffffff);
    }

    RenderTrackSnapshot(
            int id,
            String category,
            BBox box,
            float velocityXPerMs,
            float velocityYPerMs,
            boolean cached) {
        this(id, category, box, velocityXPerMs, velocityYPerMs, cached, RenderSourceReference.UNKNOWN);
    }

    RenderTrackSnapshot(int id, String category, BBox box, float velocityXPerMs,
            float velocityYPerMs, boolean cached, RenderSourceReference reference) {
        this(id, category, box, velocityXPerMs, velocityYPerMs, cached, reference, false);
    }

    RenderTrackSnapshot(int id, String category, BBox box, float velocityXPerMs,
            float velocityYPerMs, boolean cached, RenderSourceReference reference, boolean consolidated) {
        this(id, category, box, velocityXPerMs, velocityYPerMs, cached, reference,
                consolidated, box, false, id);
    }

    private RenderTrackSnapshot(int id, String category, BBox box, float velocityXPerMs,
            float velocityYPerMs, boolean cached, RenderSourceReference reference,
            boolean consolidated, BBox associationBox, boolean paddingApplied, int sourceId) {
        this.id = id;
        this.category = category;
        this.box = box;
        this.velocityXPerMs = velocityXPerMs;
        this.velocityYPerMs = velocityYPerMs;
        this.cached = cached;
        this.reference = java.util.Objects.requireNonNull(reference);
        this.consolidated = consolidated;
        this.associationBox = associationBox;
        this.paddingApplied = paddingApplied;
        this.sourceId = sourceId;
    }

    int id() { return id; }
    String category() { return category; }
    BBox box() { return box; }
    float velocityXPerMs() { return velocityXPerMs; }
    float velocityYPerMs() { return velocityYPerMs; }
    boolean isCached() { return cached; }
    RenderSourceReference reference() { return reference; }
    BBox associationBox() { return associationBox; }
    boolean paddingApplied() { return paddingApplied; }
    int sourceId() { return sourceId; }

    RenderTrackSnapshot withPadding(float padding) {
        if (paddingApplied) return this;
        float safe = Float.isFinite(padding) ? Math.max(0f, Math.min(1f, padding)) : 0f;
        int dx = Math.round(box.getWidth() * safe);
        int dy = Math.round(box.getHeight() * safe);
        BBox footprint = new BBox(box.getX() - dx, box.getY() - dy,
                box.getWidth() + 2 * dx, box.getHeight() + 2 * dy);
        return new RenderTrackSnapshot(id, category, footprint, velocityXPerMs,
                velocityYPerMs, cached, reference, consolidated, associationBox, true, sourceId);
    }

    RenderTrackSnapshot withRenderBox(BBox rendered, int renderId) {
        return new RenderTrackSnapshot(renderId, category, rendered, velocityXPerMs,
                velocityYPerMs, cached, reference, consolidated, associationBox, paddingApplied, sourceId);
    }

    RenderTrackSnapshot withGroupBox(BBox rendered) {
        return new RenderTrackSnapshot(id, category, rendered, velocityXPerMs,
                velocityYPerMs, cached, reference, true, associationBox, paddingApplied, sourceId);
    }

    /** A group uses its current footprint, never a corridor to historical smoothed geometry. */
    BBox preserveGroupCoverage(BBox steered) {
        return consolidated ? box : steered;
    }

    BBox predict(float ageMs, float maxExtrapolationMs) {
        // Accessibility/OCR rectangles already follow content through the viewport transform.
        // Extrapolating classifier geometry turns tiny line-bound changes into visible drift.
        if ("text_smut".equals(category)) return box;
        float predictionMs = Math.max(0f, Math.min(ageMs, maxExtrapolationMs));
        return new BBox(
                Math.max(0, Math.round(box.getX() + velocityXPerMs * predictionMs)),
                Math.max(0, Math.round(box.getY() + velocityYPerMs * predictionMs)),
                box.getWidth(),
                box.getHeight());
    }

    boolean isMoving() {
        return !"text_smut".equals(category)
                && (Math.abs(velocityXPerMs) >= 0.005f
                || Math.abs(velocityYPerMs) >= 0.005f);
    }
}
