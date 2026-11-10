package com.subhub.app.service;

import com.subhub.app.detection.BBox;
import java.util.Arrays;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SourcePatchMatcherTest {
    private static final int W = 64, H = 96;
    private static final BBox BOX = new BBox(16, 28, 32, 32);

    @Test public void pixelMatchRefinesNoisyDetectorCoordinates() {
        int[] pixels = texture(1);
        SourcePatchMatcher.Match result = SourcePatchMatcher.match(image(pixels), image(shift(pixels, 12)),
                BOX, new BBox(17, 39, 32, 32), W, H);
        assertNotNull(result);
        assertEquals(12, result.sourceDy, .125);
        assertEquals(4, result.patches);
    }

    @Test public void localObjectCanMatchWhileTheRestOfTheImageChanges() {
        int[] previous = texture(2), current = texture(3);
        for (int y = BOX.getY(); y < BOX.getBottom(); y++) {
            System.arraycopy(previous, y * W + BOX.getX(), current, (y + 12) * W + BOX.getX(), BOX.getWidth());
        }
        SourcePatchMatcher.Match result = SourcePatchMatcher.match(image(previous), image(current),
                BOX, new BBox(16, 40, 32, 32), W, H);
        assertNotNull(result);
        assertEquals(12, result.sourceDy, .125);
    }

    @Test public void returnsSourcePixelsNotPreparedPixels() {
        int[] pixels = texture(4);
        SourcePatchMatcher.Match result = SourcePatchMatcher.match(image(pixels), image(shift(pixels, 12)),
                new BBox(48, 84, 96, 96), new BBox(48, 120, 96, 96), W * 3, H * 3);
        assertNotNull(result);
        assertEquals(36, result.sourceDy, .375);
        result = SourcePatchMatcher.match(image(pixels), image(shift(pixels, 12)),
                new BBox(48, 84, 96, 96), new BBox(48, 121, 96, 96), W * 3, H * 3);
        assertNotNull(result);
        assertEquals(36, result.sourceDy, .375);
    }

    @Test public void unrelatedObjectsAndUninformativePatternsAreRejected() {
        assertNull(SourcePatchMatcher.match(image(texture(5)), image(texture(6)), BOX, BOX, W, H));
        for (int pattern = 0; pattern < 3; pattern++) {
            int[] pixels = new int[W * H];
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                int value = pattern == 0 ? 100 : pattern == 1 ? (x < 32 ? 20 : 220) : (y % 4 < 2 ? 20 : 220);
                pixels[y * W + x] = color(value);
            }
            assertNull(SourcePatchMatcher.match(image(pixels), image(shift(pixels, 12)),
                    BOX, new BBox(16, 40, 32, 32), W, H));
        }
    }

    @Test public void callerPixelReuseDoesNotMutateTheReference() {
        int[] pixels = texture(7), current = shift(pixels, 12);
        SourcePatchMatcher.Image previous = image(pixels);
        Arrays.fill(pixels, 0);
        assertNotNull(SourcePatchMatcher.match(previous, image(current), BOX, new BBox(16, 40, 32, 32), W, H));
    }

    @Test public void unsupportedGeometryAndLargeLateralMotionAreRejected() {
        SourcePatchMatcher.Image pixels = image(texture(8));
        assertNull(SourcePatchMatcher.match(pixels, pixels, BOX, new BBox(20, 28, 32, 32), W, H));
        assertNull(SourcePatchMatcher.match(pixels, pixels, new BBox(16, 28, 16, 16), BOX, W, H));
        assertNull(SourcePatchMatcher.match(pixels, pixels, BOX, new BBox(16, 90, 32, 32), W, H));
        assertNull(SourcePatchMatcher.Image.copyOf(new int[513 * 64], 513, 64));
        assertNull(SourcePatchMatcher.Image.copyOf(new int[64], 64, 64));
    }

    private static SourcePatchMatcher.Image image(int[] pixels) { return SourcePatchMatcher.Image.copyOf(pixels, W, H); }
    private static int[] texture(int seed) {
        Random random = new Random(seed);
        int[] pixels = new int[W * H];
        for (int i = 0; i < pixels.length; i++) pixels[i] = color(40 + random.nextInt(160));
        return pixels;
    }
    private static int color(int value) { return 0xff000000 | value << 16 | value << 8 | value; }
    private static int[] shift(int[] pixels, int dy) {
        int[] result = new int[pixels.length];
        for (int y = 0; y < H; y++) if (y + dy >= 0 && y + dy < H) {
            System.arraycopy(pixels, y * W, result, (y + dy) * W, W);
        }
        return result;
    }
}
