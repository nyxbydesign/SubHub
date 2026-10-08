package com.subhub.app.service;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public final class CurrentOnlyQualityPresentationGateTest {
    private LateQualityPresentationGate.Stamp stamp(long sequence, int window, long motion,
                                                    long cameraX, long cameraY) {
        return new LateQualityPresentationGate.Stamp(sequence, 1, 2, "", 0, motion,
                cameraX, cameraY, 1344, 2992, 1344, 2992, window, true);
    }

    @Test public void unchangedCurrentViewportCanJoinAnyLaterReadyTick() {
        assertEquals(LateQualityPresentationGate.Decision.MATCH,
                LateQualityPresentationGate.decide(stamp(10, 3, 4, 0, 90), stamp(20, 3, 4, 0, 90)));
    }

    @Test public void sameTickWaitsButNeverIgnoresWindowChange() {
        assertEquals(LateQualityPresentationGate.Decision.WAIT_FOR_NEXT_FAST,
                LateQualityPresentationGate.decide(stamp(10, 3, 4, 0, 90), stamp(10, 3, 4, 0, 90)));
        assertEquals(LateQualityPresentationGate.Decision.STALE,
                LateQualityPresentationGate.decide(stamp(10, 3, 4, 0, 90), stamp(10, 9, 4, 0, 90)));
    }

    @Test public void currentOnlyCannotReprojectAcrossMotionOrEitherCameraAxis() {
        LateQualityPresentationGate.Stamp source = stamp(10, 3, 4, 0, 90);
        for (LateQualityPresentationGate.Stamp changed : new LateQualityPresentationGate.Stamp[]{
                stamp(11, 3, 5, 0, 90), stamp(11, 3, 4, 1, 90), stamp(11, 3, 4, 0, 91)}) {
            assertEquals(LateQualityPresentationGate.Decision.STALE,
                    LateQualityPresentationGate.decide(source, changed));
        }
    }

    @Test public void unknownWindowIsNotAValidCurrentOnlyIdentity() {
        assertEquals(LateQualityPresentationGate.Decision.STALE,
                LateQualityPresentationGate.decide(stamp(10, -1, 4, 0, 90), stamp(11, -1, 4, 0, 90)));
    }

    @Test public void currentOnlyCannotBeConsumedAsCacheable() {
        LateQualityPresentationGate.Stamp consumer = new LateQualityPresentationGate.Stamp(
                11, 1, 2, "", 0, 4, 0, 90, 1344, 2992, 1344, 2992, 3, false);
        assertEquals(LateQualityPresentationGate.Decision.STALE,
                LateQualityPresentationGate.decide(stamp(10, 3, 4, 0, 90), consumer));
    }
}
