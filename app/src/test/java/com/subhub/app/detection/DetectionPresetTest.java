package com.subhub.app.detection;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class DetectionPresetTest {
    @Test
    public void recoveredPresetValuesAreApplied() {
        DetectorConfig low = DetectionPreset.LOW.applyTo(DetectorConfig.builder()).build();
        DetectorConfig high = DetectionPreset.HIGH.applyTo(DetectorConfig.builder()).build();

        assertEquals(0.30f, low.getConfidenceThreshold(), 0.0001f);
        assertEquals(0, low.getDetectionIntervalMs());
        assertEquals(0.45f, low.getCaptureScale(), 0.0001f);
        assertEquals(320, low.getInferenceResolution());
        assertEquals(0.18f, high.getConfidenceThreshold(), 0.0001f);
        assertEquals(512, high.getInferenceResolution());
        assertEquals(0.75f, high.getCaptureScale(), 0.0001f);
        assertEquals(2, low.getInferenceThreads());
        assertEquals(4, high.getInferenceThreads());
        assertEquals(3, DetectionPreset.values().length);
        assertEquals(.25f, DetectionPreset.MEDIUM.getConfidence(), .0001f);
        assertEquals(480, DetectionPreset.MEDIUM.getInferenceResolution());
        assertTrue(DetectionPreset.LOW.getCustomImageDimension()
                < DetectionPreset.HIGH.getCustomImageDimension());
        assertTrue(DetectionPreset.LOW.getCustomImageCount()
                < DetectionPreset.HIGH.getCustomImageCount());
    }

    @Test
    public void onlyCurrentPreferenceNamesAreAccepted() {
        assertSame(DetectionPreset.HIGH, DetectionPreset.fromPreference("high"));
        assertSame(DetectionPreset.MEDIUM, DetectionPreset.fromPreference("ultra"));
        assertSame(DetectionPreset.MEDIUM, DetectionPreset.fromPreference("unknown"));
        assertEquals("low", DetectionPreset.LOW.preferenceValue());
    }
}
