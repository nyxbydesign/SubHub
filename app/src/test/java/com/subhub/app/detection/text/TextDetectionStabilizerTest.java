package com.subhub.app.detection.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public final class TextDetectionStabilizerTest {
    @Test public void oneScanFalsePositiveNeverPublishes() {
        TextDetectionStabilizer stabilizer = new TextDetectionStabilizer();
        assertTrue(stabilizer.update(Collections.singletonList(text("line", 10))).isEmpty());
        assertTrue(stabilizer.update(Collections.emptyList()).isEmpty());
    }

    @Test public void repeatedAnchorPublishesAndBridgesOneMiss() {
        TextDetectionStabilizer stabilizer = new TextDetectionStabilizer();
        stabilizer.update(Collections.singletonList(text("line", 10)));
        assertEquals(1, stabilizer.update(
                Collections.singletonList(text("line", 60))).size());
        assertEquals(1, stabilizer.update(Collections.emptyList()).size());
        assertTrue(stabilizer.update(Collections.emptyList()).isEmpty());
    }

    @Test public void postScrollReconciliationDoesNotRetainMissingConfirmedLine() {
        TextDetectionStabilizer stabilizer = new TextDetectionStabilizer();
        stabilizer.update(Collections.singletonList(text("line", 10)), false);
        assertEquals(1, stabilizer.update(
                Collections.singletonList(text("line", 60)), false).size());

        assertTrue(stabilizer.update(Collections.emptyList(), false).isEmpty());
    }

    @Test public void completeScanKeepsKnownAnchorAndStagesOnlyNewAnchor() {
        TextDetectionStabilizer stabilizer = new TextDetectionStabilizer();
        stabilizer.update(Collections.singletonList(text("known", 10)), false);
        stabilizer.update(Collections.singletonList(text("known", 20)), false);

        TextDetectionStabilizer.UpdateResult result = stabilizer.updateWithMetrics(
                Arrays.asList(text("known", 70), text("new", 300)), false);

        assertEquals(1, result.getStableDetections().size());
        assertEquals("known", result.getStableDetections().get(0).getAnchorKey());
        assertEquals(1, result.getPendingCandidates());
        assertEquals(1, result.getConfirmedPresent());
    }

    @Test public void targetedRefreshPromotesPendingWithoutDroppingKnownScene() {
        TextDetectionStabilizer stabilizer = new TextDetectionStabilizer();
        stabilizer.update(Collections.singletonList(text("known", 10)), false);
        stabilizer.update(Collections.singletonList(text("known", 20)), false);
        stabilizer.updateWithMetrics(
                Arrays.asList(text("known", 70), text("new", 300)), false);

        TextDetectionStabilizer.UpdateResult result = stabilizer.confirmSubset(
                Collections.singletonList(text("new", 305)));

        assertEquals(2, result.getStableDetections().size());
        assertEquals(0, result.getPendingCandidates());
        assertEquals(1, result.getNewlyConfirmedCandidates());
    }

    @Test public void targetedRefreshDoesNotRepositionAlreadyConfirmedAnchor() {
        TextDetectionStabilizer stabilizer = new TextDetectionStabilizer();
        stabilizer.update(Collections.singletonList(text("known", 10)), false);
        stabilizer.update(Collections.singletonList(text("known", 20)), false);
        stabilizer.updateWithMetrics(
                Arrays.asList(text("known", 70), text("new", 300)), false);

        TextDetectionStabilizer.UpdateResult result = stabilizer.confirmSubset(
                Arrays.asList(text("known", 900), text("new", 305)));

        Detection known = result.getStableDetections().stream()
                .filter(value -> "known".equals(value.getAnchorKey()))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals(70, known.getBox().getY());
    }

    private static Detection text(String anchor, int top) {
        return new Detection("TEXT_SMUT_ACCESSIBILITY_EXPLICIT", "text_smut", 0.9f,
                new BBox(20, top, 300, 40), true, false,
                Detection.ObservationSource.ACCESSIBILITY,
                Detection.GeometryQuality.EXACT, anchor);
    }
}
