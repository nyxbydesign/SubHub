package com.subhub.app.service;

import java.util.ArrayDeque;

/** Resolves an event-coordinate estimate at a typed time reference, retaining its uncertainty. */
final class CaptureScrollTimeline {
    private static final int MAX_SAMPLES = 192;

    private final ArrayDeque<Motion> motions = new ArrayDeque<>();
    private long lastEffectiveUptimeMillis;

    synchronized void record(
            long effectiveUptimeMillis,
            long receivedUptimeMillis,
            int contentDx,
            int contentDy,
            long motionGeneration) {
        long effective = Math.max(0L, effectiveUptimeMillis);
        long received = Math.max(effective, receivedUptimeMillis);
        boolean outOfOrder = lastEffectiveUptimeMillis > 0L
                && effective < lastEffectiveUptimeMillis;
        motions.addLast(new Motion(effective, received, contentDx, contentDy,
                motionGeneration, outOfOrder));
        lastEffectiveUptimeMillis = Math.max(lastEffectiveUptimeMillis, effective);
        while (motions.size() > MAX_SAMPLES) motions.removeFirst();
    }

    synchronized Phase resolve(
            CaptureTimeReference time,
            long requestedScrollX,
            long requestedScrollY,
            long requestedGeneration) {
        if (time == null) throw new IllegalArgumentException("Capture time provenance required");
        long screenshotUptimeMillis = time.reportedUptimeMillis;
        long scrollX = requestedScrollX;
        long scrollY = requestedScrollY;
        long resolvedGeneration = requestedGeneration;
        long maximumDeliveryDelayMs = 0L;
        boolean resolved = false;
        boolean uncertain = !time.valid;
        for (Motion motion : motions) {
            // Generation says whether the request-time snapshot already incorporated this delta.
            // Effective time orders events against the reference. For Accessibility receipt/
            // completion stamps this is only an event-coordinate estimate, NOT proof of the
            // pixel phase. Keep pixelTimeKnown separate from event-order confidence.
            if (motion.motionGeneration <= requestedGeneration
                    || motion.effectiveUptimeMillis > screenshotUptimeMillis) continue;
            scrollX += motion.contentDx;
            scrollY += motion.contentDy;
            resolvedGeneration = Math.max(resolvedGeneration, motion.motionGeneration);
            maximumDeliveryDelayMs = Math.max(maximumDeliveryDelayMs,
                    motion.receivedUptimeMillis - motion.effectiveUptimeMillis);
            uncertain |= motion.outOfOrder;
            resolved = true;
        }
        return new Phase(scrollX, scrollY, resolvedGeneration,
                screenshotUptimeMillis, resolved, uncertain, maximumDeliveryDelayMs,
                time);
    }

    synchronized void clear() {
        motions.clear();
        lastEffectiveUptimeMillis = 0L;
    }

    private static final class Motion {
        private final long effectiveUptimeMillis;
        private final long receivedUptimeMillis;
        private final int contentDx;
        private final int contentDy;
        private final long motionGeneration;
        private final boolean outOfOrder;

        private Motion(
                long effectiveUptimeMillis,
                long receivedUptimeMillis,
                int contentDx,
                int contentDy,
                long motionGeneration,
                boolean outOfOrder) {
            this.effectiveUptimeMillis = effectiveUptimeMillis;
            this.receivedUptimeMillis = receivedUptimeMillis;
            this.contentDx = contentDx;
            this.contentDy = contentDy;
            this.motionGeneration = motionGeneration;
            this.outOfOrder = outOfOrder;
        }
    }

    static final class Phase {
        final long scrollX;
        final long scrollY;
        final long motionGeneration;
        final long screenshotUptimeMillis;
        final boolean resolvedFromMotion;
        /** Event-order confidence only; false does not establish exact pixel timing. */
        final boolean phaseUncertain;
        final long maximumDeliveryDelayMs;
        final long requestedAtUptimeMillis;
        final CaptureTimeReference timeReference;
        final boolean pixelTimeKnown;

        private Phase(
                long scrollX,
                long scrollY,
                long motionGeneration,
                long screenshotUptimeMillis,
                boolean resolvedFromMotion,
                boolean phaseUncertain,
                long maximumDeliveryDelayMs,
                CaptureTimeReference timeReference) {
            this.scrollX = scrollX;
            this.scrollY = scrollY;
            this.motionGeneration = motionGeneration;
            this.screenshotUptimeMillis = screenshotUptimeMillis;
            this.resolvedFromMotion = resolvedFromMotion;
            this.phaseUncertain = phaseUncertain;
            this.maximumDeliveryDelayMs = Math.max(0L, maximumDeliveryDelayMs);
            this.requestedAtUptimeMillis = Math.max(0L, timeReference.requestUptimeMillis);
            this.timeReference = timeReference;
            this.pixelTimeKnown = timeReference.pixelTimeKnown();
        }
    }
}
