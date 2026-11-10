package com.subhub.app.overlay;

import android.content.Context;
import android.os.SystemClock;
import android.view.ViewConfiguration;
import android.widget.OverScroller;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class AndroidSplineScrollAndroidTest {
    @Test public void frameworkFinalDistanceMatchesStaticPhysicsOnThisDevice() {
        Context context = ApplicationProvider.getApplicationContext();
        double coefficient = 9.80665 * 39.37 * context.getResources().getDisplayMetrics().density
                * 160 * .84 * ViewConfiguration.getScrollFriction();
        double rate = Math.log(.78) / Math.log(.9);
        for (int velocity : new int[]{-10000, -5000, -1000, 1000, 5000, 10000}) {
            OverScroller actual = new OverScroller(context);
            actual.fling(0, 0, 0, velocity, Integer.MIN_VALUE, Integer.MAX_VALUE,
                    Integer.MIN_VALUE, Integer.MAX_VALUE);
            double log = Math.log(.35 * Math.abs(velocity) / coefficient);
            int expected = (int) (Math.signum(velocity) * coefficient
                    * Math.exp(rate / (rate - 1) * log));
            assertEquals("public framework distance at velocity " + velocity, expected, actual.getFinalY(), 1);
        }
    }

    @Test public void frameworkIntermediatePositionsMatchTheFixedSplineTable() {
        Context context = ApplicationProvider.getApplicationContext();
        double coefficient = 9.80665 * 39.37 * context.getResources().getDisplayMetrics().density
                * 160 * .84 * ViewConfiguration.getScrollFriction();
        double rate = Math.log(.78) / Math.log(.9);
        double log = Math.log(.35 * 5000 / coefficient);
        int duration = (int) (1000 * Math.exp(log / (rate - 1)));
        int distance = (int) (coefficient * Math.exp(rate / (rate - 1) * log));
        OverScroller actual = new OverScroller(context);
        long startLower = SystemClock.uptimeMillis();
        actual.fling(0, 0, 0, 5000, Integer.MIN_VALUE, Integer.MAX_VALUE,
                Integer.MIN_VALUE, Integer.MAX_VALUE);
        long startUpper = SystemClock.uptimeMillis();
        int checked = 0;
        for (int sample = 0; sample < 24; sample++) {
            long lower = SystemClock.uptimeMillis();
            actual.computeScrollOffset();
            long upper = SystemClock.uptimeMillis();
            float minimum = distance * AndroidSplineScroll.fraction((lower - startUpper) / (float) duration);
            float maximum = distance * AndroidSplineScroll.fraction((upper - startLower) / (float) duration);
            assertTrue("framework below time-bound spline", actual.getCurrY() >= minimum - 2);
            assertTrue("framework above time-bound spline", actual.getCurrY() <= maximum + 2);
            checked++;
            SystemClock.sleep(16);
        }
        assertEquals(24, checked);
    }
}
