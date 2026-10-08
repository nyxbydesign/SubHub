package com.subhub.app.detection;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Runtime knobs shared by inference, postprocessing, tracking, and capture. */
public final class DetectorConfig {
    private final Set<String> enabledCategories;
    private final float confidenceThreshold;
    private final float trackingSmoothing;
    private final float trackMaxAgeSeconds;
    private final int minRemoveFrames;
    private final boolean motionPrediction;
    private final long detectionIntervalMs;
    private final float boxPadding;
    private final int minDetectionSize;
    private final float captureScale;
    private final int inferenceResolution;
    private final String modelFilename;
    private final int idleThresholdFrames;
    private final float renderAlpha;
    private final float velocitySmoothing;
    private final float maxExtrapolationMs;
    private final boolean detectCovered;
    private final int inferenceThreads;
    private final CensorCoverage censorCoverage;

    private DetectorConfig(Builder builder) {
        enabledCategories = Collections.unmodifiableSet(new LinkedHashSet<>(builder.enabledCategories));
        confidenceThreshold = builder.confidenceThreshold;
        trackingSmoothing = builder.trackingSmoothing;
        trackMaxAgeSeconds = builder.trackMaxAgeSeconds;
        minRemoveFrames = builder.minRemoveFrames;
        motionPrediction = builder.motionPrediction;
        detectionIntervalMs = builder.detectionIntervalMs;
        boxPadding = builder.boxPadding;
        minDetectionSize = builder.minDetectionSize;
        captureScale = builder.captureScale;
        inferenceResolution = builder.inferenceResolution;
        modelFilename = builder.modelFilename;
        idleThresholdFrames = builder.idleThresholdFrames;
        renderAlpha = builder.renderAlpha;
        velocitySmoothing = builder.velocitySmoothing;
        maxExtrapolationMs = builder.maxExtrapolationMs;
        detectCovered = builder.detectCovered;
        inferenceThreads = builder.inferenceThreads;
        censorCoverage = builder.censorCoverage;
    }

    public static Builder builder() { return new Builder(); }
    public Builder toBuilder() {
        return new Builder()
                .enabledCategories(enabledCategories)
                .confidenceThreshold(confidenceThreshold)
                .trackingSmoothing(trackingSmoothing)
                .trackMaxAgeSeconds(trackMaxAgeSeconds)
                .minRemoveFrames(minRemoveFrames)
                .motionPrediction(motionPrediction)
                .detectionIntervalMs(detectionIntervalMs)
                .boxPadding(boxPadding)
                .minDetectionSize(minDetectionSize)
                .captureScale(captureScale)
                .inferenceResolution(inferenceResolution)
                .modelFilename(modelFilename)
                .idleThresholdFrames(idleThresholdFrames)
                .renderAlpha(renderAlpha)
                .velocitySmoothing(velocitySmoothing)
                .maxExtrapolationMs(maxExtrapolationMs)
                .detectCovered(detectCovered)
                .inferenceThreads(inferenceThreads)
                .censorCoverage(censorCoverage);
    }
    public Set<String> getEnabledCategories() { return enabledCategories; }
    public float getConfidenceThreshold() { return confidenceThreshold; }
    public float getTrackingSmoothing() { return trackingSmoothing; }
    public float getTrackMaxAgeSeconds() { return trackMaxAgeSeconds; }
    public int getMinRemoveFrames() { return minRemoveFrames; }
    public boolean isMotionPrediction() { return motionPrediction; }
    public long getDetectionIntervalMs() { return detectionIntervalMs; }
    public float getBoxPadding() { return boxPadding; }
    public int getMinDetectionSize() { return minDetectionSize; }
    public float getCaptureScale() { return captureScale; }
    public int getInferenceResolution() { return inferenceResolution; }
    public String getModelFilename() { return modelFilename; }
    public int getIdleThresholdFrames() { return idleThresholdFrames; }
    public float getRenderAlpha() { return renderAlpha; }
    public float getVelocitySmoothing() { return velocitySmoothing; }
    public float getMaxExtrapolationMs() { return maxExtrapolationMs; }
    public boolean isDetectCovered() { return detectCovered; }
    public int getInferenceThreads() { return inferenceThreads; }
    public CensorCoverage getCensorCoverage() { return censorCoverage; }

    public static final class Builder {
        private Set<String> enabledCategories = NudeNetClassCatalog.DEFAULT_ENABLED;
        private float confidenceThreshold = 0.30f;
        private float trackingSmoothing = 1.0f;
        private float trackMaxAgeSeconds = 0.75f;
        private int minRemoveFrames = 3;
        private boolean motionPrediction = true;
        private long detectionIntervalMs;
        private float boxPadding;
        private int minDetectionSize = 30;
        private float captureScale = 0.5f;
        private int inferenceResolution = 320;
        private String modelFilename = "320n_fp16.onnx";
        private int idleThresholdFrames = 25;
        private float renderAlpha = 0.75f;
        private float velocitySmoothing = 0.55f;
        private float maxExtrapolationMs = 180f;
        private boolean detectCovered;
        private int inferenceThreads = 2;
        private CensorCoverage censorCoverage = CensorCoverage.DETECTED_AREAS;

        public Builder enabledCategories(Set<String> value) { enabledCategories = value; return this; }
        public Builder confidenceThreshold(float value) { confidenceThreshold = value; return this; }
        public Builder trackingSmoothing(float value) { trackingSmoothing = value; return this; }
        public Builder trackMaxAgeSeconds(float value) { trackMaxAgeSeconds = value; return this; }
        public Builder minRemoveFrames(int value) { minRemoveFrames = value; return this; }
        public Builder motionPrediction(boolean value) { motionPrediction = value; return this; }
        public Builder detectionIntervalMs(long value) { detectionIntervalMs = value; return this; }
        public Builder boxPadding(float value) { boxPadding = value; return this; }
        public Builder minDetectionSize(int value) { minDetectionSize = value; return this; }
        public Builder captureScale(float value) { captureScale = value; return this; }
        public Builder inferenceResolution(int value) { inferenceResolution = value; return this; }
        public Builder modelFilename(String value) { modelFilename = value; return this; }
        public Builder idleThresholdFrames(int value) { idleThresholdFrames = value; return this; }
        public Builder renderAlpha(float value) { renderAlpha = value; return this; }
        public Builder velocitySmoothing(float value) { velocitySmoothing = value; return this; }
        public Builder maxExtrapolationMs(float value) { maxExtrapolationMs = value; return this; }
        public Builder detectCovered(boolean value) { detectCovered = value; return this; }
        public Builder inferenceThreads(int value) { inferenceThreads = value; return this; }
        public Builder censorCoverage(CensorCoverage value) {
            censorCoverage = java.util.Objects.requireNonNull(value); return this;
        }

        public DetectorConfig build() {
            if (enabledCategories == null || modelFilename == null || modelFilename.trim().isEmpty()) {
                throw new IllegalArgumentException("Categories and model filename are required");
            }
            if (confidenceThreshold < 0f || confidenceThreshold > 1f
                    || trackingSmoothing <= 0f || trackingSmoothing > 1f
                    || inferenceResolution <= 0 || captureScale <= 0f || captureScale > 1f
                    || renderAlpha < 0f || renderAlpha > 1f
                    || velocitySmoothing < 0f || velocitySmoothing > 1f
                    || idleThresholdFrames < 0 || maxExtrapolationMs < 0f
                    || inferenceThreads < 1 || inferenceThreads > 4) {
                throw new IllegalArgumentException("Detector configuration is out of range");
            }
            return new DetectorConfig(this);
        }
    }
}
