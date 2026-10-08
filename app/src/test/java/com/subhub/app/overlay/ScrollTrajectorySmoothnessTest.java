package com.subhub.app.overlay;

import org.junit.Test;
import static org.junit.Assert.*;

/** Frame-rate motion checks: smoothness must not be bought by extra tracking lag. */
public final class ScrollTrajectorySmoothnessTest {
    @Test public void oneShortScrollDoesNotReceiveTheEstablishedStreamForecast() {
        ViewportMotion motion = new ViewportMotion();
        motion.reset(0, 0, 1000);
        motion.addDelta(0, -20, 1100, 100, 100, true, 1100);
        assertEquals(-20, motion.position(1100).y, .001f);
        assertTrue(Math.abs(motion.predictionAmplitude().y) <= 5.001f);
        assertTrue(motion.position(1200).y >= -25.001f);
        assertEquals(-20, motion.position(1500).y, .001f);
    }

    @Test public void irregularDeliveryMatrixDoesNotTradeJumpsForLongLag() {
        int[] delays = {0, 20, 4, 16, 8};
        for (int interval : new int[]{80, 120, 160}) {
            for (float speed : new float[]{.5f, 2f, 4f}) {
                for (int frame : new int[]{8, 16}) {
                    for (boolean jitter : new boolean[]{false, true}) {
                        ViewportMotion motion = new ViewportMotion();
                        motion.reset(0, 0, 1000);
                        int next = 1, frames = 0;
                        float previous = 0, maximumStep = 0;
                        double error = 0;
                        for (int elapsed = 1; elapsed <= 4000; elapsed++) {
                            int source = next * interval;
                            int delay = jitter ? delays[next % delays.length] : 0;
                            if (elapsed == source + delay) {
                                float before = motion.position(1000 + elapsed).y;
                                motion.addDelta(0, -speed * interval, 1000 + elapsed,
                                        1344, 2992, true, 1000 + source);
                                if (elapsed > 600) assertEquals(before,
                                        motion.position(1000 + elapsed).y, .001f);
                                next++;
                            }
                            if (elapsed % frame != 0) continue;
                            float position = motion.position(1000 + elapsed).y;
                            if (elapsed > 600) {
                                assertTrue("same-direction stream must not bounce backward",
                                        position <= previous + .1f);
                                maximumStep = Math.max(maximumStep, Math.abs(position - previous));
                                error += Math.abs(position + speed * elapsed);
                                frames++;
                            }
                            previous = position;
                        }
                        assertTrue("frame burst at interval=" + interval + " speed=" + speed,
                                maximumStep < speed * frame * 2.5f);
                        assertTrue("average phase lag must stay below 20ms of travel",
                                error / frames < speed * 20);
                    }
                }
            }
        }
    }

    @Test public void continuingMeasurementPreservesVelocityAsWellAsPosition() {
        EventScrollTrajectory motion = new EventScrollTrajectory();
        motion.reset(0, 0, 1000);
        motion.measure(-240, -240, 1120, 1120, 2992);
        float position = motion.position(1237), velocity = motion.velocity(1237);
        motion.measure(-474, -234, 1237, 1237, 2992);
        assertEquals(position, motion.position(1237), .001f);
        assertEquals(velocity, motion.velocity(1237), .001f);
        assertEquals(-474, motion.position(2000), .001f);
    }

    @Test public void repeatedScrollMeasurementsDoNotTeleportPresentation() {
        ViewportMotion motion = new ViewportMotion();
        motion.reset(0, 0, 1000);
        for (int sample = 1; sample <= 12; sample++) {
            long now = 1000 + sample * 120;
            float before = motion.position(now).y;
            motion.addDelta(0, -240, now, 1344, 2992, true, now);
            if (sample > 1) assertEquals("position continuity at sample " + sample,
                    before, motion.position(now).y, .001f);
        }
    }

    @Test public void steadyScrollStaysSmoothWithoutFallingBehindAt60And120Hz() {
        for (int frame : new int[]{8, 16}) {
            ViewportMotion motion = new ViewportMotion();
            motion.reset(0, 0, 1000);
            float previous = 0;
            float maximumFrameStep = 0;
            double phaseError = 0;
            int frames = 0;
            for (int elapsed = 1; elapsed <= 2400; elapsed++) {
                long now = 1000 + elapsed;
                if (elapsed % 120 == 0) {
                    motion.addDelta(0, -240, now, 1344, 2992, true, now);
                }
                if (elapsed % frame != 0) continue;
                float current = motion.position(now).y;
                if (elapsed > 480) {
                    assertTrue("no periodic backward motion", current <= previous + .01f);
                    maximumFrameStep = Math.max(maximumFrameStep, Math.abs(current - previous));
                    phaseError += Math.abs(current + 2f * elapsed);
                    frames++;
                }
                previous = current;
            }
            assertTrue("worst frame step=" + maximumFrameStep,
                    maximumFrameStep <= 2f * frame * 1.5f);
            assertTrue("mean tracking error=" + phaseError / frames, phaseError / frames < 12);
            assertEquals(-4800, motion.position(4100).y, .001f);
            assertFalse(motion.isAnimating(4100));
        }
    }
}
