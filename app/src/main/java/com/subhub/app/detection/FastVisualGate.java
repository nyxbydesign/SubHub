package com.subhub.app.detection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * High-precision gate for the low-resolution real-time detector lane.
 *
 * <p>The settled quality pass keeps the user's configured recall. The fast lane is allowed to
 * publish immediately, so it deliberately requires stronger evidence to avoid turning marginal
 * single-frame candidates into flashes.</p>
 */
public final class FastVisualGate {
    private FastVisualGate() {}

    public static List<Detection> filter(
            List<Detection> detections, DetectorConfig qualityConfig) {
        if (detections == null || detections.isEmpty()) return Collections.emptyList();
        List<Detection> accepted = new ArrayList<>(detections.size());
        for (Detection detection : detections) {
            if (detection != null
                    && detection.getConfidence() >= confidenceFloor(
                            detection.getClassName(), qualityConfig)) {
                accepted.add(detection);
            }
        }
        return accepted;
    }

    static float confidenceFloor(String className, DetectorConfig qualityConfig) {
        float configured = qualityConfig == null
                ? 0.30f : qualityConfig.getConfidenceThreshold();
        float normal = Math.max(0.32f, configured + 0.12f);
        if (className == null) return normal;
        switch (className) {
            case "FACE_FEMALE":
            case "FACE_MALE":
                return Math.max(0.25f, configured);
            case "ANUS_EXPOSED":
            case "FEMALE_GENITALIA_EXPOSED":
            case "MALE_GENITALIA_EXPOSED":
            case "BUTTOCKS_EXPOSED":
            case "FEMALE_BREAST_EXPOSED":
                return Math.max(0.45f, normal);
            case "MALE_BREAST_EXPOSED":
                return Math.max(0.65f, normal);
            case "FEET_EXPOSED":
            case "FEET_COVERED":
            case "ARMPITS_EXPOSED":
            case "ARMPITS_COVERED":
                return Math.max(0.35f, normal);
            default:
                return normal;
        }
    }
}
