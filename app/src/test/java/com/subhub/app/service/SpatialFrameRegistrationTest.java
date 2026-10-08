package com.subhub.app.service;

import java.util.Arrays;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SpatialFrameRegistrationTest {
    private static final int WIDTH = 144, HEIGHT = 320;

    @Test public void refinesCoarseTranslationWithoutChangingPixels() {
        int[] original = texture(1), copy = original.clone();
        for (int dy : new int[]{-18, -5, 0, 7, 21}) {
            SpatialFrameRegistration.Result result = refine(original, shifted(original, dy), dy + .75);
            assertTrue("dy=" + dy, result.accepted);
            assertEquals(dy, result.dy, .125);
            assertTrue(result.bands >= 3);
            assertTrue(result.columns >= 2);
        }
        assertArrayEquals(copy, original);
    }

    @Test public void rejectsBlankWrongProposalAndUnrelatedFrames() {
        int[] original = texture(2), blank = new int[WIDTH * HEIGHT];
        Arrays.fill(blank, 0xff808080);
        assertFalse(refine(blank, blank, 0).accepted);
        assertFalse(refine(original, shifted(original, 15), -15).accepted);
        assertFalse(refine(original, texture(3), 0).accepted);
    }

    @Test public void oneAnimatedBandCannotMoveStationaryBackground() {
        int[] original = texture(4), current = original.clone(), moved = shifted(original, 10);
        System.arraycopy(moved, 100 * WIDTH, current, 100 * WIDTH, 45 * WIDTH);
        assertFalse(refine(original, current, 10).accepted);
        SpatialFrameRegistration.Result stationary = refine(original, current, 0);
        assertTrue(stationary.accepted);
        assertEquals(0, stationary.dy, .125);
    }

    @Test public void splitDirectionReflowDoesNotBecomeOneCameraTranslation() {
        int[] original = texture(5), current = shifted(original, 8), opposite = shifted(original, -8);
        System.arraycopy(opposite, 160 * WIDTH, current, 160 * WIDTH, 160 * WIDTH);
        assertFalse(refine(original, current, 8).accepted);
    }

    @Test public void invalidInputsFailClosed() {
        int[] original = texture(6);
        assertFalse(SpatialFrameRegistration.refine(null, original, WIDTH, HEIGHT, 64, 304, 0).accepted);
        assertFalse(refine(original, original, Double.NaN).accepted);
        assertFalse(SpatialFrameRegistration.refine(original, original, WIDTH, HEIGHT, -1, 304, 0).accepted);
    }

    @Test public void recoversFractionalMotionFromContinuousTexture() {
        int[] source = continuousTexture(0);
        for (double shift : new double[]{-7.625, .5, 4.375}) {
            SpatialFrameRegistration.Result result = refine(source, continuousTexture(shift), Math.rint(shift));
            assertTrue("fractional=" + shift, result.accepted);
            assertEquals(shift, result.dy, .25);
        }
    }

    @Test public void boundedBrightnessChangeDoesNotMoveTheBestPatch() {
        int[] source = texture(7), current = shifted(source, 8);
        for (int i = 0; i < current.length; i++) {
            int value = Math.min(255, (current[i] & 255) + 20);
            current[i] = 0xff000000 | value << 16 | value << 8 | value;
        }
        SpatialFrameRegistration.Result result = refine(source, current, 8);
        assertTrue(result.accepted);
        assertEquals(8, result.dy, .125);
    }

    @Test public void oneTexturedColumnIsNotDistributedCameraEvidence() {
        int[] source = texture(8);
        for (int y = 0; y < HEIGHT; y++) for (int x = WIDTH / 4; x < WIDTH; x++) {
            source[y * WIDTH + x] = 0xff808080;
        }
        assertFalse(refine(source, shifted(source, 6), 6).accepted);
    }

    @Test public void verticalEdgesDoNotDrownInformativeMovingPatches() {
        int[] source = texture(9);
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            if (x < WIDTH / 4 || x >= WIDTH * 3 / 4) {
                int value = (x / 3) % 2 == 0 ? 32 : 224;
                source[y * WIDTH + x] = 0xff000000 | value << 16 | value << 8 | value;
            }
        }
        SpatialFrameRegistration.Result result = refine(source, shifted(source, 6), 6);
        assertTrue(result.accepted);
        assertEquals(6, result.dy, .125);
        assertEquals(16, result.features);
    }

    private static int[] continuousTexture(double shift) {
        int[] result = new int[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            double yy = y - shift;
            int value = (int) Math.round(128 + 45 * Math.sin(x * .83 + yy * .31)
                    + 35 * Math.sin(yy * .91 - x * .17) + 25 * Math.cos(x * .29 + yy * .47));
            result[y * WIDTH + x] = 0xff000000 | value << 16 | value << 8 | value;
        }
        return result;
    }

    private static SpatialFrameRegistration.Result refine(int[] a, int[] b, double dy) {
        return SpatialFrameRegistration.refine(a, b, WIDTH, HEIGHT, 64, 304, dy);
    }
    private static int[] texture(long seed) {
        Random random = new Random(seed);
        int[] pixels = new int[WIDTH * HEIGHT];
        for (int i = 0; i < pixels.length; i++) {
            int value = random.nextInt(192) + 32;
            pixels[i] = 0xff000000 | value << 16 | value << 8 | value;
        }
        return pixels;
    }
    private static int[] shifted(int[] pixels, int dy) {
        int[] result = new int[pixels.length];
        for (int y = 0; y < HEIGHT; y++) {
            if (y + dy >= 0 && y + dy < HEIGHT) System.arraycopy(pixels, y * WIDTH, result, (y + dy) * WIDTH, WIDTH);
        }
        return result;
    }
}
