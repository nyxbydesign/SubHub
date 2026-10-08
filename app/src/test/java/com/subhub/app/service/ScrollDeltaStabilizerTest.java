package com.subhub.app.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ScrollDeltaStabilizerTest {
    @Test
    public void isolatedOppositeCorrectionNeverBouncesTheOverlay() {
        ScrollDeltaStabilizer filter = new ScrollDeltaStabilizer();
        assertEquals(261, filter.filter(0, 261, 100L, 1080, 2400).dy);
        assertEquals(270, filter.filter(0, 270, 200L, 1080, 2400).dy);

        ScrollDeltaStabilizer.Result correction =
                filter.filter(0, -485, 300L, 1080, 2400);
        assertEquals(0, correction.dy);
        assertTrue(correction.changed());
        assertEquals(262, filter.filter(0, 262, 400L, 1080, 2400).dy);
    }

    @Test
    public void twoConsecutiveOppositeSamplesConfirmARealReversal() {
        ScrollDeltaStabilizer filter = new ScrollDeltaStabilizer();
        filter.filter(0, 200, 100L, 1080, 2400);
        filter.filter(0, 180, 160L, 1080, 2400);
        assertEquals(0, filter.filter(0, -100, 220L, 1080, 2400).dy);
        assertEquals(-120, filter.filter(0, -120, 280L, 1080, 2400).dy);
        assertEquals(-80, filter.filter(0, -80, 340L, 1080, 2400).dy);
    }

    @Test
    public void idleGapStartsANewGestureWithoutCatchUpOvershoot() {
        ScrollDeltaStabilizer filter = new ScrollDeltaStabilizer();
        filter.filter(0, 200, 100L, 1080, 2400);
        filter.filter(0, 200, 180L, 1080, 2400);
        assertEquals(-180, filter.filter(0, -180, 500L, 1080, 2400).dy);
        assertEquals(-160, filter.filter(0, -160, 580L, 1080, 2400).dy);
    }

    @Test
    public void rapidAlternatingInputNeverLumpsHeldMotionIntoTheNextEvent() {
        ScrollDeltaStabilizer filter = new ScrollDeltaStabilizer();
        int first = filter.filter(0, 100, 100L, 1080, 2400).dy;
        int second = filter.filter(0, -100, 180L, 1080, 2400).dy;
        int third = filter.filter(0, 100, 260L, 1080, 2400).dy;
        int fourth = filter.filter(0, -100, 340L, 1080, 2400).dy;

        assertEquals(100, first);
        assertEquals(0, second);
        assertEquals(100, third);
        assertEquals(-100, fourth);
        ScrollDeltaStabilizer.Result fifth =
                filter.filter(0, 90, 420L, 1080, 2400);
        assertEquals(90, fifth.dy);
        assertTrue(fifth.rapidReversal);
        assertEquals(-80, filter.filter(0, -80, 500L, 1080, 2400).dy);
    }

    @Test
    public void authoritativeAlternatingInputPassesThroughAtItsOriginalCadence() {
        ScrollDeltaStabilizer filter = new ScrollDeltaStabilizer();
        int[] raw = {100, -100, 120, -140, 90};
        long now = 100L;
        for (int value : raw) {
            ScrollDeltaStabilizer.Result result = filter.filter(
                    0, value, now, 1080, 2400, true);
            assertEquals(value, result.dy);
            assertTrue(result.authoritative);
            assertEquals(0, result.adjustedPixels());
            assertTrue(!result.amplified());
            now += 80L;
        }
    }

    @Test
    public void producerJumpPreservesResolverBoundOfTwoViewports() {
        ScrollDeltaStabilizer filter = new ScrollDeltaStabilizer();
        filter.filter(5000, -9000, 100L, 1000, 2000);
        ScrollDeltaStabilizer.Result result =
                filter.filter(5000, -9000, 180L, 1000, 2000);
        assertEquals(2000, result.dx);
        assertEquals(-4000, result.dy);
    }

    @Test
    public void capturedTwitterSequencesNeverEmitAnIsolatedWrongWayCorrection() {
        int[][] sequences = {
                {11, 261, 270, 196, 152, 73, 31, 7, -485, 262, 212, 180, 76, 38, 14},
                {12, 236, -333, 117, 50, 18, 1},
                {1, 159, 170, 195, 144, -442, 33, 10}
        };
        long now = 100L;
        ScrollDeltaStabilizer filter = new ScrollDeltaStabilizer();
        for (int[] sequence : sequences) {
            for (int raw : sequence) {
                int filtered = filter.filter(0, raw, now, 1344, 2992).dy;
                assertTrue("Wrong-way output " + filtered, filtered >= 0);
                now += 100L;
            }
            now += 300L;
        }
    }
}
