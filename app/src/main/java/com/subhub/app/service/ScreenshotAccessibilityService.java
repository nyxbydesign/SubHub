package com.subhub.app.service;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;


import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectorConfig;

import java.util.List;

/** Neutral OS and exact-selection shell; each pipeline is a fresh resource owner. */
public final class ScreenshotAccessibilityService extends AccessibilityService {
    private static final String TAG = "ScreenshotA11y";
    private volatile AccessibilityLegacyPipeline pipeline;
    private AccessibilityServiceConnection managedConnection;

    private boolean managedShell;

    private boolean managedOsConnected;

    private com.subhub.app.runtime.AppRuntimeGateway.ServiceAccess managedServiceAccess;

    private com.subhub.app.runtime.AppRuntimeGateway.LegacyWork legacyServiceWork;

    private final android.os.Handler osMain = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (managedShell) {
            if (managedServiceAccess != null) {
                boolean reconnected = !managedOsConnected;
                managedOsConnected = true;
                if (reconnected && managedConnection != null && managedConnection.retired()) registerManagedSource();
            }
            return;
        }
        if (pipeline != null) return;
        com.subhub.app.runtime.AppRuntimeGateway.ServiceAccess access;
        try {
            access = com.subhub.app.runtime.AppRuntimeGateway.services(this);
        } catch (RuntimeException failure) {
            managedShell = true; // Installed route failure can never revive legacy DEVICE state.
            Log.e(TAG, "Managed Accessibility ingress unavailable", failure);
            return;
        }
        if (access != null) {
            managedShell = true;
            managedOsConnected = true;
            managedServiceAccess = access;
            registerManagedSource();
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "Accessibility screenshot capture requires Android 11 or newer");
            disableSelf();
            return;
        }
        try {
            legacyServiceWork = com.subhub.app.runtime.AppRuntimeGateway.beginLegacyWork(this);
        } catch (RuntimeException unavailable) {
            managedShell = true; // Preparing/failed bootstrap is also fail-closed, not legacy null.
            Log.e(TAG, "Legacy Accessibility admission unavailable", unavailable);
            return;
        }
        pipeline = new AccessibilityLegacyPipeline(this);
        pipeline.initializeDeviceRuntime();
    }

    private void registerManagedSource() {
        if (!managedOsConnected || managedServiceAccess == null) return;
        com.subhub.app.runtime.AppRuntimeGateway.ServiceAccess access = managedServiceAccess;
        managedConnection = new AccessibilityServiceConnection(() -> this,
                new AccessibilityServiceConnection.Port() {
                    @Override public java.util.concurrent.CompletableFuture<Void> connected(
                            RuntimeCaptureHost.Connection connection, RuntimeCaptureHost.Completion completion) {
                        return access.accessibilityConnected(connection, completion);
                    }
                    @Override public java.util.concurrent.CompletableFuture<Void> disconnected(
                            RuntimeCaptureHost.Connection connection) {
                        return access.disconnected(connection);
                    }
                    @Override public java.util.concurrent.CompletableFuture<Void> rejected(
                            RuntimeCaptureHost.Connection connection, RuntimeCaptureHost.Request request,
                            RuntimeCaptureHost.Rejection reason) {
                        return access.rejected(connection, request, reason);
                    }
                    @Override public java.util.concurrent.CompletableFuture<Void> drained(
                            RuntimeCaptureHost.Connection connection, RuntimeCaptureHost.Selection selection) {
                        return access.attachmentDrained(connection, selection);
                    }
                }, new AccessibilityServiceConnection.MainQueue() {
                    @Override public boolean post(Runnable callback) { return osMain.post(callback); }
                    @Override public boolean postDelayed(Runnable callback, long delayMillis) {
                        return osMain.postDelayed(callback, delayMillis);
                    }
                }, this::managedConnectionRetired);
        try { managedConnection.connect(); }
        catch (RuntimeException failure) { Log.e(TAG, "Managed Accessibility connection failed", failure); }
    }

    private void managedConnectionRetired(AccessibilityServiceConnection exact) {
        if (managedConnection != exact || exact.occupied()) return;
        // Old callbacks keep their old connection/attachment. This is a fresh logical generation
        // on the same still-bound OS source, never adoption of old DEVICE state or its resources.
        pipeline = null; // Exact old selection/resource/gateway receipts returned even if OS disconnected.
        if (managedOsConnected) registerManagedSource();
    }

    public RuntimeCaptureHost.Attachment prepareDeviceAttachment(RuntimeCaptureHost.DeviceRequest request) {
        if (request.connection.accessibility().service() != this) throw new SecurityException("Foreign Accessibility host");
        return DeviceAccessibilityAttachment.prepare(request);
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        managedOsConnected = false;
        AccessibilityServiceConnection exact = managedConnection;
        try { if (exact != null) exact.disconnect(); }
        catch (RuntimeException failure) { Log.e(TAG, "Managed Accessibility disconnect failed", failure); }
        return super.onUnbind(intent);
    }

    @Override
    public void onInterrupt() {
        // Android may temporarily interrupt feedback; the scheduled capture loop remains owned
        // here.
    }

    public static boolean isRunning() { return AccessibilityLegacyPipeline.isRunning(); }

    public static boolean isRecognitionActive() { return AccessibilityLegacyPipeline.isRecognitionActive(); }

    public static boolean isCensorRuntimeActive() { return AccessibilityLegacyPipeline.isCensorRuntimeActive(); }

    static String observedCensorForeground() { return AccessibilityLegacyPipeline.observedCensorForeground(); }

    static SceneTransactionCoordinator.Mode scenePresentationMode(boolean motionSettled) { return AccessibilityLegacyPipeline.scenePresentationMode(motionSettled); }

    static boolean shouldScheduleQualityNow(boolean fastDemand, boolean fastDraining) { return AccessibilityLegacyPipeline.shouldScheduleQualityNow(fastDemand, fastDraining); }

    static boolean qualityCircuitAllows(long nowUptime, long openUntilUptime) { return AccessibilityLegacyPipeline.qualityCircuitAllows(nowUptime, openUntilUptime); }

    static boolean usesSplitHardwareQuality(String fastProvider, String qualityProvider) { return AccessibilityLegacyPipeline.usesSplitHardwareQuality(fastProvider, qualityProvider); }

    static boolean qualityObservationAccepted(
            QualityBackfillCoordinator.ObservationStatus status) { return AccessibilityLegacyPipeline.qualityObservationAccepted(status); }

    static long backfillTransformToken(
            int sourceWidth, int sourceHeight, int viewportWidth, int viewportHeight) { return AccessibilityLegacyPipeline.backfillTransformToken(sourceWidth, sourceHeight, viewportWidth, viewportHeight); }

    static boolean isQualitySubmissionCurrent(
            long sourceMotionGeneration,
            long currentMotionGeneration,
            long sourceFastSequence,
            long currentFastSequence) { return AccessibilityLegacyPipeline.isQualitySubmissionCurrent(sourceMotionGeneration, currentMotionGeneration, sourceFastSequence, currentFastSequence); }

    static boolean isFastSubmissionCurrent(
            long sourceFastSequence,
            long currentFastSequence) { return AccessibilityLegacyPipeline.isFastSubmissionCurrent(sourceFastSequence, currentFastSequence); }

    static long qualityAvailableSlackMs(long lastScreenshotRequestUptime, long nowUptime) { return AccessibilityLegacyPipeline.qualityAvailableSlackMs(lastScreenshotRequestUptime, nowUptime); }

    static boolean shouldReserveQualityTick(
            long nowUptime,
            long lastMotionUptime,
            long reservationUntilUptime,
            boolean qualityPending,
            boolean qualityActive) { return AccessibilityLegacyPipeline.shouldReserveQualityTick(nowUptime, lastMotionUptime, reservationUntilUptime, qualityPending, qualityActive); }

    static long qualityExecutionBudgetMs(long lastSuccessfulDurationMs) { return AccessibilityLegacyPipeline.qualityExecutionBudgetMs(lastSuccessfulDurationMs); }

    static long qualityReservationWindowMs(long lastSuccessfulDurationMs) { return AccessibilityLegacyPipeline.qualityReservationWindowMs(lastSuccessfulDurationMs); }

    public static int[] rectangularFastInputShape(
            int sourceWidth, int sourceHeight, int longEdge) { return AccessibilityLegacyPipeline.rectangularFastInputShape(sourceWidth, sourceHeight, longEdge); }

    static long captureDelayMs(DetectorConfig config) { return AccessibilityLegacyPipeline.captureDelayMs(config); }

    static DetectorConfig fastDetectorConfig(DetectorConfig quality) { return AccessibilityLegacyPipeline.fastDetectorConfig(quality); }

    static DetectorConfig qualityDetectorConfig(DetectorConfig source) { return AccessibilityLegacyPipeline.qualityDetectorConfig(source); }

    static DetectorConfig accessibilityTrackerConfig(DetectorConfig configured) { return AccessibilityLegacyPipeline.accessibilityTrackerConfig(configured); }

    static boolean shouldRunQualityRefinement(
            long nowUptime,
            long lastMotionUptime,
            long lastQualityUptime,
            boolean firstFrameReported,
            boolean qualityCacheEmpty) { return AccessibilityLegacyPipeline.shouldRunQualityRefinement(nowUptime, lastMotionUptime, lastQualityUptime, firstFrameReported, qualityCacheEmpty); }

    static boolean shouldRunQualityRefinement(
            long nowUptime,
            long lastMotionUptime,
            long lastQualityUptime,
            boolean firstFrameReported,
            boolean qualityCacheEmpty,
            long lastQualityRuntimeMs,
            boolean fastFramePending) { return AccessibilityLegacyPipeline.shouldRunQualityRefinement(nowUptime, lastMotionUptime, lastQualityUptime, firstFrameReported, qualityCacheEmpty, lastQualityRuntimeMs, fastFramePending); }

    static boolean shouldRunQualityRefinement(
            long nowUptime,
            long lastMotionUptime,
            long lastQualityUptime,
            boolean firstFrameReported,
            boolean qualityCacheEmpty,
            long lastQualityRuntimeMs,
            boolean fastFramePending,
            boolean confirmationRequested) { return AccessibilityLegacyPipeline.shouldRunQualityRefinement(nowUptime, lastMotionUptime, lastQualityUptime, firstFrameReported, qualityCacheEmpty, lastQualityRuntimeMs, fastFramePending, confirmationRequested); }

    static List<Detection> transactionalQualityCoverage(
            List<Detection> qualityCoverage,
            int pendingCandidates,
            boolean batchClosed) { return AccessibilityLegacyPipeline.transactionalQualityCoverage(qualityCoverage, pendingCandidates, batchClosed); }

    static boolean shouldReplaceQualityCache(
            int previousCount,
            int candidateCount,
            boolean completeScene) { return AccessibilityLegacyPipeline.shouldReplaceQualityCache(previousCount, candidateCount, completeScene); }

    static boolean usesContinuousMotionInference(DetectorConfig config) { return AccessibilityLegacyPipeline.usesContinuousMotionInference(config); }

    static boolean usesStreamingQualityPipeline(DetectorConfig config) { return AccessibilityLegacyPipeline.usesStreamingQualityPipeline(config); }

    static boolean usesAtomicScenePipeline(DetectorConfig config) { return AccessibilityLegacyPipeline.usesAtomicScenePipeline(config); }

    static boolean shouldPreemptQualityForCapture(boolean qualityActive) { return AccessibilityLegacyPipeline.shouldPreemptQualityForCapture(qualityActive); }

    static int fastInferenceFrameResolution(
            DetectorConfig config,
            boolean fastDetectorReady,
            boolean sourceFrameRequired) { return AccessibilityLegacyPipeline.fastInferenceFrameResolution(config, fastDetectorReady, sourceFrameRequired); }

    static boolean shouldEstimateFrameMotion(
            DetectorConfig config,
            long nowUptime,
            long lastAccessibilityScrollUptime) { return AccessibilityLegacyPipeline.shouldEstimateFrameMotion(config, nowUptime, lastAccessibilityScrollUptime); }

    static boolean usesSemanticTextModel(DetectorConfig config) { return AccessibilityLegacyPipeline.usesSemanticTextModel(config); }

    static boolean usesScreenshotOcr(DetectorConfig config) { return AccessibilityLegacyPipeline.usesScreenshotOcr(config); }

    static long capturePollDelayMs(DetectorConfig config) { return AccessibilityLegacyPipeline.capturePollDelayMs(config); }

    static ContentSpaceRegionCache.Evidence worldCacheEvidence(
            SceneTransactionCoordinator.CommitKind kind, boolean motionCurrent, long capturedAtUptime) { return AccessibilityLegacyPipeline.worldCacheEvidence(kind, motionCurrent, capturedAtUptime); }

    static long settledCaptureDelayMs(
            long nowUptime, long lastMotionUptime, long lastRequestUptime) { return AccessibilityLegacyPipeline.settledCaptureDelayMs(nowUptime, lastMotionUptime, lastRequestUptime); }

    static boolean shouldPublishTextScan(
            long sampledMotionGeneration,
            long currentMotionGeneration,
            long sampledScrollX,
            long sampledScrollY,
            long currentScrollX,
            long currentScrollY) { return AccessibilityLegacyPipeline.shouldPublishTextScan(sampledMotionGeneration, currentMotionGeneration, sampledScrollX, sampledScrollY, currentScrollX, currentScrollY); }

    static boolean shouldBridgeTextMisses(
            long scanStartedUptime,
            long lastMotionUptime,
            long lastContentChangeUptime) { return AccessibilityLegacyPipeline.shouldBridgeTextMisses(scanStartedUptime, lastMotionUptime, lastContentChangeUptime); }

    static long textRefreshDelayAfterMotion(long nowUptime, long lastMotionUptime) { return AccessibilityLegacyPipeline.textRefreshDelayAfterMotion(nowUptime, lastMotionUptime); }

    static long textScanStartDelayMs(
            long nowUptime,
            long lastMotionUptime,
            long nowMillis,
            long lastRefreshMillis) { return AccessibilityLegacyPipeline.textScanStartDelayMs(nowUptime, lastMotionUptime, nowMillis, lastRefreshMillis); }

    static long contentTextRefreshDelayMs(
            long nowUptime,
            long lastContentChangeUptime,
            long contentBurstStartedUptime) { return AccessibilityLegacyPipeline.contentTextRefreshDelayMs(nowUptime, lastContentChangeUptime, contentBurstStartedUptime); }

    static boolean isAccessibilityTextSnapshotFresh(
            long nowUptime,
            long capturedAtUptime,
            long invalidatedAtUptime) { return AccessibilityLegacyPipeline.isAccessibilityTextSnapshotFresh(nowUptime, capturedAtUptime, invalidatedAtUptime); }

    static boolean isQualityCacheGenerationCurrent(
            long cacheMotionGeneration,
            long currentMotionGeneration) { return AccessibilityLegacyPipeline.isQualityCacheGenerationCurrent(cacheMotionGeneration, currentMotionGeneration); }

    static long ocrDelayMs(
            long nowUptime,
            long lastCompletionUptime,
            long lastMotionUptime,
            boolean confirmationRequested) { return AccessibilityLegacyPipeline.ocrDelayMs(nowUptime, lastCompletionUptime, lastMotionUptime, confirmationRequested); }

    static boolean ocrEligibleForViewport(
            DetectorConfig config,
            boolean textEnabled,
            boolean firstVisualFramePublished,
            boolean accessibilityTextCandidatesPresent) { return AccessibilityLegacyPipeline.ocrEligibleForViewport(config, textEnabled, firstVisualFramePublished, accessibilityTextCandidatesPresent); }

    static boolean sameOcrViewport(
            long requestedMotionGeneration,
            long currentMotionGeneration,
            boolean accessibilityTextCandidatesPresent) { return AccessibilityLegacyPipeline.sameOcrViewport(requestedMotionGeneration, currentMotionGeneration, accessibilityTextCandidatesPresent); }

    static boolean isOcrSnapshotFresh(long nowUptime, long capturedAtUptime) { return AccessibilityLegacyPipeline.isOcrSnapshotFresh(nowUptime, capturedAtUptime); }

    static boolean shouldInvalidateProvisionalDocument(
            int eventType,
            boolean activeProvisional,
            int eventWindowId,
            int activeWindowId) { return AccessibilityLegacyPipeline.shouldInvalidateProvisionalDocument(eventType, activeProvisional, eventWindowId, activeWindowId); }

    static boolean isTextRelevantContentChange(int changeTypes) { return AccessibilityLegacyPipeline.isTextRelevantContentChange(changeTypes); }
    @Override protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {
        if (args != null && args.length == 1 && "render-layout".equals(args[0])) {
            AccessibilityLegacyPipeline p = pipeline;
            if (p == null) writer.println("SUBHUB_RENDER_LAYOUT {\"schemaVersion\":1,\"active\":false}");
            else p.dumpRenderLayout(writer);
            return;
        }
        super.dump(fd, writer, args);
    }
    void attachManagedDevice(DeviceAccessibilityAttachment exact) {
        if (pipeline != null) throw new SecurityException("Accessibility service still owns an earlier pipeline");
        AccessibilityLegacyPipeline prepared = new AccessibilityLegacyPipeline(this);
        pipeline = prepared;
        prepared.attachManagedDevice(exact);
    }
    private AccessibilityLegacyPipeline exactPipeline(DeviceAccessibilityAttachment exact) {
        AccessibilityLegacyPipeline p = pipeline;
        return p != null && p.managedAttachment == exact ? p : null;
    }
    void activateManagedDevice(DeviceAccessibilityAttachment exact) { AccessibilityLegacyPipeline p = exactPipeline(exact); if (p != null) p.activateManagedDevice(exact); }
    void deliverManagedEvent(DeviceAccessibilityAttachment exact, AccessibilityEvent event) { AccessibilityLegacyPipeline p = exactPipeline(exact); if (p != null) p.deliverManagedEvent(exact, event); }
    void sealManagedDevice(DeviceAccessibilityAttachment exact) { AccessibilityLegacyPipeline p = exactPipeline(exact); if (p != null) p.sealManagedDevice(exact); }
    void beginManagedDeviceClose(DeviceAccessibilityAttachment exact) { AccessibilityLegacyPipeline p = exactPipeline(exact); if (p != null) p.beginManagedDeviceClose(exact); }
    boolean managedDeviceResourcesDrained(DeviceAccessibilityAttachment exact) { AccessibilityLegacyPipeline p = exactPipeline(exact); return p == null || p.managedDeviceResourcesDrained(exact); }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (managedShell) {
            AccessibilityServiceConnection exact = managedConnection;
            try { if (exact != null) exact.event(event); }
            catch (RuntimeException failure) { Log.e(TAG, "Managed Accessibility event failed", failure); }
            return;
        }
        AccessibilityLegacyPipeline p = pipeline; if (p != null) p.onLegacyAccessibilityEvent(event);
    }
    @Override public void onDestroy() {
        managedOsConnected = false;
        try {
            if (managedShell) { AccessibilityServiceConnection exact = managedConnection; if (exact != null) exact.disconnect(); }
            else { AccessibilityLegacyPipeline p = pipeline; if (p != null) p.destroyLegacy(); }
        } finally { super.onDestroy(); }
    }
}
