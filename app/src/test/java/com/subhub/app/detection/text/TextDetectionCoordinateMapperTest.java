package com.subhub.app.detection.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

public final class TextDetectionCoordinateMapperTest {
    @Test public void windowCaptureScaleRoundTripsToOriginalScreenPosition() {
        Detection screenDetection = new Detection(
                "TEXT_SMUT_FETISH", "text_smut", 0.9f,
                new BBox(80, 1200, 920, 64), true, false);

        List<Detection> mapped = TextDetectionCoordinateMapper.screenToCapture(
                Collections.singletonList(screenDetection), 1080, 2400, 1080, 2268);

        BBox capture = mapped.get(0).getBox();
        assertEquals(1134, capture.getY());
        assertEquals(60, capture.getHeight());
        assertEquals(1200, Math.round(capture.getY() * (2400f / 2268f)));
        assertEquals(1263, Math.round(capture.getBottom() * (2400f / 2268f)));
    }

    @Test public void fullDisplayCapturePreservesTextBounds() {
        BBox original = new BBox(80, 900, 920, 64);
        Detection detection = new Detection(
                "TEXT_SMUT_EXPLICIT", "text_smut", 0.9f, original, true, false);

        List<Detection> mapped = TextDetectionCoordinateMapper.screenToCapture(
                Collections.singletonList(detection), 1080, 2400, 1080, 2400);

        assertEquals(original, mapped.get(0).getBox());
    }

    @Test public void mappingPreservesAccessibilityIdentityAndGeometryAuthority() {
        Detection detection = new Detection(
                "TEXT_SMUT_ACCESSIBILITY_EXPLICIT", "text_smut", 0.91f,
                new BBox(50, 500, 600, 80), true, false,
                Detection.ObservationSource.ACCESSIBILITY,
                Detection.GeometryQuality.EXACT,
                "a11y:id:caption-42");

        Detection mapped = TextDetectionCoordinateMapper.screenToCapture(
                Collections.singletonList(detection), 1080, 2400, 540, 1200).get(0);

        assertSame(Detection.ObservationSource.ACCESSIBILITY, mapped.getSource());
        assertSame(Detection.GeometryQuality.EXACT, mapped.getGeometryQuality());
        assertEquals("a11y:id:caption-42", mapped.getAnchorKey());
    }
}
