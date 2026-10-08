package com.subhub.app.overlay;

import org.junit.Test;
import static org.junit.Assert.*;

/** Reported movement must not wait for the speculative continuation horizon. */
public final class MeasurementReconciliationTest {
    @Test public void reportedTraceHasNoTeleportAndRemainsCaughtUpOnBothAxesAndDirections() {
        for (boolean horizontal : new boolean[]{false, true}) {
            for (int sign : new int[]{-1, 1}) {
                for (int step : new int[]{8, 16}) {
                    ViewportMotion motion = new ViewportMotion();
                    motion.reset(0, 0, 0);
                    motion.addDelta(horizontal ? sign * 338 : 0, horizontal ? 0 : sign * 338,
                            1000, 2992, 2992, true);
                    float before = axis(motion.position(1117), horizontal);
                    motion.addDelta(horizontal ? sign * 324 : 0, horizontal ? 0 : sign * 324,
                            1117, 2992, 2992, true);
                    assertEquals(before, axis(motion.position(1117), horizontal), .001f);
                    assertTrue(Math.abs(axis(motion.predictionAmplitude(), horizontal)) <= 2992 * .18f);
                    for (int age = step; age <= 32; age += step) {
                        float value = sign * axis(motion.position(1117 + age), horizontal);
                        assertTrue(value <= 662f + 2992 * .18f);
                        if (age >= 16) assertTrue(value >= 662f);
                    }
                    assertEquals(sign * 662f, axis(motion.position(2000), horizontal), .001f);
                }
            }
        }
    }

    private static float axis(ViewportMotion.Position p, boolean horizontal) {
        return horizontal ? p.x : p.y;
    }

    private static ViewportMotion pending() {
        ViewportMotion motion = new ViewportMotion();
        motion.reset(0, 0, 0);
        motion.addDelta(0, -200, 1000, 1344, 2992, true);
        motion.addDelta(0, -462, 1117, 1344, 2992, true);
        assertTrue(motion.position(1121).y > -662f);
        return motion;
    }

    @Test public void resetRebaseAndPollClearPendingCorrection() {
        ViewportMotion reset = pending(), rebase = pending(), poll = pending();
        reset.reset(0, 25, 1121);
        rebase.rebase(0, 25, 1121);
        poll.addPresentationDelta(0, -20, 1121, 1344, 2992);
        assertEquals(-682f, poll.position(1121).y, .001f);
        poll.settlePresentation(1125);
        for (long time : new long[]{1141, 1200}) {
            assertEquals(25f, reset.position(time).y, .001f);
            assertEquals(25f, rebase.position(time).y, .001f);
            assertEquals(-682f, poll.position(time).y, .001f);
        }
    }

    @Test public void fallbackAndReversalBrakeButContinuedDirectionDoesNotPullBackward() {
        ViewportMotion fallback = pending();
        float start = fallback.position(1121).y;
        fallback.addDelta(0, -20, 1121, 1344, 2992, false);
        assertEquals(start, fallback.position(1121).y, .001f);
        assertEquals(-682f, fallback.position(1137).y, .001f);
        for (int delta : new int[]{20, -20}) {
            ViewportMotion motion = pending();
            float before = motion.position(1234).y;
            motion.addDelta(0, delta, 1234, 1344, 2992, true);
            assertEquals(before, motion.position(1234).y, .001f);
            if (delta > 0) {
                assertEquals(0f, motion.predictionAmplitude().y, .001f);
                assertEquals(-662f + delta, motion.position(1266).y, .001f);
            } else {
                // Same-direction deceleration is not an observed stop or reversal.
                assertEquals(before, motion.position(1266).y, .001f);
                assertTrue(Math.abs(motion.predictionAmplitude().y) <= 2992 * .18f);
            }
            assertEquals(-662f + delta, motion.position(1500).y, .001f);
            assertFalse(motion.isAnimating(1500));
        }
    }
}
