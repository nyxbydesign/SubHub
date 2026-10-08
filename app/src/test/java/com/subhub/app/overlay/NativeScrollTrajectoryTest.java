package com.subhub.app.overlay;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeScrollTrajectoryTest {
    private NativeScrollTrajectory steady(int delay) {
        NativeScrollTrajectory result = new NativeScrollTrajectory(3, .015f);
        result.reset(0, 1000);
        for (int i = 1; i <= 6; i++) result.measure(i * 200, 200, 1000 + i * 100,
                1000 + i * 100 + delay, 2400);
        return result;
    }
    @Test public void acquisitionDoesNotInventAnIntervalOrFling() {
        NativeScrollTrajectory t = new NativeScrollTrajectory(3, .015f);
        t.reset(0, 1000);
        t.measure(200, 200, 1100, 1120, 2400);
        assertEquals(200, t.position(1120), .001f);
        assertEquals(200, t.position(1200), .001f);
        assertFalse(t.isAnimating(1200));
    }
    @Test public void deliveryDelayConsumesForecastRatherThanRestartingIt() {
        NativeScrollTrajectory timely = steady(0), delayed = steady(40);
        assertEquals("same physical clock after correction", timely.position(1700), delayed.position(1700), 1);
        assertEquals(1200, delayed.position(2000), .001f);
        assertFalse(delayed.isAnimating(2000));
    }
    @Test public void continuingCallbacksHaveNoPositionTeleport() {
        NativeScrollTrajectory t = steady(10);
        float before = t.position(1710);
        t.measure(1400, 200, 1700, 1710, 2400);
        assertEquals(before, t.position(1710), .001f);
        assertFalse(t.usesSpline());
    }
    @Test public void lateAndOutOfOrderRecordsCannotRestartPrediction() {
        NativeScrollTrajectory t = steady(0);
        t.measure(1400, 200, 1700, 2000, 2400);
        assertEquals(1400, t.position(2200), .001f);
        assertFalse(t.isAnimating(2200));
        float before = t.position(2200);
        t.measure(1420, 20, 1650, 2200, 2400);
        assertEquals(before, t.position(2200), .001f);
        assertEquals(1420, t.position(2250), .001f);
        assertEquals(0, t.predictionAmplitude(), 0);
        assertFalse(t.isAnimating(2250));
    }
    @Test public void explicitStopAndReverseAreBoundedAndResetClearsState() {
        NativeScrollTrajectory t = steady(0);
        t.measure(1200, 0, 1700, 1710, 2400);
        assertEquals(1200, t.position(1750), .001f);
        assertFalse(t.isAnimating(1750));
        t.measure(1150, -50, 1800, 1810, 2400);
        assertEquals(1150, t.position(1850), .001f);
        t.reset(8, 1900);
        assertEquals(8, t.position(1901), 0);
        assertFalse(t.isAnimating(1901));
    }
    @Test public void viewportBoundsAndQueryOrderArePreserved() {
        NativeScrollTrajectory t = steady(0);
        assertTrue(Math.abs(t.predictionAmplitude()) <= 1080);
        float value = t.position(1660);
        t.position(3000);
        assertEquals(value, t.position(1660), 0);
        assertEquals(1200, t.position(3000), .001f);
    }
    @Test public void recordedSlowdownDoesNotReverseDuringFreshForwardSamples() {
        int[] source = {657, 753, 871, 986, 1088, 1203, 1304, 1420, 1538, 1654, 1755};
        int[] delivered = {678, 769, 881, 1000, 1101, 1215, 1315, 1432, 1547, 1665, 1767};
        int[] delta = {5, 414, 617, 475, 485, 282, 220, 140, 83, 40, 15};
        for (int sign : new int[]{-1, 1}) {
            NativeScrollTrajectory t = new NativeScrollTrajectory(3, .015f);
            t.reset(0, 0);
            float exact = 0;
            for (int i = 0; i < source.length; i++) {
                float before = t.position(delivered[i]);
                exact += sign * delta[i];
                t.measure(exact, sign * delta[i], source[i], delivered[i], 2992);
                if (i >= 2) assertEquals(before, t.position(delivered[i]), .001f);
                int end = i + 1 < source.length ? delivered[i + 1] : delivered[i] + 96;
                float previous = t.position(delivered[i]);
                if (i >= 4) for (int time = delivered[i] + 8; time < end; time += 8) {
                    float current = t.position(time);
                    assertTrue("reversed sample " + i + " at " + time, sign * (current - previous) >= -.01f);
                    previous = current;
                }
            }
            assertEquals(exact, t.position(2500), .001f);
        }
    }
    @Test public void nativeStrategyRoutesThroughViewportMotionWithoutChangingCameraAuthority() {
        ViewportMotion t = new ViewportMotion();
        t.reset(0, 0, 1000);
        t.setNativeSpline(true, 3, .015f, 1000);
        t.addDelta(0, 200, 1120, 1080, 2400, true, 1100);
        assertEquals(200, t.position(1120).y, .001f);
        t.setNativeSpline(false, 3, .015f, 1121);
        assertEquals(200, t.position(1121).y, .001f);
    }
    @Test public void splineNeedsAnIndependentThirdIntervalAndThenTracksItsPhysicalContinuation() {
        NativeScrollTrajectory t = new NativeScrollTrajectory(3, .015f);
        t.reset(0, 0);
        double rate = Math.log(.78) / Math.log(.9);
        double coefficient = 9.80665 * 39.37 * 3 * 160 * .84 * .015;
        double logarithm = Math.log(.35 * 5000 / coefficient);
        float duration = (float) (1000 * Math.exp(logarithm / (rate - 1)));
        float distance = (float) (coefficient * Math.exp(rate / (rate - 1) * logarithm));
        float previous = 0;
        for (int time = 100; time <= 600; time += 100) {
            float measured = distance * AndroidSplineScroll.fraction(time / duration);
            t.measure(measured, measured - previous, time, time + 10, 2400);
            if (time <= 300) assertFalse(t.usesSpline());
            if (time >= 400) assertTrue("validated curve at " + time, t.usesSpline());
            previous = measured;
        }
        assertEquals(distance * AndroidSplineScroll.fraction(680 / duration), t.position(680), 4);
        t.measure(previous + 400, 400, 700, 710, 2400);
        assertFalse("acceleration is not a continuation of the old fling", t.usesSpline());
    }
}
