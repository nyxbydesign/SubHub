package com.subhub.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.ForegroundAppState;
import com.subhub.app.appmode.ProtectionSessionManager;
import com.subhub.app.appmode.ResumeNotificationManager;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.capture.MediaProjectionLeaseRegistry;
import com.subhub.app.capture.ScreenCaptureManager;
import com.subhub.app.censor.android.CensorScreenState;
import com.subhub.app.commitment.CommitmentManager;
import com.subhub.app.detection.Detection;
import com.subhub.app.detection.DetectionEngine;
import com.subhub.app.detection.DetectorConfig;
import com.subhub.app.detection.ObjectTracker;
import com.subhub.app.detection.TrackedObject;
import com.subhub.app.diagnostics.DiagnosticsRepository;
import com.subhub.app.overlay.OverlayController;
import com.subhub.app.penance.CensorTapTracker;
import com.subhub.app.penance.DwellInfractionTracker;
import com.subhub.app.penance.PenanceChargeNotifier;
import com.subhub.app.penance.PenanceInfraction;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.penance.HardcoreAutoPayManager;
import com.subhub.app.popup.PopupStormManager;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.security.ProtectionStopPolicy;

import com.subhub.app.settings.CensorAppearance;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.AchievementManager;
import com.subhub.app.stats.StatsRepository;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Foreground pipeline: capture -> local ONNX inference -> tracking -> touch-through overlay. */
public final class ScreenCaptureService extends Service {
    private static final String TAG = "ScreenCaptureService";
    private static final String CHANNEL_ID = "subhub_protection";
    private static final int NOTIFICATION_ID = 1701;
    private static final String ACTION_START = "com.subhub.app.action.START";
    private static final String ACTION_STOP = "com.subhub.app.action.STOP";
    private static final String ACTION_STOP_OWNED = "com.subhub.app.action.STOP_OWNED_CAPTURE";
    private static final String EXTRA_RESULT_CODE = "projection_result_code";
    private static final String EXTRA_RESULT_DATA = "projection_result_data";
    private static final String DIAGNOSTICS_MODE = "MediaProjection";
    private static final CaptureRunOwnership ownership = new CaptureRunOwnership();
    private static volatile ScreenCaptureService liveInstance;
    private final String instanceIdentity = UUID.randomUUID().toString();
    private final Object resources = new Object();
    private volatile CaptureRunOwnership.Token captureOwner;
    private volatile boolean destroyed;
    private volatile boolean destructionComplete;
    private volatile boolean resourceClosureFailed;
    private volatile int lastStartId;
    private PopupStormManager.ServiceParticipation popupParticipation;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean firstFrameReported = new AtomicBoolean();
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener =
            (preferences, key) -> mainHandler.post(() -> {
                if (destroyed) return;
                if (DiagnosticsRepository.PREF_OVERLAY.equals(key)) {
                    refreshDiagnosticsOverlay();
                } else reloadSettings();
            });
    private ScheduledExecutorService executor;
    private ExecutorService inferenceExecutor;
    private LatestFrameBroker<ProjectionFrame> frameBroker;
    private CaptureLoadGovernor loadGovernor;
    private MediaProjection projection;
    private ScreenCaptureManager capture;
    private ForegroundAppState.Snapshot clearedScope, processedScope;
    private DetectionEngine detector;
    private ObjectTracker tracker;
    private OverlayController overlay;
    private SettingsRepository settings;
    private StatsRepository stats;
    private PenanceManager penance;
    private final DwellInfractionTracker dwellTracker = new DwellInfractionTracker();
    private final CensorTapTracker tapTracker = CensorTapTracker.shared();
    private volatile DetectorConfig detectorConfig;
    private volatile boolean overlayNeedsSourceFrame;
    private volatile boolean explicitlyStoppedByController;
    private final AtomicBoolean projectionLeaseHeld = new AtomicBoolean();
    private CensorScreenState censorScreen;
    private volatile boolean pipelineActive;
    private volatile boolean projectionRevoked;
    private volatile long startingGeneration = -1;
    private volatile long pipelineGeneration;
    private volatile boolean nativePipelineOwned;
    private long observedScreenGeneration;
    private ScheduledFuture<?> captureSchedule;
    private boolean screenReceiverRegistered;
    private final android.content.BroadcastReceiver screenReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            censorScreen.refresh(intent.getAction());
            durationChanged();
            reconcileCensorScreen();
        }
    };
    private final Runnable screenTick = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            reconcileCensorScreen();
            mainHandler.postDelayed(this, 1000);
        }
    };

    public static Intent startIntent(Context context, int resultCode, Intent resultData) {
        return new Intent(context, ScreenCaptureService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData);
    }

    public static Intent stopIntent(Context context) {
        return new Intent(context, ScreenCaptureService.class).setAction(ACTION_STOP);
    }

    public static boolean isRunning() { return ownership.isRunning(); }
    public static boolean isCensorRuntimeActive() {
        ScreenCaptureService service = liveInstance;
        return service != null && !service.projectionRevoked && service.pipelineActive && service.isInstanceRunning()
                && service.censorScreen != null && service.censorScreen.eligible() && service.censorConfigured();
    }

    private void reconcileCensorScreen() {
        if (destroyed || censorScreen == null) return;
        censorScreen.refresh(null);
        boolean usable = censorScreen.eligible();
        long screenGeneration = censorScreen.generation();
        if (screenGeneration != observedScreenGeneration) durationChanged();
        if (screenGeneration != observedScreenGeneration) suspendCensorPipeline();
        observedScreenGeneration = screenGeneration;
        String observed = usable ? ScreenshotAccessibilityService.observedCensorForeground() : "";
        syncPopupParticipation(observed);
        boolean eligible = !observed.isEmpty() && observed.equals(ForegroundAppState.snapshot().packageName)
                && new AppModeManager(this).shouldCensorForeground(observed);
        if (projectionRevoked || !isInstanceRunning() || !usable) {
            suspendCensorPipeline();
            return;
        }
        if (!eligible) { pauseCensorPipeline(false); return; }
        long generation = censorScreen.generation();
        if (pipelineActive || startingGeneration == generation) return;
        startingGeneration = generation;
        nativePipelineOwned = true;
        long transition = ++pipelineGeneration;
        executor.execute(() -> inferenceExecutor.execute(() -> startPipeline(generation, transition)));
    }

    private void suspendCensorPipeline() {
        pauseCensorPipeline(true);
    }

    private void syncPopupParticipation(String observed) {
        boolean eligible = !projectionRevoked && censorScreen.eligible() && !observed.isEmpty()
                && observed.equals(ForegroundAppState.snapshot().packageName) && ownsCurrentRuntime()
                && new AppModeManager(this).shouldShowPopups(observed);
        if (eligible) popupParticipation = PopupStormManager.get().startOwned(this, popupSignature());
        else {
            PopupStormManager.get().stopOwnedService(popupParticipation);
            popupParticipation = null;
        }
    }

    private void pauseCensorPipeline(boolean retireNative) {
        if (retireNative) syncPopupParticipation("");
        boolean hadWork = pipelineActive || startingGeneration >= 0;
        boolean retire = retireNative && nativePipelineOwned;
        if (retire) nativePipelineOwned = false;
        pipelineActive = false;
        startingGeneration = -1;
        if (hadWork) durationChanged();
        if (hadWork) pipelineGeneration++;
        if (captureSchedule != null) { captureSchedule.cancel(false); captureSchedule = null; }
        ScreenCaptureManager source = capture;
        if (source != null) {
            try { source.pauseSurface(true); }
            catch (RuntimeException failure) {
                projectionRevoked = true;
                DiagnosticsRepository.fail(DIAGNOSTICS_MODE, failure);
                stopSelfResult(lastStartId);
            }
        }
        if ((!hadWork && !retire) || executor == null || executor.isShutdown()) return;
        if (overlay != null) overlay.clear();
        if (ownsCurrentRuntime()) PopupStormManager.get().updateDetections(java.util.Collections.emptyList());
        executor.execute(() -> inferenceExecutor.execute(() -> {
            // Ordinary awake app changes retain the prior model; screen suspension must retire it.
            DetectionEngine retired = retire ? detector : null;
            if (retire) detector = null;
            try { if (retired != null) retired.close(); }
            catch (RuntimeException | Error failure) {
                resourceClosureFailed = true; projectionRevoked = true;
                DiagnosticsRepository.fail(DIAGNOSTICS_MODE, failure);
                mainHandler.post(() -> stopSelfResult(lastStartId));
            }
            tracker = null; processedScope = null; dwellTracker.clear();
        }));
    }

    private void durationChanged() {
        com.subhub.app.stats.DailyDurationRecorder.changed(System.currentTimeMillis(), SystemClock.elapsedRealtime());
    }

    /** An admitted capture effect, separate from peer authorization and the enclosing runtime identity. */
    public static final class StopTarget {
        private final CaptureRunOwnership.Token owner;
        private StopTarget(CaptureRunOwnership.Token owner) { this.owner = owner; }
        public String identity() { return owner.activation; }
        /** Historical closure of this capture; it says nothing about a replacement runtime. */
        public boolean isClosed() { return owner.isClosed(); }
    }

    /** Pure app-owned observation; the command controller captures this before committing End. */
    @Nullable public static StopTarget observedStopTarget(Context context) {
        synchronized (RuntimeSessionIdentity.LOCK) {
            CaptureRunOwnership.Token owner = ownership.current();
            ScreenCaptureService service = liveInstance;
            if (owner == null || service == null || service.destroyed || !owner.equals(service.captureOwner)) return null;
            try { return owner.components.equals(components(context)) ? new StopTarget(owner) : null; }
            catch (IllegalArgumentException | ClassCastException corrupt) { return null; }
        }
    }

    /** Delivers only to the already observed instance; it never creates or globally stops a service. */
    public static boolean requestStop(StopTarget target) {
        if (target == null) return false;
        if (Thread.holdsLock(RuntimeSessionIdentity.LOCK) || Thread.holdsLock(com.subhub.app.security.SubFeaturePolicy.LOCK))
            throw new IllegalStateException("Capture dispatch must follow authority validation");
        ScreenCaptureService service = liveInstance;
        return service != null && service.instanceIdentity.equals(target.owner.instance)
                && service.mainHandler.post(() -> service.stopOwned(target.owner));
    }

    public static Intent targetedStopIntent(Context context, StopTarget target) {
        if (target == null) throw new IllegalArgumentException("Missing capture stop target");
        CaptureRunOwnership.Token owner = target.owner;
        return new Intent(context, ScreenCaptureService.class).setAction(ACTION_STOP_OWNED)
                .putExtra("capture_instance", owner.instance).putExtra("capture_activation", owner.activation)
                .putExtra("capture_start_id", owner.startId).putExtra("capture_statistics", owner.components.statistics)
                .putExtra("capture_pact", owner.components.pact).putExtra("capture_pause", owner.components.pause);
    }

    private static CaptureRunOwnership.Token stopTarget(Intent intent) {
        return new CaptureRunOwnership.Token(intent.getStringExtra("capture_instance"), intent.getStringExtra("capture_activation"),
                intent.getIntExtra("capture_start_id", 0), new CaptureRunOwnership.Components(
                        intent.getStringExtra("capture_statistics"), intent.getStringExtra("capture_pact"), intent.getStringExtra("capture_pause")));
    }

    private static CaptureRunOwnership.Components components(Context context) {
        SharedPreferences main = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
        return new CaptureRunOwnership.Components(
                context.getSharedPreferences(StatsRepository.PREFS_NAME, 0).getString("active_session_identity", ""),
                main.getString("commitment_session_identity", ""),
                context.getSharedPreferences(PenanceManager.PREFS_NAME, 0).getString("paid_pause_session_identity", ""));
    }

    private boolean ownsRuntimeLocked() {
        try { return !destroyed && ownership.mayAffectRuntime(captureOwner, components(this)); }
        catch (IllegalArgumentException | ClassCastException corrupt) { return false; }
    }

    private boolean isInstanceRunning() { return !destroyed && ownership.owns(captureOwner) && !ownership.isStopping(captureOwner); }

    private boolean ownsCurrentRuntime() {
        synchronized (RuntimeSessionIdentity.LOCK) { return ownsFrameRuntimeLocked(); }
    }

    private boolean ownsFrameRuntimeLocked() {
        try { return isInstanceRunning() && captureOwner.components.equals(components(this)); }
        catch (IllegalArgumentException | ClassCastException corrupt) { return false; }
    }

    private void updateDiagnostics(Runnable update) {
        synchronized (RuntimeSessionIdentity.LOCK) {
            if (ownsFrameRuntimeLocked()) update.run();
        }
    }

    final class FrameScope {
        private final long screenGeneration = censorScreen.generation();
        private final long pipelineTransition = pipelineGeneration;
        private final CaptureRunOwnership.Token owner = captureOwner;
        private final RuntimeSessionIdentity.ComponentObservation components = RuntimeSessionIdentity.componentObservation(ScreenCaptureService.this);
        private final ForegroundAppState.Snapshot foreground;
        final StatsRepository.SessionStamp statistics = stats.sessionStamp();
        final long tapScope = tapTracker.scope();
        private FrameScope(ForegroundAppState.Snapshot foreground) { this.foreground = foreground; }
        boolean isCurrent() {
            return !destroyed && !projectionRevoked && pipelineActive && pipelineGeneration == pipelineTransition && censorScreen.isCurrent(screenGeneration) && ForegroundAppState.isCurrent(foreground) && components.isCurrent() && statistics != null && statistics.isCurrent()
                    && ownership.owns(owner) && !ownership.isStopping(owner);
        }
    }

    FrameScope observeFrameScope() {
        return observeFrameScope(ForegroundAppState.snapshot());
    }

    private FrameScope observeFrameScope(ForegroundAppState.Snapshot foreground) {
        if (!Thread.holdsLock(RuntimeSessionIdentity.LOCK)) throw new IllegalStateException("Frame admission requires runtime ownership");
        if (!ownsFrameRuntimeLocked() || !ForegroundAppState.isCurrent(foreground)) return null;
        FrameScope scope = new FrameScope(foreground);
        return scope.isCurrent() ? scope : null;
    }

    void postFramePublication(FrameScope scope, Runnable render, Runnable release) {
        if (!mainHandler.post(() -> {
            if (scope != null && scope.isCurrent()) render.run();
            else release.run();
        })) release.run();
    }

    private String popupSignature() {
        CaptureRunOwnership.Components value = captureOwner.components;
        return value.pact + ":" + value.pause + ":" + value.statistics;
    }

    private boolean stopOwned(CaptureRunOwnership.Token target) {
        synchronized (RuntimeSessionIdentity.LOCK) {
            if (destroyed || !target.equals(captureOwner)) return false;
            boolean affectRuntime;
            try { affectRuntime = ownership.mayAffectRuntime(target, components(this)); }
            catch (IllegalArgumentException | ClassCastException corrupt) { affectRuntime = false; }
            if (!ownership.beginStop(target)) return false;
            explicitlyStoppedByController = true;
            if (affectRuntime) {
                ProtectionSessionManager.markMediaProjectionExplicitlyStopped(this);
                new AppModeManager(this).setArmed(false);
            }
        }
        // Android start IDs fence later deliveries, while the activation token fences later ownership.
        if (!stopSelfResult(lastStartId)) Log.d(TAG, "Capture retirement awaits a newer start delivery");
        durationChanged();
        return true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        executor = Executors.newSingleThreadScheduledExecutor();
        inferenceExecutor = Executors.newSingleThreadExecutor();
        loadGovernor = new CaptureLoadGovernor(this);
        settings = new SettingsRepository(this);
        censorScreen = new CensorScreenState(this);
        observedScreenGeneration = censorScreen.generation();
        android.content.IntentFilter screenFilter = new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON); screenFilter.addAction(Intent.ACTION_USER_PRESENT);
        androidx.core.content.ContextCompat.registerReceiver(this, screenReceiver, screenFilter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        screenReceiverRegistered = true;
        mainHandler.post(screenTick);
        stats = com.subhub.app.remote.RemoteSessionNative.statistics(this);
        penance = new PenanceManager(this);
        settings.preferences().registerOnSharedPreferenceChangeListener(settingsListener);
        liveInstance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        // AMS may register this ID before an earlier targeted Stop sees its delivery.
        if (!destroyed && ownership.isStopping(captureOwner)) {
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP_OWNED.equals(intent.getAction())) {
            try { stopOwned(stopTarget(intent)); }
            catch (IllegalArgumentException | ClassCastException malformed) { Log.w(TAG, "Invalid capture stop target"); }
            if (captureOwner == null) stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            if (ProtectionStopPolicy.decision(this)
                    != ProtectionStopPolicy.Decision.ALLOW) return START_STICKY;
            explicitlyStoppedByController = true;
            ProtectionSessionManager.markMediaProjectionExplicitlyStopped(this);
            new AppModeManager(this).setArmed(false);
            ResumeNotificationManager.cancel(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction()) || isRunning()) return START_NOT_STICKY;
        if (!MediaProjectionLeaseRegistry.acquire(
                MediaProjectionLeaseRegistry.Owner.PROTECTION)) {
            explicitlyStoppedByController = true;
            ProtectionSessionManager.markMediaProjectionExplicitlyStopped(this);
            new AppModeManager(this).setArmed(false);
            ResumeNotificationManager.cancel(this);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        projectionLeaseHeld.set(true);

        startForeground(NOTIFICATION_ID, buildNotification());
        Intent projectionData = projectionData(intent);
        if (projectionData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(
                intent.getIntExtra(EXTRA_RESULT_CODE, 0), projectionData);
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        AtomicReference<CaptureRunOwnership.Token> projectionOwner = new AtomicReference<>();
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                projectionRevoked = true;
                suspendCensorPipeline();
                CaptureRunOwnership.Token owner = projectionOwner.get();
                if (owner == null) stopSelf(startId);
                else if (ownership.owns(owner) && owner.equals(captureOwner)) stopSelfResult(lastStartId);
            }
        }, mainHandler);

        synchronized (RuntimeSessionIdentity.LOCK) {
            ProtectionSessionManager.markMediaProjectionStarted(this);
            new AppModeManager(this).setArmed(true);
            stats.startSession();
            CaptureRunOwnership.Components current = components(this);
            if (new AppModeManager(this).isArmed() && !current.statistics.isEmpty()) {
                captureOwner = ownership.activate(instanceIdentity, startId, current);
                projectionOwner.set(captureOwner);
            }
        }
        if (captureOwner == null) { stopSelfResult(startId); return START_NOT_STICKY; }
        durationChanged();
        overlay = new OverlayController(this);
        CensorAppearance appearance = settings.loadAppearance();
        overlayNeedsSourceFrame = appearance.requiresSourceFrame();
        overlay.setAppearance(appearance);
        overlay.setMaxExtrapolationMs(settings.loadDetectorConfig().getMaxExtrapolationMs());
        if (censorScreen.eligible()) overlay.show();
        reconcileCensorScreen();
        return START_NOT_STICKY;
    }

    private void startPipeline(long generation, long transition) {
        synchronized (resources) {
            try {
                if (projectionRevoked || !isInstanceRunning() || pipelineGeneration != transition || !censorScreen.isCurrent(generation) || !censorConfigured()) return;
                DetectorConfig config = settings.loadDetectorConfig();
                detectorConfig = config;
                updateDiagnostics(() -> DiagnosticsRepository.begin(DIAGNOSTICS_MODE, config.getInferenceResolution()));
                if (detector == null) {
                    detector = new DetectionEngine(this, config);
                    detector.initialize();
                } else detector.setConfig(config);
                String provider = detector.getActiveProvider(), model = detector.getActiveModel();
                updateDiagnostics(() -> DiagnosticsRepository.ready(DIAGNOSTICS_MODE, provider,
                        model, config.getInferenceResolution()));
                if (projectionRevoked || !isInstanceRunning() || pipelineGeneration != transition || !censorScreen.isCurrent(generation) || !censorConfigured()) return;
                tracker = new ObjectTracker(config);

                DisplayInfo display = displayInfo();
                if (capture == null) capture = new ScreenCaptureManager(
                        projection,
                        display.width,
                        display.height,
                        display.densityDpi,
                        config.getInferenceResolution(),
                        config.getCaptureScale());
                capture.start(true);
                if (frameBroker == null) frameBroker = new LatestFrameBroker<>(
                        inferenceExecutor,
                        this::processFrame,
                        ProjectionFrame::close);
                mainHandler.post(() -> {
                    if (destroyed || projectionRevoked || pipelineGeneration != transition || startingGeneration != generation || !censorScreen.isCurrent(generation)
                            || !censorConfigured() || !isInstanceRunning()) return;
                    try { capture.pauseSurface(false); }
                    catch (RuntimeException failure) {
                        projectionRevoked = true; suspendCensorPipeline();
                        DiagnosticsRepository.fail(DIAGNOSTICS_MODE, failure); stopSelfResult(lastStartId); return;
                    }
                    pipelineActive = true;
                    startingGeneration = -1;
                    durationChanged();
                    if (overlay != null) overlay.show();
                    captureSchedule = executor.scheduleWithFixedDelay(this::captureLatestFrame, 0, 16, TimeUnit.MILLISECONDS);
                });
            } catch (Exception error) {
                updateDiagnostics(() -> DiagnosticsRepository.fail(DIAGNOSTICS_MODE, error));
                Log.e(TAG, "Could not start on-device protection", error);
                mainHandler.post(() -> { if (ownership.owns(captureOwner)) stopSelfResult(lastStartId); });
            }
        }
    }

    private void reloadSettings() {
        if (!isInstanceRunning()) return;
        reconcileCensorScreen();
        durationChanged();
        if (overlay != null) overlay.clearPersonCoverage();
        if (!censorConfigured()) {
            if (overlay != null) overlay.clear();
            if (tracker != null) tracker.clear();
            synchronized (RuntimeSessionIdentity.LOCK) { if (ownsFrameRuntimeLocked()) tapTracker.clear(); }
            dwellTracker.clear();
            if (ownsCurrentRuntime()) PopupStormManager.get().updateDetections(java.util.Collections.emptyList());
        }
        CensorAppearance appearance = settings.loadAppearance();
        overlayNeedsSourceFrame = appearance.requiresSourceFrame();
        if (overlay != null) overlay.setAppearance(appearance);
        if (overlay != null) publishDiagnostics(diagnosticsOverlayText());
        DetectorConfig config = settings.loadDetectorConfig();
        detectorConfig = config;
        if (overlay != null) overlay.setMaxExtrapolationMs(config.getMaxExtrapolationMs());
        if (detector != null) detector.setConfig(config);
        if (tracker != null) tracker.setConfig(config);
        if (!ownsCurrentRuntime()) return;
        PopupStormManager.get().reloadSettings(this);
        syncPopupParticipation(censorScreen.eligible() ? ScreenshotAccessibilityService.observedCensorForeground() : "");
    }

    private void captureLatestFrame() {
        if (!pipelineActive || !censorScreen.eligible() || !isInstanceRunning() || capture == null) return;
        long screenGeneration = censorScreen.generation(), transition = pipelineGeneration;
        ForegroundAppState.Snapshot scope = ForegroundAppState.snapshot();
        if (!censorConfigured()) {
            if (clearedScope != scope) {
                clearedScope = scope;
                mainHandler.post(
                        () -> {
                            if (overlay != null && !censorConfigured()) overlay.clear();
                        });
            }
            return;
        }
        clearedScope = null;
        DetectorConfig currentConfig = detectorConfig;
        if (loadGovernor != null && !loadGovernor.shouldCapture(
                SystemClock.uptimeMillis(),
                currentConfig == null ? 0L : currentConfig.getDetectionIntervalMs())) return;
        try {
            Bitmap frame = capture.acquireLatestFrame();
            LatestFrameBroker<ProjectionFrame> broker = frameBroker;
            if (frame == null) return;
            if (!pipelineActive || pipelineGeneration != transition || !censorScreen.isCurrent(screenGeneration)) {
                capture.releaseFrame(frame); return;
            }
            if (broker == null) frame.recycle();
            else {
                broker.submit(new ProjectionFrame(frame, capture, SystemClock.uptimeMillis(), scope, screenGeneration, transition));
            }
        } catch (Exception error) {
            updateDiagnostics(() -> DiagnosticsRepository.fail(DIAGNOSTICS_MODE, error));
            Log.w(TAG, "Frame capture failed", error);
        }
    }

    private void processFrame(ProjectionFrame candidate) {
        Bitmap frame = candidate.bitmap();
        try {
            if (!pipelineActive || pipelineGeneration != candidate.pipelineTransition || !censorScreen.isCurrent(candidate.screenGeneration) || !isInstanceRunning() || !censorConfigured() || !ForegroundAppState.isCurrent(candidate.scope)
                    || frame == null || frame.isRecycled()) return;
            if (processedScope != candidate.scope) {
                tracker.clear();
                synchronized (RuntimeSessionIdentity.LOCK) { if (ownsFrameRuntimeLocked()) tapTracker.clear(); }
                dwellTracker.clear();
                processedScope = candidate.scope;
            }
            List<Detection> detections = detector.detect(frame);
            if (!pipelineActive || pipelineGeneration != candidate.pipelineTransition || !censorScreen.isCurrent(candidate.screenGeneration) || !ownsCurrentRuntime() || !censorConfigured() || !ForegroundAppState.isCurrent(candidate.scope)) return;
            List<TrackedObject> tracks = tracker.update(detections);
            DetectorConfig currentConfig = detectorConfig;
            int recordedBlocks;
            FrameScope admitted;
            synchronized (RuntimeSessionIdentity.LOCK) {
                if (!pipelineActive || pipelineGeneration != candidate.pipelineTransition || !censorScreen.isCurrent(candidate.screenGeneration)) return;
                admitted = observeFrameScope(candidate.scope);
                if (admitted == null) return;
                recordedBlocks = stats.recordTracks(tracks, currentConfig == null ? null : currentConfig.getEnabledCategories());
            }
            long now = System.currentTimeMillis();
            if (recordedBlocks > 0) {
                int charged = penance.recordSourceDetectionResult(recordedBlocks, now, admitted.statistics, admitted::isCurrent).chargedCents;
                PenanceChargeNotifier.show(this, penance,
                        PenanceInfraction.NEW_DETECTION, charged, now, admitted::isCurrent);
                if (admitted.isCurrent()) new AchievementManager(this).checkAchievements(stats.load());
            }
            int dwellInfractions = dwellTracker.update(
                    tracks, now, penance.getDwellSeconds() * 1_000L);
            if (dwellInfractions > 0) {
                int charged = penance.recordSourceDwellResult(dwellInfractions, now, admitted.statistics, admitted::isCurrent).chargedCents;
                PenanceChargeNotifier.show(this, penance,
                        PenanceInfraction.CENSORED_DWELL, charged, now, admitted::isCurrent);
            }
            int width = capture.getCaptureWidth();
            int height = capture.getCaptureHeight();
            if (!ownsCurrentRuntime()) return;
            PopupStormManager.get().updateTrackedObjects(tracks, width, height);
            DiagnosticsRepository.Snapshot diagnostics;
            synchronized (RuntimeSessionIdentity.LOCK) {
                if (!ownsFrameRuntimeLocked() || !admitted.isCurrent()) return;
                diagnostics = DiagnosticsRepository.recordFrame(
                    DIAGNOSTICS_MODE,
                    detector.getLastInferenceMs(),
                    detector.getLastPreprocessMs(),
                    detector.getLastRuntimeMs(),
                    detector.getLastPostprocessMs(),
                    SystemClock.uptimeMillis() - candidate.capturedAtUptimeMillis,
                    frameBroker == null ? 0L : frameBroker.droppedCount(),
                    tracks.size(),
                    width,
                    height);
            }
            String diagnosticText = diagnosticsOverlayText(diagnostics);
            Bitmap overlayFrame = overlayNeedsSourceFrame ? candidate.detachBitmap() : null;
            Runnable overlayFrameRelease = overlayFrame == null ? null
                    : () -> candidate.releaseDetachedBitmap(overlayFrame);
            if (firstFrameReported.compareAndSet(false, true)) {
                Log.i(TAG, "First frame processed with "
                        + detector.getActiveModel() + " on " + detector.getActiveProvider()
                        + " in " + detector.getLastInferenceMs() + " ms at "
                        + width + "x" + height);
            }
            Runnable releaseOverlayFrame = () -> {
                if (overlayFrameRelease != null) overlayFrameRelease.run();
                else if (overlayFrame != null) overlayFrame.recycle();
            };
            postFramePublication(admitted, () -> {
                if (isInstanceRunning() && overlay != null && censorConfigured()
                                && ForegroundAppState.isCurrent(candidate.scope)) {
                    publishDiagnostics(diagnosticText);
                    overlay.setTapSource(false, admitted.tapScope);
                    if (overlayFrameRelease == null) {
                        overlay.update(tracks, width, height, overlayFrame);
                    } else {
                        overlay.updatePooledFrame(
                                tracks, width, height, overlayFrame, overlayFrameRelease);
                    }
                    updateDiagnostics(() -> DiagnosticsRepository.recordPublishDelay(DIAGNOSTICS_MODE,
                            SystemClock.uptimeMillis() - candidate.capturedAtUptimeMillis));
                }
                else releaseOverlayFrame.run();
            }, releaseOverlayFrame);
        } catch (Exception error) {
            updateDiagnostics(() -> DiagnosticsRepository.fail(DIAGNOSTICS_MODE, error));
            Log.w(TAG, "Frame processing failed", error);
        }
    }

    private DisplayInfo displayInfo() {
        WindowManager manager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = manager.getMaximumWindowMetrics().getBounds();
            return new DisplayInfo(bounds.width(), bounds.height(), metrics.densityDpi);
        }
        manager.getDefaultDisplay().getRealMetrics(metrics);
        return new DisplayInfo(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi);
    }

    private Intent projectionData(Intent source) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return source.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        }
        @SuppressWarnings("deprecation")
        Intent value = source.getParcelableExtra(EXTRA_RESULT_DATA);
        return value;
    }

    private void refreshDiagnosticsOverlay() {
        mainHandler.post(() -> { if (overlay != null) publishDiagnostics(diagnosticsOverlayText()); });
    }

    /** A delayed frame must not restore diagnostics after visibility was switched off. */
    private void publishDiagnostics(String value) {
        if (overlay != null) overlay.setDiagnostics(settings != null && settings.preferences()
                .getBoolean(DiagnosticsRepository.PREF_OVERLAY, false) ? value : "");
    }

    private String diagnosticsOverlayText() {
        return diagnosticsOverlayText(DiagnosticsRepository.snapshot());
    }

    private String diagnosticsOverlayText(DiagnosticsRepository.Snapshot snapshot) {
        return settings != null && settings.preferences().getBoolean(
                DiagnosticsRepository.PREF_OVERLAY, false)
                ? DiagnosticsRepository.overlayText(snapshot) : "";
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openApp = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(
                this, 1, stopIntent(this), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(openApp)
                .setOngoing(true)
                .setSilent(true);
        if (ProtectionStopPolicy.showNotificationStop(this)) {
            builder.addAction(0, getString(R.string.notification_stop), stop);
        }
        return com.subhub.app.privacy.PrivacyNotifications.present(this, NOTIFICATION_ID, builder.build());
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private boolean censorConfigured() {
        return !projectionRevoked && censorScreen != null && censorScreen.eligible() && settings != null && new AppModeManager(this)
                        .shouldCensorForeground(ForegroundAppState.snapshot().packageName);
    }

    @Override
    public void onDestroy() {
        if (destroyed) return;
        pipelineActive = false;
        mainHandler.removeCallbacks(screenTick);
        if (screenReceiverRegistered) { unregisterReceiver(screenReceiver); screenReceiverRegistered = false; }
        CaptureRunOwnership.Token original = captureOwner;
        boolean cleanup;
        synchronized (RuntimeSessionIdentity.LOCK) {
            cleanup = ownsRuntimeLocked();
            destroyed = true;
            if (cleanup) {
                new AppModeManager(this).setArmed(false);
                if (stats != null && original.components.statistics.equals(stats.activeSessionIdentity()))
                    stats.endSessionForIdentity(original.components.statistics);
                DiagnosticsRepository.stop(DIAGNOSTICS_MODE);
                tapTracker.clear();
            }
            ownership.release(original);
            if (liveInstance == this) liveInstance = null;
        }
        durationChanged();
        if (settings != null) {
            settings.preferences().unregisterOnSharedPreferenceChangeListener(settingsListener);
        }
        if (executor != null) executor.shutdownNow();
        if (inferenceExecutor != null) {
            inferenceExecutor.shutdownNow();
            try {
                if (!inferenceExecutor.awaitTermination(2, TimeUnit.SECONDS)) resourceClosureFailed = true;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                resourceClosureFailed = true;
            }
        }
        synchronized (resources) {
            if (frameBroker != null) releaseResource("frame broker", frameBroker::close);
            frameBroker = null;
            if (capture != null) releaseResource("capture", capture::close);
            capture = null;
            if (loadGovernor != null) releaseResource("load governor", loadGovernor::close);
            loadGovernor = null;
            if (detector != null) releaseResource("detector", detector::close);
            detector = null;
            dwellTracker.clear();
            OverlayController activeOverlay = overlay;
            overlay = null;
            if (activeOverlay != null) releaseResource("overlay", activeOverlay::close);
            PopupStormManager.get().stopOwnedService(popupParticipation);
            popupParticipation = null;
            if (projection != null) releaseResource("projection", projection::stop);
            projection = null;
            if (projectionLeaseHeld.compareAndSet(true, false)) {
                MediaProjectionLeaseRegistry.release(
                        MediaProjectionLeaseRegistry.Owner.PROTECTION);
            }
        }
        if (executor != null) {
            try { if (!executor.awaitTermination(2, TimeUnit.SECONDS)) resourceClosureFailed = true; }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); resourceClosureFailed = true; }
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (cleanup && !explicitlyStoppedByController) reinforceOwnedRuntime(original);
        super.onDestroy();
        if (original != null && !resourceClosureFailed) original.acknowledgeClosure();
        destructionComplete = true;
    }

    private void releaseResource(String name, Runnable release) {
        try { release.run(); }
        catch (RuntimeException failure) { resourceClosureFailed = true; Log.w(TAG, "Could not close owned " + name, failure); }
    }

    /** Observe Android outside Runtime, then fence the only rearming write to the original components. */
    private void reinforceOwnedRuntime(CaptureRunOwnership.Token original) {
        boolean hardcore = new HardcoreModeManager(this).isEnabled();
        com.subhub.app.remote.RemoteSessionClock.Sample clock = new com.subhub.app.remote.RemoteSessionClock.Sample(
                System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                android.provider.Settings.Global.getInt(getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1));
        RuntimeSessionIdentity.ComponentObservation notificationScope;
        boolean notify;
        synchronized (RuntimeSessionIdentity.LOCK) {
            if (ownership.isRunning()) return;
            try { if (!original.components.permitsCleanupOf(components(this))) return; }
            catch (IllegalArgumentException | ClassCastException corrupt) { return; }
            RuntimeSessionIdentity.Observed current = RuntimeSessionIdentity.observe(this, clock);
            boolean pactRearm = current.pact && !current.paused;
            if (pactRearm) new AppModeManager(this).setArmed(true);
            notify = pactRearm || hardcore && new AppModeManager(this).isArmed() && !current.paused;
            notificationScope = RuntimeSessionIdentity.componentObservation(this);
        }
        if (!notificationScope.isCurrent()) return;
        if (notify) ResumeNotificationManager.show(this);
        if (hardcore && notificationScope.isCurrent()) HardcoreAutoPayManager.schedule(this);
    }

    private static final class DisplayInfo {
        private final int width;
        private final int height;
        private final int densityDpi;

        private DisplayInfo(int width, int height, int densityDpi) {
            this.width = width;
            this.height = height;
            this.densityDpi = densityDpi;
        }
    }

    private static final class ProjectionFrame implements AutoCloseable {
        private final long screenGeneration;
        private final long pipelineTransition;
        private Bitmap bitmap;
        private final ScreenCaptureManager owner;
        private final long capturedAtUptimeMillis;

        private final ForegroundAppState.Snapshot scope;

        private ProjectionFrame(
                Bitmap bitmap,
                ScreenCaptureManager owner,
                long capturedAtUptimeMillis,
                ForegroundAppState.Snapshot scope, long screenGeneration, long pipelineTransition) {
            this.bitmap = bitmap;
            this.owner = owner;
            this.capturedAtUptimeMillis = capturedAtUptimeMillis;
            this.scope = scope;
            this.screenGeneration = screenGeneration;
            this.pipelineTransition = pipelineTransition;
        }

        private Bitmap bitmap() { return bitmap; }

        private Bitmap detachBitmap() {
            Bitmap value = bitmap;
            bitmap = null;
            return value;
        }

        private void releaseDetachedBitmap(Bitmap detached) {
            if (detached != null && !detached.isRecycled()) owner.releaseFrame(detached);
        }

        @Override
        public void close() {
            if (bitmap != null && !bitmap.isRecycled()) owner.releaseFrame(bitmap);
            bitmap = null;
        }
    }
}
