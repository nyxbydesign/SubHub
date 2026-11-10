package com.subhub.app.detection;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.FloatBuffer;
import java.util.Set;
import java.util.List;

public final class DetectionPostProcessorTest {
    @Test
    public void decodesNchwFeatureOutputIntoOriginalFrameCoordinates() {
        float[][] output = outputWithCandidates(1);
        output[0][0] = 160f;
        output[1][0] = 120f;
        output[2][0] = 80f;
        output[3][0] = 100f;
        output[4 + 3][0] = 0.80f; // FEMALE_BREAST_EXPOSED

        List<Detection> detections = new DetectionPostProcessor().decode(
                output, 640, 360, 320, DetectorConfig.builder().build());

        assertEquals(1, detections.size());
        assertEquals("breasts", detections.get(0).getCategory());
        assertEquals(new BBox(234, 132, 172, 216), detections.get(0).getBox());
    }

    @Test
    public void sameCategoryNmsKeepsHigherConfidenceCandidate() {
        float[][] output = outputWithCandidates(2);
        for (int candidate = 0; candidate < 2; candidate++) {
            output[0][candidate] = 100f + candidate;
            output[1][candidate] = 100f + candidate;
            output[2][candidate] = 50f;
            output[3][candidate] = 50f;
            output[4 + 4][candidate] = candidate == 0 ? 0.90f : 0.70f;
        }

        List<Detection> detections = new DetectionPostProcessor().decode(
                output, 320, 320, 320, DetectorConfig.builder().build());

        assertEquals(1, detections.size());
        assertEquals(0.90f, detections.get(0).getConfidence(), 0.0001f);
    }

    @Test
    public void contiguousNativeOutputMatchesNestedArrayDecoder() {
        int candidates = 2;
        float[][] nested = outputWithCandidates(candidates);
        nested[0][0] = 160f;
        nested[1][0] = 120f;
        nested[2][0] = 80f;
        nested[3][0] = 100f;
        nested[7][0] = 0.80f;
        FloatBuffer contiguous = FloatBuffer.allocate(
                DetectionPostProcessor.OUTPUT_FEATURES * candidates);
        for (float[] feature : nested) contiguous.put(feature);
        contiguous.flip();

        DetectionPostProcessor processor = new DetectionPostProcessor();
        List<Detection> expected = processor.decode(
                nested, 640, 360, 320, DetectorConfig.builder().build());
        List<Detection> actual = processor.decode(
                contiguous,
                DetectionPostProcessor.OUTPUT_FEATURES,
                candidates,
                640,
                360,
                320,
                DetectorConfig.builder().build());

        assertEquals(expected.size(), actual.size());
        assertEquals(expected.get(0).getCategory(), actual.get(0).getCategory());
        assertEquals(expected.get(0).getBox(), actual.get(0).getBox());
    }

    @Test
    public void sameEnabledFaceCategoryDoesNotRejectSexLogitAmbiguity() {
        float[][] output = outputWithCandidates(1);
        output[0][0] = 160f;
        output[1][0] = 160f;
        output[2][0] = 80f;
        output[3][0] = 80f;
        output[4 + 1][0] = 0.30f;  // FACE_FEMALE
        output[4 + 12][0] = 0.25f; // FACE_MALE
        DetectorConfig config = DetectorConfig.builder()
                .enabledCategories(Set.of("face"))
                .build();

        List<Detection> detections = new DetectionPostProcessor().decode(
                output, 320, 320, 320, config);

        assertEquals(1, detections.size());
        assertEquals("face", detections.get(0).getCategory());
    }

    @Test
    public void differentEnabledCategoriesStillRejectAmbiguousCandidate() {
        float[][] output = outputWithCandidates(1);
        output[0][0] = 160f;
        output[1][0] = 160f;
        output[2][0] = 80f;
        output[3][0] = 80f;
        output[4 + 3][0] = 0.50f; // FEMALE_BREAST_EXPOSED
        output[4 + 2][0] = 0.45f; // BUTTOCKS_EXPOSED
        DetectorConfig config = DetectorConfig.builder()
                .enabledCategories(Set.of("breasts", "buttocks"))
                .build();

        assertEquals(0, new DetectionPostProcessor().decode(
                output, 320, 320, 320, config).size());
    }

    @Test
    public void faceAmbiguityAcceptsEitherWinningLabelAcrossDecoderLayouts() {
        assertFacePolicy(Set.of("face"), 0.30f, 0.25f, 0.50f, 1);
        assertFacePolicy(Set.of("face"), 0.25f, 0.30f, 0.50f, 1);
        assertFacePolicy(Set.of("face"), 0.30f, 0.30f, 0.50f, 1);
    }

    @Test
    public void sexSpecificFacePolicyStillRejectsAmbiguousLabels() {
        for (Set<String> enabled : List.of(Set.of("face_female"), Set.of("face_male"),
                Set.of("face_female", "face_male"))) {
            assertFacePolicy(enabled, 0.30f, 0.25f, 0.50f, 0);
            assertFacePolicy(enabled, 0.25f, 0.30f, 0.50f, 0);
        }
    }

    @Test
    public void unambiguousSexSpecificFaceRemainsAccepted() {
        assertFacePolicy(Set.of("face_female"), 0.50f, 0.20f, 0.50f, 1);
        assertFacePolicy(Set.of("face_male"), 0.20f, 0.50f, 0.50f, 1);
    }

    @Test
    public void sameCategoryAmbiguityDoesNotBypassConfidenceFloor() {
        assertFacePolicy(Set.of("face"), 0.30f, 0.25f, 0.80f, 0);
        assertFacePolicy(Set.of("face"), 0.24f, 0.20f, 0.50f, 0);
    }

    @Test
    public void disabledFaceCategoriesRemainDisabled() {
        assertFacePolicy(Set.of("breasts"), 0.30f, 0.25f, 0.50f, 0);
        assertFacePolicy(Set.of("breasts"), 0.25f, 0.30f, 0.50f, 0);
    }

    private static void assertFacePolicy(Set<String> enabled, float femaleScore,
            float maleScore, float threshold, int expectedCount) {
        float[][] output = outputWithCandidates(1);
        output[0][0] = 160f;
        output[1][0] = 160f;
        output[2][0] = 80f;
        output[3][0] = 80f;
        output[5][0] = femaleScore;
        output[16][0] = maleScore;
        DetectorConfig config = DetectorConfig.builder().enabledCategories(enabled)
                .confidenceThreshold(threshold).build();
        DetectionPostProcessor processor = new DetectionPostProcessor();
        // Nonzero position catches accidental absolute indexing in the native-buffer path.
        FloatBuffer buffer = FloatBuffer.allocate(DetectionPostProcessor.OUTPUT_FEATURES + 1);
        buffer.put(-123f);
        for (float[] feature : output) buffer.put(feature[0]);
        buffer.flip();
        buffer.position(1);
        List<List<Detection>> results = List.of(
                processor.decode(output, 320, 320, 320, config),
                processor.decode(output, 320, 320, 320, 320, config),
                processor.decode(buffer, DetectionPostProcessor.OUTPUT_FEATURES,
                        1, 320, 320, 320, config),
                processor.decode(buffer, DetectionPostProcessor.OUTPUT_FEATURES,
                        1, 320, 320, 320, 320, config));
        for (List<Detection> result : results) {
            assertEquals(expectedCount, result.size());
            if (expectedCount > 0) {
                assertEquals(Math.max(femaleScore, maleScore),
                        result.get(0).getConfidence(), 0.0001f);
                assertEquals(enabled.contains("face") ? "face"
                        : femaleScore > maleScore ? "face_female" : "face_male",
                        result.get(0).getCategory());
            }
        }
        assertEquals(1, buffer.position());
    }

    private static float[][] outputWithCandidates(int count) {
        float[][] output = new float[DetectionPostProcessor.OUTPUT_FEATURES][count];
        return output;
    }
}
