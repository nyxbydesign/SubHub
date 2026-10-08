package com.subhub.app.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CaptureScrollTimelineTest {
    @Test public void captureUsesLatestMotionAtOrBeforeVerifiedPixelTimestamp() {
        CaptureScrollTimeline timeline = new CaptureScrollTimeline();
        timeline.record(1_020L, 1_025L, 0, 100, 4L);
        timeline.record(1_040L, 1_045L, 0, 140, 5L);
        timeline.record(1_070L, 1_075L, 0, 160, 6L);

        CaptureScrollTimeline.Phase phase = timeline.resolve(
                CaptureTimeReference.verifiedPixelTime(1_000L, 1_055L, 1_055L), 0L, 0L, 3L);

        assertEquals(240L, phase.scrollY);
        assertEquals(5L, phase.motionGeneration);
        assertTrue(phase.resolvedFromMotion);
    }

    @Test public void motionAfterVerifiedPixelCaptureIsNotFoldedIntoDetectorSource() {
        CaptureScrollTimeline timeline = new CaptureScrollTimeline();
        timeline.record(2_020L, 2_025L, 0, 100, 8L);
        timeline.record(2_080L, 2_085L, 0, 400, 9L);

        CaptureScrollTimeline.Phase phase = timeline.resolve(
                CaptureTimeReference.verifiedPixelTime(2_000L, 2_050L, 2_050L), 0L, 0L, 7L);

        assertEquals(100L, phase.scrollY);
        assertEquals(8L, phase.motionGeneration);
    }

    @Test public void requestSnapshotIsBaselineWhenNoInterveningMotionExists() {
        CaptureScrollTimeline timeline = new CaptureScrollTimeline();
        timeline.record(2_900L, 2_905L, 10, 20, 2L);

        CaptureScrollTimeline.Phase phase = timeline.resolve(
                CaptureTimeReference.verifiedPixelTime(3_000L, 3_030L, 3_030L), 30L, 40L, 3L);

        assertEquals(30L, phase.scrollX);
        assertEquals(40L, phase.scrollY);
        assertEquals(3L, phase.motionGeneration);
        assertFalse(phase.resolvedFromMotion);
    }

    @Test public void delayedCallbackStillPlacesCaptureAtEventSourceTime() {
        CaptureScrollTimeline timeline = new CaptureScrollTimeline();
        // The request saw generation 7. Pixels moved at 1,100, verified capture was at 1,110, but
        // Accessibility did not deliver the event until 1,120.
        timeline.record(1_100L, 1_120L, 0, 360, 8L);

        CaptureScrollTimeline.Phase phase = timeline.resolve(
                CaptureTimeReference.verifiedPixelTime(1_105L, 1_110L, 1_110L), 0L, 0L, 7L);

        assertEquals(360L, phase.scrollY);
        assertEquals(8L, phase.motionGeneration);
        assertEquals(20L, phase.maximumDeliveryDelayMs);
        assertTrue(phase.resolvedFromMotion);
        assertFalse(phase.phaseUncertain);
    }

    @Test public void postRequestEventsReplayAsDeltasInsteadOfAbsoluteCallbackState() {
        CaptureScrollTimeline timeline = new CaptureScrollTimeline();
        timeline.record(1_010L, 1_030L, 0, 100, 4L);
        timeline.record(1_020L, 1_040L, 0, 150, 5L);

        CaptureScrollTimeline.Phase phase = timeline.resolve(
                CaptureTimeReference.verifiedPixelTime(1_000L, 1_025L, 1_025L), 0L, 50L, 3L);

        assertEquals(300L, phase.scrollY);
    }

    @Test public void inferenceTimeRefreshRecoversEventDeliveredAfterScreenshotCallback() {
        CaptureScrollTimeline timeline = new CaptureScrollTimeline();

        CaptureScrollTimeline.Phase callbackPhase = timeline.resolve(
                CaptureTimeReference.verifiedPixelTime(1_105L, 1_110L, 1_110L), 0L, 0L, 7L);
        timeline.record(1_100L, 1_120L, 0, 360, 8L);
        CaptureScrollTimeline.Phase inferencePhase = timeline.resolve(
                CaptureTimeReference.verifiedPixelTime(1_105L, 1_110L, 1_110L), 0L, 0L, 7L);

        assertEquals(0L, callbackPhase.scrollY);
        assertFalse(callbackPhase.resolvedFromMotion);
        assertEquals(360L, inferencePhase.scrollY);
        assertTrue(inferencePhase.resolvedFromMotion);
    }

    @Test public void orderedEventsDoNotMakeReceiptTimeAnExactPixelPhase() {
        CaptureScrollTimeline timeline = new CaptureScrollTimeline();
        timeline.record(110, 115, 0, 50, 2);
        CaptureScrollTimeline.Phase phase = timeline.resolve(
                CaptureTimeReference.accessibility(true, 100, 150, 155), 0, 0, 1);
        assertEquals(50, phase.scrollY);
        assertFalse(phase.phaseUncertain); // Event ordering is known; pixel phase is not.
        assertFalse(phase.pixelTimeKnown);
        assertEquals(CaptureTimeReference.Kind.WINDOW_CLIENT_RECEIPT, phase.timeReference.kind);
    }
}
