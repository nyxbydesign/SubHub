package com.subhub.app.service;

import org.junit.Test;
import static org.junit.Assert.*;

public final class VisualCameraShadowTest {
    private static RowMotionObserver.Scope scope(long doc) {
        return new RowMotionObserver.Scope(1, doc, 7, 1000, 2000);
    }
    private static RowMotionObserver.Sample sample(long prev, long now, double dy, boolean accepted) {
        return new RowMotionObserver.Sample(accepted, prev, now, dy, accepted ? 3 : 0);
    }

    private static VisualCameraShadow.Result measured(VisualCameraShadow shadow,
            RowMotionObserver.Scope scope, RowMotionObserver.Sample sample, int height, double eventY) {
        return shadow.observe(scope, sample, height, eventY,
                CaptureTimeReference.verifiedPixelTime(0, sample.currentTime, sample.currentTime));
    }

    @Test public void scalesPreparedMeasurementWithoutChangingEventInput() {
        VisualCameraShadow shadow = new VisualCameraShadow();
        measured(shadow, scope(1), sample(-1, 100, 0, false), 200, 0);
        shadow.event(scope(1), 1, 150, 0, -60, 0);
        VisualCameraShadow.Result result = measured(shadow, scope(1), sample(100, 200, -10, true), 200, 60);
        assertTrue(result.accepted);
        assertEquals(100, result.frameY, 0);
        assertEquals(40, result.correctionY, 0);
    }

    @Test public void independentProducerDoesNotBorrowAnotherIntervalStart() {
        VisualCameraShadow shadow = new VisualCameraShadow();
        measured(shadow, scope(1), sample(-1, 100, 0, false), 200, 0);
        measured(shadow, scope(1), sample(100, 200, -10, true), 200, 0);
        shadow.event(scope(1), 1, 220, 0, -20, 0);
        shadow.event(scope(1), 2, 240, 0, -20, 20);
        VisualCameraShadow.Result result = measured(shadow, scope(1), sample(200, 230, 0, false), 200, 20);
        assertTrue(result.uncertain);
        assertEquals(100, result.cameraY, 0);
    }

    @Test public void horizontalEventRejectsVerticalOnlyVisualAuthority() {
        VisualCameraShadow shadow = new VisualCameraShadow();
        measured(shadow, scope(1), sample(-1, 100, 0, false), 200, 0);
        shadow.event(scope(1), 1, 150, 20, 0, 0);
        VisualCameraShadow.Result result = measured(shadow, scope(1), sample(100, 200, -10, true), 200, 0);
        assertTrue(result.horizontal);
        assertFalse(result.accepted);
        assertEquals(0, result.correctionY, 0);
    }

    @Test public void oldWorkerCannotRestoreOldDocumentScope() {
        VisualCameraShadow shadow = new VisualCameraShadow();
        shadow.event(scope(2), 1, 300, 0, -10, 500);
        assertFalse(measured(shadow, scope(1), sample(100, 200, -10, true), 200, 0).scopeValid);
        VisualCameraShadow.Result current = measured(shadow, scope(2), sample(-1, 400, 0, false), 200, 510);
        assertTrue(current.scopeValid);
        assertEquals(510, current.cameraY, 0);
    }

    @Test public void dimensionChangeRequiresFreshVisualBaseline() {
        VisualCameraShadow shadow = new VisualCameraShadow();
        measured(shadow, scope(1), sample(-1, 100, 0, false), 200, 0);
        RowMotionObserver.Scope resized = new RowMotionObserver.Scope(1, 1, 7, 2000, 1000);
        assertFalse(measured(shadow, resized, sample(100, 200, -10, true), 200, 50).accepted);
    }

    @Test public void receiptStampsCannotBecomeTrustedVisualAnchors() {
        VisualCameraShadow shadow = new VisualCameraShadow();
        shadow.observe(scope(1), sample(-1, 100, 0, false), 200, 0,
                CaptureTimeReference.accessibility(true, 50, 100, 101));
        VisualCameraShadow.Result result = shadow.observe(scope(1), sample(100, 200, -10, true), 200, 0,
                CaptureTimeReference.accessibility(true, 150, 200, 201));
        assertFalse(result.accepted);
        assertFalse(result.pixelTimeKnown);
        assertTrue(result.uncertain);
        assertEquals(0, result.correctionY, 0);
    }
}
