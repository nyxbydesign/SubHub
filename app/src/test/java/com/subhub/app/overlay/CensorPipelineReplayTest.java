package com.subhub.app.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.ObjectTracker;
import com.subhub.app.detection.TrackedObject;

import org.junit.Test;

import java.util.List;

/** Deterministic display-rate replays for the motion defects this pipeline replaces. */
public final class CensorPipelineReplayTest {
    @Test
    public void tenHertzObservationsProduceBoundedOneTwentyHertzRenderSteps() {
        ObjectTracker tracker = new ObjectTracker(DetectorConfig.builder()
                .trackingSmoothing(1f)
                .velocitySmoothing(1f)
                .maxExtrapolationMs(180f)
                .build());
        tracker.update(List.of(detection(0)), 0L);
        TrackedObject track = tracker.update(List.of(detection(50)), 100_000_000L).get(0);
        RenderTrackSnapshot snapshot = RenderTrackSnapshot.from(track);
        float previous = snapshot.predict(0f, 180f).getCenterX();
        float largestStep = 0f;

        for (int displayFrame = 1; displayFrame <= 60; displayFrame++) {
            float nowMs = 100f + displayFrame * (1_000f / 120f);
            if (displayFrame % 12 == 0) {
                int observedX = Math.round(nowMs * 0.5f);
                track = tracker.update(List.of(detection(observedX)),
                        Math.round(nowMs * 1_000_000f)).get(0);
                snapshot = RenderTrackSnapshot.from(track);
            }
            float ageSinceObservationMs = (displayFrame % 12) * (1_000f / 120f);
            float center = snapshot.predict(ageSinceObservationMs, 180f).getCenterX();
            largestStep = Math.max(largestStep, Math.abs(center - previous));
            previous = center;
        }

        assertTrue("Largest display step was " + largestStep, largestStep <= 6f);
    }

    @Test
    public void scrollReplayConvergesToAuthoritativeContentPositionWithoutDrift() {
        ViewportMotion motion = new ViewportMotion();
        motion.reset(0f, 0f, 0L);
        float previous = 0f;
        for (int event = 1; event <= 12; event++) {
            motion.addDelta(0f, -12f, event * 16L);
            ViewportMotion.Position between = motion.position(event * 16L + 8L);
            assertTrue(between.y < previous);
            previous = between.y;
        }

        assertEquals(-144f, motion.position(500L).y, 0.001f);
        assertTrue(!motion.isAnimating(500L));
    }

    private static Detection detection(int x) {
        return new Detection("FEMALE_BREAST_EXPOSED", "breasts", 0.95f,
                new BBox(x, 100, 80, 80), true, true);
    }
}
