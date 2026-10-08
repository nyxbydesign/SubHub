package com.subhub.app.service;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.ObjectTracker;
import com.subhub.app.detection.TrackedObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SourceTrackLocalContinuityTest {
    private static final int W = 144, H = 320;
    private final SpatialRegionCache cache = new SpatialRegionCache(true);
    private final SourceTrackContinuity continuity = new SourceTrackContinuity();
    private final ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder()
            .motionPrediction(false).trackingSmoothing(.5f).build());
    private final int[] pixels = texture(1);

    @Test public void localPixelsBridgeUnknownGlobalPosesWithoutInventingAPose() {
        SpatialRegionCache.Frame first = frame(pixels, 1, 0, 1, false);
        SpatialRegionCache.Frame next = frame(shift(pixels, 12), 2, 0, 1, false);
        assertNull(next.result.pose);
        assertNotNull(next.trackingImage);
        List<TrackedObject> tracks = tracker.update(List.of(face(140)));
        int id = tracks.get(0).getId();
        continuity.record(first, 0, 0);
        SourceTrackContinuity.Proposal proposal = propose(next, 0, 0, 0, 0, tracks, List.of(face(152)));
        assertEquals(0, proposal.global);
        assertEquals(1, proposal.local);
        assertEquals(Integer.valueOf(12), proposal.offsets.get(id));
        tracker.offsetActiveTracks(0, 0, W, H, proposal.offsets);
        tracks = tracker.update(List.of(face(152)));
        continuity.record(next, 0, 0);
        assertEquals(id, tracks.get(0).getId());
        assertEquals(152, tracks.get(0).getBox().getY());

        SpatialRegionCache.Frame again = frame(shift(pixels, 24), 3, 0, 1, false);
        assertNull(again.result.pose);
        assertEquals(1, propose(again, 0, 0, 0, 0, tracks, List.of(face(164))).local);
    }

    @Test public void verifiedObjectCanMoveOutsideTheGlobalBodyCrop() {
        SpatialRegionCache.Frame first = frame(pixels, 1, 0, 1, true);
        SpatialRegionCache.Frame next = frame(shift(pixels, 12), 2, 0, 1, true);
        assertNotNull(next.result.pose);
        List<TrackedObject> tracks = tracker.update(List.of(face(30)));
        continuity.record(first, 0, 0);
        SourceTrackContinuity.Proposal proposal = propose(next, 0, 0, 0, 0, tracks, List.of(face(42)));
        assertEquals(0, proposal.global);
        assertEquals(1, proposal.local);
        assertEquals(Integer.valueOf(12), proposal.offsets.get(tracks.get(0).getId()));
    }

    @Test public void stationaryHeaderKeepsItsPositionWhenBodyEventsMove() {
        SpatialRegionCache.Frame first = frame(pixels, 1, 0, 1, true);
        SpatialRegionCache.Frame next = frame(pixels, 2, 12, 1, true);
        List<TrackedObject> tracks = tracker.update(List.of(face(30)));
        continuity.record(first, 0, 0);
        SourceTrackContinuity.Proposal proposal = propose(next, 12, 0, 12, -12, tracks, List.of(face(30)));
        assertEquals(1, proposal.local);
        tracker.offsetActiveTracks(0, -12, W, H, proposal.offsets);
        assertEquals(30, tracker.activeTracks().get(0).getRawBox().getY());
    }

    @Test public void changedObjectTextAndAmbiguousCandidatesDoNotMove() {
        SpatialRegionCache.Frame first = frame(pixels, 1, 0, 1, false);
        SpatialRegionCache.Frame changed = frame(texture(2), 2, 0, 1, false);
        assertNull(changed.result.pose);
        List<TrackedObject> tracks = tracker.update(List.of(face(140)));
        continuity.record(first, 0, 0);
        assertTrue(propose(changed, 0, 0, 0, 0, tracks, List.of(face(152))).offsets.isEmpty());
        SpatialRegionCache.Frame moved = frame(shift(pixels, 12), 3, 0, 1, false);
        assertTrue(propose(moved, 0, 0, 0, 0, tracks, List.of(face(152), face(152))).offsets.isEmpty());
        tracker.clear();
        Detection text = new Detection("FACE_FEMALE", "text_other", .95f, new BBox(40, 140, 32, 32), true, false);
        tracks = tracker.update(List.of(text));
        assertEquals(1, tracks.size());
        assertTrue(propose(moved, 0, 0, 0, 0, tracks, List.of(face(152))).offsets.isEmpty());
    }

    @Test public void staleAndDifferentDocumentPixelsCannotBridge() {
        SpatialRegionCache.Frame first = frame(pixels, 1, 0, 1, false);
        List<TrackedObject> tracks = tracker.update(List.of(face(30)));
        continuity.record(first, 0, 0);
        SpatialRegionCache.Frame stale = frame(shift(pixels, 12), 20, 0, 1, false);
        assertNotNull(stale.trackingImage);
        assertTrue(propose(stale, 0, 0, 0, 0, tracks, List.of(face(42))).offsets.isEmpty());
        SpatialRegionCache.Frame different = frame(shift(pixels, 12), 21, 0, 2, false);
        assertTrue(propose(different, 0, 0, 0, 0, tracks, List.of(face(42))).offsets.isEmpty());
    }

    @Test public void overBudgetSearchDoesNotClaimUniquenessFromAPartialScan() {
        SpatialRegionCache.Frame first = frame(pixels, 1, 0, 1, false);
        SpatialRegionCache.Frame next = frame(shift(pixels, 12), 2, 0, 1, false);
        List<TrackedObject> tracks = tracker.update(List.of(face(30)));
        continuity.record(first, 0, 0);
        List<Detection> detections = new ArrayList<>();
        for (int i = 0; i < 17; i++) detections.add(face(42));
        SourceTrackContinuity.Proposal proposal = propose(next, 0, 0, 0, 0, tracks, detections);
        assertEquals(17, proposal.pairs);
        assertTrue(proposal.offsets.isEmpty());
    }

    @Test public void ordinaryCacheDoesNotRetainAdditionalImageData() {
        SpatialRegionCache ordinary = new SpatialRegionCache();
        SpatialRegionCache.Frame frame = ordinary.register(pixels, W, H, 64, 304,
                new RowMotionObserver.Scope(1, 1, 7, W, H), 1, 100, true, 0, 0, W, H);
        assertNull(frame.trackingImage);
    }

    private SourceTrackContinuity.Proposal propose(SpatialRegionCache.Frame frame, long sourceY,
            long oldY, long nextY, int dy, List<TrackedObject> tracks, List<Detection> detections) {
        return continuity.propose(frame, 0, sourceY, oldY, nextY, 0, dy, W, H, tracks, detections, .5f);
    }
    private SpatialRegionCache.Frame frame(int[] image, long id, long eventY, long document, boolean hint) {
        return cache.register(image, W, H, 64, 304, new RowMotionObserver.Scope(1, document, 7, W, H),
                id, id * 100, hint, 0, eventY, W, H);
    }
    private static Detection face(int y) {
        return new Detection("FACE_FEMALE", "face", .95f, new BBox(40, y, 32, 32), true, false);
    }
    private static int[] texture(int seed) {
        Random random = new Random(seed);
        int[] result = new int[W * H];
        for (int y = 0; y < H; y++) {
            int row = random.nextInt(150) + 50;
            for (int x = 0; x < W; x++) {
                int value = row + random.nextInt(51) - 25;
                result[y * W + x] = 0xff000000 | value << 16 | value << 8 | value;
            }
        }
        return result;
    }
    private static int[] shift(int[] pixels, int dy) {
        int[] result = new int[pixels.length];
        for (int y = 0; y < H; y++) if (y + dy >= 0 && y + dy < H) {
            System.arraycopy(pixels, y * W, result, (y + dy) * W, W);
        }
        return result;
    }
}
