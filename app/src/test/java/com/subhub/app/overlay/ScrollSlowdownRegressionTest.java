package com.subhub.app.overlay;

import org.junit.Test;
import static org.junit.Assert.*;

/** Numeric source/main-thread inputs from Pass105; not a synthetic claim of Pixel accuracy. */
public final class ScrollSlowdownRegressionTest {
    private static final int[] SOURCE = {657, 753, 871, 986, 1088, 1203, 1304, 1420, 1538, 1654, 1755};
    private static final int[] DELIVERED = {678, 769, 881, 1000, 1101, 1215, 1315, 1432, 1547, 1665, 1767};
    private static final int[] DELTA = {5, 414, 617, 475, 485, 282, 220, 140, 83, 40, 15};

    private EventScrollTrajectory replay(int sign, int last) {
        EventScrollTrajectory trajectory = new EventScrollTrajectory();
        trajectory.reset(0, 0, 0);
        float exact = 0;
        for (int index = 0; index <= last; index++) {
            exact += sign * DELTA[index];
            trajectory.measure(exact, sign * DELTA[index], SOURCE[index], DELIVERED[index], 2992);
        }
        return trajectory;
    }

    @Test public void recordedSameDirectionSlowdownDoesNotReverseBetweenFreshSamples() {
        for (int sign : new int[]{1, -1}) {
            for (int frame : new int[]{8, 16}) {
                EventScrollTrajectory trajectory = replay(sign, 4);
                float exact = sign * (5 + 414 + 617 + 475 + 485);
                for (int index = 5; index < DELTA.length; index++) {
                    float before = trajectory.position(DELIVERED[index]);
                    exact += sign * DELTA[index];
                    trajectory.measure(exact, sign * DELTA[index], SOURCE[index], DELIVERED[index], 2992);
                    assertEquals("no input-time teleport", before, trajectory.position(DELIVERED[index]), .001f);
                    int end = index + 1 < DELTA.length ? DELIVERED[index + 1] : DELIVERED[index] + 96;
                    float previous = trajectory.position(DELIVERED[index]);
                    for (int now = DELIVERED[index] + frame; now < end; now += frame) {
                        float current = trajectory.position(now);
                        assertTrue("opposite motion at sample=" + index + " t=" + now + " sign=" + sign,
                                sign * (current - previous) >= -.01f);
                        previous = current;
                    }
                }
                assertEquals("missing input must still settle to authority", exact, trajectory.position(2500), .001f);
                assertFalse(trajectory.isAnimating(2500));
            }
        }
    }

    @Test public void explicitStopCanCorrectAnOvershotForecast() {
        for (int sign : new int[]{1, -1}) {
            EventScrollTrajectory trajectory = replay(sign, 5);
            float exact = sign * (5 + 414 + 617 + 475 + 485 + 282);
            trajectory.measure(exact, 0, 1250, 1255, 2992);
            assertEquals(exact, trajectory.position(1300), .001f);
            assertFalse(trajectory.isAnimating(1300));
        }
    }

    @Test public void actualReverseAndResetAreNotTrappedByOldDirection() {
        for (int sign : new int[]{1, -1}) {
            EventScrollTrajectory trajectory = replay(sign, 5);
            float exact = sign * (5 + 414 + 617 + 475 + 485 + 282 - 70);
            trajectory.measure(exact, -sign * 70, 1250, 1255, 2992);
            assertEquals(exact, trajectory.position(1300), .001f);
            trajectory.reset(12, 0, 1300);
            assertEquals(12, trajectory.position(1301), 0);
            assertFalse(trajectory.isAnimating(1301));
        }
    }

    @Test public void fasterContinuationCanCatchTheHeldDisplayWithoutTeleporting() {
        for (int sign : new int[]{1, -1}) {
            EventScrollTrajectory trajectory = replay(sign, 5);
            float before = trajectory.position(1260);
            float exact = sign * (5 + 414 + 617 + 475 + 485 + 282 + 400);
            trajectory.measure(exact, sign * 400, 1253, 1260, 2992);
            assertEquals(before, trajectory.position(1260), .001f);
            assertTrue(sign * (trajectory.position(1276) - before) > 0);
            assertEquals(exact, trajectory.position(2000), .001f);
        }
    }

    @Test public void newGestureAfterLongGapDoesNotInheritOldHold() {
        EventScrollTrajectory trajectory = replay(1, 5);
        trajectory.measure(50, 10, 1550, 1555, 2992);
        assertEquals(50, trajectory.position(1555), .001f);
        assertTrue(trajectory.predictionAmplitude() <= 2.501f);
        assertEquals(50, trajectory.position(2000), .001f);
    }

    @Test public void holdingRemainsOrderIndependentAndEventuallyStopsScheduling() {
        EventScrollTrajectory trajectory = replay(1, 5);
        float position = trajectory.position(1230);
        float velocity = trajectory.velocity(1230);
        trajectory.position(1400);
        assertEquals(position, trajectory.position(1230), 0);
        assertEquals(velocity, trajectory.velocity(1230), 0);
        assertTrue(trajectory.isAnimating(1230));
        assertFalse(trajectory.isAnimating(2000));
        assertEquals(0, trajectory.velocity(2000), .001f);
    }
}
