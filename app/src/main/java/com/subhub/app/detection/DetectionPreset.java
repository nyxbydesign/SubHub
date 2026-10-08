package com.subhub.app.detection;

import java.util.Locale;

/** Three quality/performance levels shared by live capture and image preparation. */
public enum DetectionPreset {
    LOW("Low", "Balanced", 0.30f, 0, 20, 0.45f, 320, 25, 0.75f, 0.55f,
            180f, 2, 512, 8),
    MEDIUM("Medium", "Recommended", 0.25f, 0, 12, 0.50f, 480, 35, 0.70f, 0.50f,
            230f, 3, 768, 12),
    HIGH("High", "Maximum", 0.18f, 0, 6, 0.75f, 512, 50, 0.65f, 0.45f,
            300f, 4, 1024, 16);

    private final String displayName;
    private final String description;
    private final float confidence;
    private final long intervalMs;
    private final int minimumSize;
    private final float captureScale;
    private final int inferenceResolution;
    private final int idleFrames;
    private final float renderAlpha;
    private final float velocitySmoothing;
    private final float maximumExtrapolationMs;
    private final int inferenceThreads;
    private final int customImageDimension;
    private final int customImageCount;

    DetectionPreset(
            String displayName,
            String description,
            float confidence,
            long intervalMs,
            int minimumSize,
            float captureScale,
            int inferenceResolution,
            int idleFrames,
            float renderAlpha,
            float velocitySmoothing,
            float maximumExtrapolationMs,
            int inferenceThreads,
            int customImageDimension,
            int customImageCount) {
        this.displayName = displayName;
        this.description = description;
        this.confidence = confidence;
        this.intervalMs = intervalMs;
        this.minimumSize = minimumSize;
        this.captureScale = captureScale;
        this.inferenceResolution = inferenceResolution;
        this.idleFrames = idleFrames;
        this.renderAlpha = renderAlpha;
        this.velocitySmoothing = velocitySmoothing;
        this.maximumExtrapolationMs = maximumExtrapolationMs;
        this.inferenceThreads = inferenceThreads;
        this.customImageDimension = customImageDimension;
        this.customImageCount = customImageCount;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public float getConfidence() { return confidence; }
    public long getIntervalMs() { return intervalMs; }
    public int getMinimumSize() { return minimumSize; }
    public float getCaptureScale() { return captureScale; }
    public int getInferenceResolution() { return inferenceResolution; }
    public int getInferenceThreads() { return inferenceThreads; }
    public int getCustomImageDimension() { return customImageDimension; }
    public int getCustomImageCount() { return customImageCount; }

    public DetectorConfig.Builder applyTo(DetectorConfig.Builder builder) {
        return builder
                .confidenceThreshold(confidence)
                .detectionIntervalMs(intervalMs)
                .minDetectionSize(minimumSize)
                .captureScale(captureScale)
                .inferenceResolution(inferenceResolution)
                .idleThresholdFrames(idleFrames)
                .renderAlpha(renderAlpha)
                .velocitySmoothing(velocitySmoothing)
                .maxExtrapolationMs(maximumExtrapolationMs)
                .inferenceThreads(inferenceThreads);
    }

    public String preferenceValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static DetectionPreset fromPreference(String value) {
        if (value != null) {
            for (DetectionPreset preset : values()) {
                if (preset.preferenceValue().equals(value)) return preset;
            }
        }
        return MEDIUM;
    }
}
