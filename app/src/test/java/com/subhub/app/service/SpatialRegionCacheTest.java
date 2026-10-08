package com.subhub.app.service;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SpatialRegionCacheTest {
    private static final int W = 144, H = 320;
    private static final BBox BOX = new BBox(20, 120, 20, 30);
    private static SpatialRegionCache.Frame frame(SpatialRegionCache cache, int[] pixels,
            long id, long eventY) {
        return cache.register(pixels, W, H, 64, 304, new RowMotionObserver.Scope(1, 1, 7, W, H),
                id, id * 100, true, 0, eventY, W, H);
    }
    private static List<ContentSpaceRegionCache.Observation> observation(BBox box, int tracked, int missing) {
        return Collections.singletonList(new ContentSpaceRegionCache.Observation(
                7, "FACE_FEMALE", "face", .95f, box, true, false, tracked, missing, false));
    }
    private static void write(SpatialRegionCache cache, SpatialRegionCache.Frame frame) {
        cache.observeSource(frame, frame.receipt, false, observation(BOX, 2, 0));
    }

    @Test public void imagesPlaceCachedRegionsEvenWhenEventsReportNoMovement() {
        SpatialRegionCache cache = new SpatialRegionCache();
        int[] pixels = texture(1);
        SpatialRegionCache.Frame first = frame(cache, pixels, 1, 0);
        write(cache, first);
        SpatialRegionCache.Frame next = frame(cache, shift(pixels, -6), 2, 0);
        List<Detection> found = cache.querySource(next, 200);
        assertEquals(1, found.size());
        assertEquals(114, found.get(0).getBox().getY());
        assertFalse(found.get(0).getRenderSourceReference().isKnown());
    }

    @Test public void unknownFramesNeitherWriteNorQueryAndBridgingRecoversOldRegions() {
        SpatialRegionCache cache = new SpatialRegionCache();
        int[] pixels = texture(2);
        SpatialRegionCache.Frame first = frame(cache, pixels, 1, 0);
        write(cache, first);
        SpatialRegionCache.Frame unknown = frame(cache, texture(3), 2, 0);
        assertNull(unknown.result.pose);
        assertEquals(0, cache.observeSource(unknown, 200, true, observation(BOX, 2, 0)).inserted);
        assertTrue(cache.querySource(unknown, 200).isEmpty());
        assertTrue(cache.querySource(first, 200).isEmpty());
        SpatialRegionCache.Frame recovered = frame(cache, shift(pixels, -6), 3, 0);
        assertEquals(1, cache.querySource(recovered, 300).size());
    }

    @Test public void newMapRejectsOldQualityAndDoesNotReuseOldRegions() {
        SpatialRegionCache cache = new SpatialRegionCache();
        SpatialRegionCache.Frame old = frame(cache, texture(4), 1, 0);
        write(cache, old);
        SpatialRegionCache.Frame next = frame(cache, texture(5), 40, 0);
        assertEquals(SpatialFrameMap.Status.BASELINE, next.result.status);
        write(cache, old);
        assertTrue(cache.querySource(next, 4000).isEmpty());
        assertEquals(0, cache.size());
    }

    @Test public void undoingEventTailDoesNotCountImageMotionTwice() {
        SpatialRegionCache cache = new SpatialRegionCache();
        SpatialRegionCache.Frame source = frame(cache, texture(6), 1, 50);
        List<ContentSpaceRegionCache.Observation> observations = SpatialRegionCache.sourceObservations(
                source, 0, 70, observation(new BBox(20, 100, 20, 30), 2, 0));
        cache.observeSource(source, 100, false, observations);
        assertEquals(BOX, cache.querySource(source, 100).get(0).getBox());
    }

    @Test public void registrationDoesNotWeakenConfirmationOrCropBoundaries() {
        SpatialRegionCache cache = new SpatialRegionCache();
        SpatialRegionCache.Frame source = frame(cache, texture(7), 1, 0);
        cache.observeSource(source, 100, false, observation(BOX, 1, 0));
        cache.observeSource(source, 100, false, observation(BOX, 3, 1));
        cache.observeSource(source, 100, false, observation(new BBox(20, 40, 20, 30), 3, 0));
        assertTrue(cache.querySource(source, 100).isEmpty());
        write(cache, source);
        assertEquals(1, cache.querySource(source, 100).size());
        assertTrue(cache.querySource(source, 851).isEmpty());
    }

    @Test public void horizontalEventsAndExplicitClearFenceTheCoordinateMap() {
        SpatialRegionCache cache = new SpatialRegionCache();
        int[] pixels = texture(8);
        SpatialRegionCache.Frame source = frame(cache, pixels, 1, 0);
        write(cache, source);
        SpatialRegionCache.Frame horizontal = cache.register(pixels, W, H, 64, 304,
                source.scope, 2, 200, true, 10, 0, W, H);
        assertTrue(horizontal.result.pose.mapGeneration > source.result.pose.mapGeneration);
        assertTrue(cache.querySource(horizontal, 200).isEmpty());
        write(cache, horizontal);
        cache.clear();
        assertTrue(cache.querySource(horizontal, 200).isEmpty());
    }

    @Test public void viewportScalingUsesSourcePixelsAndInvalidInputCannotResetTheMap() {
        SpatialRegionCache cache = new SpatialRegionCache();
        int[] pixels = texture(9);
        RowMotionObserver.Scope scope = new RowMotionObserver.Scope(1, 1, 7, W * 2, H * 2);
        SpatialRegionCache.Frame first = cache.register(pixels, W, H, 64, 304,
                scope, 1, 100, true, 0, 50, W, H);
        cache.observeSource(first, 100, false, SpatialRegionCache.sourceObservations(first,
                0, 60, observation(new BBox(40, 220, 40, 60), 2, 0)));
        SpatialRegionCache.Frame invalid = cache.register(null, W, H, 64, 304,
                scope, 2, 200, true, 10, 50, W, H);
        assertNull(invalid.result.pose);
        assertTrue(cache.querySource(first, 200).isEmpty());
        assertEquals(1, cache.size());
        SpatialRegionCache.Frame moved = cache.register(shift(pixels, -6), W, H, 64, 304,
                scope, 3, 300, true, 0, 50, W, H);
        assertEquals(new BBox(40, 228, 40, 60), cache.querySource(moved, 300).get(0).getBox());
    }

    @Test public void captureOutageHoldsOnlyAppliedCoverageWithoutRequeryingStaleSource() {
        SpatialRegionCache cache = new SpatialRegionCache();
        SpatialRegionCache.Frame source = frame(cache, texture(10), 1, 0);
        write(cache, source);
        assertFalse(cache.retainAppliedCoverage(source, 1200));
        cache.markApplied(source, 150, 1);
        assertEquals(source.id, cache.appliedFrameId());
        assertTrue(cache.querySource(source, 1200).isEmpty());
        assertTrue(cache.retainAppliedCoverage(source, 1200));
        assertTrue(cache.retainAppliedCoverage(source, 5500));
        assertFalse(cache.retainAppliedCoverage(source, 6101));
        assertFalse(cache.retainAppliedCoverage(source, 90));
    }

    @Test public void newUnknownAndNewMapCannotBorrowAppliedCoverage() {
        SpatialRegionCache cache = new SpatialRegionCache();
        SpatialRegionCache.Frame first = frame(cache, texture(11), 1, 0);
        write(cache, first);
        cache.markApplied(first, 100, 1);
        SpatialRegionCache.Frame unknown = frame(cache, texture(12), 2, 0);
        assertFalse(cache.retainAppliedCoverage(unknown, 1200));
        assertFalse(cache.retainAppliedCoverage(first, 1200));
        SpatialRegionCache.Frame otherMap = frame(cache, texture(13), 40, 0);
        assertFalse(cache.retainAppliedCoverage(otherMap, 5000));
        cache.clear();
        assertEquals(-1, cache.appliedFrameId());
    }

    @Test public void queuedCacheIsRecheckedWithoutRemovingIndependentCurrentQuality() {
        SpatialRegionCache cache = new SpatialRegionCache();
        SpatialRegionCache.Frame first = frame(cache, texture(14), 1, 0);
        write(cache, first);
        List<Detection> cached = cache.querySource(first, 100);
        Detection quality = new Detection("FACE_MALE", "face", .98f, BOX, true, false);
        List<Detection> combined = new java.util.ArrayList<>(cached);
        combined.add(quality);
        assertEquals(2, cache.revalidatePresentation(first, 150, cached, combined).size());
        frame(cache, texture(15), 2, 0);
        assertEquals(Collections.singletonList(quality),
                cache.revalidatePresentation(first, 210, cached, combined));
        cache.markApplied(first, 210, 1);
        assertEquals(-1, cache.appliedFrameId());
    }

    @Test public void sourceRecoveryUsesMotionDuringTheGapWithoutPredictingIntermediatePositions() {
        SpatialRegionCache cache = new SpatialRegionCache();
        int[] pixels = texture(16);
        SpatialRegionCache.Frame first = frame(cache, pixels, 1, 0);
        write(cache, first);
        cache.markApplied(first, 100, 1);
        assertTrue(cache.querySource(first, 5000).isEmpty());
        assertTrue(cache.motionHintForReference(first.scope, 2500, 5500));
        SpatialRegionCache.Frame recovered = cache.register(shift(pixels, -6), W, H, 64, 304,
                first.scope, 2, 5500, cache.motionHintForReference(first.scope, 2500, 5500), 0, 0, W, H);
        assertEquals(SpatialFrameMap.Status.REGISTERED, recovered.result.status);
        assertEquals(first.result.pose.mapGeneration, recovered.result.pose.mapGeneration);
        assertEquals(114, cache.querySource(recovered, 5500).get(0).getBox().getY());
        assertFalse(cache.retainAppliedCoverage(recovered, 6251));
    }

    @Test public void oldOtherScopeAndFutureEventsDoNotAuthorizeDelayedMotion() {
        SpatialRegionCache cache = new SpatialRegionCache();
        SpatialRegionCache.Frame source = frame(cache, texture(17), 1, 0);
        assertFalse(cache.motionHintForReference(source.scope, 50, 5500));
        assertFalse(cache.motionHintForReference(source.scope, 5501, 5500));
        assertFalse(cache.motionHintForReference(new RowMotionObserver.Scope(1, 2, 7, W, H), 2000, 5500));
    }

    @Test public void queryCandidatesAloneDoNotAuthorizeHoldingAnEmptyViewCache() {
        SpatialRegionCache cache = new SpatialRegionCache();
        SpatialRegionCache.Frame source = frame(cache, texture(18), 1, 0);
        write(cache, source);
        assertEquals(1, cache.querySource(source, 100).size());
        cache.markApplied(source, 150, 0);
        assertFalse(cache.retainAppliedCoverage(source, 1200));
        assertEquals(-1, cache.appliedFrameId());
    }

    @Test public void writeDiagnosticsDistinguishCropAndConfirmationRejection() {
        SpatialRegionCache cache = new SpatialRegionCache();
        SpatialRegionCache.Frame source = frame(cache, texture(19), 1, 0);
        SpatialRegionCache.WriteResult outside = cache.observeSourceWithStats(source, 100, false,
                observation(new BBox(20, 40, 20, 30), 3, 0));
        assertTrue(outside.known);
        assertEquals(1, outside.input);
        assertEquals(1, outside.faces);
        assertEquals(1, outside.faceCropRejected);
        assertEquals(1, outside.cropRejected);
        SpatialRegionCache.WriteResult unconfirmed = cache.observeSourceWithStats(source, 100, false,
                observation(BOX, 1, 0));
        assertEquals(0, unconfirmed.cropRejected);
        assertEquals(1, unconfirmed.unconfirmed);
        assertEquals(0, unconfirmed.update.inserted);
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
    private static int[] shift(int[] source, int dy) {
        int[] result = new int[source.length];
        for (int y = 0; y < H; y++) if (y + dy >= 0 && y + dy < H) {
            System.arraycopy(source, y * W, result, (y + dy) * W, W);
        }
        return result;
    }
}
