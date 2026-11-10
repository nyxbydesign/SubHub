package com.subhub.app.overlay;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.RenderSourceReference;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.ObjectTracker;
import com.subhub.app.detection.TrackedObject;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RenderCoordinatesTest {
    private static RenderSourceReference ref(long origin, double bias) {
        return RenderSourceReference.known(new RenderSourceReference.Origin(1, 2, 3, 1000, 2000, origin),
                100, bias, bias);
    }

    @Test public void freshCarriedUnknownAndChangedOriginsReceiveDistinctOffsets() {
        RenderSourceReference current = ref(1, 100);
        assertEquals(-200, RenderCoordinates.offsetY(-200, -100, ref(1, 100), current), .001);
        assertEquals(-100, RenderCoordinates.offsetY(-200, -100, ref(1, 0), current), .001);
        assertEquals(-200, RenderCoordinates.offsetY(-200, -100, RenderSourceReference.UNKNOWN, current), .001);
        assertEquals(-200, RenderCoordinates.offsetX(-200, -100, ref(2, 0), current), .001);
        assertEquals(-175, RenderCoordinates.offsetY(-200, -175, ref(1, 100), RenderSourceReference.UNKNOWN), .001);
    }

    @Test public void correctionIsScreenPixelsAfterUnequalSourceScale() {
        BBox world = ContentSpaceCoordinates.toWorld(new BBox(10, 100, 20, 30),
                0, 200, 500, 1000, 1000, 2000);
        float offset = RenderCoordinates.offsetY(-200, -100, ref(1, 100), ref(1, 100));
        assertEquals(200, world.getY() * 2f + offset, .001);
        assertEquals(300, world.getY() * 2f
                + RenderCoordinates.offsetY(-200, -100, ref(1, 0), ref(1, 100)), .001);
    }

    @Test public void bitmapSamplingUsesItsOwnBasisAndDeclinesUnknownMismatches() {
        RenderSourceReference region = ref(1, 20), bitmap = ref(1, 50);
        assertTrue(RenderCoordinates.canSample(region, bitmap));
        assertEquals(250, RenderCoordinates.source(400, -100, 280, 0,
                region.correctionY(bitmap)), .001);
        assertFalse(RenderCoordinates.canSample(region, RenderSourceReference.UNKNOWN));
        assertFalse(RenderCoordinates.canSample(RenderSourceReference.UNKNOWN, bitmap));
        assertFalse(RenderCoordinates.canSample(region, ref(2, 50)));
        assertTrue(RenderCoordinates.canSample(RenderSourceReference.UNKNOWN, RenderSourceReference.UNKNOWN));
        assertEquals(220, RenderCoordinates.source(400, -100, 280, 0, 0), .001);
    }

    @Test public void consolidationNeverUnionsDifferentSourceBases() {
        RenderTrackSnapshot first = new RenderTrackSnapshot(1, "face", new BBox(100, 100, 100, 100),
                0, 0, false, ref(1, 0));
        RenderTrackSnapshot second = new RenderTrackSnapshot(2, "face", new BBox(110, 110, 100, 100),
                0, 0, false, ref(1, 100));
        assertEquals(2, VisualRenderRegionConsolidator.consolidate(List.of(first, second)).outputCount());
        RenderTrackSnapshot compatible = new RenderTrackSnapshot(2, "face", second.box(),
                0, 0, false, first.reference());
        List<RenderTrackSnapshot> merged = VisualRenderRegionConsolidator.consolidate(List.of(first, compatible)).regions();
        assertEquals(1, merged.size());
        assertSame(first.reference(), merged.get(0).reference());
    }

    @Test public void detectionSnapshotsKeepTheirOwnReferences() {
        RenderSourceReference source = ref(1, 100);
        Detection detection = new Detection("TEXT", "text_smut", 1, new BBox(10, 20, 40, 50), true, true)
                .withRenderSourceReference(source);
        assertSame(source, RenderTrackSnapshot.fromTextDetection(detection).reference());
        assertSame(source, RenderTrackSnapshot.fromWorldTextDetection(detection, 0, 100, 500, 1000, 1000, 2000).reference());
        assertSame(source, RenderTrackSnapshot.fromWorldCacheDetection(detection, 0, 100, 500, 1000, 1000, 2000).reference());
    }

    @Test public void knownWorldSnapshotUsesRawGeometryWhileUnknownKeepsTrackerSmoothing() {
        for (boolean known : new boolean[]{false, true}) {
            ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder().trackingSmoothing(.5f).build());
            Detection first = new Detection("FACE", "face", 1, new BBox(10, 100, 50, 50), true, true);
            tracker.update(List.of(first), 1_000_000_000L);
            Detection next = new Detection("FACE", "face", 1, new BBox(20, 100, 50, 50), true, true)
                    .withRenderSourceReference(known ? ref(1, 100) : RenderSourceReference.UNKNOWN);
            TrackedObject track = tracker.update(List.of(next), 1_050_000_000L).get(0);
            assertEquals(15, track.getBox().getX());
            RenderTrackSnapshot snapshot = RenderTrackSnapshot.fromWorld(track, 100, 200,
                    500, 1000, 1000, 2000);
            assertEquals(known ? 70 : 65, snapshot.box().getX());
            assertSame(next.getRenderSourceReference(), snapshot.reference());
            assertEquals(15, track.getBox().getX());
        }
    }

    @Test public void forgettingAnIdentityPreventsCrossBasisSmoothing() {
        ContinuousTrackSteering steering = new ContinuousTrackSteering();
        steering.updateTarget(1, new BBox(10, 20, 40, 50), 1000, 2000, 100, false);
        steering.forget(1);
        BBox fresh = new BBox(10, 220, 40, 50);
        steering.updateTarget(1, fresh, 1000, 2000, 110, false);
        assertEquals(fresh, steering.position(1, 1000, 2000, 110));
        assertEquals(1, steering.size());
    }

    @Test public void measuredFreshnessIsReadOnlyAndExpiresOnBothAxes() {
        ViewportMotion motion = new ViewportMotion();
        motion.reset(0, 0, 100);
        assertFalse(motion.hasMeasuredPresentation(100));
        assertTrue(motion.measurePresentation(20, -30, 110, 112, 112, 1000, 2000, 16));
        assertFalse(motion.hasMeasuredPresentation(111));
        assertTrue(motion.hasMeasuredPresentation(160));
        assertFalse(motion.hasMeasuredPresentation(161));
        assertEquals(-30, motion.position(112).y, .001);
        motion.clearMeasuredPresentation(120);
        assertFalse(motion.hasMeasuredPresentation(120));
    }
}
