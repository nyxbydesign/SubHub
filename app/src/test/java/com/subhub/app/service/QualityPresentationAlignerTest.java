package com.subhub.app.service;

import static org.junit.Assert.assertEquals;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.TrackedObject;
import com.subhub.app.detection.RenderSourceReference;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.List;

public final class QualityPresentationAlignerTest {
    private static final RenderSourceReference.Origin ORIGIN =
            new RenderSourceReference.Origin(1, 2, 3, 1000, 2000, 4);

    @Test public void translatedQualityTakesCommonRawTargetBasis() throws Exception {
        RenderSourceReference old = RenderSourceReference.known(ORIGIN, 10, 0, 10);
        RenderSourceReference fresh = RenderSourceReference.known(ORIGIN, 20, 0, 30);
        List<Detection> quality = List.of(detection(100, 100, 80, 80).withRenderSourceReference(old),
                detection(300, 100, 80, 80).withRenderSourceReference(old));
        List<TrackedObject> live = List.of(track(1, detection(100, 120, 80, 80)
                .withRenderSourceReference(fresh)), track(2, detection(300, 120, 80, 80)
                .withRenderSourceReference(fresh)));
        QualityPresentationAligner.Result result = QualityPresentationAligner.align(quality, live, 1000, 2000);
        assertEquals(20, result.dy());
        for (Detection value : result.detections()) assertSame(fresh, value.getRenderSourceReference());
        assertSame(fresh, QualityPresentationAligner.addSafetyCoverage(result.detections(), 1000, 2000)
                .get(0).getRenderSourceReference());
    }

    @Test public void mixedTargetBasesDoNotRelabelQuality() throws Exception {
        RenderSourceReference old = RenderSourceReference.known(ORIGIN, 10, 0, 10);
        RenderSourceReference fresh = RenderSourceReference.known(ORIGIN, 20, 0, 30);
        List<Detection> quality = List.of(detection(100, 100, 80, 80).withRenderSourceReference(old),
                detection(300, 100, 80, 80).withRenderSourceReference(old));
        QualityPresentationAligner.Result result = QualityPresentationAligner.align(quality,
                List.of(track(1, detection(100, 120, 80, 80).withRenderSourceReference(fresh)),
                        track(2, detection(300, 120, 80, 80))), 1000, 2000);
        assertEquals(0, result.matched());
        assertEquals(0, result.dy());
        assertSame(old, result.detections().get(0).getRenderSourceReference());
    }

    @Test public void zeroShiftRetainsOffscreenGeometry() throws Exception {
        QualityPresentationAligner.Result result = QualityPresentationAligner.align(
                List.of(detection(100, 100, 80, 80), detection(300, 100, 80, 80),
                        detection(-300, 100, 80, 80)),
                List.of(track(1, 100, 100, 80, 80), track(2, 300, 100, 80, 80)), 1000, 2000);
        assertEquals(-300, result.detections().get(2).getBox().getX());
    }

    private static TrackedObject track(int id, Detection detection) throws Exception {
        Constructor<TrackedObject> constructor = TrackedObject.class.getDeclaredConstructor(
                int.class, Detection.class, long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(id, detection, 0L);
    }

    @Test public void twoLandmarksTranslateTheWholeQualityPlane() throws Exception {
        List<Detection> quality = List.of(
                detection(100, 100, 80, 80),
                detection(300, 100, 80, 80),
                detection(500, 100, 60, 60));
        List<TrackedObject> live = List.of(
                track(1, 112, 92, 80, 80),
                track(2, 312, 92, 80, 80));

        QualityPresentationAligner.Result result = QualityPresentationAligner.align(
                quality, live, 1_000, 2_000);

        assertEquals(2, result.matched());
        assertEquals(12, result.dx());
        assertEquals(-8, result.dy());
        assertEquals(new BBox(512, 92, 60, 60),
                result.detections().get(2).getBox());
    }

    @Test public void oneAmbiguousLandmarkCannotMoveThePlane() throws Exception {
        QualityPresentationAligner.Result result = QualityPresentationAligner.align(
                List.of(detection(100, 100, 80, 80)),
                List.of(track(1, 130, 100, 80, 80)), 1_000, 2_000);

        assertEquals(1, result.matched());
        assertEquals(0, result.dx());
        assertEquals(new BBox(100, 100, 80, 80),
                result.detections().get(0).getBox());
    }

    @Test public void safetyCoverageIsSmallAndClamped() {
        List<Detection> expanded = QualityPresentationAligner.addSafetyCoverage(
                List.of(detection(1, 2, 100, 50)), 1_000, 2_000);

        assertEquals(new BBox(0, 0, 105, 55), expanded.get(0).getBox());
    }

    @Test public void translatedOffscreenQualityDoesNotSnapOntoScreenEdge() throws Exception {
        QualityPresentationAligner.Result result = QualityPresentationAligner.align(
                List.of(detection(100, 100, 80, 80), detection(300, 100, 80, 80),
                        detection(-300, 100, 80, 80)),
                List.of(track(1, 112, 100, 80, 80), track(2, 312, 100, 80, 80)), 1000, 2000);
        assertEquals(-288, result.detections().get(2).getBox().getX());
        assertEquals(2, QualityPresentationAligner.addSafetyCoverage(
                result.detections(), 1000, 2000).size());
    }

    @Test public void partialOverlapCannotDiscardAdditionalQualityCoverage() throws Exception {
        Detection extra = detection(100, 100, 150, 150);
        List<Detection> result = QualityPresentationAligner.uncovered(List.of(extra),
                List.of(track(1, 100, 100, 80, 80)));
        assertEquals(1, result.size());
        assertSame(extra, result.get(0));
    }

    @Test public void fullyCoveredSameBasisQualityDoesNotDuplicateLiveRegion() throws Exception {
        assertEquals(0, QualityPresentationAligner.uncovered(
                List.of(detection(110, 110, 50, 50)),
                List.of(track(1, 100, 100, 80, 80))).size());
    }

    @Test public void unrelatedRenderBasisCannotClaimQualityIsCovered() throws Exception {
        Detection extra = detection(100, 100, 80, 80).withRenderSourceReference(
                RenderSourceReference.known(ORIGIN, 10, 0, 10));
        assertEquals(1, QualityPresentationAligner.uncovered(List.of(extra),
                List.of(track(1, 100, 100, 80, 80))).size());
    }

    private static Detection detection(int x, int y, int width, int height) {
        return new Detection("FACE_FEMALE", "face", .9f,
                new BBox(x, y, width, height), true, true,
                Detection.ObservationSource.QUALITY_VISUAL,
                Detection.GeometryQuality.MODEL, null);
    }

    private static TrackedObject track(
            int id, int x, int y, int width, int height) throws Exception {
        Constructor<TrackedObject> constructor = TrackedObject.class.getDeclaredConstructor(
                int.class, Detection.class, long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(id, detection(x, y, width, height), 0L);
    }
}
