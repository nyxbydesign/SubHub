package com.subhub.app.service;

/** Eligibility for refining the already-displayed fast source without waiting for a new capture. */
final class ImmediateQualityPresentationGate {
    private ImmediateQualityPresentationGate() {}

    static boolean allows(LateQualityPresentationGate.Stamp source,
            LateQualityPresentationGate.Stamp displayed,
            LateQualityPresentationGate.Stamp current,
            boolean phaseCertain, long capturedAt, long now, long maxAgeMillis) {
        if (source == null || displayed == null || current == null || !phaseCertain
                || capturedAt <= 0 || now < capturedAt || maxAgeMillis <= 0
                || now - capturedAt > maxAgeMillis
                || source.fastSequence <= 0 || source.sourceWidth <= 0 || source.sourceHeight <= 0
                || source.viewportWidth <= 0 || source.viewportHeight <= 0
                || source.applicationWindowId < 0
                || source.fastSequence != displayed.fastSequence
                || current.fastSequence < displayed.fastSequence) return false;
        // Existing structural fences are shared, but the same-source WAIT decision is expected:
        // a fast scene is already visible, so this operation does not need another inference.
        if (LateQualityPresentationGate.decide(source, displayed)
                    == LateQualityPresentationGate.Decision.STALE
                || LateQualityPresentationGate.decide(source, current)
                    == LateQualityPresentationGate.Decision.STALE) return false;
        // Unlike ordinary world-cache reuse, immediate refresh never infers a motion correction.
        return source.motionGeneration == displayed.motionGeneration
                && source.motionGeneration == current.motionGeneration
                && source.cameraX == displayed.cameraX && source.cameraY == displayed.cameraY
                && source.cameraX == current.cameraX && source.cameraY == current.cameraY;
    }
}
