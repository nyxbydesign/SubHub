package com.subhub.app.overlay;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AndroidSplineScrollTest {
    @Test public void fixedAospCurveIsMonotoneAndHasExactEndpoints() {
        assertEquals(0, AndroidSplineScroll.fraction(-1), 0);
        assertEquals(1, AndroidSplineScroll.fraction(2), 0);
        float previous = 0;
        for (int i = 0; i <= 1000; i++) {
            float current = AndroidSplineScroll.fraction(i / 1000f);
            assertTrue(current >= previous);
            previous = current;
        }
        assertEquals(.858, AndroidSplineScroll.fraction(.5f), .003f);
    }

    @Test public void reconstructsKnownFlingFromHistoricalIntervals() {
        float density = 3, friction = .015f, velocity = 5000;
        double rate = Math.log(.78) / Math.log(.9);
        double coefficient = 9.80665 * 39.37 * density * 160 * .84 * friction;
        double logarithm = Math.log(.35 * velocity / coefficient);
        float duration = (float) (1000 * Math.exp(logarithm / (rate - 1)));
        float distance = (float) (coefficient * Math.exp(rate / (rate - 1) * logarithm));
        for (int sign : new int[]{-1, 1}) {
            for (float end : new float[]{300, 500, 700}) {
                float a = distance * (AndroidSplineScroll.fraction((end - 110) / duration)
                        - AndroidSplineScroll.fraction((end - 210) / duration));
                float b = distance * (AndroidSplineScroll.fraction(end / duration)
                        - AndroidSplineScroll.fraction((end - 110) / duration));
                AndroidSplineScroll curve = new AndroidSplineScroll(density, friction);
                assertTrue("identification at " + end, curve.identify(sign * a, 100, sign * b, 110));
                for (float horizon : new float[]{0, 20, 60, 110}) {
                    float expected = sign * distance * (AndroidSplineScroll.fraction((end + horizon) / duration)
                            - AndroidSplineScroll.fraction(end / duration));
                    assertEquals("forecast at " + end + "/" + horizon, expected, curve.ahead(horizon), 3);
                }
            }
        }
    }

    @Test public void dragAccelerationReversalAndInvalidTimingAreNotFlings() {
        AndroidSplineScroll curve = new AndroidSplineScroll(3, .015f);
        assertFalse(curve.identify(200, 100, 200, 100));
        assertFalse(curve.identify(200, 100, 300, 100));
        assertFalse(curve.identify(200, 100, -100, 100));
        assertFalse(curve.identify(200, 0, 100, 100));
        assertFalse(curve.identify(200, 100, 0, 100));
        assertFalse(curve.identify(200, 100, 100, 300));
    }
}
