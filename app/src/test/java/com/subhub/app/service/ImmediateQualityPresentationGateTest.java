package com.subhub.app.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ImmediateQualityPresentationGateTest {
    @Test public void sameDisplayedCaptureNeedsNoNewInference() {
        assertTrue(allow(stamp(10, 3, 500, 7), stamp(10, 3, 500, 7),
                stamp(10, 3, 500, 7), true, 100, 250));
    }

    @Test public void inFlightNewCaptureDoesNotInvalidateUnchangedDisplayedSource() {
        assertTrue(allow(stamp(10, 3, 500, 7), stamp(10, 3, 500, 7),
                stamp(11, 3, 500, 7), true, 100, 250));
    }

    @Test public void differentDisplayedCaptureMustUseNormalAlignmentPath() {
        assertFalse(allow(stamp(10, 3, 500, 7), stamp(11, 3, 500, 7),
                stamp(11, 3, 500, 7), true, 100, 250));
        assertFalse(allow(stamp(11, 3, 500, 7), stamp(10, 3, 500, 7),
                stamp(11, 3, 500, 7), true, 100, 250));
    }

    @Test public void motionChangeEvenAtSameCameraRejectsImmediateRefresh() {
        assertFalse(allow(stamp(10, 3, 500, 7), stamp(10, 3, 500, 7),
                stamp(10, 4, 500, 7), true, 100, 250));
        assertFalse(allow(stamp(10, 3, 500, 7), stamp(10, 4, 500, 7),
                stamp(10, 3, 500, 7), true, 100, 250));
    }

    @Test public void changedCameraOrWindowCannotReuseDisplayedGeometry() {
        assertFalse(allow(stamp(10, 3, 500, 7), stamp(10, 3, 500, 7),
                stamp(10, 3, 501, 7), true, 100, 250));
        assertFalse(allow(stamp(10, 3, 500, 7), stamp(10, 3, 500, 7),
                stamp(10, 3, 500, 8), true, 100, 250));
        assertFalse(allow(stamp(10, 3, 500, -1), stamp(10, 3, 500, -1),
                stamp(10, 3, 500, -1), true, 100, 250));
    }

    @Test public void unknownPhaseFutureTimeAndExpiredSourceAreRejected() {
        LateQualityPresentationGate.Stamp s = stamp(10, 3, 500, 7);
        assertFalse(allow(s, s, s, false, 100, 250));
        assertFalse(allow(s, s, s, true, 100, 99));
        assertFalse(allow(s, s, s, true, 0, 250));
        assertFalse(allow(s, s, s, true, 100, 2601));
        assertTrue(allow(s, s, s, true, 100, 2600));
        assertFalse(allow(null, s, s, true, 100, 250));
        assertFalse(allow(s, null, s, true, 100, 250));
    }

    @Test public void everyStructuralFenceAppliesToDisplayAndCurrentState() {
        LateQualityPresentationGate.Stamp s = stamp(10, 3, 500, 7);
        for (int changed = 0; changed < 10; changed++) {
            LateQualityPresentationGate.Stamp other = new LateQualityPresentationGate.Stamp(
                    10, changed == 0 ? 9 : 1, changed == 1 ? 9 : 2,
                    changed == 2 ? "other" : "surface", changed == 3 ? 9 : 4,
                    3, 0, 500, changed == 4 ? 321 : 320, changed == 5 ? 715 : 714,
                    changed == 6 ? 1345 : 1344, changed == 7 ? 2993 : 2992,
                    changed == 8 ? 8 : 7, changed == 9);
            assertFalse(allow(s, s, other, true, 100, 250));
            assertFalse(allow(s, other, s, true, 100, 250));
        }
    }

    @Test public void invalidGeometryAndRegressedSequenceCannotAcquireAuthority() {
        LateQualityPresentationGate.Stamp s = stamp(10, 3, 500, 7);
        assertFalse(allow(s, s, stamp(9, 3, 500, 7), true, 100, 250));
        LateQualityPresentationGate.Stamp empty = new LateQualityPresentationGate.Stamp(
                10, 1, 2, "surface", 4, 3, 0, 500, 0, 714, 1344, 2992, 7, false);
        assertFalse(allow(empty, empty, empty, true, 100, 250));
        assertFalse(allow(s, s, null, true, 100, 250));
        assertFalse(ImmediateQualityPresentationGate.allows(s, s, s, true, 100, 250, 0));
    }

    private static boolean allow(LateQualityPresentationGate.Stamp source,
            LateQualityPresentationGate.Stamp displayed, LateQualityPresentationGate.Stamp current,
            boolean phase, long capturedAt, long now) {
        return ImmediateQualityPresentationGate.allows(
                source, displayed, current, phase, capturedAt, now, 2500);
    }

    private static LateQualityPresentationGate.Stamp stamp(
            long sequence, long generation, long y, int window) {
        return new LateQualityPresentationGate.Stamp(sequence, 1, 2, "surface", 4,
                generation, 0, y, 320, 714, 1344, 2992, window, false);
    }
}
