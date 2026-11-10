package com.subhub.app.detection;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RenderSourceReferenceTest {
    private static RenderSourceReference.Origin origin(long anchor) {
        return new RenderSourceReference.Origin(1, 2, 3, 1344, 2992, anchor);
    }

    @Test public void unknownIsCompatibleOnlyWithUnknownAndNeverCorrects() {
        RenderSourceReference unknown = RenderSourceReference.UNKNOWN;
        RenderSourceReference zero = RenderSourceReference.known(origin(4), 0, 0, 0);
        assertFalse(unknown.isKnown());
        assertNull(unknown.origin());
        assertTrue(unknown.sameBasis(unknown));
        assertFalse(unknown.sameOrigin(unknown));
        assertFalse(unknown.sameBasis(zero));
        assertFalse(zero.sameBasis(unknown));
        assertFalse(unknown.sameBasis(null));
        assertEquals(0, zero.correctionX(unknown), 0);
        assertEquals(0, unknown.correctionY(zero), 0);
    }

    @Test public void correctionUsesSourceRelativeBiasAndValueEqualOrigins() {
        RenderSourceReference source = RenderSourceReference.known(origin(4), 100, 20, -30);
        RenderSourceReference current = RenderSourceReference.known(origin(4), 200, -10, 50);
        assertTrue(source.isKnown());
        assertTrue(source.sameOrigin(current));
        assertFalse(source.sameBasis(current));
        assertEquals(-30, source.correctionX(current), 0);
        assertEquals(80, source.correctionY(current), 0);
        assertEquals(100, source.sourceUptimeMillis());
        assertEquals(20, source.biasX(), 0);
        assertEquals(-30, source.biasY(), 0);
        assertEquals(origin(4).hashCode(), source.origin().hashCode());
        assertEquals(0, source.correctionY(RenderSourceReference.known(origin(5), 200, 0, 500)), 0);
    }

    @Test public void basisIgnoresSourceTimeButRequiresBiasToleranceAndEveryFence() {
        RenderSourceReference source = RenderSourceReference.known(origin(4), 100, 20, -30);
        assertTrue(source.sameBasis(RenderSourceReference.known(origin(4), 200, 20 + .5e-6, -30)));
        assertFalse(source.sameBasis(RenderSourceReference.known(origin(4), 200, 20, -30 + 2e-6)));
        RenderSourceReference.Origin[] changed = {
            new RenderSourceReference.Origin(9, 2, 3, 1344, 2992, 4),
            new RenderSourceReference.Origin(1, 9, 3, 1344, 2992, 4),
            new RenderSourceReference.Origin(1, 2, 9, 1344, 2992, 4),
            new RenderSourceReference.Origin(1, 2, 3, 720, 2992, 4),
            new RenderSourceReference.Origin(1, 2, 3, 1344, 1600, 4), origin(9)
        };
        for (RenderSourceReference.Origin fence : changed) {
            RenderSourceReference other = RenderSourceReference.known(fence, 100, 20, -30);
            assertFalse(source.sameOrigin(other));
            assertFalse(source.sameBasis(other));
            assertEquals(0, source.correctionX(other), 0);
            assertEquals(0, source.correctionY(other), 0);
        }
    }

    @Test public void invalidOriginAndMeasurementsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RenderSourceReference.Origin(-1, 0, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RenderSourceReference.Origin(0, -1, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RenderSourceReference.Origin(0, 0, -1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RenderSourceReference.Origin(0, 0, 0, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RenderSourceReference.Origin(0, 0, 0, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new RenderSourceReference.Origin(0, 0, 0, 1, 1, 0));
        assertThrows(NullPointerException.class, () -> RenderSourceReference.known(null, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> RenderSourceReference.known(origin(4), -1, 0, 0));
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> RenderSourceReference.known(origin(4), 0, bad, 0));
            assertThrows(IllegalArgumentException.class, () -> RenderSourceReference.known(origin(4), 0, 0, bad));
        }
    }

    private static Detection detection(int x) {
        return new Detection("FACE_FEMALE", "face", .9f, new BBox(x, 100, 40, 50),
                true, false, Detection.ObservationSource.VISUAL, Detection.GeometryQuality.MODEL, "anchor");
    }

    @Test public void detectionCopiesPreserveAllFieldsAndOpaqueReference() {
        Detection original = detection(10);
        original.setTrackId(19);
        assertSame(RenderSourceReference.UNKNOWN, original.getRenderSourceReference());
        RenderSourceReference ref = RenderSourceReference.known(origin(4), 100, 10, 20);
        Detection stamped = original.withRenderSourceReference(ref);
        assertSame(RenderSourceReference.UNKNOWN, original.getRenderSourceReference());
        assertSame(ref, stamped.getRenderSourceReference());
        assertEquals(original.getClassName(), stamped.getClassName());
        assertEquals(original.getCategory(), stamped.getCategory());
        assertEquals(original.getConfidence(), stamped.getConfidence(), 0);
        assertSame(original.getBox(), stamped.getBox());
        assertEquals(original.isNsfw(), stamped.isNsfw());
        assertEquals(original.isExposed(), stamped.isExposed());
        assertEquals(original.getSource(), stamped.getSource());
        assertEquals(original.getGeometryQuality(), stamped.getGeometryQuality());
        assertEquals(original.getAnchorKey(), stamped.getAnchorKey());
        assertEquals(19, stamped.getTrackId());
        Detection observed = stamped.withObservation(Detection.ObservationSource.QUALITY_VISUAL,
                Detection.GeometryQuality.EXACT, "new-anchor");
        assertSame(ref, observed.getRenderSourceReference());
        assertEquals(19, observed.getTrackId());
        assertSame(stamped.getBox(), observed.getBox());
        assertEquals(Detection.ObservationSource.QUALITY_VISUAL, observed.getSource());
        assertEquals(Detection.GeometryQuality.EXACT, observed.getGeometryQuality());
        assertEquals("new-anchor", observed.getAnchorKey());
        assertThrows(NullPointerException.class, () -> original.withRenderSourceReference(null));
    }

    @Test public void rawObservationUpdateSnapshotMissAndOffsetKeepCorrectReference() {
        RenderSourceReference first = RenderSourceReference.known(origin(4), 100, 10, 20);
        RenderSourceReference next = RenderSourceReference.known(origin(4), 200, 30, 40);
        TrackedObject track = new TrackedObject(1, detection(10).withRenderSourceReference(first), 900);
        TrackedObject oldSnapshot = track.snapshot();
        Detection fresh = detection(30).withRenderSourceReference(next);
        BBox smoothed = new BBox(20, 100, 40, 50);
        track.update(fresh, smoothed, 1, 2, 1000);
        assertSame(next, track.getRenderSourceReference());
        assertSame(fresh.getBox(), track.getRawBox());
        assertSame(smoothed, track.getBox());
        assertSame(first, oldSnapshot.getRenderSourceReference());
        track.miss(new BBox(25, 100, 40, 50));
        track.offset(5, -10, 1344, 2992);
        assertSame(next, track.getRenderSourceReference());
        assertSame(next, track.snapshot().getRenderSourceReference());
        assertEquals(1, track.getFramesMissing());
        track.update(detection(50), smoothed, 0, 0, 1100);
        assertSame(RenderSourceReference.UNKNOWN, track.getRenderSourceReference());
    }

    @Test public void geometryHandoffTransfersReferenceButPreservesOldIdentity() {
        RenderSourceReference oldRef = RenderSourceReference.known(origin(4), 100, 10, 20);
        RenderSourceReference newRef = RenderSourceReference.known(origin(4), 200, 30, 40);
        TrackedObject old = new TrackedObject(11, detection(10).withRenderSourceReference(oldRef), 900);
        old.miss(null);
        TrackedObject fresh = new TrackedObject(22, detection(80).withRenderSourceReference(newRef), 1000);
        TrackedObject rendered = old.renderSnapshotWithGeometryFrom(fresh);
        assertEquals(11, rendered.getId());
        assertSame(fresh.getRawBox(), rendered.getRawBox());
        assertSame(fresh.getBox(), rendered.getBox());
        assertSame(newRef, rendered.getRenderSourceReference());
        assertEquals(0, rendered.getFramesMissing());
        assertSame(oldRef, old.getRenderSourceReference());
        assertEquals(1, old.getFramesMissing());
        assertSame(newRef, rendered.snapshot().getRenderSourceReference());
    }
}
