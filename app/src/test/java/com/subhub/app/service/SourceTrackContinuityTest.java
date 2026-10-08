package com.subhub.app.service;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.ObjectTracker;
import com.subhub.app.detection.TrackedObject;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SourceTrackContinuityTest {
    private static final int W = 144, H = 320;
    private final SpatialRegionCache cache = new SpatialRegionCache();
    private final SourceTrackContinuity continuity = new SourceTrackContinuity();
    private final int[] image = texture(1);
    private final ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder()
            .motionPrediction(false).trackingSmoothing(.5f).build());

    @Test public void missedImageMotionKeepsIdentityAndDoesNotAddSmoothingLag() {
        SpatialRegionCache.Frame first = frame(cache, image, 1, 1, 0, 0, 1);
        SpatialRegionCache.Frame next = frame(cache, shift(image, -30), 2, 1, 0, 5, 1);
        assertNotNull(next.result.pose);
        List<TrackedObject> before = tracker.update(List.of(face(150)), 1_000_000_000L);
        int id = before.get(0).getId();
        continuity.record(first, 0, 0);
        Detection detection = face(120);
        Map<Integer, Integer> correction = proposal(next, 5, 0, 5, -5, before, List.of(detection));
        assertEquals(Integer.valueOf(-25), correction.get(id));
        tracker.offsetActiveTracks(0, -5, W, H, correction);
        List<TrackedObject> after = tracker.update(List.of(detection), 1_333_000_000L);
        continuity.record(next, 0, 5);
        assertEquals(1, after.size());
        assertEquals(id, after.get(0).getId());
        assertEquals(120, after.get(0).getRawBox().getY());
        assertEquals(120, after.get(0).getBox().getY());

        SpatialRegionCache.Frame reverse = frame(cache, image, 3, 1, 0, 7, 1);
        Detection returned = face(150);
        correction = proposal(reverse, 7, 5, 7, -2, after, List.of(returned));
        assertEquals(Integer.valueOf(32), correction.get(id));
        tracker.offsetActiveTracks(0, -2, W, H, correction);
        assertEquals(id, tracker.update(List.of(returned), 1_666_000_000L).get(0).getId());
        assertEquals(150, tracker.activeTracks().get(0).getBox().getY());
    }

    @Test public void correctlyReportedEventMotionIsNotCountedTwice() {
        SpatialRegionCache.Frame first = frame(cache, image, 1, 1, 0, 0, 1);
        SpatialRegionCache.Frame next = frame(cache, shift(image, -30), 2, 1, 0, 30, 1);
        assertNotNull(next.result.pose);
        List<TrackedObject> before = tracker.update(List.of(face(150)));
        continuity.record(first, 0, 0);
        assertTrue(proposal(next, 30, 0, 30, -30, before, List.of(face(120))).isEmpty());
    }

    @Test public void nonUnitySourceScaleAndRefreshedSourceCameraAreRespected() {
        SpatialRegionCache.Frame first = frame(cache, image, 1, 1, 0, 0, 4);
        SpatialRegionCache.Frame next = frame(cache, shift(image, -30), 2, 1, 0, 0, 4);
        assertNotNull(next.result.pose);
        Detection initial = box("FACE_FEMALE", "face", 600, 120, 144);
        Detection target = box("FACE_FEMALE", "face", 480, 120, 144);
        List<TrackedObject> before = tracker.update(List.of(initial));
        // The frame's original eventY is deliberately not the refreshed source camera.
        continuity.record(first, 0, 10);
        Map<Integer, Integer> correction = continuity.corrections(next, 0, 15, 10, 15,
                0, -20, W * 4, H * 4, before, List.of(target), .5f);
        assertEquals(Integer.valueOf(-100), correction.get(before.get(0).getId()));
    }

    @Test public void unknownUpdatesBreakContinuityAndProposalsDoNotCommit() {
        SpatialRegionCache.Frame first = frame(cache, image, 1, 1, 0, 0, 1);
        SpatialRegionCache.Frame next = frame(cache, shift(image, -30), 2, 1, 0, 5, 1);
        List<TrackedObject> before = tracker.update(List.of(face(150)));
        continuity.record(first, 0, 0);
        assertFalse(proposal(next, 5, 0, 5, -5, before, List.of(face(120))).isEmpty());
        assertFalse(proposal(next, 5, 0, 5, -5, before, List.of(face(120))).isEmpty());
        continuity.clear(); // A tracker mutation that does not reach update must not retain its basis.
        assertTrue(proposal(next, 5, 0, 5, -5, before, List.of(face(110))).isEmpty());
        continuity.record(first, 0, 0);
        continuity.record(frame(cache, texture(2), 3, 1, 0, 5, 1), 0, 5);
        SpatialRegionCache.Frame returned = frame(cache, shift(image, -30), 4, 1, 0, 5, 1);
        assertNotNull(returned.result.pose);
        assertTrue(proposal(returned, 5, 0, 5, -5, before, List.of(face(110))).isEmpty());
    }

    @Test public void mapOwnerDocumentAndHorizontalChangesCannotBridge() {
        SpatialRegionCache.Frame first = frame(cache, image, 1, 1, 0, 0, 1);
        List<TrackedObject> before = tracker.update(List.of(face(150)));
        continuity.record(first, 0, 0);
        SpatialRegionCache.Frame foreign = frame(new SpatialRegionCache(), shift(image, -40), 2, 1, 0, 5, 1);
        assertFalse(first.sameMapAs(foreign)); // Equal generation numbers from different owners.
        assertTrue(proposal(foreign, 5, 0, 5, -5, before, List.of(face(110))).isEmpty());
        SpatialRegionCache.Frame horizontal = frame(cache, shift(image, -40), 2, 1, 1, 5, 1);
        assertTrue(proposal(horizontal, 5, 0, 5, -5, before, List.of(face(110))).isEmpty());
        SpatialRegionCache.Frame document = frame(cache, shift(image, -40), 3, 2, 0, 5, 1);
        assertTrue(proposal(document, 5, 0, 5, -5, before, List.of(face(110))).isEmpty());
    }

    @Test public void textFixedObjectsAndAmbiguousObservationsAreNotMoved() {
        SpatialRegionCache.Frame first = frame(cache, image, 1, 1, 0, 0, 1);
        SpatialRegionCache.Frame next = frame(cache, shift(image, -30), 2, 1, 0, 0, 1);
        assertNotNull(next.result.pose);
        continuity.record(first, 0, 0);
        List<TrackedObject> before = tracker.update(List.of(face(150)));
        assertTrue(proposal(next, 0, 0, 0, 0, before, List.of(face(150))).isEmpty());
        assertTrue(proposal(next, 0, 0, 0, 0, before, List.of(face(150), face(120))).isEmpty());
        assertTrue(proposal(next, 0, 0, 0, 0, before, List.of(face(120), face(121))).isEmpty());
        tracker.clear();
        before = tracker.update(List.of(box("FACE_FEMALE", "text_smut", 150, 30, 36)));
        assertTrue(proposal(next, 0, 0, 0, 0, before, List.of(face(110))).isEmpty());
        tracker.clear();
        before = tracker.update(List.of(face(20)));
        assertTrue(proposal(next, 0, 0, 0, 0, before, List.of(face(-20))).isEmpty());
    }

    @Test public void combinedMotionIsAppliedBeforeClipping() {
        List<TrackedObject> before = tracker.update(List.of(face(10)));
        int id = before.get(0).getId();
        // Sequential -100 would deactivate the track before the compensating +120 could apply.
        tracker.offsetActiveTracks(0, -100, W, H, Map.of(id, 120));
        assertEquals(1, tracker.activeTracks().size());
        assertEquals(30, tracker.activeTracks().get(0).getRawBox().getY());
    }

    @Test public void correctionCannotStealAStationaryTracksDetection() {
        SpatialRegionCache.Frame first = frame(cache, image, 1, 1, 0, 0, 1);
        SpatialRegionCache.Frame next = frame(cache, shift(image, -30), 2, 1, 0, 0, 1);
        assertNotNull(next.result.pose);
        continuity.record(first, 0, 0);
        List<TrackedObject> before = tracker.update(List.of(face(150), face(120)));
        assertEquals(2, before.size());
        assertTrue(proposal(next, 0, 0, 0, 0, before, List.of(face(120))).isEmpty());
    }

    @Test public void twoTracksCannotClaimTheSameCorrectedDetection() {
        SpatialRegionCache.Frame first = frame(cache, image, 1, 1, 0, 0, 1);
        SpatialRegionCache.Frame next = frame(cache, shift(image, -30), 2, 1, 0, 0, 1);
        continuity.record(first, 0, 0);
        List<TrackedObject> before = tracker.update(List.of(face(150), face(151)));
        assertEquals(2, before.size());
        assertTrue(proposal(next, 0, 0, 0, 0, before, List.of(face(120))).isEmpty());
    }

    @Test public void missingTracksAreNotAssumedToBelongToThePreviousImage() {
        SpatialRegionCache.Frame first = frame(cache, image, 1, 1, 0, 0, 1);
        SpatialRegionCache.Frame next = frame(cache, shift(image, -30), 2, 1, 0, 0, 1);
        continuity.record(first, 0, 0);
        tracker.update(List.of(face(150)), 1_000_000_000L);
        List<TrackedObject> before = tracker.update(List.of(), 1_010_000_000L);
        assertEquals(1, before.size());
        assertEquals(1, before.get(0).getFramesMissing());
        assertTrue(proposal(next, 0, 0, 0, 0, before, List.of(face(120))).isEmpty());
    }

    private Map<Integer, Integer> proposal(SpatialRegionCache.Frame frame, long sourceY, long oldY,
            long nextY, int dy, List<TrackedObject> tracks, List<Detection> detections) {
        return continuity.corrections(frame, 0, sourceY, oldY, nextY, 0, dy, W, H, tracks, detections, .5f);
    }
    private static Detection face(int y) { return box("FACE_FEMALE", "face", y, 30, 36); }
    private static Detection box(String label, String category, int y, int width, int height) {
        return new Detection(label, category, .95f, new BBox(40, y, width, height), true, false);
    }
    private static SpatialRegionCache.Frame frame(SpatialRegionCache cache, int[] pixels,
            long id, long document, long eventX, long eventY, int scale) {
        return cache.register(pixels, W, H, 64, 304,
                new RowMotionObserver.Scope(1, document, 7, W * scale, H * scale),
                id, id * 100, true, eventX, eventY, W, H);
    }
    private static int[] texture(long seed) {
        Random random = new Random(seed);
        int[] pixels = new int[W * H];
        for (int y = 0; y < H; y++) {
            int row = random.nextInt(150) + 50;
            for (int x = 0; x < W; x++) {
                int value = row + random.nextInt(51) - 25;
                pixels[y * W + x] = 0xff000000 | value << 16 | value << 8 | value;
            }
        }
        return pixels;
    }
    private static int[] shift(int[] pixels, int dy) {
        int[] result = new int[pixels.length];
        for (int y = 0; y < H; y++) if (y + dy >= 0 && y + dy < H) {
            System.arraycopy(pixels, y * W, result, (y + dy) * W, W);
        }
        return result;
    }
}
