package com.subhub.app.detection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;

public final class VisualDetectionStabilizerTest {
    private final DetectorConfig config = DetectorConfig.builder()
            .confidenceThreshold(0.20f)
            .build();

    @Test
    public void highConfidenceSettledDetectionPublishesImmediately() {
        VisualDetectionStabilizer stabilizer = new VisualDetectionStabilizer();
        assertEquals(1, stabilizer.update(Collections.singletonList(
                detection(0.90f, 10)), config).size());
    }

    @Test
    public void marginalSingleFrameCandidateNeverFlashes() {
        VisualDetectionStabilizer stabilizer = new VisualDetectionStabilizer();
        VisualDetectionStabilizer.UpdateResult first = stabilizer.updateWithMetrics(
                Collections.singletonList(detection(0.25f, 10)), config);
        assertTrue(first.stableDetections().isEmpty());
        assertEquals(1, first.pendingCandidates());
        assertTrue(stabilizer.update(Collections.emptyList(), config).isEmpty());
    }

    @Test
    public void confirmationClearsPendingCandidateCount() {
        VisualDetectionStabilizer stabilizer = new VisualDetectionStabilizer();
        stabilizer.updateWithMetrics(
                Collections.singletonList(detection(0.25f, 10)), config);

        VisualDetectionStabilizer.UpdateResult confirmed = stabilizer.updateWithMetrics(
                Collections.singletonList(detection(0.26f, 12)), config);

        assertEquals(1, confirmed.stableDetections().size());
        assertEquals(0, confirmed.pendingCandidates());
    }

    @Test
    public void repeatedMarginalCandidateIsConfirmedAndBridgedAcrossOneMiss() {
        VisualDetectionStabilizer stabilizer = new VisualDetectionStabilizer();
        stabilizer.update(Collections.singletonList(detection(0.25f, 10)), config);
        assertEquals(1, stabilizer.update(Collections.singletonList(
                detection(0.26f, 12)), config).size());
        assertEquals(1, stabilizer.update(Collections.emptyList(), config).size());
        assertTrue(stabilizer.update(Collections.emptyList(), config).isEmpty());
    }

    @Test
    public void adjacentLabelFlickerCanStillConfirmTheSameRegion() {
        VisualDetectionStabilizer stabilizer = new VisualDetectionStabilizer();
        stabilizer.update(Collections.singletonList(detection(0.25f, 10)), config);
        Detection relabeled = new Detection("FEMALE_BREAST_COVERED", "breasts_covered",
                0.25f, new BBox(12, 20, 100, 120), true, false);
        assertEquals(1, stabilizer.update(
                Collections.singletonList(relabeled), config).size());
    }

    private static Detection detection(float confidence, int x) {
        return new Detection("FEMALE_BREAST_EXPOSED", "breasts", confidence,
                new BBox(x, 20, 100, 120), true, true);
    }
}
