package com.subhub.app.detection;

import java.util.Objects;

/** One decoded detector result in original-frame coordinates. */
public final class Detection {
    public enum ObservationSource {
        VISUAL,
        QUALITY_VISUAL,
        ACCESSIBILITY,
        OCR,
        EXACT_GEOMETRY
    }

    public enum GeometryQuality {
        MODEL,
        ESTIMATED,
        EXACT
    }

    private final String className;
    private final String category;
    private final float confidence;
    private final BBox box;
    private final boolean nsfw;
    private final boolean exposed;
    private final ObservationSource source;
    private final GeometryQuality geometryQuality;
    private final String anchorKey;
    private final RenderSourceReference renderSourceReference;
    private int trackId = -1;

    public Detection(
            String className,
            String category,
            float confidence,
            BBox box,
            boolean nsfw,
            boolean exposed) {
        this(className, category, confidence, box, nsfw, exposed,
                inferSource(className), inferGeometryQuality(className), null);
    }

    public Detection(
            String className,
            String category,
            float confidence,
            BBox box,
            boolean nsfw,
            boolean exposed,
            ObservationSource source,
            GeometryQuality geometryQuality,
            String anchorKey) {
        this(className, category, confidence, box, nsfw, exposed, source,
                geometryQuality, anchorKey, RenderSourceReference.UNKNOWN);
    }

    private Detection(String className, String category, float confidence, BBox box,
            boolean nsfw, boolean exposed, ObservationSource source,
            GeometryQuality geometryQuality, String anchorKey,
            RenderSourceReference renderSourceReference) {
        this.className = Objects.requireNonNull(className);
        this.category = Objects.requireNonNull(category);
        this.confidence = confidence;
        this.box = Objects.requireNonNull(box);
        this.nsfw = nsfw;
        this.exposed = exposed;
        this.source = Objects.requireNonNull(source);
        this.geometryQuality = Objects.requireNonNull(geometryQuality);
        this.anchorKey = anchorKey;
        this.renderSourceReference = Objects.requireNonNull(renderSourceReference);
    }

    public String getClassName() { return className; }
    public String getCategory() { return category; }
    public float getConfidence() { return confidence; }
    public BBox getBox() { return box; }
    public boolean isNsfw() { return nsfw; }
    public boolean isExposed() { return exposed; }
    public ObservationSource getSource() { return source; }
    public GeometryQuality getGeometryQuality() { return geometryQuality; }
    public String getAnchorKey() { return anchorKey; }
    public RenderSourceReference getRenderSourceReference() { return renderSourceReference; }
    public int getTrackId() { return trackId; }
    public void setTrackId(int trackId) { this.trackId = trackId; }

    public Detection withObservation(
            ObservationSource valueSource,
            GeometryQuality valueGeometryQuality,
            String valueAnchorKey) {
        Detection copy = new Detection(className, category, confidence, box, nsfw, exposed,
                valueSource, valueGeometryQuality, valueAnchorKey, renderSourceReference);
        copy.trackId = trackId;
        return copy;
    }

    public Detection withRenderSourceReference(RenderSourceReference reference) {
        Detection copy = new Detection(className, category, confidence, box, nsfw, exposed,
                source, geometryQuality, anchorKey, reference);
        copy.trackId = trackId;
        return copy;
    }

    private static ObservationSource inferSource(String className) {
        if (className != null && className.startsWith("TEXT_SMUT_OCR_")) {
            return ObservationSource.OCR;
        }
        if (className != null && className.startsWith("TEXT_SMUT_ACCESSIBILITY_")) {
            return ObservationSource.ACCESSIBILITY;
        }
        return ObservationSource.VISUAL;
    }

    private static GeometryQuality inferGeometryQuality(String className) {
        return className != null && className.startsWith("TEXT_SMUT_OCR_")
                ? GeometryQuality.EXACT : GeometryQuality.MODEL;
    }
}
