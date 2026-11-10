package com.subhub.app.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.accessibility.AccessibilityEvent;

import com.subhub.app.detection.BBox;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectionPreset;
import com.subhub.app.detection.DetectorConfig;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class CaptureEpochTest {
    @Test public void foregroundTransitionRejectsAnInFlightFrame() {
        CaptureEpoch epoch = new CaptureEpoch();
        long inFlight = epoch.token();
        assertTrue(epoch.accepts(inFlight, true, true));

        epoch.invalidate();

        assertFalse(epoch.accepts(inFlight, true, true));
        assertTrue(epoch.accepts(epoch.token(), true, true));
    }

    @Test public void inactiveCaptureNeverAcceptsAResult() {
        CaptureEpoch epoch = new CaptureEpoch();
        assertFalse(epoch.accepts(epoch.token(), false, true));
        assertFalse(epoch.accepts(epoch.token(), true, false));
    }

    @Test public void captureCadencePreservesForegroundResponsivenessAcrossPresets() {
        DetectorConfig low = DetectionPreset.LOW.applyTo(DetectorConfig.builder()).build();
        DetectorConfig medium = DetectionPreset.MEDIUM.applyTo(DetectorConfig.builder()).build();
        DetectorConfig high = DetectionPreset.HIGH.applyTo(DetectorConfig.builder()).build();

        assertEquals(300L, ScreenshotAccessibilityService.captureDelayMs(low));
        assertEquals(240L, ScreenshotAccessibilityService.captureDelayMs(medium));
        assertEquals(180L, ScreenshotAccessibilityService.captureDelayMs(high));
        assertEquals(334L, ScreenshotAccessibilityService.capturePollDelayMs(low));
        assertEquals(334L, ScreenshotAccessibilityService.capturePollDelayMs(medium));
        assertEquals(334L, ScreenshotAccessibilityService.capturePollDelayMs(high));
    }

    @Test public void settledCaptureHonorsMotionAndPlatformGates() {
        assertEquals(130L, ScreenshotAccessibilityService.settledCaptureDelayMs(
                1_000L, 1_000L, 500L));
        assertEquals(234L, ScreenshotAccessibilityService.settledCaptureDelayMs(
                1_000L, 700L, 900L));
        assertEquals(0L, ScreenshotAccessibilityService.settledCaptureDelayMs(
                1_000L, 800L, 600L));
    }

    @Test public void onlyUltraKeepsInferenceAliveDuringMotion() {
        DetectorConfig high = DetectorConfig.builder().inferenceThreads(3).build();
        DetectorConfig ultra = DetectorConfig.builder().inferenceThreads(4).build();

        assertFalse(ScreenshotAccessibilityService.usesContinuousMotionInference(high));
        assertTrue(ScreenshotAccessibilityService.usesContinuousMotionInference(ultra));
    }

    @Test public void streamingQualityRequiresUltraAndASeparateLargerInput() {
        DetectorConfig high = DetectorConfig.builder()
                .inferenceThreads(3).inferenceResolution(512).build();
        DetectorConfig ultraFastOnly = DetectorConfig.builder()
                .inferenceThreads(4).inferenceResolution(320).build();
        DetectorConfig ultra = DetectorConfig.builder()
                .inferenceThreads(4).inferenceResolution(512).build();

        assertFalse(ScreenshotAccessibilityService.usesStreamingQualityPipeline(high));
        assertFalse(ScreenshotAccessibilityService.usesStreamingQualityPipeline(ultraFastOnly));
        assertTrue(ScreenshotAccessibilityService.usesStreamingQualityPipeline(ultra));
    }

    @Test public void ultraKeepsFastFramesCompactBeforeQualityIsReady() {
        DetectorConfig ultra = DetectorConfig.builder()
                .inferenceThreads(4).inferenceResolution(512).build();

        assertEquals(320, ScreenshotAccessibilityService.fastInferenceFrameResolution(
                ultra, true, false));
        assertEquals(512, ScreenshotAccessibilityService.fastInferenceFrameResolution(
                ultra, false, false));
        assertEquals(512, ScreenshotAccessibilityService.fastInferenceFrameResolution(
                ultra, true, true));
    }

    @Test public void dueFastCaptureAlwaysPreemptsOptionalQuality() {
        assertTrue(ScreenshotAccessibilityService.shouldPreemptQualityForCapture(true));
        assertFalse(ScreenshotAccessibilityService.shouldPreemptQualityForCapture(false));
    }

    @Test public void qualityBudgetMustFitBeforeTheNextFastCapture() {
        assertEquals(160L, ScreenshotAccessibilityService.qualityExecutionBudgetMs(0L));
        assertEquals(176L, ScreenshotAccessibilityService.qualityExecutionBudgetMs(160L));
        assertEquals(516L, ScreenshotAccessibilityService.qualityExecutionBudgetMs(500L));
        assertEquals(600L, ScreenshotAccessibilityService.qualityExecutionBudgetMs(1_000L));
        assertEquals(300L, ScreenshotAccessibilityService.qualityReservationWindowMs(0L));
        assertEquals(564L, ScreenshotAccessibilityService.qualityReservationWindowMs(500L));
        assertEquals(648L, ScreenshotAccessibilityService.qualityReservationWindowMs(1_000L));
        assertEquals(134L, ScreenshotAccessibilityService.qualityAvailableSlackMs(
                1_000L, 1_200L));
        assertEquals(0L, ScreenshotAccessibilityService.qualityAvailableSlackMs(
                1_000L, 1_700L));
    }

    @Test public void visibleSceneNeverWaitsForOptionalQuality() {
        assertEquals(SceneTransactionCoordinator.Mode.ACTIVE_FAST,
                ScreenshotAccessibilityService.scenePresentationMode(false));
        assertEquals(SceneTransactionCoordinator.Mode.SETTLED_FAST_ONLY,
                ScreenshotAccessibilityService.scenePresentationMode(true));
    }

    @Test public void sameCaptureQualityWaitsBehindFastWork() {
        assertFalse(ScreenshotAccessibilityService.shouldScheduleQualityNow(true, true));
        assertFalse(ScreenshotAccessibilityService.shouldScheduleQualityNow(true, false));
        assertFalse(ScreenshotAccessibilityService.shouldScheduleQualityNow(false, true));
        assertTrue(ScreenshotAccessibilityService.shouldScheduleQualityNow(false, false));
    }

    @Test public void stuckQualityOpensABoundedCircuitBreaker() {
        assertFalse(ScreenshotAccessibilityService.qualityCircuitAllows(1_000L, 31_000L));
        assertTrue(ScreenshotAccessibilityService.qualityCircuitAllows(31_000L, 31_000L));
    }

    @Test public void backgroundQualityRequiresSplitCpuAndNnapiHardware() {
        assertTrue(ScreenshotAccessibilityService.usesSplitHardwareQuality("CPU", "NNAPI"));
        assertFalse(ScreenshotAccessibilityService.usesSplitHardwareQuality("CPU", "CPU"));
        assertFalse(ScreenshotAccessibilityService.usesSplitHardwareQuality("CPU", "XNNPACK"));
        assertFalse(ScreenshotAccessibilityService.usesSplitHardwareQuality("NNAPI", "NNAPI"));
    }

    @Test public void portraitFastInputKeepsLongEdgeAndRoundsShortEdgeToModelStride() {
        int[] portrait = ScreenshotAccessibilityService.rectangularFastInputShape(
                1_344, 2_992, 320);
        int[] landscape = ScreenshotAccessibilityService.rectangularFastInputShape(
                2_992, 1_344, 320);

        assertEquals(160, portrait[0]);
        assertEquals(320, portrait[1]);
        assertEquals(320, landscape[0]);
        assertEquals(160, landscape[1]);
    }

    @Test public void nearSquareFastInputKeepsTheStableSquarePath() {
        assertEquals(null, ScreenshotAccessibilityService.rectangularFastInputShape(
                1_200, 1_000, 320));
    }

    @Test public void lowResolutionFastInputNeverUpscalesToTheUltraShape() {
        int[] low = ScreenshotAccessibilityService.rectangularFastInputShape(
                1_344, 2_992, 224);
        assertEquals(128, low[0]);
        assertEquals(224, low[1]);
        assertTrue((long) low[0] * low[1] < 224L * 224L);
    }

    @Test public void backfillTransformTokenChangesOnlyWithGeometry() {
        long portrait = ScreenshotAccessibilityService.backfillTransformToken(
                1_344, 2_992, 1_344, 2_992);
        assertEquals(portrait, ScreenshotAccessibilityService.backfillTransformToken(
                1_344, 2_992, 1_344, 2_992));
        assertFalse(portrait == ScreenshotAccessibilityService.backfillTransformToken(
                2_992, 1_344, 2_992, 1_344));
    }

    @Test public void fastFrameBlockedBehindQualityMustStillBeNewest() {
        assertTrue(ScreenshotAccessibilityService.isFastSubmissionCurrent(7L, 7L));
        assertFalse(ScreenshotAccessibilityService.isFastSubmissionCurrent(7L, 8L));
    }

    @Test public void partialOrEmptyQualityCannotShrinkAnExistingCache() {
        assertFalse(ScreenshotAccessibilityService.shouldReplaceQualityCache(4, 0, true));
        assertFalse(ScreenshotAccessibilityService.shouldReplaceQualityCache(4, 2, false));
        assertTrue(ScreenshotAccessibilityService.shouldReplaceQualityCache(4, 3, true));
        assertTrue(ScreenshotAccessibilityService.shouldReplaceQualityCache(0, 1, false));
    }

    @Test public void ultraNeverRunsCompetingScreenshotMotion() {
        DetectorConfig ultra = DetectorConfig.builder().inferenceThreads(4).build();

        assertFalse(ScreenshotAccessibilityService.shouldEstimateFrameMotion(
                ultra, 10_000L, 0L));
        assertFalse(ScreenshotAccessibilityService.shouldEstimateFrameMotion(
                ultra, 10_000L, 9_000L));
    }

    @Test public void slowerPresetUsesFrameMotionOnlyWithoutRecentAccessibilitySignal() {
        DetectorConfig balanced = DetectorConfig.builder().inferenceThreads(3).build();

        assertTrue(ScreenshotAccessibilityService.shouldEstimateFrameMotion(
                balanced, 10_000L, 0L));
        assertFalse(ScreenshotAccessibilityService.shouldEstimateFrameMotion(
                balanced, 10_000L, 9_500L));
        assertTrue(ScreenshotAccessibilityService.shouldEstimateFrameMotion(
                balanced, 10_000L, 9_000L));
    }

    @Test public void textScanMustMatchCurrentMotionGeneration() {
        assertTrue(ScreenshotAccessibilityService.shouldPublishTextScan(
                12L, 12L, 40L, 80L, 40L, 80L));
        assertFalse(ScreenshotAccessibilityService.shouldPublishTextScan(
                12L, 13L, 40L, 80L, 40L, 80L));
        assertFalse(ScreenshotAccessibilityService.shouldPublishTextScan(
                12L, 12L, 40L, 80L, 40L, 120L));
    }

    @Test public void postScrollTextScanDropsMissesAndRetriesAfterSettle() {
        assertFalse(ScreenshotAccessibilityService.shouldBridgeTextMisses(
                10_500L, 10_000L, 0L));
        assertTrue(ScreenshotAccessibilityService.shouldBridgeTextMisses(
                10_900L, 10_000L, 0L));
        assertFalse(ScreenshotAccessibilityService.shouldBridgeTextMisses(
                10_900L, 9_000L, 10_400L));
        assertTrue(ScreenshotAccessibilityService.shouldBridgeTextMisses(
                10_900L, 9_000L, 9_500L));
        assertEquals(190L,
                ScreenshotAccessibilityService.textRefreshDelayAfterMotion(10_050L, 10_000L));
        assertEquals(0L,
                ScreenshotAccessibilityService.textRefreshDelayAfterMotion(10_250L, 10_000L));
        assertEquals(190L, ScreenshotAccessibilityService.textScanStartDelayMs(
                10_050L, 10_000L, 20_000L, 19_000L));
        assertEquals(80L, ScreenshotAccessibilityService.textScanStartDelayMs(
                10_250L, 10_000L, 20_000L, 19_960L));
        assertEquals(0L, ScreenshotAccessibilityService.textScanStartDelayMs(
                10_250L, 10_000L, 20_000L, 19_800L));
    }

    @Test public void textRefreshIgnoresStateOnlyAccessibilityChurn() {
        assertTrue(ScreenshotAccessibilityService.isTextRelevantContentChange(
                AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED));
        assertTrue(ScreenshotAccessibilityService.isTextRelevantContentChange(
                AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT));
        assertTrue(ScreenshotAccessibilityService.isTextRelevantContentChange(
                AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE));
        assertFalse(ScreenshotAccessibilityService.isTextRelevantContentChange(
                AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION));
        assertFalse(ScreenshotAccessibilityService.isTextRelevantContentChange(
                AccessibilityEvent.CONTENT_CHANGE_TYPE_ENABLED));
    }

    @Test public void contentRefreshDebouncesUntilQuietButCannotStarveForever() {
        assertEquals(80L, ScreenshotAccessibilityService.contentTextRefreshDelayMs(
                10_000L, 10_000L, 10_000L));
        assertEquals(10L, ScreenshotAccessibilityService.contentTextRefreshDelayMs(
                10_490L, 10_490L, 10_000L));
        assertEquals(0L, ScreenshotAccessibilityService.contentTextRefreshDelayMs(
                10_500L, 10_500L, 10_000L));
        assertEquals(0L, ScreenshotAccessibilityService.contentTextRefreshDelayMs(
                10_200L, 10_100L, 10_000L));
    }

    @Test public void accessibilityTextExpiresOnlyAfterANewerViewportInvalidatesIt() {
        assertTrue(ScreenshotAccessibilityService.isAccessibilityTextSnapshotFresh(
                5_000L, 1_000L, 0L));
        assertTrue(ScreenshotAccessibilityService.isAccessibilityTextSnapshotFresh(
                5_000L, 4_500L, 4_800L));
        assertTrue(ScreenshotAccessibilityService.isAccessibilityTextSnapshotFresh(
                5_800L, 4_500L, 4_800L));
        assertFalse(ScreenshotAccessibilityService.isAccessibilityTextSnapshotFresh(
                5_801L, 4_500L, 4_800L));
        assertFalse(ScreenshotAccessibilityService.isAccessibilityTextSnapshotFresh(
                5_000L, 1_000L, 3_999L));
        assertFalse(ScreenshotAccessibilityService.isAccessibilityTextSnapshotFresh(
                500L, 1_000L, 0L));
    }

    @Test public void semanticTextAndOcrAreGatedByPresetCost() {
        DetectorConfig medium = DetectorConfig.builder().inferenceThreads(2).build();
        DetectorConfig high = DetectorConfig.builder().inferenceThreads(3).build();
        DetectorConfig ultra = DetectorConfig.builder().inferenceThreads(4).build();

        assertFalse(ScreenshotAccessibilityService.usesSemanticTextModel(medium));
        assertTrue(ScreenshotAccessibilityService.usesSemanticTextModel(high));
        assertTrue(ScreenshotAccessibilityService.usesSemanticTextModel(ultra));
        assertFalse(ScreenshotAccessibilityService.usesScreenshotOcr(medium));
        assertFalse(ScreenshotAccessibilityService.usesScreenshotOcr(high));
        assertTrue(ScreenshotAccessibilityService.usesScreenshotOcr(ultra));
    }

    @Test public void ocrWaitsForMotionAndUsesShortConfirmationBursts() {
        assertEquals(0L, ScreenshotAccessibilityService.ocrDelayMs(
                10_000L, 0L, 0L, false));
        assertEquals(3_000L, ScreenshotAccessibilityService.ocrDelayMs(
                10_000L, 10_000L, 0L, false));
        assertEquals(650L, ScreenshotAccessibilityService.ocrDelayMs(
                10_000L, 10_000L, 0L, true));
        assertEquals(600L, ScreenshotAccessibilityService.ocrDelayMs(
                10_000L, 0L, 10_000L, false));
    }

    @Test public void accessibilityTextOwnsNativeCaptionsBeforeOcr() {
        DetectorConfig ultra = DetectorConfig.builder().inferenceThreads(4).build();

        assertFalse(ScreenshotAccessibilityService.ocrEligibleForViewport(
                ultra, true, false, false));
        assertTrue(ScreenshotAccessibilityService.ocrEligibleForViewport(
                ultra, true, true, false));
        assertFalse(ScreenshotAccessibilityService.ocrEligibleForViewport(
                ultra, true, true, true));
        assertFalse(ScreenshotAccessibilityService.sameOcrViewport(4L, 5L, false));
        assertFalse(ScreenshotAccessibilityService.sameOcrViewport(5L, 5L, true));
        assertTrue(ScreenshotAccessibilityService.sameOcrViewport(5L, 5L, false));
    }

    @Test public void ocrSnapshotsExpireInsteadOfFlashingLongAfterTheirViewport() {
        assertTrue(ScreenshotAccessibilityService.isOcrSnapshotFresh(5_000L, 1_000L));
        assertFalse(ScreenshotAccessibilityService.isOcrSnapshotFresh(6_001L, 1_000L));
        assertFalse(ScreenshotAccessibilityService.isOcrSnapshotFresh(500L, 1_000L));
    }

    @Test public void realtimeDetectorKeepsQualitySettingsAtA320PixelInput() {
        DetectorConfig quality = DetectorConfig.builder()
                .inferenceResolution(512)
                .confidenceThreshold(0.18f)
                .inferenceThreads(4)
                .detectionIntervalMs(90L)
                .build();

        DetectorConfig fast = ScreenshotAccessibilityService.fastDetectorConfig(quality);

        assertEquals(320, fast.getInferenceResolution());
        assertEquals(0L, fast.getDetectionIntervalMs());
        assertEquals(4, fast.getInferenceThreads());
        assertEquals(0.18f, fast.getConfidenceThreshold(), 0.001f);
        assertEquals(512, quality.getInferenceResolution());
    }

    @Test public void accessibilityTrackerDisablesCompetingVelocityPrediction() {
        DetectorConfig configured = DetectionPreset.HIGH.applyTo(
                DetectorConfig.builder()).build();

        DetectorConfig tracker = ScreenshotAccessibilityService
                .accessibilityTrackerConfig(configured);

        assertFalse(tracker.isMotionPrediction());
        assertEquals(0f, tracker.getVelocitySmoothing(), 0f);
        assertEquals(0f, tracker.getMaxExtrapolationMs(), 0f);
        assertEquals(configured.getTrackingSmoothing(),
                tracker.getTrackingSmoothing(), 0f);
    }

    @Test public void atomicSceneQualityIsReservedForDistinctHighResolutionPresets() {
        DetectorConfig low = DetectionPreset.LOW.applyTo(DetectorConfig.builder()).build();
        DetectorConfig medium = DetectionPreset.MEDIUM.applyTo(DetectorConfig.builder()).build();
        DetectorConfig high = DetectionPreset.HIGH.applyTo(DetectorConfig.builder()).build();

        assertFalse(ScreenshotAccessibilityService.usesAtomicScenePipeline(low));
        assertTrue(ScreenshotAccessibilityService.usesAtomicScenePipeline(medium));
        assertTrue(ScreenshotAccessibilityService.usesAtomicScenePipeline(high));
        assertEquals(320, ScreenshotAccessibilityService.fastInferenceFrameResolution(
                high, true, false));
    }

    @Test public void qualityRefinementUsesTheFirstPlatformSafeSettledCapture() {
        assertFalse(ScreenshotAccessibilityService.shouldRunQualityRefinement(
                10_129L, 10_000L, 0L, true, true));
        assertTrue(ScreenshotAccessibilityService.shouldRunQualityRefinement(
                10_130L, 10_000L, 0L, true, true));
        assertTrue(ScreenshotAccessibilityService.shouldRunQualityRefinement(
                11_500L, 10_000L, 11_400L, true, true));
        assertFalse(ScreenshotAccessibilityService.shouldRunQualityRefinement(
                11_499L, 10_000L, 11_000L, true, false));
        assertTrue(ScreenshotAccessibilityService.shouldRunQualityRefinement(
                11_500L, 10_000L, 11_000L, true, false));
        assertTrue(ScreenshotAccessibilityService.shouldRunQualityRefinement(
                100L, 0L, 0L, false, true));
        assertFalse(ScreenshotAccessibilityService.shouldRunQualityRefinement(
                11_999L, 10_000L, 11_000L, true, false, 180L, false));
        assertTrue(ScreenshotAccessibilityService.shouldRunQualityRefinement(
                12_000L, 10_000L, 11_000L, true, false, 180L, false));
        assertFalse(ScreenshotAccessibilityService.shouldRunQualityRefinement(
                20_000L, 0L, 0L, false, true, 0L, true));
    }

    @Test public void qualityReservationYieldsOnlyAnIdleOrdinaryTick() {
        assertTrue(ScreenshotAccessibilityService.shouldReserveQualityTick(
                1_000L, 0L, 1_200L, true, false));
        assertTrue(ScreenshotAccessibilityService.shouldReserveQualityTick(
                1_000L, 800L, 1_200L, false, true));
        assertFalse(ScreenshotAccessibilityService.shouldReserveQualityTick(
                1_000L, 950L, 1_200L, true, false));
        assertFalse(ScreenshotAccessibilityService.shouldReserveQualityTick(
                1_200L, 0L, 1_200L, true, false));
        assertFalse(ScreenshotAccessibilityService.shouldReserveQualityTick(
                1_000L, 0L, 1_200L, false, false));
    }

    @Test public void currentQualityMayRideNextFastAfterOneAcceptedObservation() {
        assertTrue(ScreenshotAccessibilityService.qualityObservationAccepted(
                QualityBackfillCoordinator.ObservationStatus.ACCEPTED));
        assertTrue(ScreenshotAccessibilityService.qualityObservationAccepted(
                QualityBackfillCoordinator.ObservationStatus.PROMOTED));
        assertTrue(ScreenshotAccessibilityService.qualityObservationAccepted(
                QualityBackfillCoordinator.ObservationStatus.REFINED));
        assertFalse(ScreenshotAccessibilityService.qualityObservationAccepted(
                QualityBackfillCoordinator.ObservationStatus.REJECTED_STALE));
        assertFalse(ScreenshotAccessibilityService.qualityObservationAccepted(
                QualityBackfillCoordinator.ObservationStatus.REJECTED_FENCE));
        assertFalse(ScreenshotAccessibilityService.qualityObservationAccepted(
                QualityBackfillCoordinator.ObservationStatus.REJECTED_CLOSED));
    }

    @Test public void sameWindowStateTransitionFencesAProvisionalDocument() {
        assertTrue(ScreenshotAccessibilityService.shouldInvalidateProvisionalDocument(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, true, 7, 7));
        assertFalse(ScreenshotAccessibilityService.shouldInvalidateProvisionalDocument(
                AccessibilityEvent.TYPE_WINDOWS_CHANGED, true, 7, 7));
        assertFalse(ScreenshotAccessibilityService.shouldInvalidateProvisionalDocument(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, false, 7, 7));
        assertFalse(ScreenshotAccessibilityService.shouldInvalidateProvisionalDocument(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, true, 8, 7));
    }

    @Test public void qualityCacheCannotCrossAMotionGeneration() {
        assertTrue(ScreenshotAccessibilityService.isQualityCacheGenerationCurrent(12L, 12L));
        assertFalse(ScreenshotAccessibilityService.isQualityCacheGenerationCurrent(12L, 13L));
        assertFalse(ScreenshotAccessibilityService.isQualityCacheGenerationCurrent(13L, 12L));
    }

    @Test public void qualityCommitCannotCrossANewerFastSubmission() {
        assertTrue(ScreenshotAccessibilityService.isQualitySubmissionCurrent(
                12L, 12L, 40L, 40L));
        assertFalse(ScreenshotAccessibilityService.isQualitySubmissionCurrent(
                12L, 12L, 40L, 41L));
        assertFalse(ScreenshotAccessibilityService.isQualitySubmissionCurrent(
                12L, 13L, 40L, 40L));
    }

    @Test public void qualityConfirmationBurstStillRespectsMotionAndFastWork() {
        assertFalse(ScreenshotAccessibilityService.shouldRunQualityRefinement(
                11_129L, 11_000L, 11_000L, true, false, 0L, false, true));
        assertTrue(ScreenshotAccessibilityService.shouldRunQualityRefinement(
                11_250L, 10_000L, 11_000L, true, false, 0L, false, true));
        assertFalse(ScreenshotAccessibilityService.shouldRunQualityRefinement(
                10_129L, 10_000L, 10_000L, true, false, 0L, false, true));
        assertFalse(ScreenshotAccessibilityService.shouldRunQualityRefinement(
                11_500L, 10_000L, 11_000L, true, false, 0L, true, true));
    }

    @Test public void pendingCandidatesDoNotDelayIndependentlyStableCoverage() {
        Detection linked = detection(10, 20, 100, 100);
        linked.setTrackId(42);
        Detection unlinkedA = detection(200, 20, 100, 100);
        Detection unlinkedB = detection(340, 20, 100, 100);
        List<Detection> quality = Arrays.asList(linked, unlinkedA, unlinkedB);

        List<Detection> pending = ScreenshotAccessibilityService
                .transactionalQualityCoverage(quality, 2, false);
        List<Detection> closed = ScreenshotAccessibilityService
                .transactionalQualityCoverage(quality, 0, true);

        assertEquals(quality, pending);
        assertEquals(Collections.singletonList(linked), closed);
    }

    private static Detection detection(int x, int y, int width, int height) {
        return new Detection("FACE_FEMALE", "face_female", 0.9f,
                new BBox(x, y, width, height), true, false);
    }
}
