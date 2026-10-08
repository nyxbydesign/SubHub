package com.subhub.app.service;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class LateQualityPresentationGateTest {
    @Test public void nextFastTickConsumesMatchingQuality() {
        assertEquals(LateQualityPresentationGate.Decision.MATCH,
                LateQualityPresentationGate.decide(stamp(10, 3, 500), stamp(11, 3, 500)));
    }

    @Test public void qualityMayWaitAnyNumberOfFastTicks() {
        assertEquals(LateQualityPresentationGate.Decision.MATCH,
                LateQualityPresentationGate.decide(stamp(10, 3, 500), stamp(12, 3, 500)));
        assertEquals(LateQualityPresentationGate.Decision.MATCH,
                LateQualityPresentationGate.decide(stamp(10, 3, 500), stamp(10_000, 3, 500)));
    }

    @Test public void sourceFastTickNeverConsumesItsOwnLateSnapshot() {
        assertEquals(LateQualityPresentationGate.Decision.WAIT_FOR_NEXT_FAST,
                LateQualityPresentationGate.decide(stamp(10, 3, 500), stamp(10, 3, 500)));
    }

    @Test public void cameraMotionIsReprojectableButSurfaceChangeIsStale() {
        assertEquals(LateQualityPresentationGate.Decision.MATCH,
                LateQualityPresentationGate.decide(stamp(10, 3, 500), stamp(11, 4, 500)));
        assertEquals(LateQualityPresentationGate.Decision.MATCH,
                LateQualityPresentationGate.decide(stamp(10, 3, 500), stamp(11, 3, 501)));
        LateQualityPresentationGate.Stamp otherSurface = new LateQualityPresentationGate.Stamp(
                11, 1, 2, "other", 4, 3,
                0, 501, 320, 714, 1_344, 2_992);
        assertEquals(LateQualityPresentationGate.Decision.STALE,
                LateQualityPresentationGate.decide(stamp(10, 3, 500), otherSurface));
    }

    private static LateQualityPresentationGate.Stamp stamp(
            long sequence, long generation, long cameraY) {
        return new LateQualityPresentationGate.Stamp(
                sequence, 1, 2, "surface", 4, generation,
                0, cameraY, 320, 714, 1_344, 2_992);
    }
}
