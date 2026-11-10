package com.subhub.app.detection;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

/** Pure coordinate contract for dynamic rectangular model inputs. */
public final class RectangularDetectionPostProcessorTest {
    @Test
    public void decodesLetterboxedInputWithOneUniformSourceScale() {
        float[][] output = new float[DetectionPostProcessor.OUTPUT_FEATURES][1];
        output[0][0] = 72f;
        output[1][0] = 160f;
        output[2][0] = 20f;
        output[3][0] = 40f;
        output[4 + 3][0] = 0.80f; // FEMALE_BREAST_EXPOSED

        List<Detection> detections = new DetectionPostProcessor().decode(
                output,
                1080,
                2400,
                256,
                320,
                DetectorConfig.builder().build());

        assertEquals(1, detections.size());
        assertEquals("breasts", detections.get(0).getCategory());
        assertEquals(new BBox(443, 1038, 194, 324), detections.get(0).getBox());
    }
}
