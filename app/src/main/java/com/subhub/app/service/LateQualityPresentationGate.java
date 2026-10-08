package com.subhub.app.service;

import java.util.Objects;

/** Pure structural fence for folding a queued quality snapshot into any later fast publication. */
final class LateQualityPresentationGate {
    private LateQualityPresentationGate() {}

    static Decision decide(Stamp source, Stamp consumer, boolean phaseCertain,
            long capturedAt, long now, long maxAgeMillis) {
        if (source != null && source.currentOnly && (!phaseCertain || capturedAt <= 0
                || now < capturedAt || maxAgeMillis <= 0 || now - capturedAt > maxAgeMillis)) {
            return Decision.STALE;
        }
        return decide(source, consumer);
    }

    static Decision decide(Stamp source, Stamp consumer) {
        if (source == null || consumer == null) return Decision.STALE;
        boolean matches = consumer.captureEpoch == source.captureEpoch
                && consumer.documentEpoch == source.documentEpoch
                && Objects.equals(consumer.surfaceKey, source.surfaceKey)
                && consumer.surfaceToken == source.surfaceToken
                && consumer.sourceWidth == source.sourceWidth
                && consumer.sourceHeight == source.sourceHeight
                && consumer.viewportWidth == source.viewportWidth
                && consumer.viewportHeight == source.viewportHeight
                && consumer.applicationWindowId == source.applicationWindowId
                && consumer.currentOnly == source.currentOnly;
        if (source.currentOnly) {
            matches &= source.applicationWindowId >= 0
                    && consumer.motionGeneration == source.motionGeneration
                    && consumer.cameraX == source.cameraX && consumer.cameraY == source.cameraY;
        }
        if (!matches) return Decision.STALE;
        if (consumer.fastSequence <= source.fastSequence) return Decision.WAIT_FOR_NEXT_FAST;
        return Decision.MATCH;
    }

    enum Decision { MATCH, WAIT_FOR_NEXT_FAST, STALE }

    static final class Stamp {
        final long fastSequence;
        final long captureEpoch;
        final long documentEpoch;
        final String surfaceKey;
        final long surfaceToken;
        final long motionGeneration;
        final long cameraX;
        final long cameraY;
        final int sourceWidth;
        final int sourceHeight;
        final int viewportWidth;
        final int viewportHeight;
        final int applicationWindowId;
        final boolean currentOnly;

        Stamp(
                long fastSequence,
                long captureEpoch,
                long documentEpoch,
                String surfaceKey,
                long surfaceToken,
                long motionGeneration,
                long cameraX,
                long cameraY,
                int sourceWidth,
                int sourceHeight,
                int viewportWidth,
                int viewportHeight) {
            this(fastSequence, captureEpoch, documentEpoch, surfaceKey, surfaceToken,
                    motionGeneration, cameraX, cameraY, sourceWidth, sourceHeight,
                    viewportWidth, viewportHeight, -1, false);
        }

        Stamp(long fastSequence, long captureEpoch, long documentEpoch, String surfaceKey,
              long surfaceToken, long motionGeneration, long cameraX, long cameraY,
              int sourceWidth, int sourceHeight, int viewportWidth, int viewportHeight,
              int applicationWindowId, boolean currentOnly) {
            this.fastSequence = fastSequence;
            this.captureEpoch = captureEpoch;
            this.documentEpoch = documentEpoch;
            this.surfaceKey = surfaceKey == null ? "" : surfaceKey;
            this.surfaceToken = surfaceToken;
            this.motionGeneration = motionGeneration;
            this.cameraX = cameraX;
            this.cameraY = cameraY;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.viewportWidth = viewportWidth;
            this.viewportHeight = viewportHeight;
            this.applicationWindowId = applicationWindowId;
            this.currentOnly = currentOnly;
        }
    }
}
