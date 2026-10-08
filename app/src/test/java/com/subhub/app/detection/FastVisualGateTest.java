package com.subhub.app.detection;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;

public final class FastVisualGateTest {
    @Test public void fastLanePublishesStrongCandidatesAndRejectsMarginalFlashes() {
        DetectorConfig ultra = DetectorConfig.builder().confidenceThreshold(0.18f).build();
        Detection weak = detection("FEMALE_BREAST_COVERED", 0.29f);
        Detection strong = detection("FEMALE_BREAST_COVERED", 0.61f);

        assertEquals(Arrays.asList(strong),
                FastVisualGate.filter(Arrays.asList(weak, strong), ultra));
    }

    @Test public void sensitiveClassesRequireQualityEvidenceForImmediatePublication() {
        DetectorConfig ultra = DetectorConfig.builder().confidenceThreshold(0.18f).build();
        assertEquals(0.45f,
                FastVisualGate.confidenceFloor("FEMALE_GENITALIA_EXPOSED", ultra), 0.001f);
        assertEquals(0.35f,
                FastVisualGate.confidenceFloor("FEET_EXPOSED", ultra), 0.001f);
        assertEquals(0.65f,
                FastVisualGate.confidenceFloor("MALE_BREAST_EXPOSED", ultra), 0.001f);
    }

    private static Detection detection(String className, float confidence) {
        return new Detection(className, "breasts", confidence,
                new BBox(10, 20, 80, 90), true, true);
    }
}
