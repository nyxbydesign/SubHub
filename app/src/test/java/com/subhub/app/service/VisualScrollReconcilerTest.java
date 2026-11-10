package com.subhub.app.service;

import org.junit.Test;
import static org.junit.Assert.*;

public final class VisualScrollReconcilerTest {
    @Test public void missedMovementIsCorrectedWithoutRepeatingKnownMovement() {
        VisualScrollReconciler camera = new VisualScrollReconciler();
        camera.observe(-1, 100, 0, false);
        assertEquals(60, camera.recordEvent(150, 150, 60), 0);
        VisualScrollReconciler.Measurement measured = camera.observe(100, 200, -100, true);
        assertTrue(measured.accepted);
        assertEquals(100, measured.frameY, 0);
        assertEquals(40, measured.correctionY, 0);
        assertEquals(100, camera.cameraY(), 0);
    }

    @Test public void lateEventAlreadyCoveredByVisualAnchorDoesNotMoveCameraAgain() {
        VisualScrollReconciler camera = measuredCamera();
        assertEquals(0, camera.recordEvent(180, 180, 100), 0);
        assertEquals(100, camera.cameraY(), 0);
        assertEquals(20, camera.recordEvent(220, 220, 20), 0);
        assertEquals(120, camera.cameraY(), 0);
    }

    @Test public void eventsAfterScreenshotRemainWhenResultArrivesLate() {
        VisualScrollReconciler camera = new VisualScrollReconciler();
        camera.observe(-1, 100, 0, false);
        camera.recordEvent(150, 150, 60);
        camera.recordEvent(220, 220, 20);
        VisualScrollReconciler.Measurement measured = camera.observe(100, 200, -100, true);
        assertEquals(100, measured.frameY, 0);
        assertEquals(40, measured.correctionY, 0);
        assertEquals(120, camera.cameraY(), 0);
    }

    @Test public void repeatedVisualFramesDoNotAccumulateEventDisplacementTwice() {
        VisualScrollReconciler camera = measuredCamera();
        camera.recordEvent(190, 190, 100);
        camera.recordEvent(250, 250, 80);
        assertEquals(0, camera.observe(200, 300, -80, true).correctionY, 0);
        assertEquals(180, camera.cameraY(), 0);
        assertEquals(-80, camera.observe(300, 400, 80, true).correctionY, 0);
        assertEquals(0, camera.recordEvent(390, 390, -80), 0);
        assertEquals(100, camera.cameraY(), 0);
    }

    @Test public void rejectedPairFallsBackToEventPositionWithoutInventingVisualMotion() {
        VisualScrollReconciler camera = measuredCamera();
        camera.recordEvent(240, 240, 30);
        assertFalse(camera.observe(200, 300, -999, false).accepted);
        assertEquals(130, camera.cameraY(), 0);
        camera.recordEvent(270, 270, 10);
        assertEquals(190, camera.observe(300, 400, -50, true).frameY, 0);
        assertEquals(190, camera.cameraY(), 0);
    }

    @Test public void duplicateAndUnlinkedFramesCannotApplyAnotherCorrection() {
        VisualScrollReconciler camera = measuredCamera();
        assertFalse(camera.observe(100, 200, -100, true).accepted);
        assertFalse(camera.observe(50, 250, -100, true).accepted);
        assertEquals(100, camera.cameraY(), 0);
    }

    @Test public void resetDropsOldScopeAndSupportsNonzeroOrigins() {
        VisualScrollReconciler camera = measuredCamera();
        camera.reset(500);
        assertFalse(camera.observe(200, 300, -100, true).accepted);
        assertEquals(500, camera.cameraY(), 0);
        assertEquals(450, camera.observe(300, 400, 50, true).frameY, 0);
    }

    @Test public void truncatedEventHistoryCannotBecomeVisualEvidence() {
        VisualScrollReconciler camera = new VisualScrollReconciler();
        camera.observe(-1, 100, 0, false);
        for (int i = 0; i < 200; i++) camera.recordEvent(110 + i, 110 + i, 1);
        assertFalse(camera.observe(100, 400, -200, true).accepted);
        assertEquals(200, camera.cameraY(), 0);
    }

    @Test public void outOfOrderLateEventsCancelOnlyTheCoveredPortion() {
        VisualScrollReconciler camera = measuredCamera();
        assertEquals(15, camera.recordEvent(240, 240, 15), 0);
        assertEquals(0, camera.recordEvent(120, 120, 25), 0);
        assertEquals(10, camera.recordEvent(210, 210, 10), 0);
        assertEquals(125, camera.cameraY(), 0);
    }

    @Test public void intervalStraddlingAnchorIsNotGuessedOrDoubleApplied() {
        VisualScrollReconciler camera = measuredCamera();
        assertEquals(0, camera.recordEvent(150, 250, 80), 0);
        assertTrue(camera.hasUnresolvedEventInterval());
        assertEquals(100, camera.cameraY(), 0);
        assertEquals(20, camera.recordEvent(250, 280, 20), 0);
        assertEquals(120, camera.cameraY(), 0);
        assertTrue(camera.observe(200, 300, -60, true).accepted);
        assertEquals(160, camera.cameraY(), 0);
        assertFalse(camera.hasUnresolvedEventInterval());
    }

    @Test public void alreadyReceivedCrossingIntervalIsWithheldWhenAnchorArrives() {
        VisualScrollReconciler camera = new VisualScrollReconciler();
        camera.observe(-1, 100, 0, false);
        camera.recordEvent(150, 250, 80);
        assertTrue(camera.observe(100, 200, -100, true).accepted);
        assertEquals(100, camera.cameraY(), 0);
        assertTrue(camera.hasUnresolvedEventInterval());
    }

    @Test public void unknownStartIsNotPresentedAsPurelyNewMotion() {
        VisualScrollReconciler camera = measuredCamera();
        assertEquals(0, camera.recordEvent(0, 250, 100), 0);
        assertTrue(camera.hasUnresolvedEventInterval());
    }

    @Test public void firstImageOriginCannotBeRewrittenByLaterIntervalDelivery() {
        VisualScrollReconciler camera = new VisualScrollReconciler();
        camera.observe(-1, 100, 0, false);
        camera.recordEvent(50, 150, 50);
        assertTrue(camera.observe(100, 200, -100, true).accepted);
        assertEquals(100, camera.cameraY(), 0);
    }

    private static VisualScrollReconciler measuredCamera() {
        VisualScrollReconciler camera = new VisualScrollReconciler();
        camera.observe(-1, 100, 0, false);
        assertTrue(camera.observe(100, 200, -100, true).accepted);
        return camera;
    }
}
