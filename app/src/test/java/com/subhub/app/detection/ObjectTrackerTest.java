package com.subhub.app.detection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

public final class ObjectTrackerTest {
    @Test
    public void nearbyDetectionsRetainTrackIdentity() {
        DetectorConfig config = DetectorConfig.builder().trackingSmoothing(0.5f).build();
        ObjectTracker tracker = new ObjectTracker(config);
        Detection first = detection(new BBox(10, 10, 50, 50));
        List<TrackedObject> initial = tracker.update(Collections.singletonList(first), 1_000_000_000L);
        Detection moved = detection(new BBox(20, 10, 50, 50));
        List<TrackedObject> next = tracker.update(Collections.singletonList(moved), 1_050_000_000L);

        assertEquals(initial.get(0).getId(), next.get(0).getId());
        assertEquals(initial.get(0).getId(), moved.getTrackId());
        assertEquals(new BBox(15, 10, 50, 50), next.get(0).getBox());
    }

    @Test
    public void staleTrackExpiresAfterAgeAndMissingFrameThresholds() {
        DetectorConfig config = DetectorConfig.builder()
                .trackMaxAgeSeconds(0.2f)
                .minRemoveFrames(2)
                .build();
        ObjectTracker tracker = new ObjectTracker(config);
        tracker.update(Collections.singletonList(detection(new BBox(10, 10, 50, 50))), 1_000_000_000L);
        tracker.update(Collections.emptyList(), 1_100_000_000L);
        List<TrackedObject> result = tracker.update(Collections.emptyList(), 1_300_000_000L);

        assertTrue(result.isEmpty());
        assertEquals(0, tracker.retainedTrackCount());
    }

    @Test
    public void repeatedExpiryDoesNotGrowRetainedTrackerState() {
        DetectorConfig config = DetectorConfig.builder()
                .trackMaxAgeSeconds(0.01f)
                .minRemoveFrames(1)
                .build();
        ObjectTracker tracker = new ObjectTracker(config);

        for (int cycle = 0; cycle < 100; cycle++) {
            long seenAt = 1_000_000_000L + cycle * 100_000_000L;
            tracker.update(Collections.singletonList(
                    detection(new BBox(cycle, cycle, 50, 50))), seenAt);
            tracker.update(Collections.emptyList(), seenAt + 20_000_000L);
        }

        assertEquals(0, tracker.retainedTrackCount());
    }

    @Test
    public void classificationFlickerRetainsSpatialIdentity() {
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder().build());
        Detection first = detection(new BBox(10, 10, 50, 50));
        int id = tracker.update(Collections.singletonList(first), 1_000_000_000L).get(0).getId();
        Detection relabeled = new Detection("FEMALE_BREAST_COVERED", "breasts_covered",
                0.88f, new BBox(12, 11, 51, 49), true, true);

        List<TrackedObject> result = tracker.update(
                Collections.singletonList(relabeled), 1_100_000_000L);

        assertEquals(id, result.get(0).getId());
        assertEquals(id, relabeled.getTrackId());
    }

    @Test
    public void nestedOverlayDetectionDoesNotCreateRecursiveTrack() {
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder().build());
        Detection original = detection(new BBox(20, 20, 100, 100));
        tracker.update(Collections.singletonList(original), 1_000_000_000L);
        tracker.update(Collections.singletonList(
                detection(new BBox(20, 20, 100, 100))), 1_100_000_000L);
        Detection nested = detection(new BBox(45, 45, 40, 40));

        List<TrackedObject> result = tracker.update(
                java.util.Arrays.asList(detection(new BBox(20, 20, 100, 100)), nested),
                1_200_000_000L);

        assertEquals(1, result.size());
        assertEquals(result.get(0).getId(), nested.getTrackId());
    }

    @Test
    public void scrollMotionMovesTrackerWithoutCreatingAStackedIdentity() {
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder()
                .trackingSmoothing(1f).build());
        Detection original = detection(new BBox(80, 420, 140, 160));
        int id = tracker.update(Collections.singletonList(original), 1_000_000_000L)
                .get(0).getId();

        tracker.offsetActiveTracks(0, -180, 1080, 2400);
        Detection afterScroll = detection(new BBox(80, 240, 140, 160));
        List<TrackedObject> result = tracker.update(
                Collections.singletonList(afterScroll), 1_100_000_000L);

        assertEquals(1, result.size());
        assertEquals(id, result.get(0).getId());
        assertEquals(id, afterScroll.getTrackId());
    }

    @Test
    public void correctedTextGeometrySnapsWithoutVisualSmoothingLag() {
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder()
                .trackingSmoothing(0.1f).build());
        Detection estimate = textDetection(new BBox(60, 240, 500, 42));
        tracker.update(Collections.singletonList(estimate), 1_000_000_000L);
        int id = tracker.update(Collections.singletonList(
                textDetection(new BBox(60, 240, 500, 42))), 1_050_000_000L)
                .get(0).getId();
        Detection precise = textDetection(new BBox(76, 190, 470, 42));

        List<TrackedObject> result = tracker.update(
                Collections.singletonList(precise), 1_150_000_000L);

        assertEquals(id, result.get(0).getId());
        assertEquals(precise.getBox(), result.get(0).getBox());
    }

    @Test
    public void visualDetectionIsVisibleOnItsFirstQualifiedObservation() {
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder()
                .confidenceThreshold(0.30f)
                .build());
        Detection first = detection(0.40f, new BBox(30, 40, 80, 90));

        List<TrackedObject> visible = tracker.update(
                Collections.singletonList(first), 1_000_000_000L);

        assertEquals(1, visible.size());
        assertTrue(visible.get(0).isVisible());
    }

    @Test
    public void oneFrameTextDetectionNeverBecomesVisible() {
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder()
                .confidenceThreshold(0.30f)
                .build());

        assertTrue(tracker.update(Collections.singletonList(
                textDetection(new BBox(30, 40, 80, 90))), 1_000_000_000L).isEmpty());
        assertTrue(tracker.update(Collections.emptyList(), 1_050_000_000L).isEmpty());
        assertEquals(0, tracker.retainedTrackCount());
    }

    @Test
    public void textDetectionAppearsAfterSecondConsistentObservation() {
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder().build());
        Detection first = textDetection(new BBox(30, 40, 80, 24));

        assertTrue(tracker.update(Collections.singletonList(first), 1_000_000_000L).isEmpty());
        Detection repeated = textDetection(new BBox(31, 40, 80, 24));
        List<TrackedObject> visible = tracker.update(
                Collections.singletonList(repeated), 1_050_000_000L);

        assertEquals(1, visible.size());
        assertEquals(first.getTrackId(), repeated.getTrackId());
    }

    @Test
    public void stabilizedAnchoredTextIsVisibleWithoutAThirdObservation() {
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder().build());
        Detection stabilized = textDetection(new BBox(30, 40, 180, 24)).withObservation(
                Detection.ObservationSource.ACCESSIBILITY,
                Detection.GeometryQuality.EXACT,
                "feed:post-42:line-1");

        List<TrackedObject> visible = tracker.update(
                Collections.singletonList(stabilized), 1_000_000_000L);

        assertEquals(1, visible.size());
        assertTrue(visible.get(0).isVisible());
    }

    @Test
    public void velocityIsNormalizedByElapsedTime() {
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder()
                .trackingSmoothing(1f)
                .velocitySmoothing(1f)
                .build());
        tracker.update(Collections.singletonList(
                detection(new BBox(10, 10, 50, 50))), 1_000_000_000L);
        List<TrackedObject> moved = tracker.update(Collections.singletonList(
                detection(new BBox(30, 20, 50, 50))), 1_100_000_000L);

        assertEquals(0.2f, moved.get(0).getVelocityX(), 0.0001f);
        assertEquals(0.1f, moved.get(0).getVelocityY(), 0.0001f);
        assertEquals(new BBox(40, 25, 50, 50),
                moved.get(0).predict(1_150_000_000L, 50f));
        assertFalse(moved.get(0).isConfirmed());
    }

    @Test
    public void accessibilityOwnedMotionIgnoresSmallDetectorWobble() {
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder()
                .trackingSmoothing(1f)
                .motionPrediction(false)
                .velocitySmoothing(0f)
                .maxExtrapolationMs(0f)
                .build());
        BBox original = new BBox(100, 100, 200, 200);
        tracker.update(Collections.singletonList(detection(original)), 1_000_000_000L);

        List<TrackedObject> jittered = tracker.update(Collections.singletonList(
                detection(new BBox(104, 103, 204, 198))), 1_100_000_000L);

        assertEquals(original, jittered.get(0).getBox());
        assertEquals(0f, jittered.get(0).getVelocityX(), 0.0001f);
        assertEquals(0f, jittered.get(0).getVelocityY(), 0.0001f);
    }

    @Test
    public void accessibilityOwnedMotionStillFollowsMeaningfulSubjectMovement() {
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder()
                .trackingSmoothing(1f)
                .motionPrediction(false)
                .velocitySmoothing(0f)
                .maxExtrapolationMs(0f)
                .build());
        tracker.update(Collections.singletonList(
                detection(new BBox(100, 100, 200, 200))), 1_000_000_000L);
        BBox moved = new BBox(135, 100, 200, 200);

        List<TrackedObject> result = tracker.update(
                Collections.singletonList(detection(moved)), 1_100_000_000L);

        assertEquals(moved, result.get(0).getBox());
    }

    @Test
    public void qualityCoverageRefreshCannotTugExistingGeometry() {
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder()
                .trackingSmoothing(1f)
                .motionPrediction(false)
                .velocitySmoothing(0f)
                .maxExtrapolationMs(0f)
                .build());
        BBox original = new BBox(100, 100, 200, 200);
        tracker.update(Collections.singletonList(detection(original)), 1_000_000_000L);
        Detection quality = new Detection("QUALITY", "EXPOSED", 0.95f,
                new BBox(130, 115, 240, 230), true, true,
                Detection.ObservationSource.QUALITY_VISUAL,
                Detection.GeometryQuality.MODEL, null);

        List<TrackedObject> tracks = tracker.update(
                Collections.singletonList(quality), 1_100_000_000L);

        assertEquals(1, tracks.size());
        assertEquals(original, tracks.get(0).getBox());
        assertEquals(0, tracks.get(0).getFramesMissing());
    }

    @Test
    public void qualityIdentityHintCannotSpawnOffsetDuplicate() {
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder()
                .trackingSmoothing(1f)
                .motionPrediction(false)
                .velocitySmoothing(0f)
                .maxExtrapolationMs(0f)
                .build());
        BBox original = new BBox(100, 100, 180, 180);
        int id = tracker.update(Collections.singletonList(
                new Detection("FACE_FEMALE", "face", 0.9f,
                        original, false, true)), 1_000_000_000L).get(0).getId();
        Detection quality = new Detection("FACE_FEMALE", "face", 0.95f,
                new BBox(60, 70, 270, 250), false, true,
                Detection.ObservationSource.QUALITY_VISUAL,
                Detection.GeometryQuality.MODEL, null);
        quality.setTrackId(id);

        List<TrackedObject> tracks = tracker.update(
                Collections.singletonList(quality), 1_100_000_000L);

        assertEquals(1, tracks.size());
        assertEquals(id, tracks.get(0).getId());
        assertEquals(original, tracks.get(0).getBox());
        assertFalse(tracks.get(0).isQualityOnly());
    }

    @Test
    public void confirmedQualityCoverageSupplementsWithoutAgingOrReshapingLiveTracks() {
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder()
                .confidenceThreshold(0.30f)
                .build());
        BBox liveBox = new BBox(100, 100, 180, 180);
        int liveId = tracker.update(Collections.singletonList(
                detection(liveBox)), 1_000_000_000L).get(0).getId();
        Detection linked = new Detection("FACE_FEMALE", "face", 0.95f,
                new BBox(80, 85, 230, 220), false, true,
                Detection.ObservationSource.QUALITY_VISUAL,
                Detection.GeometryQuality.MODEL, null);
        linked.setTrackId(liveId);
        Detection newCoverage = new Detection("FACE_FEMALE", "face", 0.95f,
                new BBox(500, 300, 160, 180), false, true,
                Detection.ObservationSource.QUALITY_VISUAL,
                Detection.GeometryQuality.MODEL, null);

        int added = tracker.supplementConfirmedQualityCoverage(
                java.util.Arrays.asList(linked, newCoverage), 1_100_000_000L);
        List<TrackedObject> tracks = tracker.activeTracks();

        assertEquals(1, added);
        assertEquals(2, tracks.size());
        assertEquals(liveBox, tracks.get(0).getBox());
        assertEquals(0, tracks.get(0).getFramesMissing());
        assertTrue(tracks.get(1).isQualityOnly());
        assertEquals(newCoverage.getTrackId(), tracks.get(1).getId());
    }

    @Test
    public void fastMissesCannotEndQualityCoverageBeforeHardGraceCap() {
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder()
                .trackMaxAgeSeconds(0.20f)
                .minRemoveFrames(1)
                .build());
        Detection quality = new Detection("FACE_FEMALE", "face", 0.95f,
                new BBox(500, 300, 160, 180), false, true,
                Detection.ObservationSource.QUALITY_VISUAL,
                Detection.GeometryQuality.MODEL, null);
        tracker.supplementConfirmedQualityCoverage(
                Collections.singletonList(quality), 1_000_000_000L);

        assertEquals(1, tracker.update(Collections.emptyList(), 1_400_000_000L).size());
        assertEquals(1, tracker.update(Collections.emptyList(), 1_800_000_000L).size());
        assertTrue(tracker.update(Collections.emptyList(), 3_600_000_000L).isEmpty());
    }

    @Test
    public void settledObservationRefreshesQualityOnlyCoverageGracePeriod() {
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder().build());
        Detection first = new Detection("FACE_FEMALE", "face", 0.95f,
                new BBox(500, 300, 160, 180), false, true,
                Detection.ObservationSource.QUALITY_VISUAL,
                Detection.GeometryQuality.MODEL, null);
        tracker.supplementConfirmedQualityCoverage(
                Collections.singletonList(first), 1_000_000_000L);
        Detection refreshed = new Detection("FACE_FEMALE", "face", 0.96f,
                new BBox(492, 295, 170, 190), false, true,
                Detection.ObservationSource.QUALITY_VISUAL,
                Detection.GeometryQuality.MODEL, null);
        refreshed.setTrackId(first.getTrackId());

        assertEquals(0, tracker.supplementConfirmedQualityCoverage(
                Collections.singletonList(refreshed), 3_000_000_000L));
        assertEquals(1, tracker.update(Collections.emptyList(), 5_400_000_000L).size());
        assertTrue(tracker.update(Collections.emptyList(), 5_600_000_000L).isEmpty());
    }

    @Test
    public void fastEvidenceAdoptsQualityOnlyTrackBeforeGraceExpires() {
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder()
                .trackingSmoothing(1f)
                .build());
        Detection quality = qualityDetection(new BBox(100, 100, 160, 180));
        tracker.supplementConfirmedQualityCoverage(
                Collections.singletonList(quality), 1_000_000_000L);

        List<TrackedObject> tracks = tracker.update(Collections.singletonList(
                new Detection("FACE_FEMALE", "face", 0.90f,
                        new BBox(102, 101, 160, 180), false, true)), 1_100_000_000L);

        assertEquals(1, tracks.size());
        assertFalse(tracks.get(0).isQualityOnly());
    }

    @Test
    public void duplicateSupportForConsumedHintDoesNotCreateSecondTrack() {
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder().build());
        Detection original = new Detection("FACE_FEMALE", "face", 0.9f,
                new BBox(100, 100, 180, 180), false, true);
        int id = tracker.update(Collections.singletonList(original), 1_000_000_000L)
                .get(0).getId();
        Detection realtime = new Detection("FACE_FEMALE", "face", 0.9f,
                new BBox(104, 102, 180, 180), false, true);
        realtime.setTrackId(id);
        Detection support = new Detection("FACE_FEMALE", "face", 0.95f,
                new BBox(72, 78, 245, 235), false, true,
                Detection.ObservationSource.QUALITY_VISUAL,
                Detection.GeometryQuality.MODEL, null);
        support.setTrackId(id);

        List<TrackedObject> tracks = tracker.update(
                java.util.Arrays.asList(realtime, support), 1_100_000_000L);

        assertEquals(1, tracks.size());
        assertEquals(id, support.getTrackId());
    }

    @Test
    public void semanticAnchorRetainsIdentityAcrossALargeGeometryCorrection() {
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder()
                .trackingSmoothing(1f)
                .build());
        Detection first = detection(new BBox(20, 700, 400, 50)).withObservation(
                Detection.ObservationSource.ACCESSIBILITY,
                Detection.GeometryQuality.EXACT,
                "feed:post-42:line-1");
        int id = tracker.update(Collections.singletonList(first), 1_000_000_000L)
                .get(0).getId();
        Detection afterScroll = detection(new BBox(20, 100, 400, 50)).withObservation(
                Detection.ObservationSource.ACCESSIBILITY,
                Detection.GeometryQuality.EXACT,
                "feed:post-42:line-1");

        List<TrackedObject> tracks = tracker.update(
                Collections.singletonList(afterScroll), 1_100_000_000L);

        assertEquals(1, tracks.size());
        assertEquals(id, tracks.get(0).getId());
        assertEquals(id, afterScroll.getTrackId());
    }

    private static Detection detection(BBox box) {
        return detection(0.9f, box);
    }

    private static Detection detection(float confidence, BBox box) {
        return new Detection("FEMALE_BREAST_EXPOSED", "breasts", confidence, box, true, true);
    }

    private static Detection textDetection(BBox box) {
        return new Detection("TEXT_SMUT_OCR_EXPLICIT", "text_smut",
                0.9f, box, true, false);
    }

    private static Detection qualityDetection(BBox box) {
        return new Detection("FACE_FEMALE", "face", 0.95f,
                box, false, true, Detection.ObservationSource.QUALITY_VISUAL,
                Detection.GeometryQuality.MODEL, null);
    }
}
