package com.subhub.app.detection.text;

import static org.junit.Assert.assertEquals;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class DetectionFusionTest {
    @Test public void realtimeGeometryWinsOverMatchingQualityRefinement() {
        Detection realtime = new Detection("FAST", "EXPOSED", 0.55f,
                new BBox(100, 200, 180, 220), true, true);
        Detection quality = new Detection("QUALITY", "EXPOSED", 0.92f,
                new BBox(80, 170, 240, 290), true, true,
                Detection.ObservationSource.QUALITY_VISUAL,
                Detection.GeometryQuality.MODEL, null);

        List<Detection> merged = DetectionFusion.mergeVisualRefinement(
                Collections.singletonList(realtime), Collections.singletonList(quality));

        assertEquals(1, merged.size());
        assertEquals(realtime.getBox(), merged.get(0).getBox());
        assertEquals(0.92f, merged.get(0).getConfidence(), 0f);
        assertEquals(Detection.ObservationSource.VISUAL, merged.get(0).getSource());
    }

    @Test public void unmatchedQualityRefinementRemainsCoverageCandidate() {
        Detection realtime = new Detection("FAST", "EXPOSED", 0.55f,
                new BBox(20, 20, 80, 80), true, true);
        Detection quality = new Detection("QUALITY", "EXPOSED", 0.92f,
                new BBox(500, 600, 160, 180), true, true,
                Detection.ObservationSource.QUALITY_VISUAL,
                Detection.GeometryQuality.MODEL, null);

        List<Detection> merged = DetectionFusion.mergeVisualRefinement(
                Collections.singletonList(realtime), Collections.singletonList(quality));

        assertEquals(2, merged.size());
        assertEquals(Detection.ObservationSource.QUALITY_VISUAL, merged.get(1).getSource());
    }

    @Test public void overlappingAccessibilityAndVisualTextBecomeOneRegion() {
        Detection accessibility = text(new BBox(100, 200, 300, 80));
        Detection visualText = text(new BBox(105, 205, 290, 70));
        List<Detection> merged = DetectionFusion.merge(
                Collections.emptyList(),
                Collections.singletonList(accessibility),
                Collections.singletonList(visualText));
        assertEquals(1, merged.size());
        assertEquals(new BBox(100, 200, 300, 80), merged.get(0).getBox());
    }

    @Test public void adjacentExplicitLinesRemainSeparateRenderedBars() {
        Detection first = text(new BBox(80, 300, 500, 48));
        Detection second = text(new BBox(90, 354, 480, 48));
        List<Detection> merged = DetectionFusion.merge(
                Collections.emptyList(), Arrays.asList(first, second));
        assertEquals(2, merged.size());
        assertEquals(first.getBox(), merged.get(0).getBox());
        assertEquals(second.getBox(), merged.get(1).getBox());
    }

    @Test public void distantPostsRemainSeparate() {
        List<Detection> merged = DetectionFusion.merge(
                Collections.emptyList(),
                Arrays.asList(
                        text(new BBox(80, 300, 500, 48)),
                        text(new BBox(80, 700, 500, 48))));
        assertEquals(2, merged.size());
    }

    @Test public void coarseParentDoesNotEnlargeSpecificChildLine() {
        Detection parent = text(new BBox(60, 500, 960, 620));
        Detection line = text(new BBox(80, 610, 900, 58));
        List<Detection> merged = DetectionFusion.merge(
                Collections.emptyList(), Arrays.asList(parent, line));

        assertEquals(1, merged.size());
        assertEquals(line.getBox(), merged.get(0).getBox());
    }

    @Test public void bridgeRegionCannotCollapseSeparateLinesIntoOneGroup() {
        Detection upper = text(new BBox(80, 300, 500, 60));
        Detection lower = text(new BBox(80, 390, 500, 60));
        Detection bridge = text(new BBox(80, 340, 500, 70));

        List<Detection> merged = DetectionFusion.merge(
                Collections.emptyList(), Arrays.asList(upper, lower, bridge));

        assertEquals(2, merged.size());
        assertEquals(upper.getBox(), merged.get(0).getBox());
        assertEquals(lower.getBox(), merged.get(1).getBox());
    }

    @Test public void differentSemanticAnchorsNeverFuseDespiteOverlap() {
        Detection first = text(new BBox(80, 300, 500, 60)).withObservation(
                Detection.ObservationSource.ACCESSIBILITY,
                Detection.GeometryQuality.EXACT,
                "post-1:line-1");
        Detection second = text(new BBox(80, 320, 500, 60)).withObservation(
                Detection.ObservationSource.ACCESSIBILITY,
                Detection.GeometryQuality.EXACT,
                "post-1:line-2");

        List<Detection> merged = DetectionFusion.merge(
                Collections.emptyList(), Arrays.asList(first, second));

        assertEquals(2, merged.size());
    }

    @Test public void preciseOcrLineReplacesNearbyAccessibilityEstimate() {
        Detection accessibility = new Detection("TEXT_SMUT_ACCESSIBILITY_EXPLICIT",
                "text_smut", 0.9f, new BBox(60, 220, 500, 50), true, false);
        Detection ocr = new Detection("TEXT_SMUT_OCR_EXPLICIT",
                "text_smut", 0.9f, new BBox(76, 190, 470, 42), true, false);

        List<Detection> merged = DetectionFusion.merge(Collections.emptyList(),
                Collections.singletonList(accessibility), Collections.singletonList(ocr));

        assertEquals(1, merged.size());
        assertEquals(ocr.getBox(), merged.get(0).getBox());
    }

    private static Detection text(BBox box) {
        return new Detection("TEXT_SMUT_EXPLICIT", "text_smut", 0.9f, box, true, false);
    }
}
