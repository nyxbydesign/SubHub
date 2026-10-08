package com.subhub.app.service;

import java.util.Arrays;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SpatialFrameMapTest {
    private static final int W = 144, H = 320;
    private static RowMotionObserver.Scope scope(long epoch, long doc, int window) {
        return new RowMotionObserver.Scope(epoch, doc, window, W, H);
    }
    private static SpatialFrameMap.Result observe(SpatialFrameMap map, int[] pixels, long id, long now) {
        return map.observe(pixels, W, H, 64, 304, scope(1, 1, 7), id, now, true);
    }

    @Test public void accumulatesOnlyRegisteredSpatialTranslations() {
        SpatialFrameMap map = new SpatialFrameMap();
        int[] source = texture(1);
        SpatialFrameMap.Result baseline = observe(map, source, 1, 100);
        SpatialFrameMap.Result moved = observe(map, shift(source, -9), 2, 400);
        assertEquals(SpatialFrameMap.Status.REGISTERED, moved.status);
        assertEquals(9, moved.pose.sourceY, .25);
        SpatialFrameMap.Result returned = observe(map, source, 3, 700);
        assertEquals(0, returned.pose.sourceY, .25);
        assertEquals(baseline.pose.mapGeneration, returned.pose.mapGeneration);
    }

    @Test public void rejectedFrameDoesNotReplaceTheReferenceOrInventZeroPose() {
        SpatialFrameMap map = new SpatialFrameMap();
        int[] source = texture(2);
        observe(map, source, 1, 100);
        SpatialFrameMap.Result rejected = observe(map, texture(3), 2, 400);
        assertNull(rejected.pose);
        assertEquals(1, rejected.referenceId);
        SpatialFrameMap.Result recovered = observe(map, shift(source, -6), 3, 700);
        assertEquals(SpatialFrameMap.Status.REGISTERED, recovered.status);
        assertEquals(1, recovered.referenceId);
        assertEquals(6, recovered.pose.sourceY, .25);
    }

    @Test public void staleReferenceStartsAnExplicitlyDifferentMap() {
        SpatialFrameMap map = new SpatialFrameMap();
        SpatialFrameMap.Result first = observe(map, texture(4), 1, 100);
        SpatialFrameMap.Result next = observe(map, texture(5), 2, 4000);
        assertEquals(SpatialFrameMap.Status.BASELINE, next.status);
        assertTrue(next.pose.mapGeneration > first.pose.mapGeneration);
    }

    @Test public void retainedImageCanBeReidentifiedWithoutInventingIntermediatePoses() {
        SpatialFrameMap map = new SpatialFrameMap();
        int[] source = texture(12);
        SpatialFrameMap.Result first = observe(map, source, 1, 100);
        assertNull(observe(map, texture(13), 2, 400).pose);
        SpatialFrameMap.Result recovered = observe(map, shift(source, -6), 3, 1400);
        assertEquals(SpatialFrameMap.Status.REGISTERED, recovered.status);
        assertEquals(first.pose.mapGeneration, recovered.pose.mapGeneration);
        assertEquals(6, recovered.pose.sourceY, .25);
    }

    @Test public void oldFramesAndScopesCannotResetNewReference() {
        SpatialFrameMap map = new SpatialFrameMap();
        int[] source = texture(6);
        observe(map, source, 10, 1000);
        assertEquals(SpatialFrameMap.Status.STALE, observe(map, source, 9, 900).status);
        assertNull(map.observe(source, W, H, 64, 304, scope(1, 0, 7), 11, 1100, true).pose);
        assertEquals(SpatialFrameMap.Status.REGISTERED, observe(map, source, 11, 1200).status);
    }

    @Test public void windowChangeRequiresDocumentTransitionAndNewEpochCanRestartIds() {
        SpatialFrameMap map = new SpatialFrameMap();
        int[] source = texture(7);
        observe(map, source, 10, 1000);
        assertNull(map.observe(source, W, H, 64, 304, scope(1, 1, 8), 11, 1100, true).pose);
        assertEquals(SpatialFrameMap.Status.BASELINE,
                map.observe(source, W, H, 64, 304, scope(2, 0, 8), 1, 1200, true).status);
    }

    @Test public void callerMutationCannotCorruptRetainedReference() {
        SpatialFrameMap map = new SpatialFrameMap();
        int[] source = texture(8), original = source.clone();
        observe(map, source, 1, 100);
        Arrays.fill(source, 0);
        SpatialFrameMap.Result result = observe(map, shift(original, 6), 2, 400);
        assertNotNull(result.pose);
        assertEquals(-6, result.pose.sourceY, .25);
    }

    @Test public void unhintedMovingImageryIsNotAcceptedAsCameraEvidence() {
        SpatialFrameMap map = new SpatialFrameMap();
        int[] source = texture(9);
        observe(map, source, 1, 100);
        SpatialFrameMap.Result result = map.observe(shift(source, -9), W, H, 64, 304,
                scope(1, 1, 7), 2, 400, false);
        assertEquals(SpatialFrameMap.Status.NO_MOTION_HINT, result.status);
        assertNull(result.pose);
    }

    @Test public void repeatedFractionalRoundTripsDoNotAccumulateDrift() {
        SpatialFrameMap map = new SpatialFrameMap();
        int[] first = smoothTexture(0), second = smoothTexture(4.5);
        observe(map, first, 1, 100);
        for (int loop = 0; loop < 20; loop++) {
            long id = 2 + loop * 2L;
            SpatialFrameMap.Result outward = observe(map, second, id, id * 100);
            assertNotNull("outward " + loop, outward.pose);
            SpatialFrameMap.Result returned = observe(map, first, id + 1, (id + 1) * 100);
            assertNotNull("returned " + loop, returned.pose);
            assertEquals("round trip " + loop, 0, returned.pose.sourceY, .25);
        }
    }

    @Test public void ambiguousPeriodicTextureStaysUnknown() {
        SpatialFrameMap map = new SpatialFrameMap();
        int[] source = texture(10);
        for (int y = 12; y < H; y++) System.arraycopy(source, (y % 12) * W, source, y * W, W);
        observe(map, source, 1, 100);
        assertNull(observe(map, shift(source, 6), 2, 400).pose);
    }

    @Test public void cropResetAndClearCannotReuseOldMapIdentity() {
        SpatialFrameMap map = new SpatialFrameMap();
        int[] source = texture(11);
        SpatialFrameMap.Result initial = observe(map, source, 1, 100);
        SpatialFrameMap.Result resized = map.observe(source, W, H, 48, 304,
                scope(1, 1, 7), 2, 200, true);
        assertEquals(SpatialFrameMap.Status.BASELINE, resized.status);
        assertTrue(resized.pose.mapGeneration > initial.pose.mapGeneration);
        assertEquals(SpatialFrameMap.Status.STALE, observe(map, source, 1, 100).status);
        map.clear();
        SpatialFrameMap.Result reset = observe(map, source, 0, 0);
        assertTrue(reset.pose.mapGeneration > resized.pose.mapGeneration);
    }

    private static int[] smoothTexture(double shift) {
        int[] result = new int[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            double yy = y - shift;
            int anchor = (int) Math.floor(yy / 3);
            double fraction = yy / 3 - anchor;
            double blend = fraction * fraction * (3 - 2 * fraction);
            double row = noise(anchor) * (1 - blend) + noise(anchor + 1) * blend;
            int value = (int) Math.round(128 + 70 * row + 25 * Math.sin(x * .47 + yy * .13));
            result[y * W + x] = 0xff000000 | value << 16 | value << 8 | value;
        }
        return result;
    }

    private static double noise(int index) {
        int value = index * 0x9e3779b9;
        value = (value ^ (value >>> 16)) * 0x85ebca6b;
        value ^= value >>> 13;
        return ((value >>> 8) & 0xffff) / 32767.5 - 1;
    }

    private static int[] texture(long seed) {
        Random random = new Random(seed);
        int[] pixels = new int[W * H];
        // Broad row structure gives the coarse descriptor evidence, while local texture lets
        // patch verification distinguish registration from a repeated flat stripe pattern.
        for (int y = 0; y < H; y++) {
            int row = random.nextInt(150) + 50;
            for (int x = 0; x < W; x++) {
                int value = Math.max(0, Math.min(255, row + random.nextInt(51) - 25));
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
