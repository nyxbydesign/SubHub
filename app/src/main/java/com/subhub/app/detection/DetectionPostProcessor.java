package com.subhub.app.detection;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Decodes the model's [1, 22, candidates] output into frame-space detections. */
public final class DetectionPostProcessor {
    public static final int OUTPUT_FEATURES = 4 + NudeNetClassCatalog.CLASS_COUNT;

    public List<Detection> decode(
            float[][] output,
            int frameWidth,
            int frameHeight,
            int inputSize,
            DetectorConfig config) {
        if (output == null || output.length < OUTPUT_FEATURES || output[0] == null
                || frameWidth <= 0 || frameHeight <= 0 || inputSize <= 0) {
            return Collections.emptyList();
        }
        int candidateCount = output[0].length;
        for (int feature = 1; feature < OUTPUT_FEATURES; feature++) {
            if (output[feature] == null || output[feature].length != candidateCount) {
                return Collections.emptyList();
            }
        }
        float frameScale = (float) Math.max(frameWidth, frameHeight) / inputSize;
        return decodeMatrix(
                (feature, candidate) -> output[feature][candidate],
                candidateCount,
                frameWidth,
                frameHeight,
                frameScale,
                frameScale,
                config);
    }

    /** Decodes a rectangular, aspect-preserving letterboxed model input. */
    public List<Detection> decode(
            float[][] output,
            int frameWidth,
            int frameHeight,
            int inputWidth,
            int inputHeight,
            DetectorConfig config) {
        if (output == null || output.length < OUTPUT_FEATURES || output[0] == null
                || frameWidth <= 0 || frameHeight <= 0
                || inputWidth <= 0 || inputHeight <= 0) {
            return Collections.emptyList();
        }

        int candidateCount = output[0].length;
        for (int feature = 1; feature < OUTPUT_FEATURES; feature++) {
            if (output[feature] == null || output[feature].length != candidateCount) {
                return Collections.emptyList();
            }
        }
        // DetectionEngine scales the source uniformly and pads the remaining model space. The
        // inverse mapping is therefore one scale on both axes; using frameWidth/inputWidth and
        // frameHeight/inputHeight independently would undo the letterbox as if the image had
        // been stretched, shifting boxes horizontally for portrait sources.
        float frameScale = Math.max(
                (float) frameWidth / inputWidth,
                (float) frameHeight / inputHeight);
        return decodeMatrix((feature, candidate) -> output[feature][candidate],
                candidateCount, frameWidth, frameHeight, frameScale, frameScale, config);
    }

    /** Decodes ONNX's contiguous [1, features, candidates] buffer without nested arrays. */
    public List<Detection> decode(
            FloatBuffer output,
            int featureCount,
            int candidateCount,
            int frameWidth,
            int frameHeight,
            int inputSize,
            DetectorConfig config) {
        if (output == null || featureCount < OUTPUT_FEATURES || candidateCount <= 0
                || output.remaining() < featureCount * candidateCount
                || frameWidth <= 0 || frameHeight <= 0 || inputSize <= 0) {
            return Collections.emptyList();
        }
        int start = output.position();
        float frameScale = (float) Math.max(frameWidth, frameHeight) / inputSize;
        return decodeMatrix(
                (feature, candidate) -> output.get(
                        start + feature * candidateCount + candidate),
                candidateCount,
                frameWidth,
                frameHeight,
                frameScale,
                frameScale,
                config);
    }

    /** Decodes ONNX output for a dynamic rectangular, aspect-preserving letterboxed tensor. */
    public List<Detection> decode(
            FloatBuffer output,
            int featureCount,
            int candidateCount,
            int frameWidth,
            int frameHeight,
            int inputWidth,
            int inputHeight,
            DetectorConfig config) {
        if (output == null || featureCount < OUTPUT_FEATURES || candidateCount <= 0
                || output.remaining() < featureCount * candidateCount
                || frameWidth <= 0 || frameHeight <= 0
                || inputWidth <= 0 || inputHeight <= 0) {
            return Collections.emptyList();
        }
        int start = output.position();
        float frameScale = Math.max(
                (float) frameWidth / inputWidth,
                (float) frameHeight / inputHeight);
        return decodeMatrix(
                (feature, candidate) -> output.get(
                        start + feature * candidateCount + candidate),
                candidateCount, frameWidth, frameHeight, frameScale, frameScale, config);
    }

    private List<Detection> decodeMatrix(
            OutputMatrix output,
            int candidateCount,
            int frameWidth,
            int frameHeight,
            float frameScaleX,
            float frameScaleY,
            DetectorConfig config) {
        float[] confidenceFloors = confidenceFloors(config.getConfidenceThreshold());
        List<Detection> accepted = new ArrayList<>();
        List<ScoredBox> disabledClassBoxes = new ArrayList<>();

        for (int candidate = 0; candidate < candidateCount; candidate++) {
            int bestClass = -1;
            int secondClass = -1;
            float bestScore = 0f;
            float secondScore = 0f;
            for (int classIndex = 0; classIndex < NudeNetClassCatalog.CLASS_COUNT; classIndex++) {
                float score = output.get(classIndex + 4, candidate);
                if (score > bestScore) {
                    secondClass = bestClass;
                    secondScore = bestScore;
                    bestScore = score;
                    bestClass = classIndex;
                } else if (score > secondScore) {
                    secondScore = score;
                    secondClass = classIndex;
                }
            }

            if (bestClass < 0 || bestScore < confidenceFloors[bestClass]
                    || isCrossCategoryAmbiguity(
                    bestClass, secondClass, bestScore, secondScore, config)) {
                continue;
            }
            String className = NudeNetClassCatalog.nameByIndex(bestClass);
            NudeNetClassCatalog.ClassInfo info = NudeNetClassCatalog.byIndex(bestClass);
            if (className == null || info == null) continue;

            BBox rawBox = decodeBox(
                    output.get(0, candidate), output.get(1, candidate),
                    output.get(2, candidate), output.get(3, candidate),
                    frameScaleX, frameScaleY, frameWidth, frameHeight, className);

            String category = firstEnabled(info.getCategories(), config.getEnabledCategories());
            if (category == null) {
                if (bestScore >= config.getConfidenceThreshold()) {
                    disabledClassBoxes.add(new ScoredBox(rawBox, bestScore));
                }
                continue;
            }

            if (("FACE_FEMALE".equals(className) || "FACE_MALE".equals(className))
                    && config.getEnabledCategories().contains("eyes")) {
                BBox eyes = eyeBand(rawBox);
                if (eyes.getWidth() >= 6 && eyes.getHeight() >= 6) {
                    accepted.add(new Detection(
                            "EYES_DERIVED", "eyes", bestScore,
                            eyes.padded(config.getBoxPadding(), frameWidth, frameHeight), false, true));
                }
            }

            int minimumSize = className.startsWith("FACE_") ? 6 : config.getMinDetectionSize();
            if (rawBox.getWidth() < minimumSize || rawBox.getHeight() < minimumSize) continue;
            accepted.add(new Detection(
                    className,
                    category,
                    bestScore,
                    rawBox.padded(config.getBoxPadding(), frameWidth, frameHeight),
                    info.isNsfw(),
                    info.isExposed()));
        }

        List<Detection> filtered = suppressSameCategory(accepted, 0.45f);
        if (!disabledClassBoxes.isEmpty()) {
            List<Detection> ambiguityFiltered = new ArrayList<>();
            for (Detection detection : filtered) {
                boolean suppressed = false;
                for (ScoredBox other : disabledClassBoxes) {
                    if (other.score > detection.getConfidence()
                            && detection.getBox().intersectionOverUnion(other.box) > 0.20f) {
                        suppressed = true;
                        break;
                    }
                }
                if (!suppressed) ambiguityFiltered.add(detection);
            }
            filtered = ambiguityFiltered;
        }
        return suppressCrossCategory(filtered, 0.50f);
    }

    @FunctionalInterface
    private interface OutputMatrix {
        float get(int feature, int candidate);
    }

    private static float confidenceFloor(String className, float configured) {
        switch (className) {
            case "FEET_EXPOSED":
            case "FEET_COVERED":
            case "ARMPITS_EXPOSED":
            case "ARMPITS_COVERED":
                return 0.15f;
            case "FACE_FEMALE":
            case "FACE_MALE":
                return configured * 0.5f;
            case "ANUS_EXPOSED":
                return Math.max(configured, 0.50f);
            case "FEMALE_GENITALIA_EXPOSED":
            case "MALE_GENITALIA_EXPOSED":
            case "BUTTOCKS_EXPOSED":
                return Math.max(configured, 0.45f);
            case "FEMALE_BREAST_EXPOSED":
                return Math.max(configured, 0.40f);
            case "MALE_BREAST_EXPOSED":
                return Math.max(configured, 0.60f);
            default:
                return configured;
        }
    }

    private static float[] confidenceFloors(float configured) {
        float[] floors = new float[NudeNetClassCatalog.CLASS_COUNT];
        for (int index = 0; index < floors.length; index++) {
            floors[index] = confidenceFloor(NudeNetClassCatalog.nameByIndex(index), configured);
        }
        return floors;
    }

    private static boolean isCrossCategoryAmbiguity(
            int bestClass,
            int secondClass,
            float bestScore,
            float secondScore,
            DetectorConfig config) {
        if (secondClass < 0 || secondScore < 0.10f || bestScore - secondScore >= 0.10f) {
            return false;
        }
        NudeNetClassCatalog.ClassInfo best = NudeNetClassCatalog.byIndex(bestClass);
        NudeNetClassCatalog.ClassInfo second = NudeNetClassCatalog.byIndex(secondClass);
        if (best == null || second == null) return true;
        String bestCategory = firstEnabled(best.getCategories(), config.getEnabledCategories());
        String secondCategory = firstEnabled(
                second.getCategories(), config.getEnabledCategories());
        // Sex-specific face logits often trade places on stylized/AI faces. If both resolve to
        // the same user-enabled censor category, the distinction has no policy consequence.
        return bestCategory == null || !bestCategory.equals(secondCategory);
    }

    private static BBox decodeBox(
            float centerX,
            float centerY,
            float modelWidth,
            float modelHeight,
            float frameScaleX,
            float frameScaleY,
            int frameWidth,
            int frameHeight,
            String className) {
        float sizeMultiplier = expandedClass(className) ? 1.08f : 1.0f;
        int width = Math.max(1, (int) (modelWidth * frameScaleX * sizeMultiplier));
        int height = Math.max(1, (int) (modelHeight * frameScaleY * sizeMultiplier));
        width = Math.max(width, (int) (height * 0.6f));
        height = Math.max(height, (int) (width * 0.6f));
        int x = Math.max(0, (int) (centerX * frameScaleX) - width / 2);
        int y = Math.max(0, (int) (centerY * frameScaleY) - height / 2);
        return new BBox(
                x,
                y,
                Math.min(width, Math.max(0, frameWidth - x)),
                Math.min(height, Math.max(0, frameHeight - y)));
    }

    private static boolean expandedClass(String className) {
        switch (className) {
            case "FEMALE_GENITALIA_COVERED":
            case "FEMALE_GENITALIA_EXPOSED":
            case "MALE_GENITALIA_EXPOSED":
            case "FEMALE_BREAST_COVERED":
            case "FEMALE_BREAST_EXPOSED":
            case "BUTTOCKS_EXPOSED":
            case "FACE_FEMALE":
            case "FACE_MALE":
                return true;
            default:
                return false;
        }
    }

    private static String firstEnabled(List<String> categories, Set<String> enabled) {
        for (String category : categories) {
            if (enabled.contains(category)) return category;
        }
        return null;
    }

    private static BBox eyeBand(BBox face) {
        int topOffset = (int) (face.getHeight() * 0.07f);
        int bandHeight = Math.max(1, (int) (face.getHeight() * 0.28f));
        int sideInset = (int) (face.getWidth() * 0.04f);
        return new BBox(
                face.getX() + sideInset,
                face.getY() + topOffset,
                Math.max(1, face.getWidth() - sideInset * 2),
                bandHeight);
    }

    private static List<Detection> suppressSameCategory(List<Detection> detections, float threshold) {
        return suppress(detections, threshold, true);
    }

    private static List<Detection> suppressCrossCategory(List<Detection> detections, float threshold) {
        return suppress(detections, threshold, false);
    }

    private static List<Detection> suppress(
            List<Detection> detections,
            float threshold,
            boolean sameCategory) {
        if (detections.size() <= 1) return detections;
        List<Detection> sorted = new ArrayList<>(detections);
        sorted.sort(Comparator.comparing(Detection::getConfidence).reversed());
        boolean[] removed = new boolean[sorted.size()];
        for (int first = 0; first < sorted.size(); first++) {
            if (removed[first]) continue;
            for (int second = first + 1; second < sorted.size(); second++) {
                if (removed[second]) continue;
                boolean categoriesEqual = sorted.get(first).getCategory().equals(sorted.get(second).getCategory());
                if (categoriesEqual == sameCategory
                        && sorted.get(first).getBox().intersectionOverUnion(sorted.get(second).getBox()) > threshold) {
                    removed[second] = true;
                }
            }
        }
        List<Detection> kept = new ArrayList<>();
        for (int index = 0; index < sorted.size(); index++) {
            if (!removed[index]) kept.add(sorted.get(index));
        }
        return kept;
    }

    private static final class ScoredBox {
        private final BBox box;
        private final float score;

        private ScoredBox(BBox box, float score) {
            this.box = box;
            this.score = score;
        }
    }
}
