package com.subhub.app.detection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class VisualIdentityReconcilerTest {
    @Test
    public void offsetQualityCoverageLinksToEstablishedFastIdentity() {
        TrackedObject established = tracked(1, face(new BBox(100, 100, 180, 180)));
        Detection quality = qualityFace(new BBox(75, 80, 240, 230));

        VisualIdentityReconciler.Result result = VisualIdentityReconciler.reconcile(
                Collections.emptyList(), Collections.singletonList(quality),
                Collections.singletonList(established));

        assertEquals(1, result.detections().size());
        assertEquals(established.getId(), result.detections().get(0).getTrackId());
        assertEquals(1, result.qualityLinked());
        assertEquals(1, result.carriedQuality());
        assertEquals(0, result.unlinkedQuality());
    }

    @Test
    public void sharedIdentityFusesPassesAndKeepsRealtimeGeometry() {
        TrackedObject established = tracked(1, face(new BBox(100, 100, 180, 180)));
        Detection realtime = face(new BBox(110, 105, 180, 180));
        Detection quality = qualityFace(new BBox(75, 80, 240, 230));
        quality.setTrackId(established.getId());

        VisualIdentityReconciler.Result result = VisualIdentityReconciler.reconcile(
                Collections.singletonList(realtime), Collections.singletonList(quality),
                Collections.singletonList(established));

        assertEquals(1, result.detections().size());
        assertEquals(realtime.getBox(), result.detections().get(0).getBox());
        assertEquals(established.getId(), result.detections().get(0).getTrackId());
        assertEquals(1, result.fused());
        assertEquals(0, result.carriedQuality());
    }

    @Test
    public void nearbyFacesReceiveIndependentOneToOneIdentities() {
        TrackedObject first = tracked(1, face(new BBox(80, 120, 160, 170)));
        TrackedObject second = tracked(2, face(new BBox(330, 125, 160, 170)));
        List<Detection> realtime = Arrays.asList(
                face(new BBox(88, 124, 160, 170)),
                face(new BBox(338, 128, 160, 170)));
        List<Detection> quality = Arrays.asList(
                qualityFace(new BBox(66, 105, 200, 210)),
                qualityFace(new BBox(316, 108, 200, 210)));

        VisualIdentityReconciler.Result result = VisualIdentityReconciler.reconcile(
                realtime, quality, Arrays.asList(first, second));

        assertEquals(2, result.detections().size());
        assertEquals(2, result.fused());
        assertTrue(result.detections().stream().anyMatch(d -> d.getTrackId() == 1));
        assertTrue(result.detections().stream().anyMatch(d -> d.getTrackId() == 2));
    }

    @Test
    public void nestedDifferentCategoryCoverageRemainsIndependent() {
        Detection face = face(new BBox(100, 100, 220, 220));
        Detection qualityEyes = new Detection("EYES_DERIVED", "eyes", 0.95f,
                new BBox(125, 125, 170, 70), false, true,
                Detection.ObservationSource.QUALITY_VISUAL,
                Detection.GeometryQuality.MODEL, null);

        VisualIdentityReconciler.Result result = VisualIdentityReconciler.reconcile(
                Collections.singletonList(face), Collections.singletonList(qualityEyes),
                Collections.emptyList());

        assertEquals(2, result.detections().size());
        assertEquals(0, result.fused());
        assertEquals(1, result.unlinkedQuality());
    }

    @Test
    public void halfHeightScrollPhaseOffsetStillFusesOneFace() {
        Detection realtime = face(new BBox(100, 100, 180, 180));
        Detection quality = qualityFace(new BBox(100, 190, 180, 180));

        VisualIdentityReconciler.Result result = VisualIdentityReconciler.reconcile(
                Collections.singletonList(realtime), Collections.singletonList(quality),
                Collections.emptyList());

        assertEquals(1, result.detections().size());
        assertEquals(1, result.fused());
        assertEquals(realtime.getBox(), result.detections().get(0).getBox());
    }

    @Test
    public void nonOverlappingStackedFacesRemainIndependent() {
        Detection realtime = face(new BBox(100, 100, 180, 180));
        Detection quality = qualityFace(new BBox(100, 285, 180, 180));

        VisualIdentityReconciler.Result result = VisualIdentityReconciler.reconcile(
                Collections.singletonList(realtime), Collections.singletonList(quality),
                Collections.emptyList());

        assertEquals(2, result.detections().size());
        assertEquals(0, result.fused());
    }

    private static TrackedObject tracked(int id, Detection detection) {
        TrackedObject track = new TrackedObject(id, detection, 1_000_000_000L);
        track.update(detection, detection.getBox(), 0f, 0f, 1_100_000_000L);
        return track;
    }

    private static Detection face(BBox box) {
        return new Detection("FACE_FEMALE", "face", 0.82f, box, false, true);
    }

    private static Detection qualityFace(BBox box) {
        return new Detection("FACE_FEMALE", "face", 0.94f, box, false, true,
                Detection.ObservationSource.QUALITY_VISUAL,
                Detection.GeometryQuality.MODEL, null);
    }
}
